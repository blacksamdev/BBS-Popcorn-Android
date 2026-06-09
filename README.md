# BBS pOpcOrn Android 🍿

**YouTube via yt-dlp + Media3 — sans pub, sans compte**

BBS pOpcOrn Android est le port mobile de [BBS pOpcOrn](https://github.com/blacksamdev/BBS-Popcorn).  
Il résout les streams YouTube via yt-dlp (sans publicité), les joue via Media3/ExoPlayer,  
et supporte le casting Chromecast natif via le Cast SDK Google.

---

## Stack technique

| Composant | Technologie |
|---|---|
| UI | Kotlin + Android Views |
| Lecteur | Media3 / ExoPlayer |
| Résolution flux | yt-dlp (embarqué via Chaquopy) |
| SponsorBlock | API REST publique (Python embarqué) |
| Casting | Cast SDK Google (Chromecast natif) |
| Bridge Python↔Kotlin | Chaquopy |

---

## Architecture

```
WebView YouTube (interface)
        │
        ├── YtdlpBridge (Chaquopy)
        │       └── resolver.py  →  yt-dlp → URL stream propre
        │
        ├── SponsorBridge (Chaquopy)
        │       └── sponsorblock.py  →  segments à skipper
        │
        ├── BbsPlayer
        │       └── Media3/ExoPlayer  →  lecture locale
        │
        └── CastManager
                └── Cast SDK  →  Chromecast
```

---

## Fichiers Python embarqués

Les fichiers dans `app/src/main/python/` sont portés depuis BBS pOpcOrn Linux.  
Ils contiennent la logique métier sans aucune dépendance UI/GTK.

| Fichier | Origine | Rôle |
|---|---|---|
| `resolver.py` | Extrait de `player.py` | Normalisation URL + résolution yt-dlp |
| `sponsorblock.py` | Nouveau | API SponsorBlock |
| `cast_manager.py` | Port direct | Logique cast |
| `history_store.py` | Port direct | Historique local |
| `resume_store.py` | Port direct | Reprise de lecture |
| `logging_utils.py` | Port direct | Logs |

---

## Prérequis

- Android 8.0+ (API 26)
- Android Studio Hedgehog ou supérieur
- JDK 17

---

## Build

```bash
git clone https://github.com/blacksamdev/BBS-Popcorn-Android.git
cd BBS-Popcorn-Android
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/`.

---

## Avertissement légal

- Logiciel tiers non officiel, non affilié à YouTube ou Google
- Utilisation soumise aux Conditions d'utilisation de YouTube
- Les composants tiers (yt-dlp, Media3) sont soumis à leurs propres licences

---

## Licence

GPL-3.0 — développé par blacksamdev — en hommage à Samuel Bellamy 🏴‍☠️, le Prince des Pirates, capitaine du Whydah.
