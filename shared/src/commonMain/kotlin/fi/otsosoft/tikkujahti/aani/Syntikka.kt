package fi.otsosoft.tikkujahti.aani

import fi.otsosoft.tikkujahti.ukko.Aantely
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Every sound in the game, made from scratch as mono PCM (-1…1 at [TAAJUUS] Hz).
 *
 * The figures speak gibberish: a buzzing voice source shaped by three vowel formants that glide from syllable
 * to syllable, with the melody of each little phrase carrying the emotion.
 */
object Syntikka {
    const val TAAJUUS = 22050

    /** Vowel formants (Hz), already raised for a small creature's short throat. */
    private enum class V(val f1: Float, val f2: Float, val f3: Float) {
        A(1050f, 1650f, 3300f), E(600f, 2550f, 3400f), I(400f, 3000f, 3900f), O(680f, 1200f, 3300f),
        U(430f, 1050f, 3000f), AE(930f, 2250f, 3300f), OE(600f, 2000f, 3200f), M(330f, 1400f, 2900f),
    }

    /**
     * One syllable: glide to vowel [v] over [kesto] seconds while the pitch moves from [p0] to [p1]
     * (multiples of the voice's base pitch). [h] starts it with a breath, [k] with a little stop.
     */
    private class Tavu(val v: V, val kesto: Float, val p0: Float, val p1: Float, val h: Boolean = false, val k: Boolean = false, val voima: Float = 1f)

    private fun tavut(a: Aantely): List<Tavu> = when (a) {
        Aantely.HEI -> listOf(Tavu(V.E, 0.11f, 1.0f, 1.35f, h = true), Tavu(V.I, 0.2f, 1.35f, 1.1f))
        Aantely.HIH -> listOf(Tavu(V.I, 0.08f, 1.5f, 1.6f, h = true), Tavu(V.I, 0.08f, 1.7f, 1.8f, h = true), Tavu(V.I, 0.12f, 1.55f, 1.4f, h = true))
        Aantely.JEE -> listOf(Tavu(V.I, 0.06f, 1.1f, 1.3f), Tavu(V.E, 0.34f, 1.3f, 1.75f))
        Aantely.IIK -> listOf(Tavu(V.I, 0.2f, 1.9f, 2.25f), Tavu(V.I, 0.05f, 2.25f, 2.0f, k = true, voima = 0.5f))
        Aantely.AH -> listOf(Tavu(V.AE, 0.26f, 1.0f, 0.72f, h = true))
        Aantely.NANANA -> listOf(
            Tavu(V.AE, 0.13f, 1.33f, 1.33f, k = true), Tavu(V.AE, 0.13f, 1.12f, 1.12f, k = true),
            Tavu(V.AE, 0.13f, 1.5f, 1.5f, k = true), Tavu(V.AE, 0.13f, 1.33f, 1.33f, k = true),
            Tavu(V.AE, 0.3f, 1.12f, 1.05f, k = true),
        )
        Aantely.HAUKOTUS -> listOf(Tavu(V.A, 0.5f, 1.0f, 1.3f, h = true, voima = 0.7f), Tavu(V.O, 0.6f, 1.3f, 0.8f, voima = 0.7f), Tavu(V.U, 0.35f, 0.8f, 0.65f, voima = 0.45f))
        Aantely.HMM -> listOf(Tavu(V.M, 0.16f, 0.95f, 0.9f, voima = 0.7f), Tavu(V.M, 0.22f, 0.9f, 1.25f, voima = 0.7f))
        Aantely.HUPS -> listOf(Tavu(V.U, 0.1f, 1.2f, 1.6f, h = true), Tavu(V.OE, 0.05f, 1.6f, 1.3f, k = true, voima = 0.4f))
        else -> emptyList()
    }

    /** A figure's voice sound. [korkeus] is the figure's own pitch (about 0.8…1.3); [siemen] varies the take. */
    fun aantely(a: Aantely, korkeus: Float = 1f, siemen: Int = 0): FloatArray = when (a) {
        Aantely.TOMPS -> tomahdys(0.09f, 150f, 0.9f)
        Aantely.ALASTULO -> tomahdys(0.12f, 110f, 0.6f)
        Aantely.HYPPY -> viuhahdus(0.13f, 420f * korkeus, 1100f * korkeus, 0.35f)
        else -> puhe(tavut(a), 430f * korkeus, Random(siemen * 31 + a.ordinal))
    }

