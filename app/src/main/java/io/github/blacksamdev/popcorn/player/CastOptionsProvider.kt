package io.github.blacksamdev.popcorn.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * CastOptionsProvider — configuration du Cast SDK Google.
 * Référencé dans AndroidManifest.xml (meta-data OPTIONS_PROVIDER_CLASS_NAME).
 *
 * Utilise le Default Media Receiver de Google : aucune app receiver
 * custom à enregistrer, le Chromecast affiche le flux directement.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
        .setReceiverApplicationId(
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        )
        .build()
    }

    override fun getAdditionalSessionProviders(
        context: Context
    ): MutableList<SessionProvider>? = null
}
