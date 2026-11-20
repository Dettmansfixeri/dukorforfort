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
    )

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
            }
            if (best <= maxDist) {
                out += Candidate(off, kmh, flags and 3, flags and 4 != 0, rule, best, bearing)
            }
        }
        return out
    }

    companion object {
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
 * Väljer vilken väg man kör på. Tar hänsyn till avstånd, färdriktning och enkelriktade
 * gränser, och håller sig kvar på samma väg så länge den är ett rimligt val (så att
 * gränsen inte hoppar fram och tillbaka vid korsningar och parallella vägar).
 */
class RoadMatcher {
    private var currentId: Int? = null

    fun reset() { currentId = null }

    fun match(data: SpeedData, e: Double, n: Double, accuracy: Float?, course: Float?): SpeedData.Candidate? {
        val maxDist = (accuracy?.toDouble()?.times(1.5) ?: 30.0).coerceIn(25.0, 60.0)
        val cands = data.near(e, n, maxDist)
        if (cands.isEmpty()) { currentId = null; return null }

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

        val scored = cands.mapNotNull { c -> score(c)?.let { c to it } }
        if (scored.isEmpty()) { currentId = null; return null }
        val best = scored.minBy { it.second }
        val current = scored.firstOrNull { it.first.id == currentId }
        val chosen = if (current != null && current.second <= best.second + 8.0) current else best
        currentId = chosen.first.id
        return chosen.first
    }
}
