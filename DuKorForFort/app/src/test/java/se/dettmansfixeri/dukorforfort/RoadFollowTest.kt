package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Påhittade körningar längs riktiga vägar i hastighetsdatan. GPS-punkter varje sekund med
 * slumpfel, som en riktig telefon. Kollar att vägvalet ligger på rätt väg hela tiden och
 * byter där vägen verkligen byter – inte vid grannvägar som bara råkar ligga nära.
 */
class RoadFollowTest {

    private val file = File("../../data/hastigheter.bin")

    /** En bit av körningen: punkter i färdriktningen, hastighet, och en punkt som bara ligger på rätt sträcka. */
    private class Leg(val name: String, val kmh: Int, val probe: Pair<Double, Double>, vararg pts: Pair<Int, Int>) {
        val pts = pts.map { it.first.toDouble() to it.second.toDouble() }
    }

    private class Fix(val e: Double, val n: Double, val course: Float, val leg: Int, val distToBoundary: Double)

    /** Går längs benen med en GPS-punkt per sekund. */
    private fun drive(legs: List<Leg>): List<Fix> {
        // Hela vägen som en lista punkter med benindex, och var benen byts (meter från start).
        val fixes = ArrayList<Fix>()
        val bounds = ArrayList<Double>()
        var s = 0.0
        val segs = ArrayList<DoubleArray>() // x0,y0,x1,y1,s0,leg
        legs.forEachIndexed { li, leg ->
            if (li > 0) bounds += s
            for (i in 1 until leg.pts.size) {
                val (x0, y0) = leg.pts[i - 1]; val (x1, y1) = leg.pts[i]
                val l = hypot(x1 - x0, y1 - y0)
                if (l > 0) segs += doubleArrayOf(x0, y0, x1, y1, s, li.toDouble())
                s += l
            }
        }
        val total = s
        var pos = 0.0
        while (pos <= total) {
            val seg = segs.last { it[4] <= pos }
            val l = hypot(seg[2] - seg[0], seg[3] - seg[1])
            val t = ((pos - seg[4]) / l).coerceIn(0.0, 1.0)
            val e = seg[0] + t * (seg[2] - seg[0]); val n = seg[1] + t * (seg[3] - seg[1])
            val course = ((Math.toDegrees(atan2(seg[2] - seg[0], seg[3] - seg[1])) + 360) % 360).toFloat()
            val li = seg[5].toInt()
            fixes += Fix(e, n, course, li, bounds.minOfOrNull { kotlin.math.abs(it - pos) } ?: 1e9)
            pos += legs[li].kmh / 3.6
        }
        return fixes
    }

    /** Kör [runs] gånger med olika slumpfel. Returnerar en lista med fel (tom = allt rätt). */
    private fun check(data: SpeedData, legs: List<Leg>, runs: Int = 30, gpsError: Double = 3.0, old: Boolean = false): List<String> {
        val fixes = drive(legs)
        val errors = ArrayList<String>()
        for (seed in 1..runs) {
            val rnd = Random(seed.toLong())
            val m = RoadMatcher()
            val om = OldMatcher()
            for (f in fixes) {
                val e = f.e + rnd.nextGaussian() * gpsError
                val n = f.n + rnd.nextGaussian() * gpsError
                val course = (f.course + rnd.nextGaussian() * 5).toFloat()
                val got = if (old) om.match(data, e, n, course) else m.match(data, e, n, 5f, course)
                val leg = legs[f.leg]
                val ok = got != null && got.distanceTo(leg.probe.first, leg.probe.second) < 0.6
                // Precis vid bytet får det skilja en GPS-punkt (ca 1 s).
                val slack = legs.maxOf { it.kmh } / 3.6 + 2
                if (!ok && f.distToBoundary > slack) {
                    errors += "körning $seed vid ${f.e.toInt()},${f.n.toInt()} (${leg.name}): fick ${got?.kmh ?: "-"} km/h"
                }
            }
        }
        return errors
    }

    private fun data(): SpeedData {
        assumeTrue("data/hastigheter.bin saknas", file.exists())
        return SpeedData.open(file)!!
    }

    private fun assertNoErrors(errors: List<String>) =
        assertTrue("${errors.size} fel:\n" + errors.take(15).joinToString("\n"), errors.isEmpty())

    /** Vägvalet som det var före vägföljningen (version 1.1.9): närmast + 8 m spärr. För jämförelse. */
    private class OldMatcher {
        private var currentId: Int? = null
        fun match(data: SpeedData, e: Double, n: Double, course: Float?): SpeedData.Candidate? {
            val scored = data.near(e, n, 25.0).mapNotNull { c ->
                var s = c.distance
                if (course != null) {
                    val diff = SpeedData.angleDiff(course.toDouble(), c.bearing)
                    if (c.direction == 1 && diff > 100) return@mapNotNull null
                    if (c.direction == 2 && diff < 80) return@mapNotNull null
                    val axial = if (c.direction == 0) minOf(diff, 180.0 - diff) else if (c.direction == 1) diff else 180.0 - diff
                    s += axial / 90.0 * 25.0
                }
                c to s
            }
            if (scored.isEmpty()) { currentId = null; return null }
            val best = scored.minBy { it.second }
            val current = scored.firstOrNull { it.first.id == currentId }
            val chosen = if (current != null && current.second <= best.second + 8.0) current else best
            currentId = chosen.first.id
            return chosen.first
        }
    }

