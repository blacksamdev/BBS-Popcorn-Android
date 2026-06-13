# BBS pOpcOrn Android 🍿

**YouTube via Media3**

BBS pOpcOrn Android est le port mobile de [BBS pOpcOrn](https://github.com/blacksamdev/BBS-Popcorn).
Il affiche l'interface YouTube dans une WebView Android et délègue la lecture vidéo à Media3/ExoPlayer via des flux résolus par yt-dlp.

L'objectif est de proposer une application plus légère que l'application officielle, en s'appuyant sur les composants natifs Android.

---

## Fonctionnement

* Interface YouTube via WebView Android (m.youtube.com)
* Navigation, recherche et compte via l'interface web officielle
* Lecture vidéo via Media3/ExoPlayer (player natif Android)
* Résolution des flux via yt-dlp (embarqué dans l'APK via Chaquopy)
* Casting Chromecast via le Cast SDK Google (télécommande incluse)
* SponsorBlock (opt-in, désactivé par défaut) : saut automatique des segments signalés par la communauté
* Reprise de lecture par vidéo (60 jours)
* Historique des lectures (300 entrées, 90 jours)
* Qualité cible réglable (2160 / 1440 / 1080 / 720 / 480)
* Partage entrant depuis l'application YouTube officielle

Pendant la lecture, le bouton retour Android ramène à l'interface YouTube.

---

## Prérequis

* Android 8.0 minimum (API 26)
* Architectures : arm64-v8a, x86_64

---

## Installation

Télécharger l'APK depuis les [Releases](https://github.com/blacksamdev/BBS-Popcorn-Android/releases)
ou depuis les artifacts du dernier build CI.

L'installation nécessite d'autoriser les sources inconnues.

---

## Build depuis les sources

Prérequis : JDK 21, Android SDK (API 34).

```
git clone https://github.com/blacksamdev/BBS-Popcorn-Android.git
cd BBS-Popcorn-Android
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/`.
La CI GitHub Actions construit également un APK à chaque push sur `main`.

---

## Architecture

```
WebView Android (interface YouTube)
        │
        ├── interactions utilisateur — interception au clic vidéo
        │
        ├── yt-dlp (embarqué via Chaquopy)
        │
        ├── Media3/ExoPlayer (lecture locale)
        │
        └── Cast SDK (Chromecast)
```

La logique métier Python est portée depuis BBS pOpcOrn Linux et embarquée
dans l'APK via Chaquopy. La frontière Python ↔ Kotlin passe par du JSON brut.

---

## Stack technique

| Composant | Technologie |
| --- | --- |
| Interface | Kotlin + WebView Android |
| Lecteur | Media3 / ExoPlayer |
| Résolution flux | yt-dlp (embarqué via Chaquopy) |
| SponsorBlock | API REST publique |
| Casting | Cast SDK Google |
| Cookies | CookieManager Android (stockage local) |
| Pont Python ↔ Kotlin | Chaquopy |
| Packaging | APK (CI GitHub Actions) |

---

## Avertissement légal

* Logiciel tiers non officiel, non affilié à YouTube ou Google
* Utilisation soumise aux Conditions d'utilisation de YouTube
* L'utilisateur est responsable de son usage
* L'absence d'affichage publicitaire pendant la lecture n'est pas une fonctionnalité
  de l'application : c'est un effet de bord du fonctionnement de yt-dlp, qui résout
  l'URL du flux média directement. Ce comportement dépend de yt-dlp et de YouTube
  et peut changer à tout moment
* Les composants tiers (yt-dlp, Media3, Cast SDK, Chaquopy) sont soumis à leurs propres licences

---

## Données et confidentialité

* Toutes les données restent locales (historique, reprise, préférences)
* Cookies gérés par le CookieManager Android, stockage local uniquement
* Les cookies de session peuvent être transmis à yt-dlp localement
  (vidéos avec restriction d'âge) — jamais à un serveur tiers
* **Aucune communication vers un service tiers par défaut**
* SponsorBlock est désactivé par défaut. S'il est activé explicitement par
  l'utilisateur (réglages ⚙, avec consentement), l'application interroge
  l'API publique sponsor.ajay.app avec les 4 premiers caractères du hash
  SHA256 de l'identifiant vidéo (k-anonymat : le serveur ne peut pas
  identifier la vidéo regardée, le filtrage se fait localement). Aucun
  compte, aucun identifiant utilisateur, aucun titre transmis
* Aucun serveur backend, aucune télémétrie

---

## Qualité

Depuis l'icône `⚙` de la barre supérieure :

* Qualité max cible (2160 / 1440 / 1080 / 720 / 480)

Note : au-delà de 720p, YouTube fournit des flux HLS, lus nativement par Media3.

---

## Projet

Développé par **blacksamdev** — en hommage à Samuel Bellamy 🏴‍☠️,
le Prince des Pirates, capitaine du Whydah.

---

## Licence

GPL-3.0
