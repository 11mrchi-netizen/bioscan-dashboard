package com.bioscan.fieldterminal.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadCacheTest {
    private var now = 1_000_000L

    @Before
    fun setUp() {
        ReadCache.invalidateAll()
        ReadCache.clock = { now }
    }

    @After
    fun tearDown() {
        ReadCache.clock = { System.currentTimeMillis() }
        ReadCache.invalidateAll()
    }

    @Test
    fun secondCallWithinTheTtlIsServedFromTheCache() = runBlocking {
        val loads = AtomicInteger()
        val a = ReadCache.get("k") { loads.incrementAndGet(); listOf(1, 2, 3) }
        val b = ReadCache.get("k") { loads.incrementAndGet(); listOf(9) }
        assertEquals(1, loads.get())
        assertEquals(listOf(1, 2, 3), b)
        assertTrue(a === b) // same instance: row identity is preserved for every screen
    }

    @Test
    fun anExpiredEntryIsLoadedAgain() = runBlocking {
        val loads = AtomicInteger()
        ReadCache.get("k", ttlMs = 1_000) { loads.incrementAndGet() }
        now += 1_001
        ReadCache.get("k", ttlMs = 1_000) { loads.incrementAndGet() }
        assertEquals(2, loads.get())
    }

    @Test
    fun invalidateAllDropsEverything() = runBlocking {
        val loads = AtomicInteger()
        ReadCache.get("a") { loads.incrementAndGet() }
        ReadCache.get("b") { loads.incrementAndGet() }
        ReadCache.invalidateAll()
        assertEquals(0, ReadCache.size())
        ReadCache.get("a") { loads.incrementAndGet() }
        assertEquals(3, loads.get())
    }

    @Test
    fun concurrentCallersShareOneLoad() = runBlocking {
        val loads = AtomicInteger()
        val results = (1..8).map {
            async(kotlinx.coroutines.Dispatchers.Default) { ReadCache.get("shared") { loads.incrementAndGet(); delay(50); "value" } }
        }.awaitAll()
        assertEquals(1, loads.get())
        assertTrue(results.all { it == "value" })
    }

    @Test
    fun aFailedLoadIsNotCachedAndTheNextCallRetries() = runBlocking {
        val loads = AtomicInteger()
        val first = runCatching { ReadCache.get<String>("k") { loads.incrementAndGet(); error("offline") } }
        assertTrue(first.isFailure)
        val second = ReadCache.get("k") { loads.incrementAndGet(); "ok" }
        assertEquals("ok", second)
        assertEquals(2, loads.get())
    }

    @Test
    fun aWriteDropsOnlyTheEntriesThatReadItsTable() = runBlocking {
        val loads = AtomicInteger()
        ReadCache.get("sessions", tables = setOf("exercise_sessions")) { loads.incrementAndGet() }
        ReadCache.get("untagged") { loads.incrementAndGet() }
        ReadCache.invalidateWrite("wearable_daily")
        ReadCache.get("sessions", tables = setOf("exercise_sessions")) { loads.incrementAndGet() } // still cached
        ReadCache.get("untagged") { loads.incrementAndGet() } // dropped: names no tables
        assertEquals(3, loads.get())
        ReadCache.invalidateWrite("exercise_sessions")
        ReadCache.get("sessions", tables = setOf("exercise_sessions")) { loads.incrementAndGet() }
        assertEquals(4, loads.get())
        ReadCache.invalidateWrite(null) // an rpc or an edge function: everything goes
        assertEquals(0, ReadCache.size())
    }

    @Test
    fun theWrittenTableComesFromTheRestPath() {
        assertEquals("wearable_daily", writtenTable("/rest/v1/wearable_daily"))
        assertEquals("exercise_sessions", writtenTable("/rest/v1/exercise_sessions?id=eq.5"))
        assertNull(writtenTable("/rest/v1/rpc/claim_zepp_history_pull"))
        assertNull(writtenTable("/functions/v1/zepp-extract"))
    }

    @Test
    fun aCachedNullIsAHitNotAMiss() = runBlocking {
        val loads = AtomicInteger()
        val a: String? = ReadCache.get("none") { loads.incrementAndGet(); null }
        val b: String? = ReadCache.get("none") { loads.incrementAndGet(); "later" }
        assertNull(a)
        assertNull(b)
        assertEquals(1, loads.get())
    }
}
