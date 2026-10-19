package fr.nicopi.assistant

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent

/** Spotify (lecture par recherche) et télécommande de la musique en cours. */
object Musique {

    private const val SPOTIFY = "com.spotify.music"

    /**
     * Demande à Spotify de jouer directement le meilleur résultat de la recherche.
     * type : titre, artiste, album, playlist.
     */
    fun jouer(activite: Activity, requete: String, type: String, artiste: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(SPOTIFY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val recherche = if (artiste.isNotBlank() && type == "titre") "$requete $artiste" else requete
            putExtra(SearchManager.QUERY, recherche)
            when (type) {
                "artiste" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, requete)
                }
                "album" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ALBUM, requete)
                    if (artiste.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artiste)
                }
                "playlist" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                    putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, requete)
                }
                else -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, requete)
                    if (artiste.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artiste)
                }
            }
        }
        try {
            activite.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("Spotify n'est pas installé")
        }
    }

    /** pause, lecture, suivant, precedent, volume_plus, volume_moins */
    fun controler(activite: Activity, action: String) {
        val audio = activite.getSystemService(AudioManager::class.java)
        fun touche(code: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (action) {
            "pause" -> touche(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "lecture" -> touche(KeyEvent.KEYCODE_MEDIA_PLAY)
            "suivant" -> touche(KeyEvent.KEYCODE_MEDIA_NEXT)
            "precedent" -> touche(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_plus" -> repeat(2) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            "volume_moins" -> repeat(2) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        }
    }
}
