package io.github.abdukir.klippercam

import io.github.abdukir.klippercam.stream.AnnexBParser
import io.github.abdukir.klippercam.stream.SpsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class H264Test {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private data class Result(val aus: Int, val keys: Int, val firstIsKey: Boolean, val size: Pair<Int, Int>?)

    private fun parse(bytes: ByteArray, seed: Int): Result {
        var aus = 0; var keys = 0; var firstIsKey = false; var size: Pair<Int, Int>? = null
        val parser = AnnexBParser { au, key, sps, pps ->
            assertTrue("AU must start with a start code", au[0].toInt() == 0 && au[1].toInt() == 0 && au[2].toInt() == 0 && au[3].toInt() == 1)
            if (aus == 0) firstIsKey = key
            if (key) { keys++; assertNotNull(sps); assertNotNull(pps); size = SpsParser.size(sps!!) }
            aus++
        }
        // Feed in random chunk sizes, like network reads.
        val rnd = Random(seed)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(bytes.size - off, 1 + rnd.nextInt(4000))
            parser.feed(bytes, off, n)
            off += n
        }
        return Result(aus, keys, firstIsKey, size)
    }

    @Test fun highProfile720p() {
        repeat(5) { seed ->
            val r = parse(fixture("high_720p.h264"), seed)
            // 10 frames. A NAL is only known to be complete once the next start code arrives and an
            // access unit only once the next one's first slice is parsed, so the final two stay pending.
            assertEquals(8, r.aus)
            assertTrue(r.firstIsKey)
            assertEquals(2, r.keys) // GOP of 5
            assertEquals(1280 to 720, r.size)
        }
    }

    @Test fun baseline1080pWithCropping() {
        val r = parse(fixture("baseline_1080p.h264"), 1)
        assertEquals(3, r.aus)
        assertEquals(1920 to 1080, r.size) // coded as 1088 lines, cropped to 1080
    }
}
