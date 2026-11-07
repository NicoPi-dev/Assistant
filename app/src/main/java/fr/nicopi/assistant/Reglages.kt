package fr.nicopi.assistant

import android.content.Context

/** Réglages de l'appli, gardés sur le téléphone. */
object Reglages {
    private fun prefs(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    /** « Hey Claude » : écoute permanente du mot d'appel. Désactivé par défaut. */
    fun heyClaude(c: Context): Boolean = prefs(c).getBoolean("hey_claude", false)

    fun setHeyClaude(c: Context, actif: Boolean) {
        prefs(c).edit().putBoolean("hey_claude", actif).commit()
    }

    /** Volume de la voix de l'assistant, par rapport au volume du téléphone (1.0 = 100 %). */
    const val VOLUME_VOIX = 0.45f

    /** Paramètres à passer à TextToSpeech.speak() : voix à 45 % sans toucher au volume général. */
    fun parametresVoix() = android.os.Bundle().apply {
        putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, VOLUME_VOIX)
    }
}
