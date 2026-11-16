package mg.ecoledimanche.presences.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ReglesHorlogeTest {
    private val derniere = Instant.parse("2026-11-15T07:00:00Z")

    @Test
    fun premiereUtilisation_fiableSaufDateImpossible() {
        assertEquals(VerdictHorloge.FIABLE, ReglesHorloge.evaluer(derniere, null))
        assertEquals(VerdictHorloge.IMPOSSIBLE, ReglesHorloge.evaluer(Instant.parse("2015-01-01T00:00:00Z"), null))
        assertEquals(VerdictHorloge.IMPOSSIBLE, ReglesHorloge.evaluer(Instant.parse("2015-01-01T00:00:00Z"), derniere))
    }

    @Test
    fun petitRecul_toleré_grandRecul_signale() {
        assertEquals(VerdictHorloge.FIABLE, ReglesHorloge.evaluer(derniere.minus(Duration.ofHours(23)), derniere))
        assertEquals(VerdictHorloge.RECUL, ReglesHorloge.evaluer(derniere.minus(Duration.ofDays(2)), derniere))
    }

    @Test
    fun vacancesDEte_tolerees_anneeMalReglee_signalee() {
        assertEquals(VerdictHorloge.FIABLE, ReglesHorloge.evaluer(derniere.plus(Duration.ofDays(100)), derniere))
        assertEquals(VerdictHorloge.SAUT, ReglesHorloge.evaluer(derniere.plus(Duration.ofDays(365)), derniere))
    }
}
