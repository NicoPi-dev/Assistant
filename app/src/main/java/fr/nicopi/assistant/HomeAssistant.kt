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
    private val CONDITIONS = mapOf(
        "clear-night" to "nuit dégagée", "cloudy" to "couvert", "exceptional" to "exceptionnel",
        "fog" to "brouillard", "hail" to "grêle", "lightning" to "orages", "lightning-rainy" to "orages et pluie",
        "partlycloudy" to "éclaircies", "pouring" to "fortes pluies", "rainy" to "pluie", "snowy" to "neige",
        "snowy-rainy" to "pluie et neige", "sunny" to "ensoleillé", "windy" to "venteux", "windy-variant" to "venteux et nuageux"
    )

    /**
     * Prévisions de la première entité météo de la liste blanche (Météo-France).
     * type : "jour" (quotidien) ou "heure" (horaire, 24 h). Appel réseau : hors thread principal.
     */
    fun previsions(context: Context, type: String): String {
        val meteo = capteurs(context).firstOrNull { it.entityId.startsWith("weather.") }
            ?: return "Aucune entité météo autorisée."
        val horaire = type == "heure"
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/services/weather/get_forecasts?return_response"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use {
                it.write(
                    JSONObject().put("entity_id", meteo.entityId)
                        .put("type", if (horaire) "hourly" else "daily").toString().toByteArray()
                )
            }
            if (conn.responseCode != 200) return "Prévisions : erreur Home Assistant ${conn.responseCode}"
            val liste = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .getJSONObject("service_response").getJSONObject(meteo.entityId).getJSONArray("forecast")
            val zone = java.time.ZoneId.systemDefault()
            val fmt = java.time.format.DateTimeFormatter.ofPattern(
                if (horaire) "EEEE HH'h'" else "EEEE d MMMM", java.util.Locale.FRANCE
            )
            val lignes = (0 until minOf(liste.length(), if (horaire) 24 else 10)).map { i ->
                val f = liste.getJSONObject(i)
                val quand = java.time.OffsetDateTime.parse(f.getString("datetime")).atZoneSameInstant(zone)
                val jour = if (horaire) quand else quand.toLocalDate().atStartOfDay(zone)
                val cond = f.optString("condition").let { CONDITIONS[it] ?: it }
                val temp = if (horaire) "${f.opt("temperature")}°C"
                else "mini ${f.opt("templow")}°C, maxi ${f.opt("temperature")}°C"
                val pluie = f.optDouble("precipitation", 0.0).takeIf { it > 0 }?.let { ", pluie $it mm" }.orEmpty()
                val proba = if (f.has("precipitation_probability")) ", risque de pluie ${f.opt("precipitation_probability")} %" else ""
                val vent = if (f.has("wind_speed")) ", vent ${f.opt("wind_speed")} km/h" else ""
                "${jour.format(fmt)} : $cond, $temp$pluie$proba$vent"
            }
            "Prévisions Météo-France (${if (horaire) "heure par heure" else "jour par jour"}) :\n" + lignes.joinToString("\n")
        } catch (e: Exception) {
            "Prévisions indisponibles (${e.message})"
        } finally {
            conn.disconnect()
        }
    }

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
            readTimeout = 45_000
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
            // Détail jour par jour (heure locale). On ignore les 5 premières minutes après minuit :
            // un compteur journalier y affiche encore le total de la veille avant sa remise à zéro.
            val parJour = (0 until points.length()).mapNotNull { i ->
                val p = points.getJSONObject(i)
                val v = p.optString("state").toDoubleOrNull() ?: return@mapNotNull null
                val t = runCatching {
                    java.time.OffsetDateTime.parse(p.optString("last_changed")).atZoneSameInstant(zone).toLocalDateTime()
                }.getOrNull() ?: return@mapNotNull null
                if (t.hour == 0 && t.minute < 5) null else t.toLocalDate() to v
            }.groupBy({ it.first }, { it.second }).toSortedMap()
            val fmtJour = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRANCE)
            val detail = if (parJour.size >= 2) {
                "\nPar jour : " + parJour.entries.joinToString(" ; ") { (j, vs) -> "${j.format(fmtJour)} max ${r(vs.max())}" } +
                    "\nSomme des maxima journaliers (= total de la période pour un compteur remis à zéro chaque nuit) : " +
                    r(parJour.values.sumOf { it.max() }) + " sur ${parJour.size} jours"
            } else ""
            "${capteur.libelle} du ${debut.toString().replace('T', ' ')} au ${fin.toString().replace('T', ' ')} : " +
                "première valeur ${r(valeurs.first())}, dernière ${r(valeurs.last())}, " +
                "min ${r(valeurs.min())}, max ${r(valeurs.max())}, moyenne ${r(valeurs.average())} " +
                "(${valeurs.size} relevés)" + detail
        } catch (e: Exception) {
            "${capteur.libelle} : injoignable (${e.message})"
        } finally {
            conn.disconnect()
        }
    }
}
