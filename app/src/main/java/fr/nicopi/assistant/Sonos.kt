package fr.nicopi.assistant

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musique Spotify sur les enceintes Sonos, via Home Assistant.
 * - La recherche du morceau passe par l'API Spotify (clé « client credentials », lecture seule).
 * - La lecture passe par Home Assistant (media_player.play_media), avec le compte Spotify relié à Sonos.
 * Seules les enceintes listées dans local.properties (SONOS=bureau=media_player.x;sejour=media_player.y)
 * peuvent être pilotées, et seulement avec les services de lecture ci-dessous.
 */
object Sonos {

    const val TOUTES = "les_deux"

    /** nom -> entity_id, depuis BuildConfig.SONOS */
    val ENCEINTES: Map<String, String> = BuildConfig.SONOS.split(';')
        .mapNotNull { part ->
            val (nom, id) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            if (id.trim().startsWith("media_player.")) nom.trim().lowercase() to id.trim() else null
        }.toMap()

    val configure: Boolean
        get() = HomeAssistant.configure && ENCEINTES.isNotEmpty() &&
            BuildConfig.SPOTIFY_ID.isNotBlank() && BuildConfig.SPOTIFY_SECRET.isNotBlank()

    /** Schéma JSON du paramètre « ou » pour les outils musique de Claude. */
    fun schemaOu(): String {
        val valeurs = mutableListOf("telephone")
        if (configure) {
            valeurs += ENCEINTES.keys
            if (ENCEINTES.size > 1) valeurs += TOUTES
        }
        val enumJson = JSONArray(valeurs).toString()
        return """{ "type": "string", "enum": $enumJson, "description": "Où jouer : telephone (par défaut), une enceinte Sonos, ou $TOUTES pour toutes les enceintes" }"""
    }

    /** Nom lisible : « Sonos Bureau », « les deux Sonos ». */
    fun libelle(ou: String): String =
        if (ou == TOUTES) "les Sonos" else "Sonos " + ou.replaceFirstChar { it.uppercase() }

    private fun cibles(ou: String): List<String> =
        if (ou == TOUTES) ENCEINTES.values.toList()
        else listOfNotNull(ENCEINTES[ou.lowercase()]).ifEmpty { throw IllegalArgumentException("Enceinte inconnue : $ou") }

    // --- Spotify (recherche) ---

    private var jeton: String? = null
    private var expiration = 0L

    private fun jetonSpotify(): String {
        jeton?.let { if (System.currentTimeMillis() < expiration) return it }
        val auth = Base64.encodeToString(
            "${BuildConfig.SPOTIFY_ID}:${BuildConfig.SPOTIFY_SECRET}".toByteArray(), Base64.NO_WRAP
        )
        val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Basic $auth")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
        if (conn.responseCode != 200) throw IllegalStateException("Clé Spotify refusée (${conn.responseCode})")
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        expiration = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000
        return o.getString("access_token").also { jeton = it }
    }

    private fun spotifyGet(chemin: String): JSONObject {
        val conn = (URL("https://api.spotify.com/v1$chemin").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${jetonSpotify()}")
        }
        val code = conn.responseCode
        if (code != 200) throw IllegalStateException("Recherche Spotify impossible ($code)")
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private data class Resultat(val liens: List<String>, val libelle: String)

    private fun lien(o: JSONObject) = o.getJSONObject("external_urls").getString("spotify")

    private fun premier(reponse: JSONObject, cle: String): JSONObject? {
        val items = reponse.optJSONObject(cle)?.optJSONArray("items") ?: return null
        return (0 until items.length()).map { items.opt(it) }.filterIsInstance<JSONObject>().firstOrNull()
    }

    private fun chercher(requete: String, type: String, artiste: String): Resultat {
        val q = if (artiste.isNotBlank() && type in listOf("titre", "album")) "$requete $artiste" else requete
        val typeApi = when (type) { "artiste" -> "artist"; "album" -> "album"; "playlist" -> "playlist"; else -> "track" }
        val r = spotifyGet("/search?type=$typeApi&limit=5&market=FR&q=" + URLEncoder.encode(q, "UTF-8"))
        return when (typeApi) {
            "track" -> {
                val t = premier(r, "tracks") ?: throw IllegalStateException("Je ne trouve pas « $requete » sur Spotify")
                val qui = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                Resultat(listOf(lien(t)), t.getString("name") + if (qui.isNotBlank()) " — $qui" else "")
            }
            "album" -> {
                val a = premier(r, "albums") ?: throw IllegalStateException("Je ne trouve pas l'album « $requete »")
                val qui = a.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                Resultat(listOf(lien(a)), a.getString("name") + if (qui.isNotBlank()) " — $qui" else "")
            }
            "playlist" -> {
                val p = premier(r, "playlists") ?: throw IllegalStateException("Je ne trouve pas la playlist « $requete »")
                Resultat(listOf(lien(p)), "playlist " + p.getString("name"))
            }
            else -> {
                // Artiste : ses titres les plus écoutés, mis en file d'attente
                val a = premier(r, "artists") ?: throw IllegalStateException("Je ne trouve pas l'artiste « $requete »")
                val titres = runCatching {
                    spotifyGet("/artists/${a.getString("id")}/top-tracks?market=FR").getJSONArray("tracks")
                }.getOrNull()?.takeIf { it.length() > 0 }
                    ?: spotifyGet("/search?type=track&limit=10&market=FR&q=" +
                        URLEncoder.encode("artist:\"${a.getString("name")}\"", "UTF-8"))
                        .getJSONObject("tracks").getJSONArray("items")
                val liens = (0 until titres.length()).mapNotNull { titres.optJSONObject(it)?.let(::lien) }.take(10)
                if (liens.isEmpty()) throw IllegalStateException("Aucun titre trouvé pour ${a.getString("name")}")
                Resultat(liens, a.getString("name"))
            }
        }
    }

    // --- Home Assistant (lecture sur les enceintes) ---

    private fun service(service: String, donnees: JSONObject) {
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/services/media_player/$service"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(donnees.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                throw IllegalStateException("Home Assistant refuse ($code) $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Cherche puis joue. Renvoie le libellé de ce qui est joué. Appel réseau : hors thread principal. */
    fun jouer(requete: String, type: String, artiste: String, ou: String): String {
        if (!configure) throw IllegalStateException("Les Sonos ne sont pas configurés")
        val ids = JSONArray(cibles(ou))
        val r = chercher(requete, type, artiste)
        r.liens.forEachIndexed { i, lien ->
            service(
                "play_media",
                JSONObject()
                    .put("entity_id", ids)
                    .put("media_content_id", lien)
                    .put("media_content_type", "music")
                    .put("enqueue", if (i == 0) "replace" else "add")
            )
        }
        if (r.liens.size > 1) service("media_play", JSONObject().put("entity_id", ids))
        return r.libelle
    }

    /** pause, lecture, suivant, precedent, volume_plus, volume_moins */
    fun controler(action: String, ou: String) {
        if (!configure) throw IllegalStateException("Les Sonos ne sont pas configurés")
        val donnees = JSONObject().put("entity_id", JSONArray(cibles(ou)))
        when (action) {
            "pause" -> service("media_pause", donnees)
            "lecture" -> service("media_play", donnees)
            "suivant" -> service("media_next_track", donnees)
            "precedent" -> service("media_previous_track", donnees)
            "volume_plus" -> repeat(2) { service("volume_up", donnees) }
            "volume_moins" -> repeat(2) { service("volume_down", donnees) }
            else -> throw IllegalArgumentException("Action inconnue : $action")
        }
    }
}
