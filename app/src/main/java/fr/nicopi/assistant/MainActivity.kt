package fr.nicopi.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import java.util.Locale
import java.text.Normalizer

class MainActivity : ComponentActivity() {

    // --- État affiché à l'écran ---
    private var statut by mutableStateOf("Appuie sur le micro")
    private var entendu by mutableStateOf("")
    private var smsEnAttente by mutableStateOf<Pair<Contact, String>?>(null)
    private var mailEnAttente by mutableStateOf<MailBrouillon?>(null)
    private var envoiMailEnCours by mutableStateOf(false)
    private var evenementEnAttente by mutableStateOf<EvenementBrouillon?>(null)
    private var suppressionEnAttente by mutableStateOf<Agenda.Trouve?>(null)
    private var alarmeEnAttente by mutableStateOf(false)
    /** Une carte attend une validation (bouton ou « oui » à la voix). */
    private val carteEnAttente: Boolean
        get() = smsEnAttente != null || mailEnAttente != null || evenementEnAttente != null ||
            suppressionEnAttente != null || alarmeEnAttente

    /** Écran d'autorisation Google (première connexion à Gmail). */
    private val consentementGmail =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            parler(
                if (res.resultCode == RESULT_OK) "Gmail est connecté. Redis-moi ta demande."
                else "Accès à Gmail refusé."
            )
        }
    private var ecoute by mutableStateOf(false)
    private var reflexion by mutableStateOf(false)
    private var niveauMicro by mutableFloatStateOf(0f)

    // --- Conversation continue ---
    private val historique = mutableListOf<Pair<String, String>>()
    private var historiqueNonFiable = false
    private var dernierEchange = 0L
    private var phraseCourante = ""
    private var derniereReponse = ""
    private var ecouteSuite = false
    /** On attend un « oui » / « non » pour la carte affichée (rendez-vous, mail, SMS). */
    private var ecouteConfirmation = false
    private var relanceConfirmation = 0
    /** Où la musique a été lancée en dernier (pour « pause », « suivant » sans précision). */
    private var dernierOu = "telephone"
    private var visible = false
    private var lectureFinie by mutableStateOf(false)

    /** Délai avant l'envoi automatique d'un SMS, une fois le message lu à voix haute. */
    private val DELAI_ENVOI_MS = 5000L

    private lateinit var reconnaissance: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val demandePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val essentielles = res.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            if (essentielles.values.all { it }) demarrer() else statut = "Permissions refusées"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.FRANCE }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == "sms") runOnUiThread { lectureFinie = true }
                // Carte à valider : on écoute la réponse « oui » / « non »
                if (id == "confirmer" || id == "sms") runOnUiThread {
                    if (visible && !ecoute &&
                        carteEnAttente
                    ) {
                        ecouteConfirmation = true
                        relanceConfirmation = 0
                        ecouter()
                    }
                }
                // Conversation continue : après une réponse, on réécoute quelques secondes
                if (id == "suite") runOnUiThread {
                    if (visible && !ecoute && !carteEnAttente) {
                        ecouteSuite = true
                        ecouter()
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (id == "sms") runOnUiThread { lectureFinie = true } }
        })
        reconnaissance = SpeechRecognizer.createSpeechRecognizer(this)
        reconnaissance.setRecognitionListener(listener)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Ecran() }
        }
        demarrer()
    }

    /** Appelé quand l'app, déjà ouverte, est relancée (bouton assistant). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        demarrer()
    }

    private fun demarrer() {
        smsEnAttente = null
        mailEnAttente = null
        evenementEnAttente = null
        suppressionEnAttente = null
        alarmeEnAttente = false
        val essentielles = permissions.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        val toutAccorde = essentielles.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!toutAccorde) {
            demandePermissions.launch(permissions)
            return
        }
        // Coupe l'écoute du mot d'appel (et démarre le service s'il ne tourne pas)
        ReveilService.pauser(this)
        if (verifierReglages()) return
        // Laisse au service le temps de libérer le micro
        lifecycleScope.launch {
            delay(400)
            ecouter()
        }
    }

    /** Réglages spéciaux, un par lancement. Renvoie true si on a ouvert un écran de réglage. */
    private fun verifierReglages(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            statut = "Autorise « Afficher par-dessus les autres applis » pour « Hey Claude »"
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return true
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            statut = "Autorise l'app à tourner en arrière-plan"
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            return true
        }
        // Annonce vocale des nouveaux mails : lecture des notifications Gmail
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            statut = "Autorise l'accès aux notifications pour l'annonce des mails"
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return true
        }
        return false
    }

    /** L'app passe en arrière-plan : on se remet à guetter « Hey Claude ». */
    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        ecouteSuite = false
        ecouteConfirmation = false
        super.onStop()
        reconnaissance.cancel()
        ecoute = false
        ReveilService.reprendre(this)
    }

    // --- 1. Écoute ---
    private fun ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statut = "Reconnaissance vocale indisponible"
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        entendu = ""
        reflexion = false
        statut = "Je t'écoute…"
        ecoute = true
        reconnaissance.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            ecouteSuite = false
            ecoute = false
            val phrase = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (ecouteConfirmation) {
                ecouteConfirmation = false
                repondreConfirmation(phrase.orEmpty())
                return
            }
            if (phrase.isNullOrBlank()) {
                reflexion = false
                statut = "Je n'ai rien entendu"
                return
            }
            entendu = phrase
            traiter(phrase)
        }

        override fun onError(error: Int) {
            if (ecouteConfirmation) {
                // Pas de réponse : une relance pour un rendez-vous ou un mail, puis les boutons.
                // Pour un SMS, le compte à rebours continue tout seul.
                ecoute = false
                reflexion = false
                niveauMicro = 0f
                val carte = mailEnAttente != null || evenementEnAttente != null || suppressionEnAttente != null || alarmeEnAttente
                if (carte && relanceConfirmation < 1 && visible) {
                    relanceConfirmation++
                    ecouter()
                    return
                }
                ecouteConfirmation = false
                if (carte) statut = "Touche « Ajouter » / « Envoyer » ou « Annuler »"
                return
            }
            if (ecouteSuite) {
                // Silence après une réponse : on s'arrête sans message d'erreur
                ecouteSuite = false
                ecoute = false
                niveauMicro = 0f
                statut = derniereReponse
                return
            }
            ecoute = false
            reflexion = false
            niveauMicro = 0f
            statut = if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) "Je n'ai rien entendu" else "Je n'ai pas compris (erreur $error)"
        }

        override fun onEndOfSpeech() {
            ecoute = false
            niveauMicro = 0f
            reflexion = true
            statut = "Je réfléchis…"
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            niveauMicro = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { entendu = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- 2. Compréhension ---
    private fun traiter(phrase: String) {
        // La conversation s'oublie après 3 minutes sans échange
        if (System.currentTimeMillis() - dernierEchange > 3 * 60_000) {
            historique.clear()
            historiqueNonFiable = false
        }
        phraseCourante = phrase
        // Commandes simples traitées sur le téléphone : instantané et gratuit
        analyseLocale(phrase)?.let { executer(it); return }

        // Sinon, on demande à Claude
        statut = "Je réfléchis…"
        lifecycleScope.launch {
            try {
                val intention = withContext(Dispatchers.IO) { ClaudeClient.interpreter(this@MainActivity, phrase, historique.toList(), historiqueNonFiable) }
                executer(intention)
            } catch (e: ConsentementGmail) {
                reflexion = false
                statut = "Connexion à Gmail…"
                consentementGmail.launch(IntentSenderRequest.Builder(e.intention.intentSender).build())
            } catch (e: Exception) {
                reflexion = false
                statut = "Erreur : ${e.message}"
            }
        }
    }

    private fun analyseLocale(phrase: String): Intention? {
        val m = Regex(
            "^(?:appelle|appeler|appel|téléphone à)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(phrase.trim()) ?: return null
        return Intention.Appeler(m.groupValues[1])
    }

    // --- 3. Exécution ---
    private fun executer(intention: Intention) {
        when (intention) {
            is Intention.Appeler -> {
                val c = Actions.trouverContact(this, intention.contact)
                    ?: return parler("Je ne trouve pas ${intention.contact} dans tes contacts")
                parler("J'appelle ${c.nom}")
                Actions.appeler(this, c)
            }
            is Intention.EnvoyerSms -> {
                val c = Actions.trouverContact(this, intention.contact)
                    ?: return parler("Je ne trouve pas ${intention.contact} dans tes contacts")
                lectureFinie = false
                smsEnAttente = c to intention.message
                parler("Message pour ${c.nom} : ${intention.message}", "sms")
            }
            is Intention.EnvoyerMail -> {
                val b = intention.brouillon
                mailEnAttente = b
                parler("Mail pour ${b.nomAffiche} prêt. Vérifie-le, puis dis oui pour l'envoyer.", "confirmer")
            }
            is Intention.Alarme -> {
                Horloge.alarme(this, intention.heure, intention.minute, intention.libelle, intention.jours)
                parler("Alarme réglée à %dh%02d".format(intention.heure, intention.minute) +
                    if (intention.jours.isNotEmpty()) ", avec répétition." else ".")
            }
            is Intention.Naviguer -> {
                parler("C'est parti pour ${intention.destination}.")
                Horloge.naviguer(this, intention.destination, intention.mode)
            }
            is Intention.Jouer -> if (intention.ou != "telephone") {
                statut = "Je lance sur ${Sonos.libelle(intention.ou)}…"
                lifecycleScope.launch {
                    try {
                        val titre = withContext(Dispatchers.IO) {
                            Sonos.jouer(intention.requete, intention.type, intention.artiste, intention.ou)
                        }
                        dernierOu = intention.ou
                        statut = "♪ $titre · ${Sonos.libelle(intention.ou)}"
                    } catch (e: Exception) {
                        parler(e.message ?: "Impossible de lancer la musique sur le Sonos")
                    }
                }
            } else {
                dernierOu = "telephone"
                try {
                    Musique.jouer(this, intention.requete, intention.type, intention.artiste)
                    statut = "♪ " + intention.requete +
                        (if (intention.artiste.isNotBlank()) " — ${intention.artiste}" else "")
                } catch (e: Exception) {
                    parler(e.message ?: "Impossible de lancer Spotify")
                }
            }
            is Intention.Telecommande -> {
                val ou = intention.ou.ifBlank { dernierOu }
                if (ou == "telephone") Musique.controler(this, intention.action)
                else lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Sonos.controler(intention.action, ou) }
                    } catch (e: Exception) {
                        parler(e.message ?: "Le Sonos ne répond pas")
                    }
                }
                statut = (if (ou == "telephone") "" else Sonos.libelle(ou) + " : ") + when (intention.action) {
                    "pause" -> "Musique en pause"
                    "lecture" -> "Lecture reprise"
                    "suivant" -> "Morceau suivant"
                    "precedent" -> "Morceau précédent"
                    "volume_plus" -> "Volume augmenté"
                    else -> "Volume baissé"
                }
            }
            is Intention.Minuteur -> {
                Horloge.minuteur(this, intention.secondes, intention.libelle)
                parler("Minuteur de ${Horloge.duree(intention.secondes)} lancé.")
            }
            is Intention.Rappel -> {
                lifecycleScope.launch {
                    try {
                        val phrase = withContext(Dispatchers.IO) {
                            Horloge.rappel(this@MainActivity, intention.quand, intention.texte)
                        }
                        parler(phrase)
                    } catch (e: Exception) {
                        statut = "Échec du rappel : ${e.message}"
                    }
                }
            }
            is Intention.DesactiverAlarme -> {
                alarmeEnAttente = true
                parler("Je désactive l'alarme ?", "confirmer")
            }
            is Intention.SupprimerEvenement -> {
                val t = intention.trouve
                suppressionEnAttente = t
                parler("Je supprime ${t.ev.titre}, ${t.ev.resume()} ?", "confirmer")
            }
            is Intention.AjouterEvenement -> {
                val e = intention.brouillon
                evenementEnAttente = e
                parler("${e.titre}, ${e.resume()}. Je l'ajoute ?", "confirmer")
            }
            is Intention.Reponse -> {
                historique += phraseCourante to intention.texte
                while (historique.size > 4) historique.removeAt(0)
                if (intention.externes) historiqueNonFiable = true
                dernierEchange = System.currentTimeMillis()
                derniereReponse = intention.texte
                parler(intention.texte, "suite")
            }
        }
    }

    /** Oui = true, non = false, pas compris = null. En cas de doute, on ne valide pas. */
    private fun reponseOuiNon(phrase: String): Boolean? {
        val p = " " + Normalizer.normalize(phrase.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]+"), " ") + " "
        val non = listOf("non", "annule", "annuler", "annulez", "stop", "pas", "laisse tomber", "arrete", "attends", "efface")
        val oui = listOf(
            "oui", "ouais", "ajoute", "ajouter", "envoie", "envoyer", "envoi", "vas y", "valide", "valider",
            "confirme", "ok", "okay", "d accord", "c est bon", "parfait", "go", "yes", "ca marche", "exactement"
        )
        if (non.any { p.contains(" $it ") }) return false
        if (oui.any { p.contains(" $it ") }) return true
        return null
    }

    private fun repondreConfirmation(phrase: String) {
        reflexion = false
        niveauMicro = 0f
        when (reponseOuiNon(phrase)) {
            true -> when {
                evenementEnAttente != null -> ajouterEvenementEnAttente()
                suppressionEnAttente != null -> supprimerEvenementEnAttente()
                alarmeEnAttente -> desactiverAlarmeEnAttente()
                mailEnAttente != null -> envoyerMailEnAttente()
                smsEnAttente != null -> envoyerSmsEnAttente()
            }
            false -> {
                if (carteEnAttente) {
                    smsEnAttente = null
                    mailEnAttente = null
                    evenementEnAttente = null
                    suppressionEnAttente = null
                    alarmeEnAttente = false
                    parler("Annulé, l'alarme reste activée")
                }
            }
            null -> if (smsEnAttente == null) statut = "Je n'ai pas compris : touche un bouton"
        }
    }

    private fun desactiverAlarmeEnAttente() {
        if (!alarmeEnAttente) return
        alarmeEnAttente = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Maison.alarmeDesactiver() }
                parler("Alarme désactivée.")
            } catch (e: Exception) {
                parler("Échec de la désactivation : ${e.message}")
            }
        }
    }

    private fun supprimerEvenementEnAttente() {
        val t = suppressionEnAttente ?: return
        suppressionEnAttente = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Agenda.supprimer(this@MainActivity, t) }
                parler("C'est supprimé de ton agenda.")
            } catch (e: Exception) {
                statut = "Échec de la suppression : ${e.message}"
            }
        }
    }

    private fun ajouterEvenementEnAttente() {
        val ev = evenementEnAttente ?: return
        evenementEnAttente = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Agenda.creer(this@MainActivity, ev) }
                parler("C'est noté dans ton agenda.")
            } catch (e: Exception) {
                statut = "Échec de l'ajout : ${e.message}"
            }
        }
    }

    private fun envoyerMailEnAttente() {
        val mail = mailEnAttente ?: return
        envoiMailEnCours = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Gmail.envoyer(this@MainActivity, mail) }
                mailEnAttente = null
                parler("Mail envoyé à ${mail.nomAffiche}")
            } catch (e: ConsentementGmail) {
                consentementGmail.launch(IntentSenderRequest.Builder(e.intention.intentSender).build())
            } catch (e: Exception) {
                statut = "Échec de l'envoi : ${e.message}"
            } finally {
                envoiMailEnCours = false
            }
        }
    }

    private fun parler(texte: String, id: String = "assistant") {
        reflexion = false
        statut = texte
        tts.speak(texte, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun envoyerSmsEnAttente() {
        val (contact, message) = smsEnAttente ?: return
        smsEnAttente = null
        try {
            Actions.envoyerSms(this, contact, message)
            parler("Envoyé à ${contact.nom}")
        } catch (e: Exception) {
            statut = "Échec de l'envoi : ${e.message}"
        }
    }

    // --- Interface ---

    private enum class Mode(val libelle: String, val c1: Color, val c2: Color) {
        PRET("Prêt", Color(0xFFD97757), Color(0xFFF2A585)),
        ECOUTE("J'écoute", Color(0xFFF59E6B), Color(0xFFD97757)),
        REFLEXION("Je réfléchis", Color(0xFFD97757), Color(0xFFB4507A)),
        CONFIRMATION("À confirmer", Color(0xFF34D399), Color(0xFF22D3EE))
    }

    private val mode: Mode
        get() = when {
            carteEnAttente -> Mode.CONFIRMATION
            ecoute -> Mode.ECOUTE
            reflexion -> Mode.REFLEXION
            else -> Mode.PRET
        }

    private val Blanc = Color(0xFFF7F1EC)

    @Composable
    private fun Ecran() {
        val m = mode
        val c1 by animateColorAsState(m.c1, tween(600), label = "c1")
        val c2 by animateColorAsState(m.c2, tween(600), label = "c2")

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF2A1C24), Color(0xFF160F17), Color(0xFF0A070B))
                    )
                )
                // Lueur douce de la couleur de l'état, derrière l'orbe
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(c1.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(size.width / 2, size.height * 0.36f),
                            radius = size.width * 0.9f
                        ),
                        radius = size.width * 0.9f,
                        center = Offset(size.width / 2, size.height * 0.36f)
                    )
                }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EnTete(m, c1)

                // Carte de confirmation à l'écran : orbe réduite et bulle masquée,
                // pour que les boutons restent toujours visibles.
                val carte = carteEnAttente
                Spacer(Modifier.weight(1f))
                if (carte) {
                    Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(260.dp).scale(0.5f), contentAlignment = Alignment.Center) {
                            Orbe(m, c1, c2)
                        }
                    }
                } else Orbe(m, c1, c2)
                Spacer(Modifier.height(if (carte) 12.dp else 28.dp))

                val texte = if (m == Mode.CONFIRMATION && smsEnAttente != null) "Envoi automatique…"
                    else if (m == Mode.CONFIRMATION && evenementEnAttente != null) "À ajouter ? Dis « oui » ou « non »"
                    else if (m == Mode.CONFIRMATION && suppressionEnAttente != null) "À supprimer ? Dis « oui » ou « non »"
                    else if (m == Mode.CONFIRMATION && alarmeEnAttente) "Désactiver l'alarme ? Dis « oui » ou « non »"
                    else if (m == Mode.CONFIRMATION) "À vérifier avant l'envoi"
                    else statut
                AnimatedContent(
                    targetState = texte,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "statut"
                ) { t ->
                    Text(
                        t,
                        color = Blanc,
                        fontSize = if (t.length > 80) 15.sp else 24.sp,
                        lineHeight = if (t.length > 80) 20.sp else 31.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.weight(1f))

                AnimatedVisibility(
                    visible = entendu.isNotBlank() && !carte,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut()
                ) { BulleUtilisateur(entendu) }

                Spacer(Modifier.height(20.dp))

                val sms = smsEnAttente
                val mail = mailEnAttente
                if (sms != null) CarteSms(sms.first, sms.second)
                else if (mail != null) CarteMail(mail)
                else if (evenementEnAttente != null) CarteEvenement(evenementEnAttente!!)
                else if (suppressionEnAttente != null) CarteEvenement(suppressionEnAttente!!.ev, suppression = true)
                else if (alarmeEnAttente) CarteAlarme()
                else BoutonMicro(c1, c2)
            }
        }
    }

    @Composable
    private fun EnTete(m: Mode, couleur: Color) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Mini logo « Orbe »
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFF2A585), Color(0xFFD97757), Color(0xFF9E4A30)),
                            center = Offset(30f, 26f),
                            radius = 80f
                        )
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Assistant", color = Blanc, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Dis « Hey Claude »", color = Blanc.copy(alpha = 0.55f), fontSize = 13.sp)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(couleur))
                Spacer(Modifier.width(8.dp))
                Text(m.libelle, color = Blanc, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun Orbe(m: Mode, c1: Color, c2: Color) {
        val infini = rememberInfiniteTransition(label = "orbe")
        // Respiration lente au repos
        val souffle by infini.animateFloat(
            1f, 1.06f,
            infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "souffle"
        )
        // Rotation pendant la réflexion
        val rotation by infini.animateFloat(
            0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "rotation"
        )
        // Ondes qui s'éloignent pendant l'écoute
        val onde by infini.animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "onde"
        )
        val niveau by animateFloatAsState(niveauMicro, tween(120), label = "niveau")

        val echelle = when (m) {
            Mode.ECOUTE -> 1f + niveau * 0.18f
            Mode.PRET -> souffle
            else -> 1f
        }

        Box(
            Modifier
                .size(260.dp)
                .drawBehind {
                    val r = size.minDimension / 2 * 0.6f
                    if (m == Mode.ECOUTE) {
                        for (i in 0..2) {
                            val p = (onde + i / 3f) % 1f
                            drawCircle(
                                c1.copy(alpha = (1f - p) * 0.35f),
                                radius = r * (1f + p * 0.7f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                            )
                        }
                    }
                    drawCircle(c1.copy(alpha = 0.18f), radius = r * (1.18f + niveau * 0.25f))
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(156.dp)
                    .scale(echelle)
                    .graphicsLayer { rotationZ = if (m == Mode.REFLEXION) rotation else 0f }
                    .clip(CircleShape)
                    .background(
                        if (m == Mode.REFLEXION) Brush.sweepGradient(listOf(c1, c2, c1))
                        else Brush.linearGradient(listOf(c1, c2))
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Reflet en haut à gauche pour donner du volume
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                                center = Offset(140f, 120f),
                                radius = 260f
                            )
                        )
                )
            }
            Text(
                when (m) {
                    Mode.ECOUTE, Mode.PRET -> "🎙"
                    Mode.REFLEXION -> "✦"
                    Mode.CONFIRMATION -> "✉"
                },
                fontSize = 46.sp,
                color = Color.White,
                modifier = Modifier.alpha(0.95f)
            )
        }
    }

    @Composable
    private fun BulleUtilisateur(texte: String) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(
                "Toi", color = Blanc.copy(alpha = 0.5f), fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
            )
            Text(
                texte,
                color = Blanc,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }

    @Composable
    private fun CarteSms(contact: Contact, message: String) {
        val vert = Color(0xFF34D399)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text("SMS pour ${contact.nom}", color = vert, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Blanc, fontSize = 18.sp, lineHeight = 25.sp)
            Spacer(Modifier.height(18.dp))

            // Compte à rebours : démarre quand la lecture à voix haute est finie
            var progression by remember(contact, message) { mutableFloatStateOf(0f) }
            LaunchedEffect(contact, message) {
                withTimeoutOrNull(15_000) { snapshotFlow { lectureFinie }.first { it } }
                val pas = 50
                for (i in 1..pas) {
                    delay(DELAI_ENVOI_MS / pas)
                    progression = i / pas.toFloat()
                }
                envoyerSmsEnAttente()
            }
            val restant = ceil((1f - progression) * DELAI_ENVOI_MS / 1000f).toInt()
            LinearProgressIndicator(
                progress = { progression },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = vert,
                trackColor = Color.White.copy(alpha = 0.10f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (!lectureFinie && progression == 0f) "Lecture du message…"
                else "Envoi automatique dans $restant s",
                color = Blanc.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        smsEnAttente = null
                        parler("Annulé")
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanc)
                ) { Text("Annuler", fontSize = 16.sp) }

                Button(
                    onClick = { envoyerSmsEnAttente() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = vert,
                        contentColor = Color(0xFF052E1C)
                    )
                ) { Text("Envoyer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    @Composable
    private fun CarteEvenement(ev: EvenementBrouillon, suppression: Boolean = false) {
        val violet = if (suppression) Color(0xFFFF9A8B) else Color(0xFFC4A7FF)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(if (suppression) "Supprimer de l'agenda" else "Nouvel événement", color = violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(ev.titre, color = Blanc, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(ev.resume().replaceFirstChar { it.uppercase() }, color = Blanc.copy(alpha = 0.8f), fontSize = 15.sp)
            if (ev.lieu.isNotBlank()) {
                Text(ev.lieu, color = Blanc.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        evenementEnAttente = null
                        suppressionEnAttente = null
                        parler("Annulé")
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanc)
                ) { Text("Annuler", fontSize = 16.sp) }

                Button(
                    onClick = { if (suppression) supprimerEvenementEnAttente() else ajouterEvenementEnAttente() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = violet,
                        contentColor = Color(0xFF1E1236)
                    )
                ) { Text(if (suppression) "Supprimer" else "Ajouter", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    @Composable
    private fun CarteAlarme() {
        val rouge = Color(0xFFFF9A8B)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text("Alarme de la maison", color = rouge, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Désactiver l'alarme", color = Blanc, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        alarmeEnAttente = false
                        parler("Annulé, l'alarme reste activée")
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanc)
                ) { Text("Annuler", fontSize = 16.sp) }
                Button(
                    onClick = { desactiverAlarmeEnAttente() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rouge, contentColor = Color(0xFF3A0E08))
                ) { Text("Désactiver", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    @Composable
    private fun CarteMail(mail: MailBrouillon) {
        val bleu = Color(0xFF7DB7FF)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(
                (if (mail.repondreA != null) "Réponse à " else "Mail pour ") + mail.nomAffiche,
                color = bleu, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Text(mail.destinataire, color = Blanc.copy(alpha = 0.5f), fontSize = 12.sp)
            if (mail.sujet.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(mail.sujet, color = Blanc, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                mail.corps,
                color = Blanc,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        mailEnAttente = null
                        parler("Mail annulé")
                    },
                    enabled = !envoiMailEnCours,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanc)
                ) { Text("Annuler", fontSize = 16.sp) }

                Button(
                    onClick = { envoyerMailEnAttente() },
                    enabled = !envoiMailEnCours,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = bleu,
                        contentColor = Color(0xFF0B1E33)
                    )
                ) { Text(if (envoiMailEnCours) "Envoi…" else "Envoyer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    @Composable
    private fun BoutonMicro(c1: Color, c2: Color) {
        val actif = !reflexion
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c1, c2)))
                    .alpha(if (actif) 1f else 0.5f)
                    .clickable(
                        enabled = actif,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (ecoute) reconnaissance.stopListening() else demarrer()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (ecoute) "■" else "🎙", fontSize = 28.sp, color = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (ecoute) "Appuie pour arrêter" else "Appuie pour parler",
                color = Blanc.copy(alpha = 0.5f),
                fontSize = 13.sp
            )
        }
    }

    override fun onDestroy() {
        reconnaissance.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
