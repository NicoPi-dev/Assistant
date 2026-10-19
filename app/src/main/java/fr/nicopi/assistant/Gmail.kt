package fr.nicopi.assistant

import android.app.PendingIntent
import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** Google demande ton accord (première fois, ou après expiration) : l'écran doit s'afficher. */
class ConsentementGmail(val intention: PendingIntent) : Exception("Autorisation Gmail nécessaire")

/** Un mail à envoyer, en attente de ta confirmation. */
data class MailBrouillon(
    val destinataire: String,   // adresse e-mail
    val nomAffiche: String,     // ce qu'on affiche/lit : "Julie" ou l'adresse
    val sujet: String,
    val corps: String,
    val repondreA: String?      // id du mail auquel on répond, ou null
)

/** Accès à Gmail par l'API officielle de Google. Appels réseau : hors du thread principal. */
object Gmail {

    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val TAILLE_MAX_CORPS = 3000

    private val SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/gmail.readonly"),
        Scope("https://www.googleapis.com/auth/gmail.send")
    )

    /** Jeton d'accès Google (mis en cache par les services Google, rapide). */
    private fun jeton(context: Context): String {
        val requete = AuthorizationRequest.builder().setRequestedScopes(SCOPES).build()
        val resultat = Tasks.await(Identity.getAuthorizationClient(context).authorize(requete))
        if (resultat.hasResolution()) {
            throw ConsentementGmail(resultat.pendingIntent!!)
        }
        return resultat.accessToken ?: throw IllegalStateException("Pas de jeton Google")
    }

    private fun get(context: Context, chemin: String): JSONObject {
        val conn = (URL(API + chemin).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${jeton(context)}")
        }
        val code = conn.responseCode
        val texte = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("Gmail $code : $texte")
        return JSONObject(texte)
    }

    private fun entetes(message: JSONObject): Map<String, String> {
        val h = message.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        return (0 until h.length()).associate {
            val o = h.getJSONObject(it)
            o.getString("name").lowercase() to o.getString("value")
        }
    }

    // --- Lecture ---

    /** Recherche avec la syntaxe Gmail (is:unread, from:, newer_than:2d…). */
    fun chercher(context: Context, requete: String, max: Int = 5): String {
        val q = URLEncoder.encode(requete, "UTF-8")
        val liste = get(context, "/messages?maxResults=${max.coerceIn(1, 10)}&q=$q")
            .optJSONArray("messages") ?: return "Aucun mail trouvé pour « $requete »."
        return (0 until liste.length()).joinToString("\n\n") { i ->
            val id = liste.getJSONObject(i).getString("id")
            val m = get(
                context,
                "/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
            )
            val h = entetes(m)
            val nonLu = m.optJSONArray("labelIds")?.toString()?.contains("UNREAD") == true
            "id=$id${if (nonLu) " (non lu)" else ""}\nDe : ${h["from"]}\nDate : ${h["date"]}\n" +
                "Sujet : ${h["subject"]}\nAperçu : ${m.optString("snippet")}"
        }
    }

    /** Contenu complet d'un mail (texte brut, tronqué). */
    fun lire(context: Context, id: String): String {
        val m = get(context, "/messages/${URLEncoder.encode(id, "UTF-8")}?format=full")
        val h = entetes(m)
        val corps = extraireTexte(m.optJSONObject("payload") ?: JSONObject())
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(TAILLE_MAX_CORPS)
        return "De : ${h["from"]}\nÀ : ${h["to"]}\nDate : ${h["date"]}\nSujet : ${h["subject"]}\n\n$corps"
    }

    private fun decoder(data: String): String =
        String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    /** Cherche la partie texte/plain, sinon le HTML débarrassé de ses balises. */
    private fun extraireTexte(partie: JSONObject): String {
        val type = partie.optString("mimeType")
        val data = partie.optJSONObject("body")?.optString("data").orEmpty()
        if (type == "text/plain" && data.isNotEmpty()) return decoder(data)
        val sous = partie.optJSONArray("parts")
        if (sous != null) {
            for (i in 0 until sous.length()) {
                val t = extraireTexte(sous.getJSONObject(i))
                if (t.isNotBlank()) return t
            }
        }
        if (type == "text/html" && data.isNotEmpty()) {
            return decoder(data)
                .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace(Regex("[ \\t]+"), " ")
        }
        return ""
    }

    // --- Envoi ---

    /** "Julie" -> son adresse e-mail dans les contacts ; une adresse est gardée telle quelle. */
    fun resoudreDestinataire(context: Context, nom: String): Pair<String, String>? {
        if ("@" in nom) return nom.trim() to nom.trim()
        fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().trim()
        val cible = norm(nom)
        val trouves = mutableListOf<Pair<String, String>>() // (nom, email)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val e = c.getString(1) ?: continue
                trouves += n to e
            }
        }
        val choix = trouves.firstOrNull { norm(it.first) == cible }
            ?: trouves.firstOrNull { norm(it.first).startsWith(cible) }
            ?: trouves.firstOrNull { norm(it.first).split(" ").any { m -> m == cible } }
            ?: return null
        return choix.second to choix.first
    }

    private fun encoderEntete(texte: String): String =
        if (texte.all { it.code < 128 }) texte
        else "=?UTF-8?B?" + Base64.encodeToString(texte.toByteArray(), Base64.NO_WRAP) + "?="

    fun envoyer(context: Context, mail: MailBrouillon) {
        var sujet = mail.sujet
        var threadId: String? = null
        val lignes = mutableListOf("To: ${mail.destinataire}")

        if (mail.repondreA != null) {
            val orig = get(
                context,
                "/messages/${mail.repondreA}?format=metadata&metadataHeaders=Message-ID&metadataHeaders=Subject"
            )
            threadId = orig.optString("threadId").ifBlank { null }
            val h = entetes(orig)
            h["message-id"]?.let {
                lignes += "In-Reply-To: $it"
                lignes += "References: $it"
            }
            val sujetOrig = h["subject"].orEmpty()
            if (sujet.isBlank()) sujet = sujetOrig
            if (!sujet.startsWith("Re:", ignoreCase = true)) sujet = "Re: $sujet"
        }

        lignes += "Subject: ${encoderEntete(sujet)}"
        lignes += "MIME-Version: 1.0"
        lignes += "Content-Type: text/plain; charset=UTF-8"
        lignes += "Content-Transfer-Encoding: 8bit"
        val brut = lignes.joinToString("\r\n") + "\r\n\r\n" + mail.corps.replace("\n", "\r\n")
        val raw = Base64.encodeToString(
            brut.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val body = JSONObject().put("raw", raw)
        threadId?.let { body.put("threadId", it) }

        val conn = (URL("$API/messages/send").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${jeton(context)}")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw RuntimeException("Gmail $code : $err")
        }
    }
}
