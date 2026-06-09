"""
sponsorblock.py — BBS Popcorn Android
Récupération des segments SponsorBlock via API REST publique.
Aucune dépendance GTK/UI — portable desktop → Android.
"""

import hashlib
import json
import logging
import urllib.request
import urllib.error
from typing import Optional

log = logging.getLogger("bbs.sponsorblock")

SPONSORBLOCK_API = "https://sponsor.ajay.app/api"

# Catégories skip par défaut
DEFAULT_CATEGORIES = [
    "sponsor",
    "selfpromo",
    "interaction",
    "intro",
    "outro",
    "preview",
    "filler",
]


def get_segments(
    video_id: str,
    categories: list[str] = None,
    timeout: int = 10,
) -> list[dict]:
    """
    Récupère les segments SponsorBlock pour une vidéo YouTube.

    Retourne une liste de dicts :
        [{"category": "sponsor", "start": 12.5, "end": 45.0}, ...]
    Retourne [] si aucun segment ou erreur.

    Utilise le hash partiel (8 premiers chars SHA256) pour la confidentialité.
    """
    if categories is None:
        categories = DEFAULT_CATEGORIES

    if not video_id:
        return []

    hash_prefix = hashlib.sha256(video_id.encode()).hexdigest()[:8]
    cats_param = "&".join(f"categories[]={c}" for c in categories)
    url = f"{SPONSORBLOCK_API}/skipSegments/{hash_prefix}?{cats_param}"

    try:
        log.debug(f"sponsorblock: fetching segments for {video_id}")
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "BBS-Popcorn-Android/1.0"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())

        segments = []
        for entry in data:
            if entry.get("videoID") != video_id:
                continue
            for seg in entry.get("segments", []):
                start, end = seg.get("segment", [None, None])
                category = seg.get("category", "")
                if start is not None and end is not None and category:
                    segments.append({
                        "category": category,
                        "start": float(start),
                        "end": float(end),
                    })

        log.debug(f"sponsorblock: {len(segments)} segments trouvés")
        return segments

    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            log.debug(f"sponsorblock: aucun segment pour {video_id}")
        else:
            log.debug(f"sponsorblock: HTTP {exc.code}")
    except Exception as exc:
        log.debug(f"sponsorblock: erreur: {exc}")

    return []


def extract_video_id(url: str) -> Optional[str]:
    """
    Extrait le video_id depuis une URL YouTube normalisée.
    Attend une URL de type https://www.youtube.com/watch?v=VIDEO_ID.
    """
    try:
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(url)
        params = parse_qs(parsed.query)
        ids = params.get("v", [])
        return ids[0] if ids else None
    except Exception:
        return None
