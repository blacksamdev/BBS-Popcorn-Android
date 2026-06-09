package io.github.blacksamdev.popcorn.model

/**
 * VideoItem — représentation d'une vidéo dans BBS Popcorn Android.
 */
data class VideoItem(
    val url: String,            // URL YouTube normalisée
    val streamUrl: String? = null, // URL stream résolu par yt-dlp
    val title: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
)
