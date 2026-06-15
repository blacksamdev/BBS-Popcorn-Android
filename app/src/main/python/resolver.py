"""resolver.py — DIAGNOSTIC 4 : liste les formats réels d'une vidéo 18+."""

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
                netloc="www.youtube.com", path="/watch", query=clean))
    except Exception as exc:
        log.debug(f"prepare_url: {exc}")
    return url


def _opts(quality, cookiefile=None, fmt=None):
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True,
            "skip_download": True}
    if fmt:
        opts["format"] = fmt
    if cookiefile:
        opts["cookiefile"] = cookiefile
    return opts


def _extract_stream(info):
    stream_url = info.get("url")
    if stream_url:
        return stream_url
    formats = info.get("formats") or []
    for fmt in reversed(formats):
        if (fmt.get("url") and fmt.get("acodec") not in (None, "none")
                and fmt.get("vcodec") not in (None, "none")):
            return fmt["url"]
    for fmt in reversed(formats):
        if fmt.get("url") and fmt.get("protocol", "").startswith("m3u8"):
            return fmt["url"]
    return None


def fetch_title(url):
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True,
                               "noplaylist": True, "skip_download": True}) as ydl:
            info = ydl.extract_info(url, download=False)
        return (info or {}).get("title", "").strip() or None
    except Exception:
        return None


def fetch_info(url, quality="1080", cookiefile=None):
    sel = (f"best[height<={quality}][vcodec!=none][acodec!=none]/"
           f"best[height<={quality}][protocol^=m3u8]/18/b*")
    for cf in (None, cookiefile):
        try:
            with yt_dlp.YoutubeDL(_opts(quality, cf, sel)) as ydl:
                info = ydl.extract_info(url, download=False)
            if info:
                s = _extract_stream(info)
                if s:
                    return {"title": (info.get("title") or "").strip(),
                            "stream_url": s, "thumbnail": info.get("thumbnail"),
                            "duration_s": info.get("duration") or 0}
        except Exception as exc:
            log.debug(f"fetch_info: {exc}")
        if not cookiefile:
            break
    return None


def resolve_stream_url(url, quality="1080", cookiefile=None):
    info = fetch_info(url, quality, cookiefile)
    return info["stream_url"] if info else None


def list_formats_debug(url, cookiefile=None):
    """Liste TOUS les formats vus avec le cookiefile (sans sélecteur)."""
    try:
        # extract complet sans format selector → on voit tout
        with yt_dlp.YoutubeDL(_opts("1080", cookiefile, fmt=None)) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return json.dumps({"ok": False, "error": "info=None"})
        fmts = info.get("formats") or []
        rows = []
        for f in fmts:
            rows.append({
                "id": f.get("format_id"),
                "ext": f.get("ext"),
                "proto": (f.get("protocol") or "")[:12],
                "h": f.get("height"),
                "v": "1" if f.get("vcodec") not in (None, "none") else "0",
                "a": "1" if f.get("acodec") not in (None, "none") else "0",
            })
        # compacte : id ext proto h v a
        compact = [f"{r['id']} {r['ext']} {r['proto']} h{r['h']} v{r['v']}a{r['a']}"
                   for r in rows]
        return json.dumps({
            "ok": True,
            "title": (info.get("title") or "")[:40],
            "n": len(rows),
            "formats": compact,
        })
    except Exception as exc:
        return json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"})
