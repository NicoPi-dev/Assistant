package fr.nicopi.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Calendar
import java.util.Locale

/**
 * Réglages de l'appli, gardés sur le téléphone (écran « Réglages », roue dentée en haut).
 * Les règles de sécurité (confirmations, blocages après lecture d'un mail…) n'en font pas
 * partie : elles restent fixes dans le code.
 */
object Reglages {
    private fun prefs(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    /** « Hey Claude » : écoute permanente du mot d'appel. Désactivé par défaut. */
    fun heyClaude(c: Context): Boolean = prefs(c).getBoolean("hey_claude", false)

    fun setHeyClaude(c: Context, actif: Boolean) {
        prefs(c).edit().putBoolean("hey_claude", actif).commit()
    }

    // --- Voix ---
    /** Volume de la voix, par rapport au volume du téléphone (1.0 = 100 %). */
    fun volume(c: Context): Float = prefs(c).getFloat("volume_voix", 0.45f)
    fun vitesse(c: Context): Float = prefs(c).getFloat("vitesse_voix", 1.0f)
    fun hauteur(c: Context): Float = prefs(c).getFloat("hauteur_voix", 1.0f)
    /** Nom de la voix choisie ; vide = voix française par défaut du moteur. */
    fun voix(c: Context): String = prefs(c).getString("voix", "").orEmpty()

    // --- Comportement ---
    fun fermetureSecondes(c: Context): Int = prefs(c).getInt("fermeture_s", 10)
    fun conversationContinue(c: Context): Boolean = prefs(c).getBoolean("conversation_continue", true)
    fun delaiSmsSecondes(c: Context): Int = prefs(c).getInt("delai_sms_s", 5)

    // --- Annonces ---
    fun annonceMails(c: Context): Boolean = prefs(c).getBoolean("annonce_mails", true)
    fun heuresCalmes(c: Context): Boolean = prefs(c).getBoolean("heures_calmes", false)
    fun calmeDebut(c: Context): Int = prefs(c).getInt("calme_debut", 22)
    fun calmeFin(c: Context): Int = prefs(c).getInt("calme_fin", 7)

    fun ecrire(c: Context, cle: String, valeur: Any) {
        val e = prefs(c).edit()
        when (valeur) {
            is Boolean -> e.putBoolean(cle, valeur)
            is Int -> e.putInt(cle, valeur)
            is Float -> e.putFloat(cle, valeur)
            is String -> e.putString(cle, valeur)
        }
        e.apply()
    }

    /** Vrai pendant les heures calmes : pas d'annonce vocale (les notifications restent). */
    fun enHeuresCalmes(c: Context): Boolean {
        if (!heuresCalmes(c)) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val d = calmeDebut(c)
        val f = calmeFin(c)
        return when {
            d == f -> false
            d < f -> h in d until f
            else -> h >= d || h < f
        }
    }

    /** Paramètres à passer à TextToSpeech.speak() : volume de la voix sans toucher au volume général. */
    fun parametresVoix(c: Context) = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume(c))
    }

    /** Applique la langue, la voix choisie, la vitesse et la hauteur à un moteur prêt. */
    fun appliquerVoix(c: Context, tts: TextToSpeech) {
        tts.language = Locale.FRANCE
        val nom = voix(c)
        if (nom.isNotEmpty()) {
            runCatching { tts.voices?.firstOrNull { it.name == nom } }.getOrNull()?.let { tts.voice = it }
        }
        tts.setSpeechRate(vitesse(c))
        tts.setPitch(hauteur(c))
    }

    /** Voix françaises installées sur le téléphone (sans celles qui demandent internet). */
    fun voixFrancaises(tts: TextToSpeech): List<Voice> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())
}
