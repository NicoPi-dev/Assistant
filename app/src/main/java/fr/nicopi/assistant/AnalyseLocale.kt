package fr.nicopi.assistant

import android.content.Context
import java.text.Normalizer

/**
 * Tri rapide sur le téléphone, AVANT Claude : les ordres simples et sans ambiguïté sont exécutés
 * tout de suite (lumières, musique, TV marche/arrêt, liste de courses, activation de l'alarme,
 * minuteur, réveil). Au moindre doute, on renvoie null et la phrase part chez Claude.
 *
 * Règles :
 *  - Phrase entière reconnue, sinon rien (pas de devinette).
 *  - Mêmes listes blanches et mêmes fonctions que les outils de Claude : aucun droit en plus.
 *  - Rien de ce qui demande une confirmation (alarme à désactiver, SMS, mails, agenda).
 * Appel réseau possible (Home Assistant) : à lancer hors du thread principal.
 */
object AnalyseLocale {

    /** Mots de politesse ou de remplissage retirés avant l'analyse. */
    private val REMPLISSAGE = listOf(
        "hey claude", "he claude", "eh claude", "ok claude", "s il te plait", "s il vous plait", "stp", "svp",
        "merci", "est ce que tu peux", "est ce que tu pourrais", "tu peux", "tu pourrais", "peux tu", "pourrais tu",
        "je voudrais que tu", "j aimerais que tu", "vas y", "allez"
    )

    /** Mots sans importance pour reconnaître un appareil. */
    private val VIDES = setOf(
        "la", "le", "les", "l", "du", "de", "des", "d", "dans", "au", "aux", "a", "en", "sur", "cote", "et",
        "lumiere", "lumieres", "lampe", "lampes", "eclairage", "lampadaire"
    )

