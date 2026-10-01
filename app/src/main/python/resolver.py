"""
resolver.py — BBS Popcorn Android
Résolution via yt-dlp (API Python, Chaquopy). Aucune dépendance GTK/UI.

Stratégie cookies (alignée desktop) :
- résolution d'abord SANS cookies (cas majoritaire), puis AVEC cookiefile
  en repli (vidéos à restriction d'âge). cookiefile = fichier Netscape,
  jamais de header HTTP brut.

Sélection de format (Android, SANS ffmpeg) — POINT CRITIQUE :
On ne peut PAS merger vidéo+audio séparés (ffmpeg absent). Il faut donc
ne JAMAIS sélectionner un couple "bestvideo+bestaudio" : yt-dlp tenterait
un merge et échouerait avec "Requested format is not available".
On force uniquement des flux UNIQUES contenant déjà audio+vidéo :
  1. format progressif combiné <= Q (mp4)
  2. flux HLS <= Q (m3u8, lu par Media3)
  3. n'importe quel format combiné <= Q
  4. format 18 (mp4 360p combiné, quasi toujours présent, même en 18+)
  5. 'best*' = meilleur flux UNIQUE (l'étoile évite le merge auto)
"""

import json
import logging
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse

import yt_dlp

log = logging.getLogger("bbs.resolver")


def prepare_url(url: str) -> str:
    try:
        parsed = urlparse(url)
        params = parse_qs(parsed.query, keep_blank_values=False)
        if "youtu.be" in parsed.netloc:
            video_id = parsed.path.lstrip("/").split("?")[0]
            if video_id:
                return f"https://www.youtube.com/watch?v={video_id}"
            return url
        if "list" in params:
            playlist_id = params["list"][0]
            if not playlist_id.startswith("RD"):
                return f"https://www.youtube.com/playlist?list={playlist_id}"
        if "v" in params:
            clean = urlencode({"v": params["v"][0]})
            return urlunparse(parsed._replace(
                netloc="www.youtube.com", path="/watch", query=clean
            ))
    except Exception as exc:
        log.debug(f"prepare_url: erreur: {exc}")
    return url


def _opts(quality: str, cookiefile: str = None) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": _build_format_selector(quality),
        "skip_download": True,
    }
    if cookiefile:
        opts["cookiefile"] = cookiefile
    return opts


# En-tetes HTTP a rejouer a l'identique sur les URLs de flux.
# googlevideo verifie que la requete de lecture correspond au client
# YouTube qui a produit l'URL ; sans cela il repond 403 au bout de
# quelques secondes. yt-dlp fournit ces en-tetes format par format.
_DROP_HEADERS = {
    "range", "accept-encoding", "host", "connection",
    "content-length", "transfer-encoding",
}


def _headers_of(fmt: dict, info: dict) -> dict:
    """En-tetes du format choisi, sinon ceux de la video, nettoyes."""
    raw = (fmt or {}).get("http_headers") or (info or {}).get("http_headers") or {}
    return {
        k: v for k, v in raw.items()
        if isinstance(k, str) and isinstance(v, str)
        and k.lower() not in _DROP_HEADERS
    }


