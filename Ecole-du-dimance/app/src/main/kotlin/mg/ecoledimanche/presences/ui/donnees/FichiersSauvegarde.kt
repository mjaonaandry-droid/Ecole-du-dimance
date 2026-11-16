package mg.ecoledimanche.presences.ui.donnees

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Accès au fichier choisi avec le sélecteur de fichiers du système (aucune permission de stockage :
 * l'accès est accordé pour ce seul fichier). Les fonctions rendues ouvrent un NOUVEAU flux à chaque appel :
 * la restauration lit le fichier deux fois (vérification, puis copie des photos).
 */
fun ouvrirLecture(contexte: Context, source: Uri): () -> InputStream {
    val application = contexte.applicationContext
    return { application.contentResolver.openInputStream(source) ?: throw IOException("Impossible d'ouvrir le fichier") }
}

fun ouvrirEcriture(contexte: Context, destination: Uri): () -> OutputStream {
    val application = contexte.applicationContext
    return {
        application.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Impossible d'ouvrir le fichier de destination")
    }
}
