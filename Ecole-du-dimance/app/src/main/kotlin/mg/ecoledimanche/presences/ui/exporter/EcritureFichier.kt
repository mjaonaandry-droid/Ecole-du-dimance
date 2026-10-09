package mg.ecoledimanche.presences.ui.exporter

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Écrit [texte] (UTF-8) dans le fichier que l'utilisateur vient de créer avec le sélecteur de fichiers
 * du système. Aucune permission de stockage : l'accès est accordé par le sélecteur pour ce seul fichier,
 * et rien ne quitte le téléphone sans que l'utilisateur l'ait choisi.
 */
suspend fun ecrireFichier(contexte: Context, destination: Uri, texte: String) {
    val application = contexte.applicationContext
    withContext(Dispatchers.IO) {
        val flux = application.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Impossible d'ouvrir le fichier de destination")
        flux.use { it.write(texte.toByteArray(Charsets.UTF_8)) }
    }
}
