"""
history_store.py — BBS Popcorn Android
Historique des URLs jouées. Adapté depuis le desktop :
GLib.get_user_data_dir() remplacé par un data_dir fourni par Android
(context.filesDir via HistoryBridge).

Interface Chaquopy : fonctions module-level + JSON brut vers Kotlin.
"""

import json
import os
import time

_MAX_ENTRIES = 300
_MAX_AGE_SECONDS = 90 * 86400   # 90 jours


class HistoryStore:
    """
    Stocke l'historique des URLs jouées.
    Limite : 300 entrées max, 90 jours max.
    Les doublons sont dédupés (une URL = une entrée, la plus récente gagne).
    """

    def __init__(self, data_dir: str):
        self.path = os.path.join(data_dir, "bbs-popcorn", "history.json")
        self._data: list = []   # liste de {url, title, ts}, ordre chronologique
        self._load()

    # ─────────────────────────────
    # persistence
    # ─────────────────────────────

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                if isinstance(loaded, list):
                    self._data = loaded
        except Exception:
            self._data = []

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
        self._data = [e for e in self._data if e.get("ts", 0) >= cutoff]
        if len(self._data) > _MAX_ENTRIES:
            self._data = self._data[-_MAX_ENTRIES:]

    # ─────────────────────────────
    # public API
    # ─────────────────────────────

    def add(self, url: str, title: str = ""):
        """Ajoute une entrée. Dédupe par URL (la plus récente remplace l'ancienne)."""
        existing = next((e for e in self._data if e.get("url") == url), None)
        kept_title = title.strip() or (existing.get("title", "") if existing else "") or url
        self._data = [e for e in self._data if e.get("url") != url]
        self._data.append({
            "url": url,
            "title": kept_title,
            "ts": int(time.time()),
        })
        self._purge()
        self._save()

    def entries(self) -> list:
        """Retourne les entrées, la plus récente en premier."""
        return list(reversed(self._data))

    def clear(self):
        self._data = []
        self._save()


# ─────────────────────────────
# Interface Chaquopy (module-level)
# ─────────────────────────────

_store: HistoryStore | None = None


def init(data_dir: str):
    """À appeler une fois au démarrage avec context.filesDir."""
    global _store
    _store = HistoryStore(data_dir)


def add(url: str, title: str = ""):
    if _store is not None:
        _store.add(url, title)


def entries_json() -> str:
    """Retourne l'historique en JSON brut (plus récent en premier)."""
    try:
        return json.dumps(_store.entries() if _store else [])
    except Exception:
        return "[]"


def clear():
    if _store is not None:
        _store.clear()