    private fun puhe(tavut: List<Tavu>, perus: Float, rnd: Random): FloatArray {
        val tauko = 0.035f
        val n = ((tavut.sumOf { (it.kesto + tauko).toDouble() } + 0.08) * TAAJUUS).toInt()
        val ulos = FloatArray(n)
        val suotimet = Array(3) { Resonaattori() }
        var vaihe = 0f
        var i = 0
        var f1 = tavut.first().v.f1
        var f2 = tavut.first().v.f2
        var f3 = tavut.first().v.f3
        val vire = 1f + (rnd.nextFloat() - 0.5f) * 0.08f
        var kohina = 0f
        for (t in tavut) {
            val pituus = (t.kesto * TAAJUUS).toInt()
            val luisu = 1f - exp(-1f / (0.018f * TAAJUUS)) // formants glide to the new vowel in about 20 ms
            for (k in 0 until pituus) {
                val x = k / pituus.toFloat()
                val aika = k / TAAJUUS.toFloat()
                f1 += (t.v.f1 - f1) * luisu; f2 += (t.v.f2 - f2) * luisu; f3 += (t.v.f3 - f3) * luisu
                val vibrato = 1f + 0.018f * sin(2f * PI.toFloat() * 6.5f * aika)
                val f0 = perus * vire * (t.p0 + (t.p1 - t.p0) * x) * vibrato
                vaihe += f0 / TAAJUUS
                if (vaihe >= 1f) vaihe -= 1f
                // Voice source: a rounded pulse, brighter than a sine and softer than a sawtooth
                val pulssi = (1f - vaihe).pow(3) * 2f - 0.5f
                kohina = kohina * 0.5f + (rnd.nextFloat() - 0.5f)
                val henkays = if (t.h) max(0f, 1f - aika / 0.05f) else 0f
                val lahde = pulssi * (1f - 0.6f * henkays) + kohina * (0.05f + 1.2f * henkays)
                // Envelope: quick attack, soft release; a stop consonant starts from a short silence
                val alku = if (t.k) ((aika - 0.012f) / 0.008f).coerceIn(0f, 1f) else min(1f, aika / 0.012f)
                val loppu = min(1f, (t.kesto - aika) / 0.035f)
                val amp = alku * loppu * t.voima
                suotimet[0].aseta(f1, 90f); suotimet[1].aseta(f2, 130f); suotimet[2].aseta(f3, 200f)
                val s = suotimet[0].aja(lahde) + 0.55f * suotimet[1].aja(lahde) + 0.25f * suotimet[2].aja(lahde)
                if (i < n) ulos[i++] = s * amp
            }
            i += (tauko * TAAJUUS).toInt()
        }
        return normalisoi(ulos, 0.75f)
    }

    /** A two-pole band-pass resonator: one formant. */
    private class Resonaattori {
        private var a1 = 0f
        private var a2 = 0f
        private var b0 = 0f
        private var y1 = 0f
        private var y2 = 0f
        private var edF = -1f

        fun aseta(f: Float, kaista: Float) {
            if (abs(f - edF) < 1f) return
            edF = f
            val r = exp(-PI.toFloat() * kaista / TAAJUUS)
            a1 = 2f * r * cos(2f * PI.toFloat() * f / TAAJUUS)
            a2 = -r * r
            b0 = 1f - r
        }

        fun aja(x: Float): Float {
            val y = b0 * x + a1 * y1 + a2 * y2
            y2 = y1; y1 = y
            return y
        }
    }

