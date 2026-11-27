package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Högarna: grova kommentarer sägs först när man är en bit över eller legat över ett tag. */
class HogarTest {

    @Test
    fun tillatnaHogar() {
        assertEquals(1, ClipLines.maxPile(1, 0))
        assertEquals(1, ClipLines.maxPile(5, 9_999))
        assertEquals(2, ClipLines.maxPile(6, 0))
        assertEquals(2, ClipLines.maxPile(1, 10_000))
        assertEquals(2, ClipLines.maxPile(15, 29_999))
        assertEquals(3, ClipLines.maxPile(16, 0))
        assertEquals(3, ClipLines.maxPile(2, 30_000))
    }

    @Test
    fun indelning() {
        assertEquals(3, ClipLines.pile("Är gaspedalen fastlimmad, eller?"))
        assertEquals(3, ClipLines.pile("  är gaspedalen fastlimmad eller "))   // små skillnader gör inget
        assertEquals(2, ClipLines.pile("Tror du att skyltarna är förslag?"))
        assertEquals(1, ClipLines.pile("Du kör lite för fort."))
        assertEquals(1, ClipLines.pile("Hallå? Skylten säger 50."))
    }

    @Test
    fun vikter() {
        val grov = "Är gaspedalen fastlimmad, eller?"
        val mellan = "Tror du att skyltarna är förslag?"
        val mild = "Du kör lite för fort."
        assertEquals(0, ClipLines.pileWeight(grov, 1))
        assertEquals(0, ClipLines.pileWeight(mellan, 1))
        assertEquals(3, ClipLines.pileWeight(mild, 1))
        assertEquals(3, ClipLines.pileWeight(mellan, 2))
        assertEquals(1, ClipLines.pileWeight(mild, 2))
        assertEquals(3, ClipLines.pileWeight(grov, 3))
        assertEquals(1, ClipLines.pileWeight(mellan, 3))
    }

    @Test
    fun viktatVal() {
        val rnd = Random(1)
        val n = (1..4000).map { ClipLines.pickWeighted(listOf("a" to 3, "b" to 1, "c" to 0), rnd) }
        assertEquals(0, n.count { it == "c" })
        val a = n.count { it == "a" }
        assertTrue("a valdes $a av 4000", a in 2800..3200)
        assertEquals(null, ClipLines.pickWeighted(listOf("x" to 0)))
    }

    /** Varje fras i högarna måste finnas i fraslistan, annars har den stavats fel och hamnar i hög 1. */
    @Test
    fun finnsIFraslistan() {
        val lista = File("../../verktyg/fraser-nya.txt")
        assumeTrue(lista.exists())
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val fraser = lista.readLines().filter { "|" in it }.map { key(it.substringAfter("|")) }.toSet()
        for (f in ClipLines.PILE_2 + ClipLines.PILE_3) assertTrue("Saknas i fraslistan: $f", key(f) in fraser)
    }
}
