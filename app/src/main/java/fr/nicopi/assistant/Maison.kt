package fr.nicopi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** Un appareil que l'assistant a le droit de commander. */
data class Commande(val entityId: String, val libelle: String)

/**
 * Les SEULES actions autorisées dans Home Assistant : allumer / éteindre les lumières
 * de la liste blanche, et gérer la liste de courses. Tout le reste est refusé.
 * Liste blanche : clé "commandes" de assets/home_assistant_capteurs.json.
 */
object Maison {

    private var lumieres: List<Commande> = emptyList()
    private var courses: String? = null
    private var charge = false

    private fun charger(context: Context) {
        if (charge) return
        charge = true
        val json = runCatching {
            context.assets.open("home_assistant_capteurs.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val c = JSONObject(json).optJSONObject("commandes") ?: return
        val l = c.optJSONArray("lumieres") ?: JSONArray()
        lumieres = (0 until l.length()).map {
            val o = l.getJSONObject(it)
            Commande(o.getString("entity_id"), o.getString("libelle"))
        }.filter { it.entityId.startsWith("light.") || it.entityId.startsWith("switch.") }
        courses = c.optString("courses").takeIf { it.startsWith("todo.") }
    }

    fun lumieres(context: Context): List<Commande> {
        charger(context); return lumieres
    }

    /** Ajouté aux instructions de Claude. */
    fun extraSysteme(context: Context): String {
        val l = lumieres(context)
        return if (l.isEmpty()) "" else
            "\n\nLumières pilotables (outil lumiere) :\n" + l.joinToString("\n") { "- ${it.entityId} : ${it.libelle}" }
    }

    // --- Appels Home Assistant ---

    internal fun service(domaine: String, service: String, donnees: JSONObject, reponse: Boolean = false): JSONObject? {
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/services/$domaine/$service" +
            if (reponse) "?return_response" else ""
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(donnees.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Home Assistant refuse ($code)")
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            return if (reponse) JSONObject(texte) else null
        } finally {
            conn.disconnect()
        }
    }

    private fun etat(entityId: String): String {
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/states/" + URLEncoder.encode(entityId, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("erreur ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return when (o.optString("state")) {
                "on" -> "allumée"
                "off" -> "éteinte"
                else -> "indisponible"
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Lumières ---

    /** action : allumer, eteindre, etat. luminosite (1-100) seulement pour les vraies lampes (light.). */
    fun lumiere(context: Context, ids: List<String>, action: String, luminosite: Int?): String {
        val autorisees = lumieres(context).associateBy { it.entityId }
        if (autorisees.isEmpty()) return "Aucune lumière n'est autorisée."
        val refusees = ids.filter { it !in autorisees }
        val lignes = ids.mapNotNull { autorisees[it] }.distinct().map { c ->
            val domaine = c.entityId.substringBefore('.')
            runCatching {
                when (action) {
                    "etat" -> etat(c.entityId)
                    "eteindre" -> {
                        service(domaine, "turn_off", JSONObject().put("entity_id", c.entityId)); "éteinte"
                    }
                    else -> {
                        val d = JSONObject().put("entity_id", c.entityId)
                        val avecLum = luminosite != null && domaine == "light"
                        if (avecLum) d.put("brightness_pct", luminosite!!.coerceIn(1, 100))
                        service(domaine, "turn_on", d)
                        if (avecLum) "allumée à ${luminosite!!.coerceIn(1, 100)} %" else "allumée"
                    }
                }
            }.fold({ "${c.libelle} : $it" }, { "${c.libelle} : échec (${it.message})" })
        }
        return (lignes + refusees.map { "$it : non autorisé" }).joinToString("\n").ifBlank { "Aucune lumière demandée." }
    }

    // --- Alarme ---
    // Seuls ces deux points d'entrée sont utilisés. Le contrôle des capteurs reste celui du
    // script Home Assistant alarme_bouton (appelé par alarme_activer, qui ne coupe jamais l'alarme).
    private const val ALARME = "input_boolean.alarme_active"
    private const val ACTIVER = "script.alarme_activer"
    private const val EJECTES = "sensor.alarme_capteurs_ejectes"

    internal fun lireEtat(entityId: String): JSONObject? {
        val url = BuildConfig.HA_URL.trimEnd('/') + "/api/states/" + URLEncoder.encode(entityId, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
        }
        return try {
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** true = active, false = désactivée, null = illisible. */
    fun alarmeActive(): Boolean? = when (lireEtat(ALARME)?.optString("state")) {
        "on" -> true
        "off" -> false
        else -> null
    }

    private fun ejectes(): String {
        val noms = lireEtat(EJECTES)?.optJSONObject("attributes")?.optJSONArray("noms") ?: return ""
        val l = (0 until noms.length()).map { noms.optString(it) }.filter { it.isNotBlank() }
        return if (l.isEmpty()) "" else " Capteurs éjectés (non surveillés) : ${l.joinToString()}."
    }

    fun alarmeEtat(): String = when (alarmeActive()) {
        true -> "Alarme activée." + ejectes()
        false -> "Alarme désactivée."
        null -> "État de l'alarme illisible (Home Assistant injoignable ?)."
    }

    /** Lance le contrôle des capteurs puis l'activation. Appel réseau : hors thread principal. */
    fun alarmeActiver(): String {
        when (alarmeActive()) {
            true -> return "L'alarme est déjà activée." + ejectes()
            null -> return "Je ne peux pas lire l'état de l'alarme : je n'active rien."
            false -> {}
        }
        service("script", "turn_on", JSONObject().put("entity_id", ACTIVER))
        repeat(8) {
            Thread.sleep(1_000)
            if (alarmeActive() == true) return "Alarme activée." + ejectes()
        }
        return "Alarme PAS activée pour l'instant : au moins une porte ou fenêtre est ouverte ou hors ligne " +
            "(utilise lire_maison sur les portes et fenêtres pour dire lesquelles). Une notification Home Assistant " +
            "demande sur le téléphone s'il faut éjecter ces capteurs et activer (OUI) ou renoncer (NON)."
    }

    fun alarmeDesactiver() {
        service("input_boolean", "turn_off", JSONObject().put("entity_id", ALARME))
        Thread.sleep(500)
        if (alarmeActive() != false) throw IllegalStateException("l'alarme n'a pas confirmé sa désactivation")
    }

    // --- Liste de courses ---

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().trim()

    private fun articles(entite: String): List<String> {
        val r = service(
            "todo", "get_items",
            JSONObject().put("entity_id", entite).put("status", JSONArray().put("needs_action")),
            reponse = true
        ) ?: return emptyList()
        val items = r.getJSONObject("service_response").getJSONObject(entite).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("summary") }
    }

    /** action : lire, ajouter, retirer. */
    fun courses(context: Context, action: String, demandes: List<String>): String {
        charger(context)
        val entite = courses ?: return "La liste de courses n'est pas configurée."
        return try {
            when (action) {
                "ajouter" -> {
                    val deja = articles(entite).map(::norm).toSet()
                    val nouveaux = demandes.map { it.trim() }.filter { it.isNotBlank() && norm(it) !in deja }.distinct()
                    nouveaux.forEach {
                        service("todo", "add_item", JSONObject().put("entity_id", entite).put("item", it))
                    }
                    val doublons = demandes.size - nouveaux.size
                    "Ajouté : ${nouveaux.joinToString().ifBlank { "rien" }}" +
                        (if (doublons > 0) " ($doublons déjà sur la liste)" else "")
                }
                "retirer" -> {
                    val liste = articles(entite)
                    val faits = mutableListOf<String>()
                    val absents = mutableListOf<String>()
                    for (d in demandes) {
                        val cible = liste.firstOrNull { norm(it) == norm(d) }
                            ?: liste.firstOrNull { norm(it).contains(norm(d)) || norm(d).contains(norm(it)) }
                        if (cible == null) absents += d else {
                            service(
                                "todo", "update_item",
                                JSONObject().put("entity_id", entite).put("item", cible).put("status", "completed")
                            )
                            faits += cible
                        }
                    }
                    "Retiré : ${faits.joinToString().ifBlank { "rien" }}" +
                        (if (absents.isNotEmpty()) ". Pas sur la liste : ${absents.joinToString()}" else "")
                }
                else -> {
                    val liste = articles(entite)
                    if (liste.isEmpty()) "La liste de courses est vide."
                    else "Liste de courses (${liste.size}) : " + liste.joinToString()
                }
            }
        } catch (e: Exception) {
            "Liste de courses injoignable (${e.message})"
        }
    }
}
