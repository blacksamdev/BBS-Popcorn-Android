"""
resolver.py — BBS Popcorn Android — DIAGNOSTIC 3
Version cookieB (cookiefile en repli) + fetch_info_debug qui retourne
l'erreur EXACTE des deux tentatives (sans cookies, puis avec cookiefile).
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
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True,
            "format": _build_format_selector(quality), "skip_download": True}
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


def fetch_info_debug(url: str, quality: str = "1080", cookiefile: str = None) -> str:
    """Diagnostic : erreurs des DEUX tentatives séparément."""
    err_no_cookie = None
    err_cookie = None
    has_cookiefile = bool(cookiefile)

    # tentative 1 : sans cookies
    try:
        r = _try_extract(url, quality, cookiefile=None)
        if r:
            return json.dumps({"ok": True, "via": "no_cookie", **r})
    except Exception as exc:
        err_no_cookie = f"{type(exc).__name__}: {exc}"

    # tentative 2 : avec cookiefile
    if cookiefile:
        try:
            r = _try_extract(url, quality, cookiefile=cookiefile)
            if r:
                return json.dumps({"ok": True, "via": "cookiefile", **r})
        except Exception as exc:
            err_cookie = f"{type(exc).__name__}: {exc}"

    return json.dumps({
        "ok": False,
        "has_cookiefile": has_cookiefile,
        "err_no_cookie": err_no_cookie or "—",
        "err_cookie": err_cookie or "—",
    })


def _build_format_selector(quality: str) -> str:
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return (
        f"best[height<={q}][vcodec!=none][acodec!=none]/"
        f"best[height<={q}][protocol^=m3u8]/"
        f"best[height<={q}]/"
        f"best"
    )
