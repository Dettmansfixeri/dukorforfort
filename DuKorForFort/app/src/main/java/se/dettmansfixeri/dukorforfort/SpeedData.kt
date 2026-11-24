package se.dettmansfixeri.dukorforfort

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Läser hastigheter.bin (byggd av verktyg/konvertera-isa.mjs) direkt från filen
 * utan att läsa in allt i minnet. Koordinater i SWEREF 99 TM.
 */
class SpeedData private constructor(private val b: ByteBuffer) {

    private val cellSize = b.getInt(4)
    private val minE = b.getInt(8)
    private val minN = b.getInt(12)
    private val cols = b.getInt(16)
    private val rows = b.getInt(20)
    private val cellOff = b.getInt(36)
    private val refsOff = b.getInt(40)
    private val segOff = b.getInt(44)
    val rules: List<List<Deviation>> = run {
        val off = b.getInt(28)
        val len = b.getInt(32)
        val bytes = ByteArray(len)
        b.duplicate().apply { position(off) }.get(bytes)
        Deviation.parseAll(String(bytes, Charsets.UTF_8))
    }

    /** En vägsträcka nära positionen. */
    class Candidate(
        val id: Int,            // offset i filen, stabil identitet för sträckan
        val kmh: Int,
        val direction: Int,     // 0 = båda håll, 1 = med linjens riktning, 2 = mot
        val ramp: Boolean,
        val ruleId: Int,        // -1 = inga avvikelser
        val distance: Double,   // meter
        val bearing: Double,    // linjens riktning vid närmaste punkt, grader 0–360 (0 = norr)
        val pts: DoubleArray = DoubleArray(0),                // linjens punkter: e0, n0, e1, n1, …
    ) {
        val startE get() = pts[0]
        val startN get() = pts[1]
        val endE get() = pts[pts.size - 2]
        val endN get() = pts[pts.size - 1]

        /** Avstånd från (e, n) till linjen. */
        fun distanceTo(e: Double, n: Double): Double {
            var best = hypot(e - pts[0], n - pts[1])
            var i = 2
            while (i < pts.size) {
                val x = pts[i - 2]; val y = pts[i - 1]
                val dx = pts[i] - x; val dy = pts[i + 1] - y
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((e - x) * dx + (n - y) * dy) / l2).coerceIn(0.0, 1.0)
                best = min(best, hypot(e - x - t * dx, n - y - t * dy))
                i += 2
            }
            return best
        }

        /**
         * Hur hänger sträckan ihop med [o]?
         * END = en ände på samma ställe (samma väg som byter gräns, eller vägar som möts i en korsning).
         * MID = en ände ligger på den andra linjen (sidoväg som ansluter mitt på en väg).
         */
        fun linkTo(o: Candidate): Link {
            fun same(e1: Double, n1: Double, e2: Double, n2: Double) = hypot(e1 - e2, n1 - n2) <= END_TOL
            if (same(startE, startN, o.startE, o.startN) || same(startE, startN, o.endE, o.endN) ||
                same(endE, endN, o.startE, o.startN) || same(endE, endN, o.endE, o.endN)) return Link.END
            if (o.distanceTo(startE, startN) <= MID_TOL || o.distanceTo(endE, endN) <= MID_TOL ||
                distanceTo(o.startE, o.startN) <= MID_TOL || distanceTo(o.endE, o.endN) <= MID_TOL) return Link.MID
            return Link.NONE
        }
    }

    enum class Link { END, MID, NONE }

    /** Alla sträckor inom [maxDist] meter från (e, n). */
    fun near(e: Double, n: Double, maxDist: Double): List<Candidate> {
        val c = floor((e - minE) / cellSize).toInt()
        val r = floor((n - minN) / cellSize).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return emptyList()
        val cell = r * cols + c
        val from = b.getInt(cellOff + cell * 4)
        val to = b.getInt(cellOff + (cell + 1) * 4)
        val out = ArrayList<Candidate>()
        for (i in from until to) {
            val off = b.getInt(refsOff + i * 4)
            var o = segOff + off
            val kmh = b.get(o).toInt() and 0xFF
            val flags = b.get(o + 1).toInt() and 0xFF
            o += 2
            var rule = -1
            if (flags and 8 != 0) { rule = b.getShort(o).toInt() and 0xFFFF; o += 2 }
            val nPts = b.getShort(o).toInt() and 0xFFFF
            o += 2
            var x = b.getInt(o).toDouble()
            var y = b.getInt(o + 4).toDouble()
            val pts = DoubleArray(nPts * 2)
            pts[0] = x
            pts[1] = y
            o += 8
            var best = Double.MAX_VALUE
            var bearing = 0.0
            for (k in 1 until nPts) {
                val nx = x + b.getShort(o)
                val ny = y + b.getShort(o + 2)
                o += 4
                val dx = nx - x
                val dy = ny - y
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else ((e - x) * dx + (n - y) * dy) / l2
                val tc = t.coerceIn(0.0, 1.0)
                val d = hypot(e - x - tc * dx, n - y - tc * dy)
                if (d < best && l2 > 0.0) {
                    best = d
                    bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                }
                x = nx
                y = ny
                pts[k * 2] = x
                pts[k * 2 + 1] = y
            }
            if (best <= maxDist) {
                out += Candidate(off, kmh, flags and 3, flags and 4 != 0, rule, best, bearing, pts)
            }
        }
        return out
    }

    companion object {
        /** Ändar närmare än så räknas som samma punkt (meter). */
        const val END_TOL = 3.0
        /** En ände så här nära en annan linje räknas som ansluten mitt på den (meter). */
        const val MID_TOL = 5.0

        fun open(file: File): SpeedData? {
            if (!file.exists()) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val magic = ByteArray(4).also { buf.duplicate().get(it) }
                    if (String(magic, Charsets.US_ASCII) != "DKF1") null else SpeedData(buf)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Minsta vinkelskillnad mellan två riktningar (0–180). */
        fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return min(d, 360.0 - d)
        }
    }
}

