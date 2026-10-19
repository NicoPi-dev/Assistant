package fr.nicopi.assistant

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Un capteur que l'assistant a le droit de lire. */
data class Capteur(val entityId: String, val libelle: String, val groupe: String)

/**
 * Lecture seule de Home Assistant, limitée à la liste blanche
 * `assets/home_assistant_capteurs.json`.
 */
object HomeAssistant {

    private var capteurs: List<Capteur>? = null

    val configure: Boolean
        get() = BuildConfig.HA_URL.isNotBlank() && BuildConfig.HA_TOKEN.isNotBlank()

    /** La liste blanche, chargée une fois depuis les assets. */
    fun capteurs(context: Context): List<Capteur> {
        capteurs?.let { return it }
        val json = runCatching {
            context.assets.open("home_assistant_capteurs.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList<Capteur>().also { capteurs = it }
        val groupes = JSONObject(json).getJSONObject("groupes")
        val liste = mutableListOf<Capteur>()
        for (groupe in groupes.keys()) {
            val items = groupes.getJSONArray(groupe)
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                liste += Capteur(o.getString("entity_id"), o.getString("libelle"), groupe)
            }
        }
        return liste.also { capteurs = it }
    }

    /** Texte injecté dans les instructions de Claude : un capteur par ligne. */
    fun catalogue(context: Context): String =
        capteurs(context).groupBy { it.groupe }.entries.joinToString("\n") { (groupe, liste) ->
            "[$groupe]\n" + liste.joinToString("\n") { "- ${it.entityId} : ${it.libelle}" }
        }

    /**
     * Lit l'état d'un capteur. Refuse tout ce qui n'est pas dans la liste blanche.
     * Appel réseau : à lancer hors du thread principal.
     */
    fun lire(context: Context, entityId: String): String {
        val capteur = capteurs(context).firstOrNull { it.entityId == entityId }
            ?: return "$entityId : non autorisé"

        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/states/" +
            URLEncoder.encode(entityId, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) return "${capteur.libelle} : erreur Home Assistant $code"
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val etat = o.getString("state")
            val attr = o.optJSONObject("attributes") ?: JSONObject()
            val unite = attr.optString("unit_of_measurement", "")
            val details = if (entityId.startsWith("weather.")) {
                listOf("temperature", "humidity", "wind_speed")
                    .filter { attr.has(it) }
                    .joinToString(", ") { "$it=${attr.get(it)}" }
            } else ""
            val extra = if (details.isNotEmpty()) " ($details)" else ""
            "${capteur.libelle} : $etat $unite$extra".trim()
        } catch (e: Exception) {
            "${capteur.libelle} : injoignable (${e.message})"
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Historique d'un capteur entre deux dates (heure locale) : premières et dernières valeurs,
     * minimum, maximum et moyenne. Pour un compteur journalier (remis à zéro chaque nuit),
     * la consommation d'une journée est sa valeur maximale ce jour-là ; pour un compteur
     * qui ne fait qu'augmenter (index), c'est la différence dernière − première valeur.
     */
    fun historique(
        context: Context,
        entityId: String,
        debut: java.time.LocalDateTime,
        fin: java.time.LocalDateTime
    ): String {
        val capteur = capteurs(context).firstOrNull { it.entityId == entityId }
            ?: return "$entityId : non autorisé"
        val zone = java.time.ZoneId.systemDefault()
        val iso = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val d = debut.atZone(zone).toOffsetDateTime().format(iso)
        val f = fin.atZone(zone).toOffsetDateTime().format(iso)
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/history/period/" + URLEncoder.encode(d, "UTF-8") +
            "?filter_entity_id=" + URLEncoder.encode(entityId, "UTF-8") +
            "&end_time=" + URLEncoder.encode(f, "UTF-8") +
            "&minimal_response&no_attributes"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
        }
        return try {
            if (conn.responseCode != 200) return "${capteur.libelle} : erreur Home Assistant ${conn.responseCode}"
            val tout = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (tout.length() == 0) return "${capteur.libelle} : aucun historique sur cette période"
            val points = tout.getJSONArray(0)
            val valeurs = (0 until points.length())
                .mapNotNull { points.getJSONObject(it).optString("state").toDoubleOrNull() }
            if (valeurs.isEmpty()) {
                // Capteur non numérique (porte, pompe…) : on liste les changements d'état
                val etats = (0 until points.length()).map {
                    val p = points.getJSONObject(it)
                    p.optString("last_changed").take(16).replace('T', ' ') + " → " + p.optString("state")
                }
                return "${capteur.libelle} (changements d'état) :\n" + etats.takeLast(20).joinToString("\n")
            }
            fun r(x: Double) = "%.2f".format(java.util.Locale.FRANCE, x)
            "${capteur.libelle} du ${debut.toString().replace('T', ' ')} au ${fin.toString().replace('T', ' ')} : " +
                "première valeur ${r(valeurs.first())}, dernière ${r(valeurs.last())}, " +
                "min ${r(valeurs.min())}, max ${r(valeurs.max())}, moyenne ${r(valeurs.average())} " +
                "(${valeurs.size} relevés)"
        } catch (e: Exception) {
            "${capteur.libelle} : injoignable (${e.message})"
        } finally {
            conn.disconnect()
        }
    }
}
