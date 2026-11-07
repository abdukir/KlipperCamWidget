package io.github.abdukir.klippercam.stream

import java.io.ByteArrayOutputStream

/** Splits an Annex-B H.264 byte stream into access units. */
class AnnexBParser(private val onAccessUnit: (au: ByteArray, isKey: Boolean, sps: ByteArray?, pps: ByteArray?) -> Unit) {
    private var buf = ByteArray(1 shl 20)
    private var len = 0
    private val au = ByteArrayOutputStream(512 * 1024)
    private var auHasVcl = false
    private var auKey = false
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    fun feed(data: ByteArray, off: Int = 0, count: Int = data.size) {
        if (len + count > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        }
        System.arraycopy(data, off, buf, len, count)
        len += count
        // Emit every complete NAL (one that is followed by another start code).
        var start = findStart(0) ?: return
        while (true) {
            val next = findStart(start.second) ?: break
            handleNal(buf, start.second, next.first)
            start = next
        }
        // Keep the incomplete tail, starting at its start code.
        val keepFrom = start.first
        System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
        len -= keepFrom
    }

    /** Returns (startCodeOffset, payloadOffset) of the next start code at or after [from]. */
    private fun findStart(from: Int): Pair<Int, Int>? {
        var i = from
        while (i + 3 <= len) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (buf[i + 2].toInt() == 1) {
                    val s = if (i > 0 && buf[i - 1].toInt() == 0) i - 1 else i
                    return s to i + 3
                }
            }
            i++
        }
        return null
    }

    private fun handleNal(b: ByteArray, from: Int, to: Int) {
        var end = to
        while (end > from && b[end - 1].toInt() == 0) end-- // trailing zero bytes belong to next start code
        if (end <= from) return
        val type = b[from].toInt() and 0x1f
        val isVcl = type in 1..5
        if (isVcl) {
            val firstMbIsZero = end > from + 1 && (b[from + 1].toInt() and 0x80) != 0
            if (auHasVcl && firstMbIsZero) flush()
        } else if (type in 6..9 && auHasVcl) {
            flush()
        }
        when (type) {
            7 -> sps = b.copyOfRange(from, end)
            8 -> pps = b.copyOfRange(from, end)
        }
        au.write(START, 0, 4)
        au.write(b, from, end - from)
        if (isVcl) auHasVcl = true
        if (type == 5) auKey = true
    }

    private fun flush() {
        if (au.size() > 0) onAccessUnit(au.toByteArray(), auKey, sps, pps)
        au.reset()
        auHasVcl = false
        auKey = false
    }

    companion object {
        val START = byteArrayOf(0, 0, 0, 1)
    }
}

/** Reads width/height from an H.264 SPS NAL (without start code). */
object SpsParser {
    fun size(spsNal: ByteArray): Pair<Int, Int>? = runCatching {
        val r = BitReader(unescape(spsNal, 1))
        val profile = r.bits(8)
        r.bits(16) // constraint flags + level
        r.ue() // sps id
        var chromaFormat = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1)
            if (r.bits(1) == 1) {
                val count = if (chromaFormat != 3) 8 else 12
                for (i in 0 until count) if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.bits(1) // max_num_ref_frames, gaps
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var w = wMbs * 16
        var h = (2 - frameMbsOnly) * hMapUnits * 16
        if (r.bits(1) == 1) { // cropping
            val l = r.ue(); val rr = r.ue(); val t = r.ue(); val bt = r.ue()
            val cx = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
            val cy = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (l + rr) * cx
            h -= (t + bt) * cy
        }
        w to h
    }.getOrNull()

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8; var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private fun unescape(b: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var zeros = 0
        for (i in from until b.size) {
            val v = b[i].toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            zeros = if (v == 0) zeros + 1 else 0
            out.write(v)
        }
        return out.toByteArray()
    }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos shr 3].toInt() and 0xff
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) { zeros++; if (zeros > 31) error("bad ue") }
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }
}
