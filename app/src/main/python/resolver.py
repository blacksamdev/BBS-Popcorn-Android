"""
resolver.py — BBS Popcorn Android
Résolution via yt-dlp (API Python, Chaquopy). Aucune dépendance GTK/UI.

Stratégie cookies (alignée desktop, en mieux) :
- On résout d'ABORD sans cookies (cas majoritaire, le plus fiable).
- Si la résolution échoue ET qu'un fichier cookies.txt filtré est fourni,
  on RÉESSAYE avec yt-dlp `cookiefile` (vidéos à restriction d'âge).
- On n'utilise JAMAIS de header Cookie HTTP brut : ça court-circuite la
  gestion de session de yt-dlp et casse la résolution sur compte connecté.

Format (Android, sans ffmpeg) : combiné → HLS (lu par Media3) → filet 'best'.
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
        opts["cookiefile"] = cookiefile   # fichier Netscape, PAS un header brut
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
    Résout titre + stream. Tente sans cookies, puis avec cookiefile en repli.
    """
    # 1. tentative sans cookies (cas majoritaire, le plus fiable)
    try:
        result = _try_extract(url, quality, cookiefile=None)
        if result:
            return result
    except Exception as exc:
        log.debug(f"fetch_info: sans cookies échoue: {exc}")

    # 2. repli avec cookiefile (vidéos à restriction d'âge), si fourni
    if cookiefile:
        try:
            log.debug("fetch_info: nouvelle tentative avec cookiefile")
            result = _try_extract(url, quality, cookiefile=cookiefile)
            if result:
                return result
        except Exception as exc:
            log.debug(f"fetch_info: avec cookies échoue aussi: {exc}")

    return None


def _build_format_selector(quality: str) -> str:
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return (
        f"best[height<={q}][vcodec!=none][acodec!=none]/"
        f"best[height<={q}][protocol^=m3u8]/"
        f"best[height<={q}]/"
        f"best"
    )