    fun norm(s: String): String = Normalizer.normalize(s.lowercase().replace("œ", "oe"), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun nettoyer(phrase: String): String {
        var p = " " + norm(phrase) + " "
        for (r in REMPLISSAGE) p = p.replace(" $r ", " ")
        return p.replace(Regex("\\s+"), " ").trim()
    }

    private fun mots(s: String) = s.split(" ").filter { it.isNotBlank() }

    /** Mots qui identifient un appareil à partir de son libellé (« Projecteur de la cour » → projecteur, cour). */
    private fun cles(libelle: String) = mots(norm(libelle)).filter { it !in VIDES }.toSet()

    /** L'appareil dont tous les mots-clés sont dans la phrase, et elle ne contient rien d'autre. */
    private fun <T> trouver(reste: String, appareils: List<T>, libelle: (T) -> String, ignores: Set<String> = emptySet()): T? {
        val utiles = mots(reste).filter { it !in VIDES && it !in ignores }.toSet()
        if (utiles.isEmpty()) return null
        val candidats = appareils.filter { (cles(libelle(it)) - ignores).let { c -> c.isNotEmpty() && utiles == c } }
        return candidats.singleOrNull()
    }

    fun essayer(context: Context, phrase: String): Intention? {
        val p = nettoyer(phrase)
        if (p.isEmpty() || p.length > 80) return null
        return lumiere(context, p)
            ?: tv(context, p)
            ?: musique(context, p)
            ?: courses(context, phrase)
            ?: alarme(p)
            ?: minuteur(p)
            ?: reveil(p)
    }

    // --- Lumières ---

    private val ALLUMER = Regex("^(allume|allumer|rallume|mets|mais)( les\\b| la\\b| le\\b)?( lumieres\\b| lumiere\\b| lampe\\b)?( dans la\\b| dans le\\b| de la\\b| de l\\b| du\\b| de\\b| d\\b| au\\b| a la\\b| a l\\b)? ?(.*)$")
    private val ETEINDRE = Regex("^(eteins|eteint|etein|eteindre|coupe|couper)( les\\b| la\\b| le\\b)?( lumieres\\b| lumiere\\b| lampe\\b)?( dans la\\b| dans le\\b| de la\\b| de l\\b| du\\b| de\\b| d\\b| au\\b| a la\\b| a l\\b)? ?(.*)$")

    private fun lumiere(context: Context, p: String): Intention? {
        val lumieres = Maison.lumieres(context)
        if (lumieres.isEmpty()) return null
        if (Regex("\\b(tele|tv|television|teles|alarme|musique|son|radio)\\b").containsMatchIn(p)) return null

        if (p.matches(Regex("^(eteins|eteint|coupe) (tout|toutes les lumieres|les lumieres|toute la lumiere)$"))) {
            val r = Maison.lumiere(context, lumieres.map { it.entityId }, "eteindre", null)
            return Intention.Reponse(if ("échec" in r) r else "C'est éteint partout.")
        }
        val (action, reste) = ETEINDRE.find(p)?.let { "eteindre" to it.groupValues[5] }
            ?: ALLUMER.find(p)?.let { "allumer" to it.groupValues[5] }
            ?: return null
        // « mets » seul est trop vague (« mets la 13 », « mets du Daft Punk ») : il faut un appareil reconnu
        val l = trouver(reste, lumieres, { it.libelle }) ?: return null
        val r = Maison.lumiere(context, listOf(l.entityId), action, null)
        return Intention.Reponse(
            if ("échec" in r || "non autorisé" in r) r
            else if (action == "allumer") "${l.libelle} allumée." else "${l.libelle} éteinte."
        )
    }

    // --- TV : marche / arrêt / muet ---

    private val TV = Regex("^(coupe le son de|remets le son de|mets en sourdine|allume|eteins|eteint|coupe)( les\\b| la\\b| le\\b)? (televisions|television|teles|tele|tv)\\b( dans la\\b| dans le\\b| de la\\b| de l\\b| du\\b| de\\b| d\\b| au\\b)? ?(.*)$")

    private fun tv(context: Context, p: String): Intention? {
        val m = TV.find(p) ?: return null
        val tvs = Televiseurs.liste(context)
        if (tvs.isEmpty()) return null
        val verbe = m.groupValues[1]
        val reste = m.groupValues[5]
        val action = when {
            verbe.startsWith("coupe le son") || verbe == "mets en sourdine" -> "muet"
            verbe.startsWith("remets") -> "son"
            verbe == "allume" -> "allumer"
            else -> "eteindre"
        }
        val cibles = when {
            reste.isBlank() && m.groupValues[3] in setOf("teles", "televisions") && action == "eteindre" -> tvs
            reste.isBlank() -> if (tvs.size == 1) tvs else return null
            reste == "toutes" || reste == "partout" -> if (action == "eteindre") tvs else return null
            else -> listOf(trouver(reste, tvs, { it.libelle }, setOf("tele", "tv")) ?: return null)
        }
        val r = Televiseurs.commander(context, cibles.map { it.mediaPlayer }, Televiseurs.Demande(action = action))
        return Intention.Reponse(r)
    }

    // --- Musique (téléphone ou dernier Sonos) ---

    private val MEDIA = listOf(
        Regex("^(mets )?(la musique )?(en )?pause$|^(stop|arrete|stoppe|coupe) la musique$|^pause la musique$") to "pause",
        Regex("^(reprends|reprend|relance|remets)( la musique| la lecture)?$|^lecture$") to "lecture",
        Regex("^(morceau |titre |chanson )?suivant(e)?$|^(passe|zappe)( (au|a la) (morceau|titre|chanson) suivant(e)?| la chanson| le morceau)?$") to "suivant",
        Regex("^(morceau |titre |chanson )?precedent(e)?$|^(reviens|retour) (au|a la) (morceau|titre|chanson) precedent(e)?$") to "precedent",
        Regex("^(monte|augmente) (la musique|le son de la musique|le volume de la musique)$|^musique plus fort$") to "volume_plus",
        Regex("^(baisse|diminue) (la musique|le son de la musique|le volume de la musique)$|^musique moins fort$") to "volume_moins"
    )

    private fun musique(context: Context, p: String): Intention? {
        val action = MEDIA.firstOrNull { it.first.matches(p) }?.second ?: return null
        // Une TV allumée : « pause » ou « suivant » pourrait la viser, on laisse Claude trancher
        val generique = !p.contains("musique") && !p.contains("chanson") && !p.contains("morceau") && !p.contains("titre")
        if (generique && Televiseurs.liste(context).any { tv ->
                Maison.lireEtat(tv.mediaPlayer)?.optString("state") in setOf("on", "idle", "playing", "paused", "buffering")
            }) return null
        return Intention.Telecommande(action, "")
    }

    // --- Liste de courses (sur la phrase d'origine, pour garder les accents) ---

    private val COURSES = Regex(
        "^(?:ajoute|ajouter|rajoute|mets|met|note|inscris)\\s+(.+?)\\s+(?:à|a|sur|dans)\\s+(?:la\\s+)?liste(?:\\s+(?:de|des|d')\\s*(?:courses?|achats?))?$"
    )
    private val ARTICLE = Regex("^(?:du|de la|de l'|des|d'|un|une|le|la|les|l'|quelques|deux|trois)\\s*")

    private fun courses(context: Context, phrase: String): Intention? {
        var p = phrase.lowercase().replace(Regex("[!?.]"), " ").replace('’', '\'').replace(Regex("\\s+"), " ").trim()
        p = p.removePrefix("hey claude").removePrefix("hé claude").trim().removePrefix(",").trim()
        val m = COURSES.find(p) ?: return null
        val articles = m.groupValues[1].split(Regex(",| et ")).map { it.trim().replace(ARTICLE, "").trim() }
            .filter { it.isNotBlank() }
        if (articles.isEmpty() || articles.size > 10 || articles.any { it.length > 40 }) return null
        return Intention.Reponse(Maison.courses(context, "ajouter", articles))
    }

    // --- Alarme : activation seulement (le contrôle des capteurs reste celui de Home Assistant) ---

    private fun alarme(p: String): Intention? {
        if (!p.matches(Regex("^(active|activer|mets|enclenche|arme|allume)( l| la)? alarme( de la maison)?$"))) return null
        val r = Maison.alarmeActiver()
        return Intention.Reponse(
            if (r.startsWith("Alarme PAS"))
                "Pas encore : une porte ou une fenêtre est ouverte ou hors ligne. Réponds OUI ou NON sur la notification Home Assistant."
            else r
        )
    }

    // --- Minuteur et réveil (applis du téléphone) ---

    private val MINUTEUR = Regex("^(?:mets |lance |demarre |programme )?(?:un )?minuteur (?:de |d )?(\\d{1,3}) (secondes?|s|minutes?|min|heures?|h)$|^(\\d{1,3}) (secondes?|minutes?|heures?) de minuteur$")

    private fun minuteur(p: String): Intention? {
        val m = MINUTEUR.find(p) ?: return null
        val n = (m.groupValues[1].ifBlank { m.groupValues[3] }).toIntOrNull() ?: return null
        val u = m.groupValues[2].ifBlank { m.groupValues[4] }
        val s = when {
            u.startsWith("s") -> n
            u.startsWith("h") -> n * 3600
            else -> n * 60
        }
        if (s !in 1..24 * 3600) return null
        return Intention.Minuteur(s, "")
    }

    private val REVEIL = Regex("^(?:reveille moi|reveil|mets un reveil|mets moi un reveil|programme un reveil|mets une alarme|programme une alarme) (?:a|pour|demain a) (\\d{1,2}) ?(?:h|heures?)? ?(\\d{2})?$")

    private fun reveil(p: String): Intention? {
        val m = REVEIL.find(p) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mn = m.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (h !in 0..23 || mn !in 0..59) return null
        return Intention.Alarme(h, mn, "", emptyList())
    }
}
