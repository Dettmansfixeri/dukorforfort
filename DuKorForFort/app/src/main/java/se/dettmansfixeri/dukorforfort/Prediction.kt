package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Hur farten ändras, från GPS-farten de senaste sekunderna.
 * Används för "Förvarna när jag gasar på": var landar farten om man släpper gasen nu?
 */
class SpeedTrend {
    private val times = ArrayDeque<Double>()
    private val speeds = ArrayDeque<Double>()

    fun reset() { times.clear(); speeds.clear() }

    /** [time] i sekunder, [speed] i m/s. */
    fun add(time: Double, speed: Double) {
        if (times.isNotEmpty() && time <= times.last()) return
        if (times.isNotEmpty() && time - times.last() > GAP_S) reset()   // glapp i GPS:en – börja om
        times.addLast(time); speeds.addLast(speed)
        while (time - times.first() > WINDOW_S) { times.removeFirst(); speeds.removeFirst() }
    }

    /** Accelerationen i m/s² (lutningen på farten), eller null om det är för få punkter. */
    fun accel(): Double? {
        if (times.size < 3 || times.last() - times.first() < 1.5) return null
        val n = times.size
        val mt = times.sum() / n
        val mv = speeds.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dt = times[i] - mt
            num += dt * (speeds[i] - mv)
            den += dt * dt
        }
        return if (den == 0.0) null else num / den
    }

    /** Farten (km/h) man hamnar på om man släpper gasen nu, eller null om man inte gasar på. */
    fun landingKmh(speed: Double): Int? {
        val a = accel() ?: return null
        if (a < MIN_ACCEL) return null
        return ((speed + a * LAND_S) * 3.6).roundToInt()
    }

    companion object {
        /** Så många sekunder bakåt räknas accelerationen på. */
        const val WINDOW_S = 3.0
        private const val GAP_S = 3.0
        /** Under så här (m/s², ≈ 1,4 km/h per sekund) räknas det inte som att man gasar på. */
        const val MIN_ACCEL = 0.4
        /** Farten fortsätter öka så här länge (s): GPS-fördröjning + reaktionstid + att bilen inte tappar direkt. */
        const val LAND_S = 1.5
    }
}

/**
 * Lär sig hur snabbt bilen tappar fart när man släpper gasen (jämna, mjuka fartminskningar
 * utan hård inbromsning). Används av förvarningen om lägre gräns.
 */
class BrakeLearner {
    private var learned = DEFAULT
    /** Antal inlärda tillfällen. */
    var count = 0
        private set

    /** Inbromsningen att räkna med (m/s²): det inlärda värdet efter några mätningar, annars standard. */
    val decel get() = if (count >= MIN_COUNT) learned else DEFAULT

    private var startT = Double.NaN
    private var startV = 0.0
    private var lastT = 0.0
    private var lastV = 0.0

    fun reset() { startT = Double.NaN }

    fun load(p: SharedPreferences) {
        learned = p.getFloat("inbromsning", DEFAULT.toFloat()).toDouble()
        count = p.getInt("inbromsning_n", 0)
    }

    fun save(ctx: Context, prefs: String) {
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
            .putFloat("inbromsning", learned.toFloat()).putInt("inbromsning_n", count).apply()
    }

    /** Ny fart ([t] s, [v] m/s). Sant om en ny mätning lärdes in. */
    fun add(t: Double, v: Double): Boolean {
        if (startT.isNaN()) { begin(t, v); return false }
        val dt = t - lastT
        if (dt <= 0) return false
        val a = (lastV - v) / dt
        if (dt <= 2.5 && v > 8.0 && a in 0.1..1.8) {   // farten sjunker mjukt – fortsätt mäta
            lastT = t; lastV = v
            return false
        }
        val dur = lastT - startT
        val drop = startV - lastV
        var got = false
        if (dur >= 4.0 && drop >= 2.0) {
            val m = drop / dur
            if (m in 0.15..1.6) {
                learned = if (count == 0) m else learned * 0.8 + m * 0.2
                count++
                got = true
            }
        }
        begin(t, v)
        return got
    }

    private fun begin(t: Double, v: Double) { startT = t; startV = v; lastT = t; lastV = v }

    companion object {
        /** Standard tills appen lärt sig: ungefär att släppa gasen i växel. */
        const val DEFAULT = 0.7
        private const val MIN_COUNT = 3
    }
}

