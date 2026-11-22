package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.aani.Syntikka
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Tapahtuma
import fi.otsosoft.tikkujahti.ukko.Aantely
import kotlinx.browser.window
import kotlin.math.roundToInt

/** Plays the synthesized sounds with Web Audio. Browsers only allow sound after a touch: call [herata] from one. */
class Aanet {
    private var ctx: dynamic = null
    private val puskurit = HashMap<String, dynamic>()
    private var vuoro = 0

    fun herata() {
        if (ctx == null) {
            val luokka = window.asDynamic().AudioContext ?: window.asDynamic().webkitAudioContext ?: return
            ctx = js("new luokka()")
        }
        if (ctx.state == "suspended") ctx.resume()
    }

    private fun soita(avain: String, voimakkuus: Double, tee: () -> FloatArray) {
        val c = ctx ?: return
        if (c.state != "running" || voimakkuus < 0.02) return
        val puskuri = puskurit.getOrPut(avain) {
            val data = tee() // a Float32Array
            val b = c.createBuffer(1, data.size, Syntikka.TAAJUUS)
            b.getChannelData(0).set(data)
            b
        }
        val lahde = c.createBufferSource()
        lahde.buffer = puskuri
        val vahvistus = c.createGain()
        vahvistus.gain.value = voimakkuus
        lahde.connect(vahvistus)
        vahvistus.connect(c.destination)
        lahde.start()
    }

    /** Plays a game event as heard from [kuulija]: further away is quieter. */
    fun soita(t: Tapahtuma, kuulija: V3) {
        val etaisyys = t.paikka.etaisyys(kuulija)
        val v = (1.3 / (0.8 + etaisyys)).coerceAtMost(1.0)
        when (t.laji) {
            Aani.HEITTO -> soita("heitto${(t.voima * 3).roundToInt()}", 0.8) { Syntikka.heitto((t.voima * 3).roundToInt() / 3f) }
            Aani.POMPPU -> soita("pomppu", v * t.voima.toDouble()) { Syntikka.pomppu(1f) }
            Aani.OSUMA -> soita("osuma", v) { Syntikka.osuma() }
            Aani.KERAYS -> soita("kerays", 0.9) { Syntikka.kerays() }
            Aani.AANTELY -> t.aantely?.let { aantely(it, t.korkeus, v) }
        }
    }

    fun aantely(a: Aantely, korkeus: Float, voimakkuus: Double = 1.0) {
        // A few pitches and takes per sound are enough, and keep the cache small
        val k = (korkeus * 8).roundToInt() / 8f
        val otto = vuoro++ % 2
        val hiljaa = a == Aantely.TOMPS || a == Aantely.ALASTULO || a == Aantely.HYPPY
        soita("$a/$k/$otto", voimakkuus * (if (hiljaa) 0.5 else 0.9)) { Syntikka.aantely(a, k, otto) }
    }
}
