package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HecklerTest {

    @Test
    fun impactSpeed() {
        // Där man stannar från 70 kör man fortfarande runt 28 km/h från 74.
        assertEquals(28.0, SpeedFacts.impactKmh(74, 70).toDouble(), 2.0)
        assertEquals(0, SpeedFacts.impactKmh(70, 70))
        assertTrue(SpeedFacts.impactKmh(90, 70) > 60)
    }

    @Test
    fun otherFacts() {
        assertEquals(28, SpeedFacts.secondsSavedPerMil(74, 70)) // 514,3 − 486,5 s
        assertTrue(SpeedFacts.extraStopMeters(74, 70) in 4..7)
        assertEquals(12, SpeedFacts.extraDragPercent(74, 70))
    }

    @Test
    fun linesAlternateAndNeverCrash() {
        for (mode in listOf(HeckleMode.NICE, HeckleMode.MEAN))
            for (n in 0..40)
                for ((v, l) in listOf(71 to 70, 74 to 70, 140 to 30, 31 to 30))
                    assertTrue(Heckler.line(n, mode, v, l).isNotBlank())
        assertEquals("Hallå? Skylten säger 70.", Heckler.line(2, HeckleMode.MEAN, 74, 70))
    }
}
