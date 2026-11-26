package se.dettmansfixeri.dukorforfort

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test fun versioner() {
        assertTrue(AppUpdater.isNewer("1.1.10", "1.1.9"))      // 10 > 9, inte som text
        assertTrue(AppUpdater.isNewer("1.2.1", "1.1.40"))
        assertFalse(AppUpdater.isNewer("1.1.9", "1.1.9"))
        assertFalse(AppUpdater.isNewer("1.1.8", "1.1.9"))
        assertFalse(AppUpdater.isNewer("1.1.50", "1.1-lokal"))  // lokala byggen frågar aldrig
        assertFalse(AppUpdater.isNewer("skräp", "1.1.9"))
    }

    @Test fun nyheter() {
        val json = JSONObject("""{"version":"1.1.36","changes":[
            {"v":"1.1.36","c":["Större siffror"]},
            {"v":"1.1.35","c":["Ändringar visas", ""]},
            {"v":"1.1.34","c":["Gammalt"]}]}""")
        val c = AppUpdater.parseChanges(json, "1.1.34")
        assertEquals(listOf("1.1.36" to listOf("Större siffror"), "1.1.35" to listOf("Ändringar visas")), c)
        assertTrue(AppUpdater.parseChanges(JSONObject("""{"version":"1.1.36"}"""), "1.1.34").isEmpty())
    }
}
