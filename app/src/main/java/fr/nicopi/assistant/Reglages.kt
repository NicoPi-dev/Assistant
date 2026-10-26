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
}