    /** A soft thud: a low tone dropping in pitch, with a puff of noise. */
    private fun tomahdys(kesto: Float, f: Float, voima: Float): FloatArray {
        val n = (kesto * TAAJUUS).toInt()
        val rnd = Random(n)
        var vaihe = 0f
        var kohina = 0f
        return normalisoi(FloatArray(n) { i ->
            val x = i / n.toFloat()
            vaihe += f * (1f - 0.6f * x) / TAAJUUS
            kohina = kohina * 0.85f + (rnd.nextFloat() - 0.5f) * 0.3f
            (sin(2f * PI.toFloat() * vaihe) + kohina * (1f - x)) * (1f - x) * (1f - x)
        }, 0.7f * voima)
    }

    /** A quick rising whistle: a hop. */
    private fun viuhahdus(kesto: Float, f0: Float, f1: Float, voima: Float): FloatArray {
        val n = (kesto * TAAJUUS).toInt()
        var vaihe = 0f
        return FloatArray(n) { i ->
            val x = i / n.toFloat()
            vaihe += (f0 + (f1 - f0) * x * x) / TAAJUUS
            sin(2f * PI.toFloat() * vaihe) * sin(PI.toFloat() * x) * voima
        }
    }

    /** A throw: wind rushing past, brighter and shorter the harder the throw. */
    fun heitto(voima: Float = 0.5f): FloatArray {
        val kesto = 0.3f - 0.1f * voima
        val n = (kesto * TAAJUUS).toInt()
        val rnd = Random(4)
        val suodin = Resonaattori()
        return normalisoi(FloatArray(n) { i ->
            val x = i / n.toFloat()
            suodin.aseta(500f + (1600f + 1400f * voima) * sin(PI.toFloat() * x.pow(0.6f)), 500f)
            suodin.aja(rnd.nextFloat() - 0.5f) * sin(PI.toFloat() * x.pow(0.5f))
        }, 0.35f + 0.25f * voima)
    }

    /** A rubber ball bouncing: a short "boing" whose pitch dips and springs back. */
    fun pomppu(voima: Float = 0.5f): FloatArray {
        val n = (0.16f * TAAJUUS).toInt()
        var vaihe = 0f
        return FloatArray(n) { i ->
            val x = i / n.toFloat()
            val f = 260f + 180f * exp(-x * 18f) + 60f * x
            vaihe += f / TAAJUUS
            (sin(2f * PI.toFloat() * vaihe) + 0.3f * sin(4f * PI.toFloat() * vaihe)) * exp(-x * 7f) * min(1f, i / 30f) * (0.15f + 0.6f * voima)
        }
    }

    /** A hit: a pop and a springy "pwoip" as the ball swallows the figure. */
    fun osuma(): FloatArray {
        val n = (0.32f * TAAJUUS).toInt()
        val rnd = Random(9)
        var vaihe = 0f
        return normalisoi(FloatArray(n) { i ->
            val t = i / TAAJUUS.toFloat()
            val x = i / n.toFloat()
            val poks = (rnd.nextFloat() - 0.5f) * exp(-t * 90f) * 1.5f * min(1f, i / 16f)
            vaihe += (300f + 900f * x * x) / TAAJUUS
            poks + sin(2f * PI.toFloat() * vaihe) * sin(PI.toFloat() * x).pow(0.7f) * 0.8f
        }, 0.7f)
    }

    /** A catch: three bright notes climbing, like a tiny xylophone. */
    fun kerays(): FloatArray {
        val nuotit = floatArrayOf(784f, 988f, 1319f) // G5, B5, E6
        val vali = 0.085f
        val n = ((vali * nuotit.size + 0.45f) * TAAJUUS).toInt()
        val ulos = FloatArray(n)
        for ((k, f) in nuotit.withIndex()) {
            val alku = (k * vali * TAAJUUS).toInt()
            for (i in alku until n) {
                val t = (i - alku) / TAAJUUS.toFloat()
                val w = 2f * PI.toFloat() * f * t
                ulos[i] += (sin(w) + 0.35f * sin(2.76f * w) * exp(-t * 20f)) * exp(-t * 9f) * min(1f, t / 0.003f)
            }
        }
        return normalisoi(ulos, 0.6f)
    }

    private fun normalisoi(a: FloatArray, huippu: Float): FloatArray {
        var m = 1e-6f
        for (x in a) m = max(m, abs(x))
        val k = huippu / m
        for (i in a.indices) a[i] *= k
        return a
    }
}
