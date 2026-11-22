package dev.whoop4.bloop.app

import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.compareVersions("0.4.10", "0.4.9") > 0)
        assertTrue(Updater.compareVersions("0.5", "0.4.9") > 0)
        assertTrue(Updater.compareVersions("0.4.2", "0.4.2") == 0)
        assertTrue(Updater.compareVersions("0.4", "0.4.0") == 0)
        assertTrue(Updater.compareVersions("0.4.2", "0.4.3") < 0)
    }
}
