"""
cookies.py — BBS Popcorn Android
Écriture d'un fichier cookies.txt au format Netscape pour yt-dlp,
à partir du header Cookie de la WebView Android (CookieManager).

Aligné sur l'approche desktop (cookies.py Linux) :
- on ne garde QUE les cookies des domaines YouTube/Google utiles
- on passe ensuite ce fichier à yt-dlp via l'option `cookiefile`
  (et NON via un header HTTP brut, qui court-circuite la gestion de
  session de yt-dlp et casse la résolution sur compte connecté)

Sur desktop, les cookies viennent du SQLite WebKit. Sur Android, le
CookieManager nous donne directement une chaîne "k=v; k2=v2; ..." pour
un domaine donné — on la convertit au format Netscape.
"""

import os
import time
import logging

log = logging.getLogger("bbs.cookies")

NETSCAPE_HEADER = "# Netscape HTTP Cookie File\n"

# Mêmes domaines que le desktop (_is_allowed_cookie_host)
ALLOWED_DOMAINS = (
    "youtube.com",
    "youtu.be",
    "google.com",
    "googlevideo.com",
    "ytimg.com",
)

_cookie_file_path: str | None = None


def init(data_dir: str):
    """Définit le chemin du fichier cookies.txt (dans le stockage app)."""
    global _cookie_file_path
    _cookie_file_path = os.path.join(data_dir, "bbs-popcorn", "cookies.txt")


def _is_allowed_host(host: str) -> bool:
    if not host:
        return False
    normalized = host.lstrip(".").lower()
    return any(
        normalized == d or normalized.endswith(f".{d}")
        for d in ALLOWED_DOMAINS
    )


def write_cookies(cookie_pairs_by_domain: dict) -> str | None:
    """
    Écrit le fichier Netscape à partir d'un dict :
        { ".youtube.com": "VISITOR_INFO1_LIVE=xxx; PREF=yyy; ...", ... }

    Retourne le chemin du fichier, ou None si rien à écrire / erreur.
    Seuls les domaines autorisés sont conservés.
    """
    if not _cookie_file_path:
        log.debug("write_cookies: chemin non initialisé")
        return None

    lines = [NETSCAPE_HEADER]
    expiry = int(time.time()) + 31536000  # +1 an
    count = 0

    for domain, cookie_str in (cookie_pairs_by_domain or {}).items():
        if not _is_allowed_host(domain):
            continue
        if not cookie_str:
            continue

        host = domain if domain.startswith(".") else f".{domain}"
        include_sub = "TRUE"
        path = "/"
        secure = "TRUE"

        for pair in cookie_str.split(";"):
            pair = pair.strip()
            if not pair or "=" not in pair:
                continue
            name, value = pair.split("=", 1)
            name = name.strip()
            value = value.strip()
            if not name:
                continue
            # Format Netscape : host \t include_sub \t path \t secure \t expiry \t name \t value
            lines.append(
                f"{host}\t{include_sub}\t{path}\t{secure}\t{expiry}\t{name}\t{value}\n"
            )
            count += 1

    if count == 0:
        log.debug("write_cookies: aucun cookie autorisé")
        return None

    try:
        os.makedirs(os.path.dirname(_cookie_file_path), exist_ok=True)
        with open(_cookie_file_path, "w", encoding="utf-8") as f:
            f.writelines(lines)
        os.chmod(_cookie_file_path, 0o600)
        log.debug(f"write_cookies: {count} cookies écrits")
        return _cookie_file_path
    except Exception as exc:
        log.debug(f"write_cookies: erreur écriture: {exc}")
        return None


def clear_cookies():
    """Supprime le fichier cookies.txt (déconnexion)."""
    if _cookie_file_path and os.path.exists(_cookie_file_path):
        try:
            os.remove(_cookie_file_path)
        except OSError:
            pass
