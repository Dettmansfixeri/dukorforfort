package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Texterna appen letar efter måste vara exakt de som spelats in (verktyg/fraser-nya.txt och bötesfraserna). */
class ClipLinesTest {

    private val lista = File("../../verktyg/fraser-nya.txt")
    private val workflow = File("../../.github/workflows/rostprov.yml")

    private fun inspelade(): Set<String> {
        assumeTrue("verktyg/fraser-nya.txt saknas", lista.exists() && workflow.exists())
        val fraser = lista.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringAfter("|").trim() }
        val boter = Regex("default: \"(Det här kostar dig[^\"]*)\"").find(workflow.readText())!!.groupValues[1].split("|")
        return (fraser + boter).toSet()
    }

    @Test fun allaUtrakandeFraserFinnsInspelade() {
        val finns = inspelade()
        for (limit in ClipLines.LIMITS) for (over in 1..45) {
            for (f in ClipLines.facts(over, limit)) assertTrue("saknas: $f", f in finns)
            assertTrue("saknas: ${ClipLines.fineText(over, limit)}", ClipLines.fineText(over, limit) in finns)
        }
        for (limit in ClipLines.LIMITS) assertTrue(ClipLines.signText(limit) in finns)
        for (f in ClipLines.INSULTS.drop(1)) assertTrue("saknas: $f", f in finns)
        assertTrue(ClipLines.THANKS in finns)
        for (f in ClipLines.TOWN + ClipLines.NICE + ClipLines.NICE_TOWN + ClipLines.NICE_THANKS + ClipLines.NICE_LICENSE) assertTrue("saknas: $f", f in finns)
        for (limit in ClipLines.LIMITS) assertTrue(ClipLines.niceSignText(limit) in finns)
    }

    @Test fun boter() {
        assertEquals(2000, ClipLines.fine(5, 50))
        assertEquals(1500, ClipLines.fine(5, 70))
        assertEquals(3600, ClipLines.fine(33, 70))
        assertEquals(4000, ClipLines.fine(36, 70))
        assertEquals(4000, ClipLines.fine(31, 50))
        assertEquals("Det här kostar dig tre tusen tvåhundra kronor i böter. Plus busskort i minst två månader.", ClipLines.fineText(22, 30))
        assertEquals("Det här kostar dig tre tusen tvåhundra kronor i böter.", ClipLines.fineText(22, 50))
        assertEquals("Det här kostar dig tre tusen sexhundra kronor i böter. Plus busskort i minst två månader.", ClipLines.fineText(31, 70))
    }

    @Test fun steg() {
        assertEquals(10, ClipLines.step(13, 70))
        assertEquals(30, ClipLines.step(45, 70))
        assertEquals(20, ClipLines.step(28, 30))
        assertEquals(null, ClipLines.step(0, 70))
    }
}
