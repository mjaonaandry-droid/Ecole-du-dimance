package mg.ecoledimanche.presences.domain

import java.time.Duration
import java.time.Instant

/** Ce que l'on pense de l'horloge du téléphone au moment de s'en servir. */
enum class VerdictHorloge {
    /** Date plausible : l'application peut clôturer les séances et inscrire des enfants. */
    FIABLE,

    /** La date est antérieure à une date déjà vue par l'application (horloge remise en arrière, batterie vidée…). */
    RECUL,

    /** La date fait un bond en avant inhabituel depuis la dernière utilisation (année mal réglée…). */
    SAUT,

    /** La date est antérieure à la création de l'application : elle ne peut pas être juste. */
    IMPOSSIBLE,
}

/**
 * Garde-fou de la date du téléphone. L'application est hors ligne : sans réseau, le téléphone ne remet pas
 * toujours son heure à l'heure tout seul, et une date fausse aurait des effets durables :
 * - date trop en AVANCE : tous les dimanches jusqu'à cette date seraient clôturés, enfants « Absent » ;
 * - date trop en ARRIÈRE : un enfant ajouté recevrait un début de suivi des années trop tôt.
 * Tant que la date est douteuse, rien n'est clôturé et l'application demande de la vérifier.
 */
object ReglesHorloge {
    /** Aucune date antérieure n'est possible : l'application n'existait pas. */
    val PLANCHER: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Petit recul toléré (changement de fuseau, réglage de quelques heures). */
    val TOLERANCE_RECUL: Duration = Duration.ofDays(1)

    /**
     * Au-delà de cet écart depuis la dernière utilisation, on demande confirmation. Assez long pour les
     * grandes vacances (l'application peut rester fermée tout l'été), assez court pour repérer une année
     * mal réglée.
     */
    val SAUT_MAX: Duration = Duration.ofDays(120)

    /**
     * @param derniere instant le plus récent déjà vu par l'application (nul à la première utilisation).
     */
    fun evaluer(maintenant: Instant, derniere: Instant?): VerdictHorloge = when {
        maintenant.isBefore(PLANCHER) -> VerdictHorloge.IMPOSSIBLE
        derniere == null -> VerdictHorloge.FIABLE
        maintenant.isBefore(derniere.minus(TOLERANCE_RECUL)) -> VerdictHorloge.RECUL
        maintenant.isAfter(derniere.plus(SAUT_MAX)) -> VerdictHorloge.SAUT
        else -> VerdictHorloge.FIABLE
    }
}