def _pick_streams(info: dict, quality: str) -> tuple:
    """
    Choisit les flux à lire, façon mpv : vidéo et audio SÉPARÉS quand c'est
    possible (ExoPlayer les synchronise via MergingMediaSource), sinon un
    flux combiné, sinon du HLS.

    YouTube ne propose plus de format combiné au-delà de 360p : c'est en
    prenant les pistes séparément qu'on récupère le 1080p avec le son.

    Retourne (video_url, audio_url) — audio_url vaut None si le flux vidéo
    contient déjà l'audio.
    """
    try:
        q = int(quality)
    except (TypeError, ValueError):
        q = 1080

    formats = [f for f in (info.get("formats") or []) if f.get("url")]

    def direct(f):
        p = (f.get("protocol") or "").lower()
        return p.startswith("http") and "m3u8" not in p

    def has_video(f):
        return f.get("vcodec") not in (None, "none")

    def has_audio(f):
        return f.get("acodec") not in (None, "none")

    # 1. pistes séparées : meilleure vidéo <= q + meilleur audio
    vids = [f for f in formats
            if direct(f) and has_video(f) and not has_audio(f)
            and (f.get("height") or 0) <= q]
    auds = [f for f in formats
            if direct(f) and has_audio(f) and not has_video(f)]

    def codec_rank(f) -> int:
        """
        Préférence de codec à résolution égale.

        VP9 transporte environ 40 % de données en moins que H.264 pour la
        même image (1080p60 : ~2500 kb/s contre ~4200), ce qui évite les
        coupures sur une connexion moyenne. Il est décodé en matériel sur
        la quasi-totalité des appareils Android 8+, et Android fournit de
        toute façon un décodeur logiciel de secours.
        AV1 arrive en dernier : décodage matériel encore rare.
        """
        v = (f.get("vcodec") or "").lower()
        if v.startswith("vp9") or v.startswith("vp09"):
            return 2
        if v.startswith("avc"):
            return 1
        return 0

    if vids and auds:
        # hauteur d'abord, puis codec le plus économe, puis débit le plus bas
        vids.sort(key=lambda f: (
            f.get("height") or 0,
            codec_rank(f),
            -(f.get("tbr") or 0),
        ))
        auds.sort(key=lambda f: (
            (f.get("acodec") or "").startswith("mp4a"),
            f.get("abr") or 0,
        ))
        return vids[-1]["url"], auds[-1]["url"], _headers_of(vids[-1], info)

    # 2. repli : flux combiné (audio + vidéo intégrés)
    muxed = [f for f in formats
             if has_video(f) and has_audio(f) and (f.get("height") or 0) <= q]
    if muxed:
        muxed.sort(key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
        return muxed[-1]["url"], None, _headers_of(muxed[-1], info)

    # 3. repli : HLS (ExoPlayer le lit nativement, audio inclus)
    for f in formats:
        if "m3u8" in (f.get("protocol") or "").lower():
            return f["url"], None, _headers_of(f, info)

    # 4. dernier recours : ce que yt-dlp a sélectionné
    if info.get("url"):
        return info["url"], None, _headers_of(info, info)
    return None, None, {}


def _try_extract(url: str, quality: str, cookiefile: str = None) -> dict | None:
    with yt_dlp.YoutubeDL(_opts(quality, cookiefile)) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        return None
    stream_url, audio_url, headers = _pick_streams(info, quality)
    if not stream_url:
        return None
    return {
        "title": (info.get("title") or "").strip(),
        "stream_url": stream_url,
        "audio_url": audio_url or "",
        "http_headers": json.dumps(headers or {}),
        "thumbnail": info.get("thumbnail"),
        "duration_s": info.get("duration") or 0,
    }


def fetch_title(url: str) -> str | None:
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True,
            "skip_download": True, "extract_flat": False}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        title = (info or {}).get("title", "").strip()
        return title or None
    except Exception as exc:
        log.debug(f"fetch_title: erreur: {exc}")
    return None


def resolve_stream_url(url: str, quality: str = "1080", cookiefile: str = None) -> str | None:
    info = fetch_info(url, quality, cookiefile)
    return info["stream_url"] if info else None


def fetch_info(url: str, quality: str = "1080", cookiefile: str = None) -> dict | None:
    """
    Résout titre + flux d'une vidéo YouTube.
    Tente d'abord sans cookies, puis avec cookiefile en repli
    (vidéos à restriction d'âge).
    """
    try:
        result = _try_extract(url, quality, cookiefile=None)
        if result:
            return result
    except Exception as exc:
        log.debug(f"fetch_info sans cookies: {exc}")
    if cookiefile:
        try:
            result = _try_extract(url, quality, cookiefile=cookiefile)
            if result:
                return result
        except Exception as exc:
            log.debug(f"fetch_info avec cookies: {exc}")
    return None


def _build_format_selector(quality: str) -> str:
    """
    Sélecteur volontairement permissif : il ne doit jamais faire échouer
    l'extraction. Le vrai choix des pistes est fait par _pick_streams()
    sur la liste complète des formats.
    """
    return "b*/best"
