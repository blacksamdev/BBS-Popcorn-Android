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


def _extract_stream(info: dict) -> str | None:
    stream_url = info.get("url")
    if stream_url:
        return stream_url
    formats = info.get("formats") or []
    for fmt in reversed(formats):
        if (fmt.get("url")
                and fmt.get("acodec") not in (None, "none")
                and fmt.get("vcodec") not in (None, "none")):
            return fmt["url"]
    for fmt in reversed(formats):
        if fmt.get("url") and fmt.get("protocol", "").startswith("m3u8"):
            return fmt["url"]
    return None


def _try_extract(url: str, quality: str, cookiefile: str = None) -> dict | None:
    with yt_dlp.YoutubeDL(_opts(quality, cookiefile)) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        return None
    stream_url = _extract_stream(info)
    if not stream_url:
        return None
    return {
        "title": (info.get("title") or "").strip(),
        "stream_url": stream_url,
        "thumbnail": info.get("thumbnail"),
        "duration_s": info.get("duration") or 0,
        "is_live": bool(info.get("is_live")),
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
    # 1. sans cookies (cas majoritaire)
    try:
        result = _try_extract(url, quality, cookiefile=None)
        if result:
            return result
    except Exception as exc:
        log.debug(f"fetch_info sans cookies: {exc}")
    # 2. avec cookiefile (vidéos restreintes)
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
    Sélecteur ANTI-MERGE : uniquement des flux uniques (audio+vidéo intégrés).

    Aucune syntaxe "v+a" → yt-dlp ne tentera jamais de merger (pas de ffmpeg).
    'b' / 'best' SANS étoile peut déclencher un merge ; on n'utilise donc que
    des sélecteurs de flux uniques explicites, et 'b*'/'best*' qui autorise
    yt-dlp à prendre un format unique même non-mergé.

    Paliers :
      1. best[height<=Q][vcodec!=none][acodec!=none]  (combiné progressif)
      2. best[height<=Q][protocol^=m3u8]              (HLS, lu par Media3)
      3. b*[height<=Q][vcodec!=none][acodec!=none]    (combiné, variante)
      4. 18                                           (mp4 360p combiné — filet)
      5. b*                                           (meilleur flux unique)
    """
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return (
        f"best[height<={q}][vcodec!=none][acodec!=none]/"
        f"best[height<={q}][protocol^=m3u8]/"
        f"b*[height<={q}][vcodec!=none][acodec!=none]/"
        f"18/"
        f"b*"
    )
