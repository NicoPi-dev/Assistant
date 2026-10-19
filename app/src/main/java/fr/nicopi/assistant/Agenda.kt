package fr.nicopi.assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un événement à créer, en attente de ta confirmation. */
data class EvenementBrouillon(
    val titre: String,
    val debut: LocalDateTime,
    val fin: LocalDateTime,
    val journeeEntiere: Boolean,
    val lieu: String
) {
    /** Description en clair pour la carte et la voix. */
    fun resume(): String {
        val jour = debut.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
        return if (journeeEntiere) "$jour, toute la journée"
        else "$jour de ${debut.format(H)} à ${fin.format(H)}"
    }

    companion object {
        private val H = DateTimeFormatter.ofPattern("HH'h'mm")
    }
}

/**
 * L'agenda du téléphone (dont Google Agenda, déjà synchronisé par Android).
 * Aucune connexion internet ni configuration Google nécessaire.
 */
object Agenda {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", Locale.FRANCE)

    /** Date et heure actuelles, pour que Claude comprenne « demain », « vendredi »… */
    fun maintenant(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE))

    /** "2026-10-20T14:00" ou "2026-10-20" -> date et heure locales. */
    fun lireDate(texte: String): LocalDateTime =
        if (texte.length <= 10) LocalDate.parse(texte).atStartOfDay()
        else LocalDateTime.parse(texte.take(16))

    /** Les événements entre deux dates, triés. */
    fun lister(context: Context, debut: LocalDateTime, fin: LocalDateTime): String {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, debut.atZone(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, fin.atZone(zone).toInstant().toEpochMilli())
        }.build()

        val lignes = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            ),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && lignes.size < 30) {
                val titre = c.getString(0) ?: "(sans titre)"
                val toute = c.getInt(3) == 1
                val d = Instant.ofEpochMilli(c.getLong(1)).atZone(if (toute) ZoneId.of("UTC") else zone)
                val f = Instant.ofEpochMilli(c.getLong(2)).atZone(if (toute) ZoneId.of("UTC") else zone)
                val quand = if (toute) d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)) + " (toute la journée)"
                else d.format(FORMAT) + " → " + f.format(DateTimeFormatter.ofPattern("HH:mm"))
                val lieu = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " — lieu : $it" }.orEmpty()
                lignes += "$quand : $titre$lieu [${c.getString(5)}]"
            }
        }
        return if (lignes.isEmpty()) "Aucun événement dans cette période." else lignes.joinToString("\n")
    }

    /** L'agenda principal Google, sinon le premier agenda modifiable. */
    private fun agendaParDefaut(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}" +
            " AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var premier: Long? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (premier == null) premier = id
                if (c.getInt(1) == 1 && c.getString(2) == "com.google") return id
            }
        }
        return premier
    }

    fun creer(context: Context, ev: EvenementBrouillon) {
        val agenda = agendaParDefaut(context) ?: throw IllegalStateException("Aucun agenda modifiable trouvé")
        val valeurs = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, agenda)
            put(CalendarContract.Events.TITLE, ev.titre)
            if (ev.lieu.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, ev.lieu)
            if (ev.journeeEntiere) {
                // Les journées entières se notent en UTC, de minuit à minuit
                val utc = ZoneId.of("UTC")
                put(CalendarContract.Events.DTSTART, ev.debut.toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, ev.fin.toLocalDate().plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.ALL_DAY, 1)
            } else {
                put(CalendarContract.Events.DTSTART, ev.debut.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, ev.fin.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valeurs)
            ?: throw IllegalStateException("L'agenda a refusé l'événement")
    }
}
