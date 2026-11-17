package io.github.dovecoteescapee.byedpi.obhod

import io.github.dovecoteescapee.byedpi.tgws.TgWsBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertTrue(StrategyPresets.byId("youtube-only").id == StrategyPresets.YouTubeOnly.id)
    }

    @Test
    fun universalHasKtFilter() {
        assertTrue(StrategyPresets.Universal.cmdArgs.contains("-Kt,h"))
        assertFalse(StrategyPresets.Universal.cmdArgs.contains("-Kt,h,u"))
    }

    /** Aggressive used to be a byte-for-byte copy of Universal. */
    @Test
    fun aggressiveIsNotACopyOfUniversal() {
        assertNotEquals(StrategyPresets.Aggressive.cmdArgs, StrategyPresets.Universal.cmdArgs)
        assertTrue(StrategyPresets.Aggressive.cmdArgs.contains("-f1"))
        assertTrue(StrategyPresets.Aggressive.cmdArgs.contains("-t2"))
    }

    /**
     * A stray `-An` inside a preset body opened an empty passthrough group that
     * swallowed the rules after it. Retry ladders such as Universal's `-As`
     * are legitimate and must be preserved.
     */
    @Test
    fun presetBodiesContainNoPassthroughGroup() {
        StrategyPresets.all.forEach { preset ->
            assertFalse("${preset.id} has embedded -An", preset.cmdArgs.contains("-An"))
        }
    }

    @Test
    fun universalKeepsTlsRetryLadder() {
        assertTrue(StrategyPresets.Universal.cmdArgs.contains("-As"))
    }

    @Test
    fun youtubeOnlyTargetsTls() {
        assertTrue(StrategyPresets.YouTubeOnly.cmdArgs.contains("-Kt"))
        assertFalse(StrategyPresets.YouTubeOnly.cmdArgs.contains("-Kt,h"))
    }
}

/**
 * The upstream CF list is obfuscated; feeding raw names into the desync list
 * was the actual reason Telegram stopped working.
 */
class CfProxyDomainsTest {
    @Test
    fun decodesUpstreamEntries() {
        assertEquals("pclead.co.uk", CfProxyDomains.decode("virkgj.com"))
        assertEquals("offshor.co.uk", CfProxyDomains.decode("vmmzovy.com"))
        assertEquals("ebally.co.uk", CfProxyDomains.decode("khgrre.com"))
        assertEquals("nothingthere.co.uk", CfProxyDomains.decode("zaftuzsftqdq.com"))
        assertEquals("kartoshka.co.uk", CfProxyDomains.decode("tjacxbqtj.com"))
    }

    /**
     * `.com` is the upstream encoding marker, so decode() intentionally
     * rewrites every `.com` entry. Non-`.com` names pass through untouched.
     */
    @Test
    fun leavesNonEncodedNamesAlone() {
        assertEquals("my.zone.co.uk", CfProxyDomains.decode("my.zone.co.uk"))
        assertEquals("example.net", CfProxyDomains.decode("example.net"))
        assertEquals("", CfProxyDomains.decode(""))
    }

    /** A user's own zone is already real — it must never be de-obfuscated. */
    @Test
    fun userDomainsAreVerbatim() {
        val parsed = CfProxyDomains.userDomains(" MyZone.com; second.co.uk , bogus ")
        assertEquals(listOf("myzone.com", "second.co.uk"), parsed)
    }

    @Test
    fun decodedFormDiffersFromRaw() {
        // Guards against a silent regression where decode() becomes identity.
        assertNotEquals("virkgj.com", CfProxyDomains.decode("virkgj.com"))
    }
}

class BridgeStatsTest {
    private fun parse(raw: String) = TgWsBridge.parseStats(raw)

    @Test
    fun parsesNativeStats() {
        val s = parse("active=1 ws=2 tcp_fb=0 cf=3 bad=0 err=1 pool=8")
        assertEquals(1, s.active)
        assertEquals(2, s.ws)
        assertEquals(0, s.tcpFallback)
        assertEquals(3, s.cf)
        assertEquals(1, s.err)
        assertEquals(8, s.pool)
        assertTrue(s.hasTransport)
    }

    @Test
    fun noTransportWhenAllCountersAreZero() {
        val s = parse("active=0 ws=0 tcp_fb=0 cf=0 bad=7 err=2 pool=8")
        assertFalse(s.hasTransport)
        assertTrue(s.summary.contains("bad=7"))
    }

    @Test
    fun directRelayCountsAsTransport() {
        assertTrue(parse("ws=0 cf=0 tcp_fb=2").hasTransport)
    }

    @Test
    fun ignoresGarbageTokens() {
        val s = parse("garbage active=x ws=1")
        assertEquals(1, s.ws)
        assertEquals(0, s.active)
    }

    @Test
    fun emptyStringYieldsNoTransport() {
        assertFalse(parse("").hasTransport)
    }
}
