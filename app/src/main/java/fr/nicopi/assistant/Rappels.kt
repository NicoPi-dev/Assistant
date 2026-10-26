package fr.nicopi.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Rappels discrets : à l'heure dite, une notification et une annonce vocale (« Rappel : … »),
 * même si l'appli est fermée. Programmés avec AlarmManager, gardés sur le téléphone
 * et reprogrammés au redémarrage.
 */
object Rappels {

    private const val CANAL = "rappels"
    private const val CLE = "liste"

    private fun prefs(c: Context) = c.getSharedPreferences("rappels", Context.MODE_PRIVATE)

    private fun charger(c: Context): JSONArray =
        runCatching { JSONArray(prefs(c).getString(CLE, "[]")) }.getOrDefault(JSONArray())

    private fun sauver(c: Context, liste: JSONArray) {
        prefs(c).edit().putString(CLE, liste.toString()).commit()
    }

    fun planifier(c: Context, quand: LocalDateTime, texte: String) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val ms = quand.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val liste = charger(c)
        liste.put(JSONObject().put("id", id).put("ms", ms).put("texte", texte))
        sauver(c, liste)
        programmer(c, id, ms, texte)
    }

    private fun intention(c: Context, id: Int, texte: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, RappelRecu::class.java).putExtra("id", id).putExtra("texte", texte),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun programmer(c: Context, id: Int, ms: Long, texte: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = intention(c, id, texte)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    fun retirer(c: Context, id: Int) {
        val liste = charger(c)
        val reste = JSONArray()
        for (i in 0 until liste.length()) liste.optJSONObject(i)?.let { if (it.optInt("id") != id) reste.put(it) }
        sauver(c, reste)
    }

    /** Après un redémarrage du téléphone : reprogramme les rappels à venir, annonce ceux qui sont passés. */
    fun reprogrammer(c: Context) {
        val maintenant = System.currentTimeMillis()
        val liste = charger(c)
        for (i in 0 until liste.length()) {
            val r = liste.optJSONObject(i) ?: continue
            val id = r.optInt("id")
            val texte = r.optString("texte")
            if (r.optLong("ms") > maintenant) programmer(c, id, r.optLong("ms"), texte)
            else { retirer(c, id); notifier(c, id, "$texte (rappel manqué pendant que le téléphone était éteint)") }
        }
    }

    fun notifier(c: Context, id: Int, texte: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Rappels", NotificationManager.IMPORTANCE_HIGH))
        val ouvrir = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Rappel")
            .setContentText(texte)
            .setStyle(android.app.Notification.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        nm.notify(10_000 + (id % 10_000), n)
    }
}

/** Réception d'un rappel : notification, puis annonce vocale si le téléphone n'est pas en silencieux. */
class RappelRecu : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val texte = intent.getStringExtra("texte").orEmpty()
        Rappels.retirer(context, id)
        Rappels.notifier(context, id, texte)

        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val fin = goAsync()
        val main = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var termine = false
        fun terminer() {
            if (termine) return
            termine = true
            tts?.shutdown()
            fin.finish()
        }
        tts = TextToSpeech(context.applicationContext) { etat ->
            val moteur = tts
            if (etat != TextToSpeech.SUCCESS || moteur == null) { terminer(); return@TextToSpeech }
            moteur.language = Locale.FRANCE
            moteur.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { terminer() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { terminer() } }
            })
            moteur.speak("Rappel : $texte", TextToSpeech.QUEUE_FLUSH, null, "rappel")
        }
        // Sécurité : un récepteur ne doit pas durer plus de quelques secondes
        main.postDelayed({ terminer() }, 9_000)
    }
}

/** Redémarrage du téléphone : les rappels programmés sont perdus par Android, on les remet. */
class RappelDemarrage : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Rappels.reprogrammer(context)
    }
}
