package fr.nicopi.assistant

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Alarmes, minuteurs et rappels, via l'application Horloge et l'agenda du téléphone. */
object Horloge {

    fun alarme(activite: Activity, heure: Int, minute: Int, libelle: String, jours: List<Int>) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, heure)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (libelle.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, libelle)
            // Jours au format Android : 1 = dimanche … 7 = samedi
            if (jours.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(jours))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        activite.startActivity(intent)
    }

    fun minuteur(activite: Activity, secondes: Int, libelle: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secondes)
            if (libelle.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, libelle)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        activite.startActivity(intent)
    }

    /**
     * Rappel : dans les 24 h, une alarme nommée (impossible à rater) ;
     * au-delà, un événement d'agenda avec notification à l'heure dite.
     * Renvoie une phrase de confirmation.
     */
    fun rappel(activite: Activity, quand: LocalDateTime, texte: String, sonnerie: Boolean = false): String {
        val maintenant = LocalDateTime.now()
        val jour = quand.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
        val h = quand.format(DateTimeFormatter.ofPattern("HH'h'mm"))
        if (quand.isBefore(maintenant)) return "Cette heure est déjà passée."

        if (Duration.between(maintenant, quand).toHours() < 24) {
            // Par défaut, un rappel discret (notification lue à voix haute) ; une alarme seulement si demandé
            if (sonnerie) alarme(activite, quand.hour, quand.minute, "Rappel : $texte", emptyList())
            else Rappels.planifier(activite, quand, texte)
            return "C'est noté, je te le rappelle à $h."
        }

        Agenda.creer(
            activite,
            EvenementBrouillon(texte, quand, quand.plusMinutes(15), false, "")
        )
        // Notification à l'heure exacte de l'événement
        val id = dernierEvenement(activite, texte)
        if (id != null) {
            val rappel = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, 0)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            activite.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rappel)
        }
        return "C'est noté, je te le rappelle $jour à $h."
    }

    private fun dernierEvenement(activite: Activity, titre: String): Long? =
        activite.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE} = ?",
            arrayOf(titre),
            "${CalendarContract.Events._ID} DESC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Lieux favoris lus dans local.properties : FAVORIS=maison=lat,lon;travail=lat,lon */
    val FAVORIS: Map<String, String> = BuildConfig.FAVORIS.split(';')
        .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
        .associate { (k, v) -> k.trim().lowercase() to v.trim() }

    /** Ouvre Google Maps en navigation guidée vers la destination. */
    fun naviguer(activite: Activity, destination: String, mode: String) {
        val cle = destination.lowercase().trim()
        val cible = FAVORIS.entries.firstOrNull { cle == it.key || cle.endsWith(" " + it.key) }?.value ?: destination
        val m = when (mode) { "marche" -> "w"; "velo" -> "b"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(cible) + "&mode=" + m))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activite.startActivity(nav)
        } catch (e: android.content.ActivityNotFoundException) {
            // Google Maps absent : n'importe quelle appli de cartes
            activite.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(cible)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Durée lisible : 90 -> "1 minute 30", 3600 -> "1 heure". */
    fun duree(secondes: Int): String {
        val h = secondes / 3600
        val m = (secondes % 3600) / 60
        val s = secondes % 60
        return listOfNotNull(
            if (h > 0) "$h heure" + (if (h > 1) "s" else "") else null,
            if (m > 0) "$m minute" + (if (m > 1) "s" else "") else null,
            if (s > 0) "$s seconde" + (if (s > 1) "s" else "") else null
        ).joinToString(" ")
    }
}
