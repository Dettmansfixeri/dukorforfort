package se.dettmansfixeri.dukorforfort

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
}
