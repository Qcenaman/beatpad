package com.beatpad.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val SR = 48000

/** RBJ biquad filter. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun set(type: Int, freq: Float, q: Float, sr: Int = SR): Biquad {
        val f = freq.coerceIn(10f, sr * 0.45f)
        val w0 = 2.0 * PI * f / sr
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val nb0: Double
        val nb1: Double
        val nb2: Double
        when (type) {
            LP -> { nb0 = (1 - c) / 2; nb1 = 1 - c; nb2 = (1 - c) / 2 }
            HP -> { nb0 = (1 + c) / 2; nb1 = -(1 + c); nb2 = (1 + c) / 2 }
            else -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha }
        }
        b0 = (nb0 / a0).toFloat()
        b1 = (nb1 / a0).toFloat()
        b2 = (nb2 / a0).toFloat()
        a1 = (-2 * c / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
        return this
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        const val LP = 0
        const val HP = 1
        const val BP = 2
    }
}

object FFT {
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = (re[b] * cr - im[b] * ci).toFloat()
                    val vi = (re[b] * ci + im[b] * cr).toFloat()
                    val ur = re[a]
                    val ui = im[a]
                    re[a] = ur + vr
                    im[a] = ui + vi
                    re[b] = ur - vr
                    im[b] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum (n/2 bins) of x[start until start+n] with window. Zero-pads past the end. */
    fun magnitudes(x: FloatArray, start: Int, n: Int, window: FloatArray): FloatArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) {
            val idx = start + i
            re[i] = if (idx in x.indices) x[idx] * window[i] else 0f
        }
        fft(re, im)
        val out = FloatArray(n / 2)
        for (i in 0 until n / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        return out
    }

    fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
}

/** Growable float buffer. */
class FloatGrow(initial: Int = 48000) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

object SampleOps {
    fun peak(d: FloatArray): Float {
        var p = 0f
        for (v in d) {
            val a = abs(v)
            if (a > p) p = a
        }
        return p
    }

    fun normalize(d: FloatArray, target: Float = 0.95f): FloatArray {
        val p = peak(d)
        if (p < 1e-6f) return d
        val g = target / p
        return FloatArray(d.size) { d[it] * g }
    }

    fun reverse(d: FloatArray): FloatArray = FloatArray(d.size) { d[d.size - 1 - it] }

    fun fadeEdges(d: FloatArray, inMs: Int = 2, outMs: Int = 10): FloatArray {
        val out = d.copyOf()
        val fi = min(out.size / 2, SR * inMs / 1000)
        val fo = min(out.size / 2, SR * outMs / 1000)
        for (i in 0 until fi) out[i] *= i.toFloat() / fi
        for (i in 0 until fo) out[out.size - 1 - i] *= i.toFloat() / fo
        return out
    }

    /** Removes silence at the start and end. */
    fun autoTrim(d: FloatArray): FloatArray {
        val p = peak(d)
        if (p < 1e-4f || d.size < 100) return d
        val th = max(0.002f, p * 0.03f)
        var s = d.indexOfFirst { abs(it) > th }
        var e = d.indexOfLast { abs(it) > th }
        if (s < 0 || e <= s) return d
        s = max(0, s - SR * 5 / 1000)
        e = min(d.size, e + SR * 40 / 1000)
        return fadeEdges(d.copyOfRange(s, e))
    }

    fun crop(d: FloatArray, startFrac: Float, endFrac: Float): FloatArray {
        val s = (startFrac * d.size).toInt().coerceIn(0, d.size)
        val e = (endFrac * d.size).toInt().coerceIn(s, d.size)
        if (e - s < 32) return d
        return fadeEdges(d.copyOfRange(s, e))
    }

    fun resample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || src.isEmpty()) return src
        val ratio = srcRate.toDouble() / dstRate
        val n = (src.size / ratio).toInt()
        return FloatArray(n) {
            val p = it * ratio
            val i = p.toInt()
            val f = (p - i).toFloat()
            val a = src[min(i, src.size - 1)]
            val b = src[min(i + 1, src.size - 1)]
            a + (b - a) * f
        }
    }
}

/** Minimal 16-bit PCM WAV reader/writer. */
object Wav {
    fun write(file: File, d: FloatArray) {
        FileOutputStream(file).use { write(it, d) }
    }

    fun write(out: OutputStream, d: FloatArray) {
        val dataLen = d.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(SR); bb.putInt(SR * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (v in d) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(bb.array())
        out.flush()
    }

    fun read(file: File): FloatArray? = try {
        FileInputStream(file).use { read(it) }
    } catch (e: Exception) {
        null
    }

    fun read(input: InputStream): FloatArray? {
        val bytes = input.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 1
        var rate = SR
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val end = min(bytes.size, body + len)
                val frames = (end - body) / (2 * channels)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += bb.getShort(body + (f * channels + c) * 2) / 32768f
                    out[f] = s / channels
                }
                return SampleOps.resample(out, rate, SR)
            }
            pos = body + len + (len and 1)
        }
        return null
    }
}
