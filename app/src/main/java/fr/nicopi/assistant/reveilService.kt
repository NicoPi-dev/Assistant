package fr.nicopi.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Écoute en permanence le mot d'appel « Hey Claude », hors ligne, avec Vosk.
 * Quand il est détecté : libère le micro et ouvre l'assistant.
 */
class ReveilService : Service(), RecognitionListener {

    companion object {
        const val ACTION_PAUSE = "fr.nicopi.assistant.PAUSE"
        const val ACTION_REPRENDRE = "fr.nicopi.assistant.REPRENDRE"
        private const val CANAL = "reveil"
        private const val MODELE_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"

        // Mots que Vosk a le droit de reconnaître. Tout le reste devient [unk].
        private const val GRAMMAIRE =
            "[\"h\u00e9 claude\", \"eh claude\", \"hey claude\", \"[unk]\"]"

        // Sensibilite : confiance minimale (0 a 1) exigee sur chaque mot du mot d'appel.
        // Plus haut = moins de faux declenchements, mais il faut parler plus distinctement.
        private const val SEUIL = 0.85
        private val INTERJECTIONS = setOf("h\u00e9", "eh", "hey")

        /** Depuis l'app au premier plan : démarre le service si besoin et coupe l'écoute. */
        fun pauser(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ReveilService::class.java).setAction(ACTION_PAUSE))
        }

        /** Quand l'app passe en arrière-plan : reprend l'écoute du mot d'appel. */
        fun reprendre(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ReveilService::class.java).setAction(ACTION_REPRENDRE))
            } catch (_: Exception) { /* service pas encore démarré : rien à reprendre */ }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var enPause = false
    private var overlay: View? = null
    private var texteNotif = "Préparation…"

    /** Si l'assistant ne s'est pas ouvert après un réveil, on se remet à écouter. */
    private val securite = Runnable { enPause = false; demarrerEcoute() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Mot d'appel", NotificationManager.IMPORTANCE_LOW)
        )
        passerAuPremierPlan()
        chargerModele()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                passerAuPremierPlan()
                main.removeCallbacks(securite)
                enPause = true
                arreterEcoute()
                majNotif("En pause (assistant ouvert)")
            }
            ACTION_REPRENDRE -> {
                enPause = false
                demarrerEcoute()
            }
        }
        return START_STICKY
    }

    // --- Modèle Vosk (téléchargé une seule fois, ~40 Mo) ---

    private fun chargerModele() = thread {
        try {
            val dossier = File(filesDir, "modele-fr")
            if (!File(dossier, "am/final.mdl").exists()) {
                majNotif("Téléchargement du modèle français (40 Mo)…")
                telechargerModele(dossier)
            }
            model = Model(dossier.absolutePath)
            main.post { demarrerEcoute() }
        } catch (e: Exception) {
            majNotif("Erreur modèle : ${e.message}")
        }
    }

    private fun telechargerModele(dossier: File) {
        val tmp = File(filesDir, "modele-tmp").apply { deleteRecursively(); mkdirs() }
        val base = tmp.canonicalPath + File.separator
        ZipInputStream(URL(MODELE_URL).openStream().buffered()).use { zip ->
            while (true) {
                val entree = zip.nextEntry ?: break
                // On retire le dossier racine du zip ("vosk-model-small-fr-0.22/...")
                val nom = entree.name.substringAfter('/')
                if (nom.isEmpty()) continue
                val cible = File(tmp, nom)
                if (!cible.canonicalPath.startsWith(base)) continue // protection zip-slip
                if (entree.isDirectory) cible.mkdirs()
                else {
                    cible.parentFile?.mkdirs()
                    cible.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        dossier.deleteRecursively()
        tmp.renameTo(dossier)
    }

    // --- Écoute ---

    private fun demarrerEcoute() {
        val m = model ?: return
        if (enPause || speech != null) return
        try {
            val rec = Recognizer(m, 16000f, GRAMMAIRE)
            rec.setWords(true)
            recognizer = rec
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            majNotif("À l'écoute de « Hey Claude »")
        } catch (e: Exception) {
            majNotif("Micro indisponible : ${e.message}")
            main.postDelayed({ demarrerEcoute() }, 5000)
        }
    }

    private fun arreterEcoute() {
        speech?.stop()
        speech?.shutdown()
        speech = null
        recognizer?.close()
        recognizer = null
    }

    private fun verifier(json: String?) {
        val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return
        val mots = o.optJSONArray("result") ?: return
        // Deux mots consecutifs exiges : une interjection puis "claude", tous deux surs
        for (i in 1 until mots.length()) {
            val avant = mots.getJSONObject(i - 1)
            val mot = mots.getJSONObject(i)
            if (mot.optString("word") == "claude" &&
                avant.optString("word") in INTERJECTIONS &&
                mot.optDouble("conf") >= SEUIL &&
                avant.optDouble("conf") >= SEUIL
            ) {
                reveil()
                return
            }
        }
    }

    // Les resultats partiels sont ignores : seuls les resultats complets comptent
    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) = verifier(hypothesis)
    override fun onFinalResult(hypothesis: String?) = verifier(hypothesis)
    override fun onError(exception: Exception?) {
        arreterEcoute()
        main.postDelayed({ demarrerEcoute() }, 3000)
    }

    override fun onTimeout() {
        arreterEcoute()
        demarrerEcoute()
    }

    // --- Réveil ---

    private fun reveil() {
        if (enPause) return
        enPause = true
        arreterEcoute() // libère le micro pour la reconnaissance de la commande

        // Android interdit d'ouvrir une app depuis l'arrière-plan, sauf si une
        // bulle "par-dessus les autres apps" est visible : on l'affiche d'abord.
        afficherBulle()
        main.postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("reveil", true)
            )
        }, 150)
        main.postDelayed({ retirerBulle() }, 2500)
        main.postDelayed(securite, 10_000)
    }

    private fun afficherBulle() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return
        val vue = TextView(this).apply {
            text = "🎤 Hey Claude !"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(48, 28, 48, 28)
            background = GradientDrawable().apply {
                cornerRadius = 80f
                setColor(0xFF6750A4.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 160
        }
        getSystemService(WindowManager::class.java).addView(vue, params)
        overlay = vue
    }

    private fun retirerBulle() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    // --- Notification obligatoire du service ---

    private fun notif(texte: String): Notification =
        Notification.Builder(this, CANAL)
            .setContentTitle("Assistant")
            .setContentText(texte)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun passerAuPremierPlan() {
        startForeground(1, notif(texteNotif), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun majNotif(texte: String) {
        texteNotif = texte
        getSystemService(NotificationManager::class.java).notify(1, notif(texte))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        arreterEcoute()
        retirerBulle()
        model?.close()
        super.onDestroy()
    }
}
