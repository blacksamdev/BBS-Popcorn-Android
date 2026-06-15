"""
resolver.py — BBS Popcorn Android
Résolution via yt-dlp (API Python, Chaquopy). Version diagnostic 2 :
filet de sécurité format CONSERVÉ + fetch_info_debug pour voir l'erreur réelle.
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


def _base_opts(quality: str, cookie_header: str = None) -> dict:
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


def resolve_stream_url(url: str, quality: str = "1080", cookie_header: str = None) -> str | None:
    try:
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return None
        return _extract_stream(info)
    except Exception as exc:
        log.debug(f"resolve_stream_url: erreur: {exc}")
    return None


def fetch_info(url: str, quality: str = "1080", cookie_header: str = None) -> dict | None:
    try:
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
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
    except Exception as exc:
        log.debug(f"fetch_info: erreur: {exc}")
    return None


def fetch_info_debug(url: str, quality: str = "1080", cookie_header: str = None) -> str:
    """Diagnostic : JSON avec infos OU erreur yt-dlp exacte + nb de formats vus."""
    had_cookies = bool(cookie_header)
    cookie_len = len(cookie_header) if cookie_header else 0
    try:
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return json.dumps({"ok": False, "error": "extract_info=None",
                               "had_cookies": had_cookies, "cookie_len": cookie_len})
        nb_formats = len(info.get("formats") or [])
        stream_url = _extract_stream(info)
        if not stream_url:
            # liste les protocoles/formats vus pour comprendre
            fmts = info.get("formats") or []
            sample = []
            for f in fmts[-8:]:
                sample.append(f"{f.get('format_id')}:{f.get('protocol')}:"
                              f"v={f.get('vcodec')}:a={f.get('acodec')}")
            return json.dumps({
                "ok": False,
                "error": f"aucun flux jouable extrait ({nb_formats} formats)",
                "sample": sample,
                "had_cookies": had_cookies, "cookie_len": cookie_len,
            })
        return json.dumps({
            "ok": True,
            "title": (info.get("title") or "").strip(),
            "stream_url": stream_url,
            "thumbnail": info.get("thumbnail"),
            "duration_s": info.get("duration") or 0,
            "nb_formats": nb_formats,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
            "had_cookies": had_cookies, "cookie_len": cookie_len,
        })


def _build_format_selector(quality: str) -> str:
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return (
        f"best[height<={q}][vcodec!=none][acodec!=none]/"
        f"best[height<={q}][protocol^=m3u8]/"
        f"best[height<={q}]/"
        f"best"
    )
