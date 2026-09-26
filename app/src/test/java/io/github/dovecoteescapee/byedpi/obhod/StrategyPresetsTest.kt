package io.github.dovecoteescapee.byedpi.obhod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyPresetsTest {
    @Test
    fun allPresetsHaveIdsAndArgs() {
        StrategyPresets.all.forEach { preset ->
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.title.isNotBlank())
            assertTrue(preset.cmdArgs.isNotBlank())
        }
    }

    @Test
    fun byIdFallsBackToUniversal() {
        assertTrue(StrategyPresets.byId(null).id == StrategyPresets.Universal.id)
        assertTrue(StrategyPresets.byId("missing").id == StrategyPresets.Universal.id)
        assertTrue(StrategyPresets.byId("aggressive").id == StrategyPresets.Aggressive.id)
        assertTrue(StrategyPresets.byId("telegram").id == StrategyPresets.Universal.id)
        assertTrue(StrategyPresets.byId("fake-tls").id == StrategyPresets.Universal.id)
    }

    @Test
    fun universalHasKtFilter() {
        assertTrue(StrategyPresets.Universal.cmdArgs.contains("-Kt,h"))
        assertFalse(StrategyPresets.Universal.cmdArgs.contains("-Kt,h,u"))
    }

    @Test
    fun injectHostsFlagCoversAutoGroups() {
        val path = "/data/whitelist.txt"
        val raw = "-U -d1 -An -s1 -As -d1 -Kt,h"
        val out = StrategyPresets.injectHostsFlag(raw, path)
        assertTrue(out.startsWith("-H $path"))
        assertTrue(out.contains("-An -H $path"))
        assertTrue(out.contains("-As -H $path"))
    }
}
