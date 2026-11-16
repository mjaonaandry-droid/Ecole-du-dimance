package mg.ecoledimanche.presences.data.repository

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mg.ecoledimanche.presences.data.local.CleParametre
import mg.ecoledimanche.presences.data.local.ParametreDao
import mg.ecoledimanche.presences.data.local.ParametreEntity
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.ReglesHorloge
import mg.ecoledimanche.presences.domain.VerdictHorloge

/** Vérification de la date du téléphone avant toute clôture automatique (voir [ReglesHorloge]). */
fun interface ControleHorloge {
    /** Vrai si l'horloge est plausible ; sinon rien ne doit être clôturé. */
    suspend fun horlogeFiable(): Boolean

    companion object {
        /** Sans garde-fou (tests des règles de séance, qui pilotent eux-mêmes l'horloge). */
        val TOUJOURS_FIABLE = ControleHorloge { true }
    }
}

sealed interface EtatHorloge {
    data object Fiable : EtatHorloge

    /**
     * Date douteuse : l'application se bloque sur un écran d'avertissement.
     * @param reference la dernière date vue par l'application (ou la date minimale possible).
     */
    data class Douteuse(val verdict: VerdictHorloge, val maintenant: Instant, val reference: Instant) : EtatHorloge {
        /** Une date impossible ne peut pas être confirmée : il faut régler le téléphone. */
        val confirmable: Boolean get() = verdict != VerdictHorloge.IMPOSSIBLE
    }
}

/**
 * Retient la date la plus récente vue par l'application (table PARAMETRES, donc incluse dans la
 * sauvegarde) et compare l'horloge à chaque rattrapage. L'écran observe [etat].
 */
class GardienHorloge(
    private val parametres: ParametreDao,
    private val horloge: AppClock,
) : ControleHorloge {
    private val _etat = MutableStateFlow<EtatHorloge>(EtatHorloge.Fiable)
    val etat: StateFlow<EtatHorloge> = _etat
    private val verrou = Mutex()

    override suspend fun horlogeFiable(): Boolean = verrou.withLock {
        val maintenant = horloge.instant()
        val derniere = parametres.lire(CleParametre.HORLOGE_DERNIERE)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        when (val verdict = ReglesHorloge.evaluer(maintenant, derniere)) {
            VerdictHorloge.FIABLE -> {
                // Écriture espacée : le rattrapage est appelé souvent, la précision voulue est de l'ordre du jour.
                if (derniere == null || maintenant.isAfter(derniere.plus(ECART_ECRITURE))) memoriser(maintenant)
                _etat.value = EtatHorloge.Fiable
                true
            }
            else -> {
                _etat.value = EtatHorloge.Douteuse(verdict, maintenant, derniere ?: ReglesHorloge.PLANCHER)
                false
            }
        }
    }

    /**
     * L'utilisateur confirme que la date du téléphone est juste (par exemple : la date retenue venait
     * elle-même d'une horloge mal réglée). Sans effet pour une date impossible.
     */
    suspend fun confirmerDateActuelle() = verrou.withLock {
        val maintenant = horloge.instant()
        if (ReglesHorloge.evaluer(maintenant, null) == VerdictHorloge.IMPOSSIBLE) return@withLock
        memoriser(maintenant)
        _etat.value = EtatHorloge.Fiable
    }

    private suspend fun memoriser(instant: Instant) {
        parametres.ecrire(ParametreEntity(CleParametre.HORLOGE_DERNIERE, instant.toEpochMilli().toString()))
    }

    private companion object {
        val ECART_ECRITURE: Duration = Duration.ofMinutes(10)
    }
}
