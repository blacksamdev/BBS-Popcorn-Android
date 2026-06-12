"""
resume_store.py — BBS Popcorn Android
Reprise de lecture : mémorise la position par URL.
Adapté depuis le desktop : GLib remplacé par un data_dir fourni par Android.

Règles :
- Position < 10s          → pas de sauvegarde (lecture à peine commencée)
- Position > 95% de durée → entrée effacée (vidéo considérée terminée)
- 200 entrées max, 60 jours max

Interface Chaquopy : fonctions module-level, valeurs scalaires simples.
"""

import json
import os
import time

_MAX_ENTRIES = 200
_MAX_AGE_SECONDS = 60 * 86400   # 60 jours
_MIN_POSITION_S = 10.0
_COMPLETED_RATIO = 0.95


class ResumeStore:

    def __init__(self, data_dir: str):
        self.path = os.path.join(data_dir, "bbs-popcorn", "resume.json")
        self._data: dict = {}   # url -> {pos, dur, ts}
        self._load()

    # ─────────────────────────────
    # persistence
    # ─────────────────────────────

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                if isinstance(loaded, dict):
                    self._data = loaded
        except Exception:
            self._data = {}

    def _save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self._data, f)
        except Exception:
            pass

    # ─────────────────────────────
    # purge
    # ─────────────────────────────

    def _purge(self):
        cutoff = time.time() - _MAX_AGE_SECONDS
        self._data = {
            url: e for url, e in self._data.items()
            if e.get("ts", 0) >= cutoff
        }
        if len(self._data) > _MAX_ENTRIES:
            # garder les plus récentes
            items = sorted(self._data.items(), key=lambda kv: kv[1].get("ts", 0))
            self._data = dict(items[-_MAX_ENTRIES:])

    # ─────────────────────────────
    # public API
    # ─────────────────────────────

    def get(self, url: str) -> float:
        """Retourne la position de reprise en secondes, 0.0 si aucune."""
        entry = self._data.get(url)
        if not entry:
            return 0.0
        return float(entry.get("pos", 0.0))

    def set(self, url: str, position_s: float, duration_s: float = 0.0):
        """Enregistre la position. Efface si quasi-terminée, ignore si < 10s."""
        if position_s < _MIN_POSITION_S:
            return
        if duration_s > 0 and position_s >= duration_s * _COMPLETED_RATIO:
            self._data.pop(url, None)
            self._save()
            return
        self._data[url] = {
            "pos": float(position_s),
            "dur": float(duration_s),
            "ts": int(time.time()),
        }
        self._purge()
        self._save()

    def clear(self):
        self._data = {}
        self._save()


# ─────────────────────────────
# Interface Chaquopy (module-level)
# ─────────────────────────────

_store: ResumeStore | None = None


def init(data_dir: str):
    global _store
    _store = ResumeStore(data_dir)


def get(url: str) -> float:
    return _store.get(url) if _store else 0.0


def set_position(url: str, position_s: float, duration_s: float = 0.0):
    if _store is not None:
        _store.set(url, position_s, duration_s)


def clear():
    if _store is not None:
        _store.clear()