    private val alla get() = listOf(
        "W786 norrut" to { listOf(
            Leg("", 70, 544975.5 to 6696618.0, 544946 to 6696561, 545005 to 6696675),
            Leg("", 70, 545068.5 to 6696797.0, 545005 to 6696675, 545132 to 6696919),
            Leg("", 70, 545192.0 to 6697031.0, 545132 to 6696919, 545192 to 6697031, 545219 to 6697074, 545429 to 6697313),
            Leg("", 70, 545505.0 to 6697402.0, 545429 to 6697313, 545505 to 6697402, 545531 to 6697444, 545553 to 6697512, 545595 to 6697672)) },
        "Stångjärnsgatan" to { listOf(Leg("", 40, 583713.0 to 6607626.5, 583484 to 6607514, 583942 to 6607739)) },
    )

    /** Skriver ut hur många GPS-punkter som fick fel väg, gammalt mot nytt vägval. Misslyckas aldrig. */
    @Test fun jamforGammaltOchNytt() {
        val d = data()
        for ((namn, legs) in alla) for (err in listOf(3.0, 5.0)) {
            val total = drive(legs()).size * 100
            val gammalt = check(d, legs(), 100, err, old = true).size
            val nytt = check(d, legs(), 100, err).size
            println("JAMFOR $namn, GPS-fel ${err.toInt()} m: gammalt $gammalt fel, nytt $nytt fel av $total punkter")
        }
    }

    // W786 norrut genom fem gränsbyten 50-70-50-70 (där skylten bytte ~20 m för sent),
    // förbi uppfarten vid Landa (50) som ligger 10–20 m från vägen.
    @Test fun w786Norrut() = assertNoErrors(check(data(), listOf(
        Leg("W786 50", 70, 544975.5 to 6696618.0, 544946 to 6696561, 545005 to 6696675),
        Leg("W786 70", 70, 545068.5 to 6696797.0, 545005 to 6696675, 545132 to 6696919),
        Leg("W786 50", 70, 545192.0 to 6697031.0, 545132 to 6696919, 545192 to 6697031, 545219 to 6697074, 545429 to 6697313),
        Leg("W786 70", 70, 545505.0 to 6697402.0, 545429 to 6697313, 545505 to 6697402, 545531 to 6697444, 545553 to 6697512, 545595 to 6697672),
    )))

    @Test fun w786Soderut() = assertNoErrors(check(data(), listOf(
        Leg("W786 70", 70, 545505.0 to 6697402.0, 545595 to 6697672, 545553 to 6697512, 545531 to 6697444, 545505 to 6697402, 545429 to 6697313),
        Leg("W786 50", 70, 545192.0 to 6697031.0, 545429 to 6697313, 545219 to 6697074, 545192 to 6697031, 545132 to 6696919),
        Leg("W786 70", 70, 545068.5 to 6696797.0, 545132 to 6696919, 545005 to 6696675),
        Leg("W786 50", 70, 544975.5 to 6696618.0, 545005 to 6696675, 544946 to 6696561),
    )))

    // In på uppfarten vid Landa, runt bågen och ut på W786 igen.
    @Test fun uppfartenVidLanda() = assertNoErrors(check(data(), listOf(
        Leg("W786 50", 50, 545192.0 to 6697031.0, 545192 to 6697031, 545219 to 6697074, 545429 to 6697313),
        Leg("W786 70", 30, 545505.0 to 6697402.0, 545429 to 6697313, 545448 to 6697334),
        Leg("uppfart", 15, 545451.0 to 6697368.0, 545448 to 6697334, 545441 to 6697354, 545451 to 6697368, 545464 to 6697374, 545475 to 6697368),
        Leg("W786 70", 50, 545505.0 to 6697402.0, 545475 to 6697368, 545505 to 6697402, 545531 to 6697444, 545553 to 6697512),
    )))

    // Från W786 in på Landa Backe (ansluter mitt på W786) och vidare österut.
    @Test fun svangInPaLandaBacke() = assertNoErrors(check(data(), listOf(
        Leg("W786 50", 50, 545192.0 to 6697031.0, 545162 to 6696975, 545192 to 6697031, 545219 to 6697074, 545384 to 6697261),
        Leg("Landa Backe", 30, 545419.0 to 6697232.0, 545384 to 6697260, 545419 to 6697232, 545436 to 6697212, 545451 to 6697174),
        Leg("Landa Backe forts.", 50, 545518.0 to 6697185.0, 545451 to 6697174, 545498 to 6697184, 545518 to 6697185, 545536 to 6697183, 545560 to 6697172, 545591 to 6697153),
    )))

    // Stångjärnsgatan i Västerås: 40-vägen med en 50-väg parallellt ~10 m bort. Ska hålla sig på 40.
    @Test fun stangjarnsgatanOsterut() = assertNoErrors(check(data(), listOf(
        Leg("Stångjärnsgatan 40", 40, 583713.0 to 6607626.5, 583484 to 6607514, 583942 to 6607739),
    )))

    @Test fun stangjarnsgatanVasterut() = assertNoErrors(check(data(), listOf(
        Leg("Stångjärnsgatan 40", 40, 583713.0 to 6607626.5, 583942 to 6607739, 583484 to 6607514),
    )))
}
