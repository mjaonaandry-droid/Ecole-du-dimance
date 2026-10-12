package mg.ecoledimanche.presences.ui.importer

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mg.ecoledimanche.presences.domain.ImportCsv

/**
 * Lit les octets du fichier que l'utilisateur vient de choisir avec le sélecteur de fichiers du système.
 * Aucune permission de stockage : l'accès est accordé pour ce seul fichier. Au-delà de
 * [ImportCsv.TAILLE_MAX_OCTETS], la lecture s'arrête (le fichier est alors refusé).
 */
suspend fun lireFichier(contexte: Context, source: Uri): ByteArray {
    val application = contexte.applicationContext
    return withContext(Dispatchers.IO) {
        val flux = application.contentResolver.openInputStream(source)
            ?: throw IOException("Impossible d'ouvrir le fichier")
        flux.use { entree ->
            val sortie = ByteArrayOutputStream()
            val tampon = ByteArray(16 * 1024)
            while (true) {
                val lus = entree.read(tampon)
                if (lus < 0) break
                sortie.write(tampon, 0, lus)
                if (sortie.size() > ImportCsv.TAILLE_MAX_OCTETS) break
            }
            sortie.toByteArray()
        }
    }
}
