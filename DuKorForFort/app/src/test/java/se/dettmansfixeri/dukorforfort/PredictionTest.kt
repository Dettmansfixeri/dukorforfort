package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PredictionTest {

    @Test fun landarHogreNarManGasarPa() {
        val t = SpeedTrend()
        // 60 → 66 km/h på 3 s (2 km/h per sekund ≈ 0,56 m/s²)
        for (i in 0..3) t.add(i.toDouble(), (60 + 2 * i) / 3.6)
        val land = t.landingKmh(66 / 3.6)
        assertNotNull(land)
        assertEquals(69, land!!)
    }

    @Test fun ingenLandningVidJamnFart() {
        val t = SpeedTrend()
        for (i in 0..3) t.add(i.toDouble(), 70 / 3.6)
        assertNull(t.landingKmh(70 / 3.6))
    }

    @Test fun fullgas() {
        val t = SpeedTrend()
        // 40 → 70 km/h på 3 s: landar långt över
        for (i in 0..3) t.add(i.toDouble(), (40 + 10 * i) / 3.6)
        assertTrue(t.landingKmh(70 / 3.6)!! >= 80)
    }

    @Test fun larSigInbromsning() {
        val b = BrakeLearner()
        assertEquals(BrakeLearner.DEFAULT, b.decel, 1e-9)
        repeat(3) { r ->
            val start = r * 100.0
            // Släpper gasen: 90 → ~72 km/h med 1,0 m/s² i 5 s, sedan jämn fart.
            for (i in 0..5) b.add(start + i, 25.0 - i * 1.0)
            for (i in 6..10) b.add(start + i, 20.0)
        }
        assertEquals(3, b.count)
        assertEquals(1.0, b.decel, 0.05)
    }

    @Test fun hardInbromsningRaknasInte() {
        val b = BrakeLearner()
        for (i in 0..5) b.add(i.toDouble(), 25.0 - i * 4.0)   // 4 m/s² = bromsar
        b.add(6.0, 5.0)
        assertEquals(0, b.count)
    }

    private val file = File("../../data/hastigheter.bin")

    private fun data(): SpeedData {
        assumeTrue("data/hastigheter.bin saknas", file.exists())
        return SpeedData.open(file)!!
    }

    private fun ahead(d: SpeedData, e: Double, n: Double, course: Double): Ahead.Result? {
        val road = RoadMatcher().let { m -> var r: SpeedData.Candidate? = null; repeat(3) { r = m.match(d, e, n, 5f, course.toFloat()) }; r }!!
        return Ahead.find(d, road, e, n, course) { it.kmh }
    }

    // W786 norrut på 70: 50-sträckan börjar vid (545132, 6696919), ca 220 m fram.
    @Test fun lagreGransFramNorrut() {
        val a = ahead(data(), 545030.0, 6696723.0, 27.5)
        assertNotNull(a)
        assertEquals(50, a!!.kmh)
        assertTrue("avstånd ${a.distance}", a.distance in 190.0..250.0)
    }

    // W786 söderut på 70: 50-sträckan börjar vid (545005, 6696675), ca 205 m fram.
    @Test fun lagreGransFramSoderut() {
        val a = ahead(data(), 545100.0, 6696857.0, 207.5)
        assertNotNull(a)
        assertEquals(50, a!!.kmh)
        assertTrue("avstånd ${a.distance}", a.distance in 175.0..235.0)
    }

    @Test fun sirenVid21PaLagGrans() {
        assertEquals(21, Beeper.licenseOver(30))
        assertEquals(31, Beeper.licenseOver(50))
        assertEquals(0, Beeper.pattern(21, Beeper.licenseOver(30))!!.count)
        assertEquals(12, Beeper.pattern(21, Beeper.licenseOver(50))!!.count)
    }

    @Test fun tonlaget() {
        val p = Beeper.pattern(5, tone = true)!!
        assertTrue(p.tone)
        assertEquals(Math.round(Beeper.hz(5)).toDouble(), p.hz, 1e-9)
        assertTrue(Beeper.pattern(6, tone = true)!!.hz > p.hz)
        val pcm = Beeper.render(p)
        assertEquals(22050, pcm.size)
        assertTrue(pcm.count { it.toInt() == 0 } < 200)   // ingen paus
        assertEquals(22050 * 600 / 1000, Beeper.render(Beeper.click(600)).size)
    }
}
