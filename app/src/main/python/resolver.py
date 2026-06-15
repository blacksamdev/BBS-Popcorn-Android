"""
resolver.py — BBS Popcorn Android
Résolution et normalisation des URLs YouTube via yt-dlp (API Python).
Version Chaquopy : yt-dlp importé comme module, pas de subprocess.
Aucune dépendance GTK/UI.

Cookies : le header Cookie de la WebView (CookieManager Android) peut être
transmis à yt-dlp pour les vidéos avec restriction d'âge — équivalent
Android du cookies.py desktop, en beaucoup plus simple.
"""

import json
import logging
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse

import yt_dlp

log = logging.getLogger("bbs.resolver")


def prepare_url(url: str) -> str:
    """
    Normalise une URL YouTube en supprimant les paramètres de tracking.
    Conserve uniquement v= (vidéo) et list= (playlist).
    """
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
        log.debug(f"prepare_url: erreur normalisation: {exc}")

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
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "extract_flat": False,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        title = (info or {}).get("title", "").strip()
        return title or None
    except Exception as exc:
        log.debug(f"fetch_title: erreur: {exc}")
    return None


def resolve_stream_url(url: str, quality: str = "1080", cookie_header: str = None) -> str | None:
    try:
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return None
        stream_url = info.get("url")
        if stream_url:
            return stream_url
        formats = info.get("formats") or []
        for fmt in reversed(formats):
            if fmt.get("url") and fmt.get("acodec") != "none" and fmt.get("vcodec") != "none":
                return fmt["url"]
    except Exception as exc:
        log.debug(f"resolve_stream_url: erreur: {exc}")
    return None


def _extract_stream(info: dict) -> str | None:
    stream_url = info.get("url")
    if stream_url:
        return stream_url
    formats = info.get("formats") or []
    for fmt in reversed(formats):
        if fmt.get("url") and fmt.get("acodec") != "none" and fmt.get("vcodec") != "none":
            return fmt["url"]
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
    """
    Version diagnostic : retourne un JSON avec soit les infos, soit l'erreur
    yt-dlp EXACTE. Permet d'afficher la vraie cause dans le toast.

    Retour JSON :
      {"ok": true, "title": "...", "stream_url": "...", ...}
      {"ok": false, "error": "<message yt-dlp réel>", "had_cookies": true}
    """
    had_cookies = bool(cookie_header)
    cookie_len = len(cookie_header) if cookie_header else 0
    try:
        with yt_dlp.YoutubeDL(_base_opts(quality, cookie_header)) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return json.dumps({
                "ok": False,
                "error": "extract_info a retourné None",
                "had_cookies": had_cookies,
                "cookie_len": cookie_len,
            })
        stream_url = _extract_stream(info)
        if not stream_url:
            return json.dumps({
                "ok": False,
                "error": "aucun format combiné audio+vidéo trouvé",
                "had_cookies": had_cookies,
                "cookie_len": cookie_len,
            })
        return json.dumps({
            "ok": True,
            "title": (info.get("title") or "").strip(),
            "stream_url": stream_url,
            "thumbnail": info.get("thumbnail"),
            "duration_s": info.get("duration") or 0,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
            "had_cookies": had_cookies,
            "cookie_len": cookie_len,
        })


def _build_format_selector(quality: str) -> str:
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return f"best[height<={q}][vcodec!=none][acodec!=none]/best[height<={q}]"