/**
 * Väljer vilken väg man kör på, och följer vägnätet som en navigator: appen vet vilken
 * sträcka man är på och byter bara till sträckor man kan ha kört in på därifrån.
 *
 * - Fortsättning (ände mot ände, t.ex. samma väg med ny gräns): byter så fort den passar bättre.
 * - Sidoväg som ansluter mitt på: byter när den passat bättre två GPS-punkter i rad.
 * - Väg som inte hänger ihop (parallell väg, grannväg): byter bara om den passat mycket bättre
 *   tre punkter i rad (GPS-hopp, glapp i datan), eller om nuvarande väg är borta.
 *
 * Avstånd och färdriktning avgör vilken sträcka som passar bäst; enkelriktade gränser
 * gäller bara åt sitt håll.
 */
class RoadMatcher {
    private var current: SpeedData.Candidate? = null
    private var held = 0                 // antal GPS-punkter på nuvarande sträcka
    private var since = 0                // antal GPS-punkter sedan appen hittade en väg
    private var pendingId: Int? = null   // sträcka som håller på att bekräftas
    private var pendingCount = 0
    private var lastE = Double.NaN
    private var lastN = Double.NaN

    fun reset() {
        current = null; held = 0; since = 0; pendingId = null; pendingCount = 0; lastE = Double.NaN; lastN = Double.NaN; misses = 0
    }

