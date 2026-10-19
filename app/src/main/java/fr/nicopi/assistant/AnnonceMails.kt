package fr.nicopi.assistant

import android.app.Notification
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Annonce à voix haute les nouveaux mails Gmail (expéditeur et sujet).
 * Lecture locale uniquement : rien n'est envoyé à Claude, aucun risque de piège.
 */
class AnnonceMails : NotificationListenerService() {

    private var tts: TextToSpeech? = null
    private var pret = false
    private val dejaAnnonces = ArrayDeque<String>()

    override fun onListenerConnected() {
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                pret = true
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.gm") return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Pas d'annonce si le téléphone est en silencieux ou vibreur
        val audio = getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        // Une seule annonce par mail, même si la notification est mise à jour
        // Gmail garde la même clé pour toute une conversation : on y ajoute
        // l'heure du mail, sinon les mails suivants du même fil sont ignorés.
        val id = "${sbn.key}|${n.`when`}"
        if (id in dejaAnnonces) return
        dejaAnnonces.addLast(id)
        if (dejaAnnonces.size > 50) dejaAnnonces.removeFirst()

        val de = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(60) ?: return
        val sujet = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(120).orEmpty()
        if (pret) {
            tts?.speak("Nouveau mail de $de. $sujet", TextToSpeech.QUEUE_ADD, null, sbn.key)
        }
    }

    override fun onListenerDisconnected() {
        tts?.shutdown()
        tts = null
        pret = false
    }
}
