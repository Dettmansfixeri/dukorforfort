package se.dettmansfixeri.dukorforfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class SpeedDataTest {

    // Skapas av verktyg/konvertera-isa.mjs. Testerna som behöver den hoppas över om den saknas.
    private val file = File("../../data/hastigheter.bin")

    @Test fun helgdagar2026() {
        assertTrue(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 4, 3)))   // långfredag
        assertTrue(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 4, 6)))   // annandag påsk
        assertTrue(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 5, 14)))  // Kristi himmelsfärd
        assertTrue(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 6, 20)))  // midsommardagen
        assertTrue(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 10, 31))) // alla helgons dag
        assertFalse(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 6, 19))) // midsommarafton
        assertFalse(SwedishHolidays.isSundayOrHoliday(LocalDate.of(2026, 11, 20))) // vanlig fredag
    }

    @Test fun skolzonVardagar() {
        val rule = Deviation.parseAll("""[[{"v":30,"d":"VUF","k":[[700,1700]]}]]""")[0][0]
        val p = Vehicle.PERSONBIL
        assertTrue(rule.applies(LocalDateTime.of(2026, 11, 19, 8, 0), p, false))   // torsdag 08:00
        assertFalse(rule.applies(LocalDateTime.of(2026, 11, 19, 17, 0), p, false)) // torsdag 17:00
        assertFalse(rule.applies(LocalDateTime.of(2026, 11, 21, 10, 0), p, false)) // lördag (dag före söndag)
        assertFalse(rule.applies(LocalDateTime.of(2026, 6, 19, 10, 0), p, false))  // midsommarafton
    }

    @Test fun nattOverMidnatt() {
        val rule = Deviation.parseAll("""[[{"v":40,"d":"VUF","k":[[1700,700]]}]]""")[0][0]
        assertTrue(rule.applies(LocalDateTime.of(2026, 11, 19, 22, 0), Vehicle.PERSONBIL, false))
        assertFalse(rule.applies(LocalDateTime.of(2026, 11, 19, 12, 0), Vehicle.PERSONBIL, false))
    }

    @Test fun sasong() {
        val rule = Deviation.parseAll("""[[{"v":60,"s":"0615","e":"0815"}]]""")[0][0]
        assertTrue(rule.applies(LocalDateTime.of(2026, 7, 1, 12, 0), Vehicle.PERSONBIL, false))
        assertFalse(rule.applies(LocalDateTime.of(2026, 9, 1, 12, 0), Vehicle.PERSONBIL, false))
    }

    @Test fun fordonsregler() {
        val tung = Deviation.parseAll("""[[{"v":70,"f":[280],"fi":2,"tv":7}]]""")[0][0]
        val t = LocalDateTime.of(2026, 11, 19, 12, 0)
        assertTrue(tung.applies(t, Vehicle.TUNG_LASTBIL, false))
        assertFalse(tung.applies(t, Vehicle.PERSONBIL, false))
        val allaMotorfordon = Deviation.parseAll("""[[{"v":30,"f":[170]}]]""")[0][0]
        assertTrue(allaMotorfordon.applies(t, Vehicle.PERSONBIL, false))
        val inteBuss = Deviation.parseAll("""[[{"v":30,"f":[20],"fi":1}]]""")[0][0]
        assertFalse(inteBuss.applies(t, Vehicle.BUSS, false))
        assertTrue(inteBuss.applies(t, Vehicle.PERSONBIL, false))
        val utanVillkor = Deviation.parseAll("""[[{"v":50}]]""")[0][0]
        assertFalse(utanVillkor.applies(t, Vehicle.PERSONBIL, false))
    }

    @Test fun fordonsmax() {
        assertEquals(null, Vehicle.PERSONBIL.cap(110, false))
        assertEquals(80, Vehicle.PERSONBIL.cap(110, true))
        assertEquals(90, Vehicle.TUNG_LASTBIL.cap(110, false))
        assertEquals(80, Vehicle.TUNG_LASTBIL.cap(70, false))
        assertEquals(80, Vehicle.BUSS_BALTE.cap(110, true))
        assertEquals(45, Vehicle.MOPED.cap(70, true))
        assertEquals(45, Vehicle.MOPEDBIL.cap(70, false))
        assertEquals(25, Vehicle.MOPED_II.cap(70, true))
        assertTrue(160 in Vehicle.TUNG_LASTBIL.codes(true))
    }

    @Test fun uppslagIRiktigData() {
        assumeTrue("data/hastigheter.bin saknas", file.exists())
        val data = SpeedData.open(file)
        assertNotNull(data)
        // Startpunkt för en 120-sträcka (hittad i filen): ska ge 120 på 0 m.
        val c = data!!.near(374518.0, 6264212.0, 30.0).minBy { it.distance }
        assertEquals(120, c.kmh)
        assertTrue(c.distance < 0.5)
        // Centrala Stockholm (Sergels torg-trakten): någon väg inom 60 m.
        val (e, n) = Sweref99.fromWgs84(59.3326, 18.0649)
        assertTrue(data.near(e, n, 60.0).isNotEmpty())
        // Mitt i Vättern: ingen väg.
        val (ve, vn) = Sweref99.fromWgs84(58.40, 14.55)
        assertTrue(data.near(ve, vn, 60.0).isEmpty())
        // Reglerna går att läsa
        assertTrue(data.rules.size > 100)
        val m = RoadMatcher().match(data, 374518.0, 6264212.0, 5f, null)
        assertEquals(120, m?.kmh)
    }
}
