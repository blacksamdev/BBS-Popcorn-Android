# BBS Popcorn Android — règles ProGuard/R8 pour le build release.
# Le build debug ne minifie pas ; ces règles protègent un futur release.

# Chaquopy : ne pas obfusquer le pont Python
-keep class com.chaquo.python.** { *; }

# Cast SDK
-keep class com.google.android.gms.cast.** { *; }

# Media3
-keep class androidx.media3.** { *; }

# Classes de l'app appelées par réflexion / binding
-keep class io.github.blacksamdev.popcorn.player.CastOptionsProvider { *; }
