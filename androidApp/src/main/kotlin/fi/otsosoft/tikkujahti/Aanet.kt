package fi.otsosoft.tikkujahti

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import fi.otsosoft.tikkujahti.aani.Syntikka
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Tapahtuma
import fi.otsosoft.tikkujahti.ukko.Aantely
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Plays the synthesized sounds: a small mixer thread adds up the sounds that are playing and writes them to
 * one [AudioTrack]. Sounds are made on first use, on a background thread, and kept.
 */
class Aanet {
    private class Soiva(val data: FloatArray, val voimakkuus: Float) {
        var kohta = 0
    }

    private val valmiit = ConcurrentHashMap<String, FloatArray>()
    private val tulossa = ConcurrentLinkedQueue<Soiva>()
    private val tekija = Executors.newSingleThreadExecutor()
    @Volatile private var kaynnissa = false
    private var saie: Thread? = null
    private var vuoro = 0

    fun kaynnista() {
        if (kaynnissa) return
        kaynnissa = true
        saie = Thread({ miksaa() }, "aanet").also { it.start() }
        // The sounds every game needs, ready before the first throw
        tekija.execute {
            valmiit["heitto1"] = Syntikka.heitto(1f / 3f)
            valmiit["pomppu"] = Syntikka.pomppu(1f)
            valmiit["osuma"] = Syntikka.osuma()
            valmiit["kerays"] = Syntikka.kerays()
        }
    }

    fun pysayta() {
        kaynnissa = false
        saie?.join(500)
        saie = null
        tulossa.clear()
    }

    private fun miksaa() {
        val lohko = 512
        val koko = maxOf(AudioTrack.getMinBufferSize(Syntikka.TAAJUUS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), lohko * 4 * 2)
        val raita = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Syntikka.TAAJUUS).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(koko)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return // no sound on this device; the game goes on silently
        }
        val soivat = ArrayList<Soiva>()
        val ulos = FloatArray(lohko)
        raita.play()
        try {
            while (kaynnissa) {
                while (true) soivat += tulossa.poll() ?: break
                ulos.fill(0f)
                val it = soivat.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    val n = minOf(lohko, s.data.size - s.kohta)
                    for (i in 0 until n) ulos[i] += s.data[s.kohta + i] * s.voimakkuus
                    s.kohta += n
                    if (s.kohta >= s.data.size) it.remove()
                }
                for (i in 0 until lohko) ulos[i] = ulos[i].coerceIn(-1f, 1f)
                raita.write(ulos, 0, lohko, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            raita.release()
        }
    }

    private fun soita(avain: String, voimakkuus: Float, tee: () -> FloatArray) {
        if (!kaynnissa || voimakkuus < 0.02f) return
        val valmis = valmiit[avain]
        if (valmis != null) {
            if (tulossa.size < 12) tulossa += Soiva(valmis, voimakkuus)
        } else {
            tekija.execute {
                val data = valmiit.getOrPut(avain, tee)
                if (kaynnissa) tulossa += Soiva(data, voimakkuus)
            }
        }
    }

    /** Plays a game event as heard from [kuulija]: further away is quieter. */
    fun soita(t: Tapahtuma, kuulija: V3) {
        val v = (1.3f / (0.8f + t.paikka.etaisyys(kuulija))).coerceAtMost(1f)
        when (t.laji) {
            Aani.HEITTO -> {
                val porras = (t.voima * 3).roundToInt()
                soita("heitto$porras", 0.8f) { Syntikka.heitto(porras / 3f) }
            }
            Aani.POMPPU -> soita("pomppu", v * t.voima) { Syntikka.pomppu(1f) }
            Aani.OSUMA -> soita("osuma", v) { Syntikka.osuma() }
            Aani.KERAYS -> soita("kerays", 0.9f) { Syntikka.kerays() }
            Aani.TORJUNTA -> soita("torjunta", v) { Syntikka.torjunta() }
            Aani.TUTKA -> soita("tutka", 0.8f) { Syntikka.tutka() }
            Aani.HIDASTUS -> soita("hidastus", v) { Syntikka.hidastus() }
            Aani.LIIMA -> soita("liima", v) { Syntikka.liima() }
            Aani.POIMINTA -> soita("poiminta", 0.9f) { Syntikka.poiminta() }
            Aani.PYRISTELY -> soita("pyristely", 0.9f) { Syntikka.pyristely() }
            Aani.KARKASI -> soita("karkasi", 0.8f) { Syntikka.karkasi() }
            // The glitter of a hidden ball grows louder as the player gets closer
            Aani.KIMALLUS -> {
                val d = t.paikka.etaisyys(kuulija)
                soita("kimallus", (0.9f / (0.3f + d * d)).coerceAtMost(0.8f) * t.voima) { Syntikka.kimallus() }
            }
            Aani.AANTELY -> t.aantely?.let { a ->
                // A few pitches and takes per sound are enough, and keep the cache small
                val k = (t.korkeus * 8).roundToInt() / 8f
                val otto = vuoro++ % 2
                val hiljaa = a == Aantely.TOMPS || a == Aantely.ALASTULO || a == Aantely.HYPPY
                soita("$a/$k/$otto", v * (if (hiljaa) 0.5f else 0.9f)) { Syntikka.aantely(a, k, otto) }
            }
        }
    }
}
