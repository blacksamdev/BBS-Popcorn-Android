# BBS Popcorn Android — règles ProGuard/R8 pour le build release.
# Le build debug ne minifie pas ; ces règles protègent un futur release.

# Chaquopy : ne pas obfusquer le pont Python
-keep class com.chaquo.python.** { *; }

# Media3
-keep class androidx.media3.** { *; }

# Classes de l'app appelées par réflexion / binding
