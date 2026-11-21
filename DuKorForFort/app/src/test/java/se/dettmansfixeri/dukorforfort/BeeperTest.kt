package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeperTest {

    @Test fun piptabellen() {
        assertNull(Beeper.pattern(0))
        assertEquals(1, Beeper.pattern(1)!!.count)
        assertEquals(1, Beeper.pattern(2)!!.count)
        assertEquals(2, Beeper.pattern(3)!!.count)
        assertEquals(2, Beeper.pattern(4)!!.count)
        assertEquals(3, Beeper.pattern(5)!!.count)
        assertEquals(3, Beeper.pattern(7)!!.count)
        assertEquals(4, Beeper.pattern(8)!!.count)
        assertEquals(4, Beeper.pattern(10)!!.count)
        assertEquals(8, Beeper.pattern(11)!!.count)
        assertEquals(12, Beeper.pattern(16)!!.count)
        assertEquals(12, Beeper.pattern(30)!!.count)
        assertEquals(0, Beeper.pattern(31)!!.count)   // siren
        // Ljusare ju mer över
        assertTrue(Beeper.pattern(5)!!.hz > Beeper.pattern(4)!!.hz)
        assertTrue(Beeper.pattern(8)!!.hz > Beeper.pattern(7)!!.hz)
    }

    @Test fun ljudetGarAttSkapa() {
        for (over in listOf(1, 3, 5, 8, 11, 16, 31)) {
            val pcm = Beeper.render(Beeper.pattern(over)!!)
            assertEquals(22050, pcm.size)
            assertTrue(pcm.any { it.toInt() != 0 })
        }
    }
}
