package com.bioscan.fieldterminal.data

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Pages open from the last data they showed: the read cache's disk copies (data/ReadCache.kt).
class ReadCacheDiskTest {
    private lateinit var dir: File
    private val ser = ListSerializer(Int.serializer())

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("readcache").toFile()
        ReadCache.init(dir)
        ReadCache.clearEverything()
    }

    @After
    fun tearDown() {
        ReadCache.clearEverything()
        dir.deleteRecursively()
    }

    @Test
    fun aNewRunOfTheAppAnswersFromDiskAtOnceAndRefreshesBehind() = runBlocking {
        val key = "test.disk.${System.nanoTime()}"
        assertEquals(listOf(1, 2), ReadCache.get(key, tables = setOf("t"), serializer = ser) { listOf(1, 2) })
        ReadCache.forgetMemoryForTest()

        var loads = 0
        val updated = async(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { ReadCache.updates.first { it == key } } }
        kotlinx.coroutines.yield()
        // The last value comes back without waiting for the (slow, changed) load...
        val first = ReadCache.get(key, tables = setOf("t"), serializer = ser) { loads++; kotlinx.coroutines.delay(200); listOf(1, 2, 3) }
        assertEquals(listOf(1, 2), first)
        // ...which lands in the background, says so, and is what the next read gets.
        updated.await()
        assertEquals(1, loads)
        assertEquals(listOf(1, 2, 3), ReadCache.get(key, tables = setOf("t"), serializer = ser) { error("fresh in memory") })
    }

    @Test
    fun anUnchangedRefreshSaysNothing() = runBlocking {
        val key = "test.same.${System.nanoTime()}"
        ReadCache.get(key, tables = setOf("t"), serializer = ser) { listOf(7) }
        ReadCache.forgetMemoryForTest()
        assertEquals(listOf(7), ReadCache.get(key, tables = setOf("t"), serializer = ser) { listOf(7) })
        val heard = kotlinx.coroutines.withTimeoutOrNull(500) { ReadCache.updates.first { it == key } }
        assertEquals(null, heard)
    }

    @Test
    fun requireFreshWaitsForTheLoad() = runBlocking {
        val key = "test.fresh.${System.nanoTime()}"
        ReadCache.get(key, tables = setOf("t"), serializer = ser) { listOf(1) }
        ReadCache.invalidateWrite("t")
        val fresh = withContext(ReadCache.RequireFresh) { ReadCache.get(key, tables = setOf("t"), serializer = ser) { listOf(2) } }
        assertEquals(listOf(2), fresh)
    }

    @Test
    fun aReadWithoutASerializerIsKeptInMemoryOnly() = runBlocking {
        val key = "test.memory.${System.nanoTime()}"
        ReadCache.get(key, tables = setOf("t")) { listOf(1) }
        ReadCache.forgetMemoryForTest()
        assertEquals(listOf(9), ReadCache.get(key, tables = setOf("t")) { listOf(9) })
    }

    @Test
    fun signOutLeavesNothingOnDisk() = runBlocking {
        ReadCache.get("test.signout", tables = setOf("t"), serializer = ser) { listOf(1) }
        assertTrue(File(dir, "read_cache").listFiles()!!.isNotEmpty())
        ReadCache.clearEverything()
        assertTrue(File(dir, "read_cache").listFiles()!!.isEmpty())
        assertEquals(listOf(5), ReadCache.get("test.signout", tables = setOf("t"), serializer = ser) { listOf(5) })
    }

    // After an edit of the person's own, the page reloads with the edit: never the copy from before it.
    @Test
    fun anOwnEditForcesAFreshRead() = runBlocking {
        val key = "test.edit.${System.nanoTime()}"
        ReadCache.get(key, tables = setOf("supplements"), serializer = ser) { listOf(1) }
        ReadCache.invalidateWrite("supplements")
        assertEquals(listOf(2), ReadCache.get(key, tables = setOf("supplements"), serializer = ser) { listOf(2) })
    }

    // A watch sync (on every open) writes its tables: the page shows the last copy at once and redraws when it lands.
    @Test
    fun aSyncWriteKeepsTheLastCopyOnScreen() = runBlocking {
        val key = "test.sync.${System.nanoTime()}"
        ReadCache.get(key, tables = setOf("wearable_daily"), serializer = ser) { listOf(1) }
        ReadCache.invalidateWrite("wearable_daily")
        val updated = async(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { ReadCache.updates.first { it == key } } }
        kotlinx.coroutines.yield()
        assertEquals(listOf(1), ReadCache.get(key, tables = setOf("wearable_daily"), serializer = ser) { listOf(1, 2) })
        updated.await()
        assertEquals(listOf(1, 2), ReadCache.get(key, tables = setOf("wearable_daily"), serializer = ser) { error("fresh in memory") })
    }
}
