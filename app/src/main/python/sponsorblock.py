"""
sponsorblock.py — BBS Popcorn Android
Récupération des segments SponsorBlock via API REST publique.
Aucune dépendance GTK/UI — portable desktop → Android.

Optimisations :
- Cache mémoire par video_id : relire la même vidéo ne refait pas la requête
  (durée de vie du process ; les segments changent rarement).

Interface Android : get_segments_json() retourne du JSON brut,
parsé côté Kotlin (frontière de types propre entre les deux langages).
"""

import hashlib
import json
import logging
import urllib.request
import urllib.error
from typing import Optional

log = logging.getLogger("bbs.sponsorblock")

SPONSORBLOCK_API = "https://sponsor.ajay.app/api"

DEFAULT_CATEGORIES = [
    "sponsor",
    "selfpromo",
    "interaction",
    "intro",
    "outro",
    "preview",
    "filler",
]

# Cache mémoire : video_id -> list[dict]
_cache: dict = {}
_CACHE_MAX = 100


def get_segments(
    video_id: str,
    categories: list[str] = None,
    timeout: int = 10,
) -> list[dict]:
    """
    Récupère les segments SponsorBlock pour une vidéo YouTube.
    Résultat mis en cache mémoire (par video_id).

    Retourne une liste de dicts :
        [{"category": "sponsor", "start": 12.5, "end": 45.0}, ...]
    Retourne [] si aucun segment ou erreur.

    Utilise le hash partiel (4 premiers chars SHA256) pour la confidentialité.
    """
    if categories is None:
        categories = DEFAULT_CATEGORIES

    if not video_id:
        return []

    if video_id in _cache:
        log.debug(f"sponsorblock: cache hit pour {video_id}")
        return _cache[video_id]

    hash_prefix = hashlib.sha256(video_id.encode()).hexdigest()[:4]
    cats_param = "&".join(f"category={c}" for c in categories)
    url = f"{SPONSORBLOCK_API}/skipSegments/{hash_prefix}?{cats_param}"

    segments = []
    try:
        log.debug(f"sponsorblock: fetching segments for {video_id}")
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "BBS-Popcorn-Android/1.0"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())

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

    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            log.debug(f"sponsorblock: aucun segment pour {video_id}")
        else:
            log.debug(f"sponsorblock: HTTP {exc.code}")
            return []  # erreur serveur : ne pas cacher
    except Exception as exc:
        log.debug(f"sponsorblock: erreur: {exc}")
        return []  # erreur réseau : ne pas cacher

    # Cache (y compris liste vide sur 404 : la vidéo n'a pas de segments)
    if len(_cache) >= _CACHE_MAX:
        _cache.pop(next(iter(_cache)))  # éviction FIFO simple
    _cache[video_id] = segments
    return segments


def get_segments_json(video_id: str) -> str:
    """
    Interface Android/Chaquopy : retourne les segments en JSON brut.
    Toujours une string JSON valide, '[]' en cas d'échec.
    """
    try:
        return json.dumps(get_segments(video_id))
    except Exception as exc:
        log.debug(f"get_segments_json: erreur: {exc}")
        return "[]"


def extract_video_id(url: str) -> Optional[str]:
    """
    Extrait le video_id depuis une URL YouTube normalisée.
    """
    try:
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(url)
        params = parse_qs(parsed.query)
        ids = params.get("v", [])
        return ids[0] if ids else None
    except Exception:
        return None