/**
 * Tittar framåt längs vägnätet: följer vägen åt det håll man kör (rakaste fortsättningen
 * i varje skarv) och hittar närmaste lägre gräns.
 */
object Ahead {
    /** Reaktionstid (s) innan man släpper gasen. */
    const val REACTION_S = 1.0
    /** Så länge (s) det tar att säga "Nu blir det 40 här framme." – man släpper gasen när frasen är slut. */
    const val SPEAK_S = 2.0
    /** Så långt fram (m) letar den. */
    const val MAX_DIST = 800.0
    /** Större sväng än så (grader) räknas inte som att vägen fortsätter. */
    private const val MAX_TURN = 35.0

    class Result(val distance: Double, val kmh: Int)

    fun find(
        data: SpeedData, cur: SpeedData.Candidate, e: Double, n: Double, course: Double,
        limitOf: (SpeedData.Candidate) -> Int,
    ): Result? {
        val p = cur.pts
        if (p.size < 4) return null
        val pieces = p.size / 2 - 1
        // Närmaste bit av linjen och var på den man är.
        var bi = 0
        var bt = 0.0
        var bd = Double.MAX_VALUE
        for (i in 0 until pieces) {
            val x = p[2 * i]; val y = p[2 * i + 1]
            val dx = p[2 * i + 2] - x; val dy = p[2 * i + 3] - y
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((e - x) * dx + (n - y) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(e - x - t * dx, n - y - t * dy)
            if (d < bd) { bd = d; bi = i; bt = t }
        }
        val forward = SpeedData.angleDiff(course, bearing(p, bi)) < 90.0
        var dist = if (forward) (1 - bt) * pieceLen(p, bi) + (bi + 1 until pieces).sumOf { pieceLen(p, it) }
        else bt * pieceLen(p, bi) + (0 until bi).sumOf { pieceLen(p, it) }
        var exitE: Double
        var exitN: Double
        var exitBear: Double
        if (forward) { exitE = cur.endE; exitN = cur.endN; exitBear = bearing(p, pieces - 1) }
        else { exitE = cur.startE; exitN = cur.startN; exitBear = (bearing(p, 0) + 180.0) % 360.0 }

        val curLimit = limitOf(cur)
        var prevId = cur.id
        var hops = 0
        while (dist < MAX_DIST && hops++ < 15) {
            var best: SpeedData.Candidate? = null
            var bestFwd = true
            var bestTurn = Double.MAX_VALUE
            for (c in data.near(exitE, exitN, SpeedData.END_TOL + 1.0)) {
                if (c.id == prevId || c.pts.size < 4) continue
                val atStart = hypot(c.startE - exitE, c.startN - exitN) <= SpeedData.END_TOL
                val atEnd = hypot(c.endE - exitE, c.endN - exitN) <= SpeedData.END_TOL
                if (!atStart && !atEnd) continue
                val fwd = atStart
                if (c.direction == 1 && !fwd) continue    // enkelriktad åt andra hållet
                if (c.direction == 2 && fwd) continue
                val last = c.pts.size / 2 - 2
                val entry = if (fwd) bearing(c.pts, 0) else (bearing(c.pts, last) + 180.0) % 360.0
                val turn = SpeedData.angleDiff(exitBear, entry)
                if (turn <= MAX_TURN && turn < bestTurn) { best = c; bestFwd = fwd; bestTurn = turn }
            }
            val b = best ?: return null
            val lim = limitOf(b)
            if (lim < curLimit) return Result(dist, lim)
            val bp = b.pts
            val bPieces = bp.size / 2 - 1
            dist += (0 until bPieces).sumOf { pieceLen(bp, it) }
            if (bestFwd) { exitE = b.endE; exitN = b.endN; exitBear = bearing(bp, bPieces - 1) }
            else { exitE = b.startE; exitN = b.startN; exitBear = (bearing(bp, 0) + 180.0) % 360.0 }
            prevId = b.id
        }
        return null
    }

    private fun pieceLen(p: DoubleArray, i: Int) = hypot(p[2 * i + 2] - p[2 * i], p[2 * i + 3] - p[2 * i + 1])

    /** Riktningen på bit [i] (grader, 0 = norr), samma som GPS-kursen. */
    private fun bearing(p: DoubleArray, i: Int) =
        (Math.toDegrees(atan2(p[2 * i + 2] - p[2 * i], p[2 * i + 3] - p[2 * i + 1])) + 360.0) % 360.0
}
