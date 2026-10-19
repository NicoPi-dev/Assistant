package fr.nicopi.assistant


import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import java.text.Normalizer

data class Contact(val nom: String, val numero: String)

/** Les actions concrètes sur le téléphone. */
object Actions {

    /** "Julie " -> "julie" : minuscules, sans accents ni espaces autour. */
    private fun normaliser(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .trim()

    /** Cherche un contact par son nom, du plus exact au plus approximatif. */
    fun trouverContact(context: Context, nomDit: String): Contact? {
        val cible = normaliser(nomDit)
        val contacts = mutableListOf<Contact>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val nom = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                contacts += Contact(nom, num)
            }
        }

        return contacts.firstOrNull { normaliser(it.nom) == cible }
            ?: contacts.firstOrNull { normaliser(it.nom).startsWith(cible) }
            ?: contacts.firstOrNull { normaliser(it.nom).split(" ").any { mot -> mot == cible } }
            ?: contacts.firstOrNull { normaliser(it.nom).contains(cible) }
    }

    fun appeler(context: Context, contact: Contact) {
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contact.numero, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun envoyerSms(context: Context, contact: Contact, message: String) {
        val sms = context.getSystemService(SmsManager::class.java)
        val parties = sms.divideMessage(message)
        sms.sendMultipartTextMessage(contact.numero, null, parties, null, null)
    }
}
