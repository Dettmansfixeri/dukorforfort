package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Texterna appen räknar ut måste vara exakt de som spelats in (verktyg/fraser-nya.txt och bötesfraserna). */
class ClipLinesTest {

    private val lista = File("../../verktyg/fraser-nya.txt")
    private val workflow = File("../../.github/workflows/rostprov.yml")

    /** Grupp → fraser ur inspelningslistan, plus böterna. */
    private fun grupper(): Map<String, List<String>> {
        assumeTrue("verktyg/fraser-nya.txt saknas", lista.exists() && workflow.exists())
        val ut = LinkedHashMap<String, MutableList<String>>()
        var g = ""
        for (rad in lista.readLines().map { it.trim() }) {
            if (rad.startsWith("##")) g = rad.removePrefix("##").substringBefore(" @ ").trim()
            else if (rad.isNotEmpty() && !rad.startsWith("#")) ut.getOrPut(g) { ArrayList() } += rad.substringAfter("|").trim()
        }
        ut["Böter"] = Regex("default: \"(Det här kostar dig[^\"]*)\"").find(workflow.readText())!!.groupValues[1].split("|").toMutableList()
        return ut
    }

    @Test fun allaUtrakandeFraserFinnsInspelade() {
        val finns = grupper().values.flatten().toSet()
        for (limit in ClipLines.LIMITS) for (over in 1..45) {
            for (f in ClipLines.facts(over, limit)) assertTrue("saknas: $f", f in finns)
            assertTrue("saknas: ${ClipLines.fineText(over, limit)}", ClipLines.fineText(over, limit) in finns)
        }
        assertTrue(ClipLines.DRY in finns)
        for (nice in listOf(true, false)) for (p in ClipLines.praise(nice)) assertTrue("saknas: $p", p in finns)
        assertTrue(ClipLines.epaText(true) in finns)
        assertTrue(ClipLines.epaText(false) in finns)
        for (k in ClipLines.LIMITS.dropLast(1)) assertTrue("saknas: ${ClipLines.aheadText(k)}", ClipLines.aheadText(k) in finns)
    }

    @Test fun gruppreglerna() {
        val g = grupper()
        val elak50 = ClipLines.fromGroups(g, nice = false, closing = false, over = 5, limit = 50)
        val elak90 = ClipLines.fromGroups(g, nice = false, closing = false, over = 5, limit = 90)
        val snall50 = ClipLines.fromGroups(g, nice = true, closing = false, over = 5, limit = 50)
        assertTrue("Det här är en gata, inte Le Mans." in elak50)          // tätort på 50
        assertFalse("Det här är en gata, inte Le Mans." in elak90)         // men inte på 90
        assertTrue("Hallå? Skylten säger 50." in elak50)
        assertFalse("Hallå? Skylten säger 30." in elak50)                  // bara rätt gräns
        assertFalse(elak50.any { it.startsWith("Det här kostar dig") })     // böterna sägs för sig
        assertFalse("Du kör lite för fort." in elak50)                     // snällt bara på snäll nivå
        assertFalse(elak50.any { it.endsWith("här framme.") })       // förvarningen är ingen kommentar
        assertFalse(elak50.any { it in ClipLines.praise(false) })           // beröm sägs bara när man hållit gränsen
        assertFalse(ClipLines.DRY in elak50)                               // sägs bara efter krockfarten
        assertFalse(elak50.any { "EPA" in it })                             // EPA sägs i stället för böterna
        assertTrue("Du kör lite för fort." in snall50)
        assertTrue("Skylten säger 50, bara så du vet." in snall50)
        assertFalse("Nu är du nära att bli av med körkortet. Släpp gasen." in snall50)
        assertTrue("Nu är du nära att bli av med körkortet. Släpp gasen." in
            ClipLines.fromGroups(g, nice = true, closing = false, over = 31, limit = 50))
        assertEquals(listOf("Tack, nu kör du lagligt igen.", "Bra, så ska det se ut."),
            ClipLines.fromGroups(g, nice = true, closing = true, over = 1, limit = 50))
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
