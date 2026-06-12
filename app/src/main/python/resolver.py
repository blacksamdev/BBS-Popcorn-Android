"""
resolver.py — BBS Popcorn Android
Résolution et normalisation des URLs YouTube via yt-dlp (API Python).
Version Chaquopy : yt-dlp importé comme module, pas de subprocess.
Aucune dépendance GTK/UI.

Cookies : le header Cookie de la WebView (CookieManager Android) peut être
transmis à yt-dlp pour les vidéos avec restriction d'âge — équivalent
Android du cookies.py desktop, en beaucoup plus simple.
"""

import logging
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse

import yt_dlp

log = logging.getLogger("bbs.resolver")


def prepare_url(url: str) -> str:
    """
    Normalise une URL YouTube en supprimant les paramètres de tracking.
    Conserve uniquement v= (vidéo) et list= (playlist).
    Extrait de MpvPlayer._prepare_url() — player.py (BBS Popcorn Linux).
    """
    try:
        parsed = urlparse(url)
        params = parse_qs(parsed.query, keep_blank_values=False)

        # youtu.be/VIDEO_ID → watch?v=VIDEO_ID
        if "youtu.be" in parsed.netloc:
            video_id = parsed.path.lstrip("/").split("?")[0]
            if video_id:
                return f"https://www.youtube.com/watch?v={video_id}"
            return url

        # Playlist (hors mixes YouTube RD...)
        if "list" in params:
            playlist_id = params["list"][0]
            if not playlist_id.startswith("RD"):
                return f"https://www.youtube.com/playlist?list={playlist_id}"

        # Vidéo simple — ne garder que v=
        if "v" in params:
            clean = urlencode({"v": params["v"][0]})
            return urlunparse(parsed._replace(
                netloc="www.youtube.com", path="/watch", query=clean
            ))

    except Exception as exc:
        log.debug(f"prepare_url: erreur normalisation: {exc}")

    return url


def _base_opts(quality: str, cookie_header: str = None) -> dict:
    """Options yt-dlp communes."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": _build_format_selector(quality),
        "skip_download": True,
    }
    if cookie_header:
        opts["http_headers"] = {"Cookie": cookie_header}
    return opts


def fetch_title(url: str) -> str | None:
    """
    Récupère le titre d'une vidéo YouTube via l'API yt-dlp.
    Retourne le titre ou None en cas d'échec.
    """
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "extract_flat": False,
    }
    try:
        log.debug(f"fetch_title: start for {url}")
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        title = (info or {}).get("title", "").strip()
        log.debug(f"fetch_title: title='{title}'")
        return title or None
    except Exception as exc:
        log.debug(f"fetch_title: erreur: {exc}")
    return None


def resolve_stream_url(url: str, quality: str = "1080", cookie_header: str = None) -> str | None:
    """
    Résout l'URL de stream direct via l'API yt-dlp (sans pub).
    Retourne l'URL du flux ou None en cas d'échec.
    quality : '2160', '1440', '1080', '720', '480'
    """
    try:
        log.debug(f"resolve_stream_url: resolving {url} @ {quality}p")
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)

        if not info:
            return None

        stream_url = info.get("url")
        if stream_url:
            log.debug("resolve_stream_url: OK (direct)")
            return stream_url

        formats = info.get("formats") or []
        for fmt in reversed(formats):
            if fmt.get("url") and fmt.get("acodec") != "none" and fmt.get("vcodec") != "none":
                log.debug("resolve_stream_url: OK (fallback formats)")
                return fmt["url"]

        log.debug("resolve_stream_url: aucun format combiné trouvé")
    except Exception as exc:
        log.debug(f"resolve_stream_url: erreur: {exc}")
    return None


def fetch_info(url: str, quality: str = "1080", cookie_header: str = None) -> dict | None:
    """
    Récupère titre + URL stream + miniature + durée en UN SEUL appel yt-dlp.
    Plus efficace que fetch_title() + resolve_stream_url() séparés.
    cookie_header : header Cookie de la WebView pour les vidéos restreintes.
    Retourne un dict {'title', 'stream_url', 'thumbnail', 'duration_s'}
    ou None en cas d'échec.
    """
    try:
        log.debug(f"fetch_info: start for {url}")
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)

        if not info:
            return None

        stream_url = info.get("url")
        if not stream_url:
            formats = info.get("formats") or []
            for fmt in reversed(formats):
                if fmt.get("url") and fmt.get("acodec") != "none" and fmt.get("vcodec") != "none":
                    stream_url = fmt["url"]
                    break

        if not stream_url:
            log.debug("fetch_info: aucun stream exploitable")
            return None

        return {
            "title": (info.get("title") or "").strip(),
            "stream_url": stream_url,
            "thumbnail": info.get("thumbnail"),
            "duration_s": info.get("duration") or 0,
        }
    except Exception as exc:
        log.debug(f"fetch_info: erreur: {exc}")
    return None


def _build_format_selector(quality: str) -> str:
    """
    Construit le sélecteur de format yt-dlp selon la qualité cible.

    Android/Chaquopy : pas de ffmpeg → pas de merge possible.
    On exige un format combiné (vcodec ET acodec dans le même flux).
    YouTube fournit ces formats en MP4 jusqu'à 720p, et en HLS (m3u8)
    pour les résolutions supérieures — Media3 lit les deux nativement.
    """
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return f"best[height<={q}][vcodec!=none][acodec!=none]/best[height<={q}]"
