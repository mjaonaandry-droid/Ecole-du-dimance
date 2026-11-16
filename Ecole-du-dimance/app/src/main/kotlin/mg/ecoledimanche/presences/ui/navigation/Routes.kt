package mg.ecoledimanche.presences.ui.navigation

/** Routes de navigation (chaînes simples, sans plugin de sérialisation). */
object Routes {
    const val DIMANCHE = "dimanche"
    const val ENFANTS = "enfants"
    const val ASSIDUITE = "assiduite"
    const val NOTES = "notes"
    const val DONNEES = "donnees"
    const val EXPORTER = "exporter"
    const val AJOUTER = "ajouter"
    const val IMPORTER = "importer"

    const val ARG_ID = "id"
    const val ARG_GALERIE = "galerie"

    /** Examen à modifier ; [NOUVEL_EXAMEN] pour en créer un. */
    const val EXAMEN = "examen?$ARG_ID={$ARG_ID}"
    const val NOUVEL_EXAMEN = -1L
    const val FICHE = "enfant/{$ARG_ID}"
    const val MODIFIER = "enfant/{$ARG_ID}/modifier"
    const val PHOTO = "enfant/{$ARG_ID}/photo?$ARG_GALERIE={$ARG_GALERIE}"
    const val HISTORIQUE = "enfant/{$ARG_ID}/historique"

    fun fiche(id: Long) = "enfant/$id"
    fun modifier(id: Long) = "enfant/$id/modifier"
    fun photo(id: Long, galerie: Boolean = false) = "enfant/$id/photo?$ARG_GALERIE=$galerie"
    fun historique(id: Long) = "enfant/$id/historique"
    fun examen(id: Long?) = "examen?$ARG_ID=${id ?: NOUVEL_EXAMEN}"

    /** Écrans qui affichent la barre de navigation basse. */
    val ONGLETS = setOf(DIMANCHE, ENFANTS, ASSIDUITE, NOTES, DONNEES)
}
