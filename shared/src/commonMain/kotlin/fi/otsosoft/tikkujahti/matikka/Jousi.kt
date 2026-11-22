package fi.otsosoft.tikkujahti.matikka

import kotlin.math.PI
import kotlin.math.max

/**
 * Second-order dynamics: the output follows the input like a damped spring. This is what keeps the stick
 * figures from moving like robots.
 *
 * - [taajuus] (Hz): how fast it responds.
 * - [vaimennus]: 0 wobbles forever, 1 settles without overshoot, below 1 overshoots and bounces back.
 * - [ennakointi]: 0 starts slowly, 1 reacts at once, above 1 overshoots, below 0 first moves the wrong way
 *   (anticipation, like crouching before a jump).
 */
class Jousi(taajuus: Float, vaimennus: Float, ennakointi: Float, alku: Float = 0f) {
    private var k1 = 0f
    private var k2 = 0f
    private var k3 = 0f
    private var edellinenX = alku
    var arvo = alku
        private set
    var nopeus = 0f
        private set

    init {
        aseta(taajuus, vaimennus, ennakointi)
    }

    fun aseta(taajuus: Float, vaimennus: Float, ennakointi: Float) {
        val w = 2f * PI.toFloat() * taajuus
        k1 = vaimennus / (PI.toFloat() * taajuus)
        k2 = 1f / (w * w)
        k3 = ennakointi * vaimennus / w
    }

    fun paivita(dt: Float, x: Float): Float {
        if (dt <= 0f) return arvo
        val xd = (x - edellinenX) / dt
        edellinenX = x
        val k2s = max(k2, max(dt * dt / 2f + dt * k1 / 2f, dt * k1)) // stays stable at low frame rates
        arvo += dt * nopeus
        nopeus += dt * (x + k3 * xd - arvo - k1 * nopeus) / k2s
        return arvo
    }

    /** Jumps straight to [x] with no motion. */
    fun nollaa(x: Float) {
        arvo = x
        edellinenX = x
        nopeus = 0f
    }

    /** Gives the spring a kick, e.g. a landing that squashes the body. */
    fun tyonna(nopeusLisa: Float) {
        nopeus += nopeusLisa
    }
}

/** [Jousi] for vectors. */
class Jousi3(taajuus: Float, vaimennus: Float, ennakointi: Float, alku: V3 = V3.NOLLA) {
    private val x = Jousi(taajuus, vaimennus, ennakointi, alku.x)
    private val y = Jousi(taajuus, vaimennus, ennakointi, alku.y)
    private val z = Jousi(taajuus, vaimennus, ennakointi, alku.z)

    val arvo: V3 get() = V3(x.arvo, y.arvo, z.arvo)

    fun aseta(taajuus: Float, vaimennus: Float, ennakointi: Float) {
        x.aseta(taajuus, vaimennus, ennakointi)
        y.aseta(taajuus, vaimennus, ennakointi)
        z.aseta(taajuus, vaimennus, ennakointi)
    }

    fun paivita(dt: Float, kohde: V3): V3 {
        x.paivita(dt, kohde.x)
        y.paivita(dt, kohde.y)
        z.paivita(dt, kohde.z)
        return arvo
    }

    fun nollaa(v: V3) {
        x.nollaa(v.x); y.nollaa(v.y); z.nollaa(v.z)
    }

    fun tyonna(v: V3) {
        x.tyonna(v.x); y.tyonna(v.y); z.tyonna(v.z)
    }
}
