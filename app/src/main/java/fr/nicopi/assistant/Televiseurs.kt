package fr.nicopi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Une TV que l'assistant a le droit de commander. */
data class Televiseur(
    val mediaPlayer: String,
    val remote: String,
    val libelle: String,
    /** "samsung" (Tizen) ou "fire" (Fire TV, via ADB dans Home Assistant). */
    val type: String,
    /** Nom dit à voix haute -> identifiant de l'appli sur la TV. */
    val applis: Map<String, String>
)

/**
 * Commande des TV via Home Assistant (entités media_player + remote).
 * Liste blanche : clé "commandes" > "televiseurs" de assets/home_assistant_capteurs.json.
 * Aucune commande ADB libre : seules les touches de TOUCHES et les applis de la liste blanche passent.
 */
object Televiseurs {

    private var tvs: List<Televiseur> = emptyList()
    private var charge = false

    private fun charger(context: Context) {
        if (charge) return
        charge = true
        val json = runCatching {
            context.assets.open("home_assistant_capteurs.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val l = JSONObject(json).optJSONObject("commandes")?.optJSONArray("televiseurs") ?: return
        tvs = (0 until l.length()).mapNotNull { i ->
            val o = l.getJSONObject(i)
            val mp = o.optString("media_player")
            val rm = o.optString("remote")
            val type = o.optString("type")
            if (!mp.startsWith("media_player.") || !rm.startsWith("remote.") || type !in setOf("samsung", "fire"))
                return@mapNotNull null
            val a = o.optJSONObject("applis") ?: JSONObject()
            Televiseur(mp, rm, o.optString("libelle", mp), type, a.keys().asSequence().associateWith { a.getString(it) })
        }
    }

    fun liste(context: Context): List<Televiseur> {
        charger(context); return tvs
    }

    /** Ajouté aux instructions de Claude. */
    fun extraSysteme(context: Context): String {
        val l = liste(context)
        return if (l.isEmpty()) "" else "\n\nTV pilotables (outil tv) :\n" + l.joinToString("\n") {
            "- ${it.mediaPlayer} : ${it.libelle} (applis : ${it.applis.keys.joinToString()})"
        }
    }

    // --- Touches autorisées : nom -> (touche Samsung, keycode Android pour la Fire TV) ---

    private val TOUCHES: Map<String, Pair<String, String>> = mapOf(
        "haut" to ("KEY_UP" to "KEYCODE_DPAD_UP"),
        "bas" to ("KEY_DOWN" to "KEYCODE_DPAD_DOWN"),
        "gauche" to ("KEY_LEFT" to "KEYCODE_DPAD_LEFT"),
        "droite" to ("KEY_RIGHT" to "KEYCODE_DPAD_RIGHT"),
        "ok" to ("KEY_ENTER" to "KEYCODE_DPAD_CENTER"),
        "retour" to ("KEY_RETURN" to "KEYCODE_BACK"),
        "accueil" to ("KEY_HOME" to "KEYCODE_HOME"),
        "menu" to ("KEY_MENU" to "KEYCODE_MENU"),
        "info" to ("KEY_INFO" to "KEYCODE_INFO"),
        "guide" to ("KEY_GUIDE" to "KEYCODE_GUIDE"),
        "avance_rapide" to ("KEY_FF" to "KEYCODE_MEDIA_FAST_FORWARD"),
        "retour_rapide" to ("KEY_REWIND" to "KEYCODE_MEDIA_REWIND"),
        "chaine_plus" to ("KEY_CHUP" to "KEYCODE_CHANNEL_UP"),
        "chaine_moins" to ("KEY_CHDOWN" to "KEYCODE_CHANNEL_DOWN")
    ) + (0..9).associate { "$it" to ("KEY_$it" to "KEYCODE_$it") }

    /** Pour le schéma de l'outil. */
    val NOMS_TOUCHES: List<String> get() = TOUCHES.keys.toList()

    private val SOURCES_SAMSUNG = mapOf(
        "tv" to "KEY_TV", "hdmi1" to "KEY_HDMI1", "hdmi2" to "KEY_HDMI2", "hdmi3" to "KEY_HDMI3", "hdmi4" to "KEY_HDMI4"
    )

    // --- Briques Home Assistant ---

    private fun mp(service: String, tv: Televiseur, extra: JSONObject = JSONObject()) =
        Maison.service("media_player", service, extra.put("entity_id", tv.mediaPlayer))

    private fun envoyerTouches(tv: Televiseur, noms: List<String>) {
        val inconnues = noms.filter { it !in TOUCHES }
        if (inconnues.isNotEmpty()) throw IllegalArgumentException("touche non autorisée : ${inconnues.joinToString()}")
        if (tv.type == "samsung") {
            val cmds = JSONArray().apply { noms.forEach { put(TOUCHES.getValue(it).first) } }
            Maison.service(
                "remote", "send_command",
                JSONObject().put("entity_id", tv.remote).put("command", cmds).put("delay_secs", 0.4)
            )
        } else {
            // Fire TV : une commande ADB par touche, construite ici à partir de la table (jamais du texte libre)
            noms.forEach {
                Maison.service(
                    "androidtv", "adb_command",
                    JSONObject().put("entity_id", tv.mediaPlayer).put("command", "input keyevent ${TOUCHES.getValue(it).second}")
                )
            }
        }
    }

    /** off / standby / unavailable / on / playing… et attributs utiles. */
    private fun lireEtat(tv: Televiseur): JSONObject? = Maison.lireEtat(tv.mediaPlayer)

    private fun allumee(o: JSONObject?) = o?.optString("state") in setOf("on", "idle", "playing", "paused", "buffering")

    private fun decrire(tv: Televiseur): String {
        val o = lireEtat(tv) ?: return "${tv.libelle} : injoignable (Home Assistant ne répond pas)"
        val a = o.optJSONObject("attributes") ?: JSONObject()
        return when (val s = o.optString("state")) {
            "off", "standby" -> "${tv.libelle} : éteinte"
            "unavailable" -> "${tv.libelle} : indisponible" +
                if (tv.type == "fire") " (si elle a redémarré, le débogage ADB est à réactiver dans ses options développeur)" else ""
            else -> {
                val details = mutableListOf("allumée")
                if (s == "playing") details += "en lecture"
                if (s == "paused") details += "en pause"
                val appli = a.optString("app_name").ifBlank {
                    val id = a.optString("app_id")
                    tv.applis.entries.firstOrNull { it.value == id }?.key ?: ""
                }.ifBlank { a.optString("source") }
                if (appli.isNotBlank() && appli != "null") details += "sur $appli"
                if (a.has("volume_level")) details += "volume ${Math.round(a.optDouble("volume_level") * 100)} %"
                if (a.optBoolean("is_volume_muted")) details += "son coupé"
                "${tv.libelle} : ${details.joinToString(", ")}"
            }
        }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9+]"), "")

    private fun trouverAppli(tv: Televiseur, demande: String): Pair<String, String>? {
        val d = norm(demande)
        if (d.isEmpty()) return null
        return tv.applis.entries.firstOrNull { norm(it.key) == d }?.toPair()
            ?: tv.applis.entries.firstOrNull { norm(it.key).contains(d) || d.contains(norm(it.key)) }?.toPair()
    }

    private fun allumerSiBesoin(tv: Televiseur) {
        if (allumee(lireEtat(tv))) return
        mp("turn_on", tv)
        repeat(15) {
            Thread.sleep(1_000)
            if (allumee(lireEtat(tv))) {
                Thread.sleep(3_000) // le temps que l'accueil de la TV soit prêt
                return
            }
        }
        throw IllegalStateException("elle ne s'est pas allumée")
    }

    // --- Point d'entrée de l'outil ---

    data class Demande(
        val action: String,
        val niveau: Int? = null,
        val pas: Int? = null,
        val appli: String? = null,
        val chaine: String? = null,
        val touches: List<String> = emptyList(),
        val source: String? = null
    )

    /** Appel réseau : hors thread principal. */
    fun commander(context: Context, ids: List<String>, d: Demande): String {
        val autorisees = liste(context).associateBy { it.mediaPlayer }
        if (autorisees.isEmpty()) return "Aucune TV n'est configurée."
        val cibles = (if (ids.isEmpty() || ids.any { it == "toutes" }) autorisees.values.toList()
        else ids.mapNotNull { autorisees[it] }).distinct()
        val refusees = ids.filter { it != "toutes" && it !in autorisees }
        val lignes = cibles.map { tv ->
            runCatching { executer(tv, d) }
                .fold({ it }, { "${tv.libelle} : échec (${it.message})" })
        }
        return (lignes + refusees.map { "$it : non autorisée" }).joinToString("\n").ifBlank { "Aucune TV demandée." }
    }

    private fun executer(tv: Televiseur, d: Demande): String {
        val nom = tv.libelle
        return when (d.action) {
            "etat" -> decrire(tv)
            "allumer" -> {
                allumerSiBesoin(tv); "$nom : allumée"
            }
            "eteindre" -> {
                mp("turn_off", tv); "$nom : éteinte"
            }
            "volume" -> {
                val n = (d.niveau ?: return "$nom : niveau de volume manquant").coerceIn(0, 100)
                mp("volume_set", tv, JSONObject().put("volume_level", n / 100.0)); "$nom : volume à $n %"
            }
            "volume_plus", "volume_moins" -> {
                val n = (d.pas ?: 2).coerceIn(1, 15)
                val service = if (d.action == "volume_plus") "volume_up" else "volume_down"
                repeat(n) { mp(service, tv); Thread.sleep(150) }
                "$nom : volume ${if (d.action == "volume_plus") "monté" else "baissé"} de $n crans"
            }
            "muet", "son" -> {
                mp("volume_mute", tv, JSONObject().put("is_volume_muted", d.action == "muet"))
                if (d.action == "muet") "$nom : son coupé" else "$nom : son remis"
            }
            "lecture" -> { mp("media_play", tv); "$nom : lecture" }
            "pause" -> { mp("media_pause", tv); "$nom : pause" }
            "stop" -> { mp("media_stop", tv); "$nom : arrêt" }
            "suivant" -> { mp("media_next_track", tv); "$nom : suivant" }
            "precedent" -> { mp("media_previous_track", tv); "$nom : précédent" }
            "appli" -> {
                val (libelleAppli, id) = trouverAppli(tv, d.appli.orEmpty())
                    ?: return "$nom : appli « ${d.appli} » inconnue. Disponibles : ${tv.applis.keys.joinToString()}"
                allumerSiBesoin(tv)
                if (tv.type == "samsung")
                    mp("play_media", tv, JSONObject().put("media_content_type", "app").put("media_content_id", id))
                else
                    mp("select_source", tv, JSONObject().put("source", id))
                "$nom : $libelleAppli lancé"
            }
            "chaine" -> {
                val chiffres = d.chaine.orEmpty().filter { it.isDigit() }.take(4)
                if (chiffres.isEmpty()) return "$nom : numéro de chaîne manquant"
                allumerSiBesoin(tv)
                envoyerTouches(tv, chiffres.map { it.toString() })
                "$nom : chaîne $chiffres demandée"
            }
            "touches" -> {
                if (d.touches.isEmpty()) return "$nom : aucune touche demandée"
                envoyerTouches(tv, d.touches.take(20))
                "$nom : ${d.touches.take(20).joinToString(", ")} envoyé"
            }
            "source" -> {
                val s = d.source.orEmpty().lowercase().replace(" ", "")
                if (tv.type == "samsung") {
                    val touche = SOURCES_SAMSUNG[s] ?: return "$nom : source « ${d.source} » inconnue (tv, hdmi1 à hdmi4)"
                    Maison.service(
                        "remote", "send_command",
                        JSONObject().put("entity_id", tv.remote).put("command", JSONArray().put(touche))
                    )
                    "$nom : source $s"
                } else if (s == "tv") {
                    val id = tv.applis.values.firstOrNull { it == "com.amazon.tv.livetv" }
                        ?: return "$nom : la TV en direct n'est pas dans la liste blanche"
                    mp("select_source", tv, JSONObject().put("source", id)); "$nom : TV en direct"
                } else "$nom : le changement d'entrée HDMI n'est pas pris en charge sur cette TV"
            }
            else -> "$nom : action « ${d.action} » inconnue"
        }
    }
}
