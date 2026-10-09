package mg.ecoledimanche.presences.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.camera.StockagePhotosPrive
import mg.ecoledimanche.presences.domain.Sexe

val LocalStockagePhotos = staticCompositionLocalOf<StockagePhotosPrive> {
    error("LocalStockagePhotos n'est pas fourni : voir MainActivity")
}

/**
 * Photo d'un enfant. Tant que la photo n'a pas été prise (chemin vide), tant que l'image n'est pas
 * chargée, ou si le fichier est absent ou illisible, une silhouette grisée est affichée : garçon,
 * fille, ou neutre si le [sexe] n'est pas encore connu. L'application ne plante jamais à cause d'une photo.
 */
@Composable
fun PhotoEnfant(
    chemin: String?,
    description: String?,
    modifier: Modifier = Modifier,
    sexe: Sexe? = null,
    coteMaxPx: Int = 480,
    forme: Shape = RoundedCornerShape(12.dp),
    echelle: ContentScale = ContentScale.Crop,
) {
    val stockage = LocalStockagePhotos.current
    val image by produceState<ImageBitmap?>(initialValue = null, chemin, coteMaxPx) {
        value = if (chemin.isNullOrBlank()) null else ChargeurImages.charger(stockage, chemin, coteMaxPx)
    }
    val sombre = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .clip(forme)
            .background(if (sombre) FondSilhouetteSombre else FondSilhouetteClair),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = echelle,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(silhouette(sexe)),
                contentDescription = description,
                tint = if (sombre) TeinteSilhouetteSombre else TeinteSilhouetteClair,
                modifier = Modifier.fillMaxSize(0.8f),
            )
        }
    }
}

private fun silhouette(sexe: Sexe?): Int = when (sexe) {
    Sexe.GARCON -> R.drawable.silhouette_garcon
    Sexe.FILLE -> R.drawable.silhouette_fille
    null -> R.drawable.silhouette_neutre
}

// Gris neutres (indépendants de la couleur du thème) : l'enfant « sans photo » se voit au premier coup d'œil.
private val FondSilhouetteClair = Color(0xFFE3E3E6)
private val TeinteSilhouetteClair = Color(0xFF9A9AA0)
private val FondSilhouetteSombre = Color(0xFF3B3B40)
private val TeinteSilhouetteSombre = Color(0xFF8E8E94)
