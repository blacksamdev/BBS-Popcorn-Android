"""
resolver.py — BBS Popcorn Android
Résolution et normalisation des URLs YouTube via yt-dlp (API Python).
Version Chaquopy : yt-dlp importé comme module, pas de subprocess.
Aucune dépendance GTK/UI.

Cookies : le header Cookie de la WebView (CookieManager Android) peut être
transmis à yt-dlp pour les vidéos avec restriction d'âge.

Sélection de format (Android, sans ffmpeg) :
On ne peut pas merger vidéo+audio séparés. On privilégie donc dans l'ordre :
  1. un format combiné progressif (mp4 18/22 : audio+vidéo dans un seul flux)
  2. un flux HLS (m3u8) que Media3 lit nativement, audio+vidéo inclus
  3. en dernier recours, le meilleur format jouable (filet de sécurité)
Le filet de sécurité final évite l'erreur "Requested format is not available"
qui survenait quand l'utilisateur connecté reçoit un catalogue de formats
différent (souvent sans le combiné mp4 classique).
"""

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


def _extract_stream(info: dict) -> str | None:
    """
    Extrait une URL de flux jouable depuis l'info yt-dlp.
    Priorité : url directe (déjà sélectionnée par le format selector),
    puis recherche d'un format combiné dans la liste.
    """
    stream_url = info.get("url")
    if stream_url:
        return stream_url

    # requested_formats = cas où yt-dlp a choisi vidéo+audio séparés.
    # Sur Android sans ffmpeg, on ne peut pas merger : on cherche un combiné.
    formats = info.get("formats") or []
    # 1. un format combiné (vcodec ET acodec présents)
    for fmt in reversed(formats):
        if (fmt.get("url")
                and fmt.get("acodec") not in (None, "none")
                and fmt.get("vcodec") not in (None, "none")):
            return fmt["url"]
    # 2. un flux HLS (m3u8) — Media3 le lit nativement
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


def _build_format_selector(quality: str) -> str:
    """
    Sélecteur progressif avec filet de sécurité.

    Ordre de préférence (yt-dlp essaie chaque option jusqu'à en trouver une) :
      1. best[height<=Q][vcodec!=none][acodec!=none]
         → format combiné à la qualité voulue (idéal, lecture directe)
      2. best[height<=Q][protocol^=m3u8]
         → flux HLS à la qualité voulue (Media3 lit le HLS nativement)
      3. best[height<=Q]
         → meilleur format <= Q, quel qu'il soit
      4. best
         → filet de sécurité ABSOLU : n'importe quel format jouable.
            Évite "Requested format is not available".
    """
    q = quality if quality in ("2160", "1440", "1080", "720", "480") else "1080"
    return (
        f"best[height<={q}][vcodec!=none][acodec!=none]/"
        f"best[height<={q}][protocol^=m3u8]/"
        f"best[height<={q}]/"
        f"best"
    )
