package mg.ecoledimanche.presences.domain

/**
 * Notes d'examen : toutes sur 20, enregistrées en CENTIÈMES de point (1250 = 12,5) pour calculer sans
 * erreur d'arrondi.
 *
 * Règle du cumul (choisie pour l'école) : chaque enfant ACTIF est noté à TOUS les examens ; un examen
 * sans note pour lui compte 0. Exemple avec trois examens : un enfant noté 12 et 15 seulement a un
 * total de 27 / 60 et une moyenne de 9 / 20.
 */
object Notes {
    const val BAREME = 20
    const val MAX_CENTIEMES = BAREME * 100
    const val LONGUEUR_MAX_INTITULE = 100

    private val format = Regex("""(\d{1,2})(?:[.,](\d{1,2}))?""")

    /** « 12 », « 12,5 », « 12.25 », « 9,0 » → centièmes ; nul si ce n'est pas une note de 0 à 20 (deux décimales au plus). */
    fun lire(texte: String): Int? {
        val m = format.matchEntire(texte.trim()) ?: return null
        val entier = m.groupValues[1].toInt()
        val decimales = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toInt()
        val centiemes = entier * 100 + decimales
        return centiemes.takeIf { it <= MAX_CENTIEMES }
    }

    /** 1250 → « 12,5 » ; 1225 → « 12,25 » ; 1200 → « 12 » (virgule française, zéros inutiles retirés). */
    fun format(centiemes: Int): String {
        val entier = centiemes / 100
        val reste = centiemes % 100
        return when {
            reste == 0 -> "$entier"
            reste % 10 == 0 -> "$entier,${reste / 10}"
            else -> "$entier,${reste.toString().padStart(2, '0')}"
        }
    }
}

/**
 * Cumul d'un enfant sur une liste d'examens.
 * @param notes une valeur par examen, dans l'ordre des examens ; nul = pas de note (compte 0).
 */
data class CumulNotes(val notes: List<Int?>) {
    val nombreExamens: Int get() = notes.size

    /** Somme des notes, un examen sans note comptant 0 (centièmes). */
    val totalCentiemes: Int get() = notes.sumOf { it ?: 0 }

    /** Total possible : 20 par examen (centièmes). */
    val maximumCentiemes: Int get() = nombreExamens * Notes.MAX_CENTIEMES

    /** Examens sans note (comptés 0). */
    val manquantes: Int get() = notes.count { it == null }

    /**
     * Moyenne sur 20 en centièmes, arrondie au centième le plus proche (demi-centième vers le haut) ;
     * nul s'il n'y a encore aucun examen.
     */
    val moyenneCentiemes: Int?
        get() = if (nombreExamens == 0) null else ((2L * totalCentiemes + nombreExamens) / (2L * nombreExamens)).toInt()

    companion object {
        /** Cumul d'un enfant : [notesParExamen] ne contient que les notes saisies (examen → centièmes). */
        fun calculer(examens: List<Long>, notesParExamen: Map<Long, Int>): CumulNotes =
            CumulNotes(examens.map { notesParExamen[it] })
    }
}
