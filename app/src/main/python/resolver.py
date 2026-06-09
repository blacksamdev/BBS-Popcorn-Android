"""
resolver.py — BBS Popcorn Android
Résolution et normalisation des URLs YouTube via yt-dlp.
Extrait de player.py (BBS Popcorn Linux).
Aucune dépendance GTK/UI.
"""

import subprocess
import json
import logging
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse

log = logging.getLogger("bbs.resolver")


def prepare_url(url: str) -> str:
    """
    Normalise une URL YouTube en supprimant les paramètres de tracking.
    Conserve uniquement v= (vidéo) et list= (playlist).
    Extrait de MpvPlayer._prepare_url() — player.py.
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


def fetch_title(url: str, timeout: int = 30) -> str | None:
    """
    Récupère le titre d'une vidéo YouTube via yt-dlp.
    Retourne le titre ou None en cas d'échec.
    Extrait de MpvPlayer._fetch_title_async() — player.py.
    """
    try:
        log.debug(f"fetch_title: start for {url}")
        proc = subprocess.run(
            [
                "yt-dlp",
                "--no-playlist",
                "--skip-download",
                "--dump-single-json",
                url,
            ],
            capture_output=True,
            timeout=timeout,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            info = json.loads(proc.stdout.decode())
            title = info.get("title", "").strip()
            log.debug(f"fetch_title: title='{title}'")
            return title or None
        else:
            log.debug(f"fetch_title: échec returncode={proc.returncode}")
    except subprocess.TimeoutExpired:
        log.debug("fetch_title: timeout")
    except Exception as exc:
        log.debug(f"fetch_title: erreur: {exc}")
    return None


def resolve_stream_url(url: str, quality: str = "1080", timeout: int = 30) -> str | None:
    """
    Résout l'URL de stream direct via yt-dlp (sans pub).
    Retourne l'URL du flux ou None en cas d'échec.
    quality : '2160', '1440', '1080', '720', '480'
    """
    format_selector = _build_format_selector(quality)
    try:
        log.debug(f"resolve_stream_url: resolving {url} @ {quality}p")
        proc = subprocess.run(
            [
                "yt-dlp",
                "--no-playlist",
                "-f", format_selector,
                "-g",
                url,
            ],
            capture_output=True,
            timeout=timeout,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            stream_url = proc.stdout.decode().strip().splitlines()[0]
            log.debug("resolve_stream_url: OK")
            return stream_url
        else:
            log.debug(f"resolve_stream_url: échec returncode={proc.returncode}")
    except subprocess.TimeoutExpired:
        log.debug("resolve_stream_url: timeout")
    except Exception as exc:
        log.debug(f"resolve_stream_url: erreur: {exc}")
    return None


def _build_format_selector(quality: str) -> str:
    """Construit le sélecteur de format yt-dlp selon la qualité cible."""
    quality_map = {
        "2160": "bestvideo[height<=2160]+bestaudio/best[height<=2160]",
        "1440": "bestvideo[height<=1440]+bestaudio/best[height<=1440]",
        "1080": "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
        "720":  "bestvideo[height<=720]+bestaudio/best[height<=720]",
        "480":  "bestvideo[height<=480]+bestaudio/best[height<=480]",
    }
    return quality_map.get(quality, quality_map["1080"])