    fun match(data: SpeedData, e: Double, n: Double, accuracy: Float?, course: Float?): SpeedData.Candidate? {
        val maxDist = (accuracy?.toDouble()?.times(1.5) ?: 30.0).coerceIn(25.0, 60.0)
        val cands = data.near(e, n, maxDist)
        if (cands.isEmpty()) { miss(); return null }

        fun score(c: SpeedData.Candidate): Double? {
            var s = c.distance
            if (course != null) {
                val diff = SpeedData.angleDiff(course.toDouble(), c.bearing)
                when (c.direction) {
                    1 -> if (diff > 100) return null           // gäller bara i linjens riktning
                    2 -> if (diff < 80) return null            // gäller bara mot linjens riktning
                }
                val axial = if (c.direction == 0) minOf(diff, 180.0 - diff)
                else if (c.direction == 1) diff else 180.0 - diff
                s += axial / 90.0 * 25.0                       // 90° fel kurs = 25 m "straff"
            }
            return s
        }

        val scored = cands.mapNotNull { c -> score(c)?.let { c to it } }.sortedBy { it.second }
        if (scored.isEmpty()) { miss(); return null }
        misses = 0
        // Samma position igen (omräkning utan ny GPS-punkt) räknas inte som en ny bekräftelse.
        val fresh = e != lastE || n != lastN
        lastE = e; lastN = n

        val cur = current
        val best = scored.first()
        // Ingen väg än, eller precis börjat köra: ta den som passar bäst. (Inte efter varje byte – då kunde
        // en avfart som ligger tätt intill stora vägen ta över direkt.)
        if (fresh) since++
        if (cur == null || since <= START_FIXES) {
            val keep = cur?.let { c -> scored.firstOrNull { it.first.id == c.id } }
            return choose(if (keep != null && keep.second <= best.second + 3.0) keep.first else best.first, fresh)
        }
        val curNow = scored.firstOrNull { it.first.id == cur.id }
        /**
         * Avfart: vägen delar sig, [c] har lägre gräns, men en annan fortsättning som passar nästan
         * lika bra behåller gränsen. Vid delningen ligger linjerna tätt, så då krävs mer för att byta.
         */
        fun forkLower(c: SpeedData.Candidate, s: Double): Boolean {
            val toRamp = c.ramp && !cur.ramp                     // från stora vägen in på en avfart
            if (!(c.kmh < cur.kmh || toRamp) || cur.linkTo(c) != SpeedData.Link.END) return false
            if (toRamp) return true
            fun same(e1: Double, n1: Double, e2: Double, n2: Double) = hypot(e1 - e2, n1 - n2) <= SpeedData.END_TOL
            // Delar sig från samma punkt som nuvarande sträcka (två vägar som går isär).
            if (same(c.startE, c.startN, cur.startE, cur.startN) || same(c.endE, c.endN, cur.endE, cur.endN)) return true
            return scored.any { (o, os) -> o.id != cur.id && o.id != c.id && o.kmh >= cur.kmh && os <= s + FORK_SIMILAR && cur.linkTo(o) == SpeedData.Link.END }
        }
        // Nuvarande sträcka är borta (för långt bort / fel håll): ta bästa anslutande, annars bästa.
        if (curNow == null) {
            val linked = scored.filter { cur.linkTo(it.first) != SpeedData.Link.NONE }
            val first = linked.firstOrNull()
            val next = if (first != null && forkLower(first.first, first.second))
                (linked.firstOrNull { it.first.kmh >= cur.kmh && it.second <= first.second + FORK_SIMILAR } ?: first) else first ?: best
            return choose(next.first, fresh)
        }
        // Bästa utmanare: en sträcka som passar tillräckligt mycket bättre, beroende på hur den hänger ihop.
        var needed = 0
        val challenger = scored.firstOrNull { (c, s) ->
            if (c.id == cur.id) return@firstOrNull false
            val (margin, fixes) = when (cur.linkTo(c)) {
                SpeedData.Link.END -> if (forkLower(c, s)) FORK_MARGIN to FORK_FIXES
                    else (if (course == null) 3.0 else 0.0) to 1
                SpeedData.Link.MID -> 2.0 to 2
                SpeedData.Link.NONE -> 12.0 to 3
            }
            needed = fixes
            s < curNow.second - margin
        }
        if (challenger == null) {
            if (fresh) { pendingId = null; pendingCount = 0 }
            return choose(curNow.first, fresh)
        }
        val id = challenger.first.id
        if (pendingId != id) { pendingId = id; pendingCount = 1 } else if (fresh) pendingCount++
        return choose(if (pendingCount >= needed) challenger.first else curNow.first, fresh)
    }

    /** Ingen väg i närheten. Ett enstaka GPS-hopp glömmer inte vägen – först efter flera i rad. */
    private var misses = 0
    private fun miss() { if (++misses >= 3) reset() }

    private fun choose(c: SpeedData.Candidate, fresh: Boolean): SpeedData.Candidate {
        if (current?.id == c.id) { if (fresh) held++ } else { held = 1; pendingId = null; pendingCount = 0 }
        current = c
        return c
    }

    private companion object {
        /** Så många GPS-punkter i början där vi bara tar den väg som passar bäst. */
        const val START_FIXES = 3
        /** Avfart med lägre gräns: så mycket bättre (m) och så många GPS-punkter i rad innan bytet. */
        const val FORK_MARGIN = 4.0
        const val FORK_FIXES = 3
        /** En annan fortsättning som passar inom så här mycket (m) räknas som "lika trolig". */
        const val FORK_SIMILAR = 10.0
    }
}
