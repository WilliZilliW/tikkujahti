package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kierra
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Vari

enum class PallonTila {
    /** Flying, bouncing and rolling. */
    LENTAA,
    /** Hit a figure: hangs in the air while the figure curls into it. */
    VANGITSEE,
    /** The caught figure struggles inside: tap the ball quickly or it gets out. */
    PYRISTELEE,
    /** Carries the caught figure back to the player. */
    PALAA,
    /** A miss that has come to rest and is fading away. */
    HAIPUU,
    POIS,
}

/**
 * The kinds of ball. Plain balls never run out; the special ones are found lying about the room.
 *
 * @param a, b the two colours of the ball
 * @param pomput how many bounces a ball may take and still catch
 * @param osumaKerroin how much bigger than a plain ball it is for hit tests
 */
enum class PalloLaji(
    val nimi: String,
    val kuvaus: String,
    val a: Vari,
    val b: Vari,
    val pomput: Int = 1,
    val osumaKerroin: Float = 1f,
    val kimmoisuus: Float = 0.62f,
    val paino: Float = 1f,
) {
    NORMI("Pallo", "Näitä riittää loputtomasti.", Vari.hex(0xFFC93C), Vari.hex(0xFFF7E6)),
    VERKKO("Verkkopallo", "Leviää verkoksi: sitä ei voi väistää eikä torjua.", Vari.hex(0xEEF2F6), Vari.hex(0x4A5568), osumaKerroin = 2.4f),
    TUTKA("Tutkapallo", "Paljastaa lähellä piileskelevät ukot.", Vari.hex(0x3DDC97), Vari.hex(0x12343B)),
    HIDASTUS("Hidastuspallo", "Hidastaa ukot, joiden lähelle se osuu.", Vari.hex(0x7FD4FF), Vari.hex(0x3D5AFE)),
    MONI("Monipallo", "Hajoaa lennossa kolmeksi.", Vari.hex(0xFF8A3D), Vari.hex(0xFFE0B2)),
    HAKEUTUVA("Hakeutuva pallo", "Kääntyy itse kohti lähintä ukkoa.", Vari.hex(0xE85D9A), Vari.hex(0x5B2A86), paino = 0.2f),
    KULTAINEN("Kultainen pallo", "Ainoa, jolla kultaisen ukon saa.", Vari.hex(0xF4BE1E), Vari.hex(0xFFF2B0), osumaKerroin = 1.3f),
    POMPPU("Pomppupallo", "Pomppii pitkään ja nappaa vielä monen pompun jälkeen.", Vari.hex(0x7BC043), Vari.hex(0xF4FFE0), pomput = 5, kimmoisuus = 0.9f),
    TAHMA("Tahmapallo", "Liimaa ukon hetkeksi paikalleen.", Vari.hex(0xB07CFF), Vari.hex(0xFFD1F0)),
}

/**
 * The special balls the player carries: only a few at a time. The platform saves [teksti] and restores it
 * with [lue].
 */
class Reppu {
    private val maarat = IntArray(PalloLaji.entries.size)
    /** The ball the next throw uses. */
    var valittu = PalloLaji.NORMI
        private set
    /** The lab has every ball, as many as it likes. */
    var rajaton = false
    var muutoksia = 0
        private set

    operator fun get(l: PalloLaji) = if (l == PalloLaji.NORMI || rajaton) 99 else maarat[l.ordinal]
    val yhteensa: Int get() = maarat.sum()
    val taynna: Boolean get() = yhteensa >= KOKO

    fun lisaa(l: PalloLaji): Boolean {
        if (l == PalloLaji.NORMI || taynna) return false
        maarat[l.ordinal]++
        muutoksia++
        return true
    }

    fun valitse(l: PalloLaji) {
        if (this[l] > 0) valittu = l
    }

    /** Takes the chosen ball out for a throw. Back to plain balls when that kind runs out. */
    fun kayta(): PalloLaji {
        val l = valittu
        if (l != PalloLaji.NORMI && !rajaton) {
            maarat[l.ordinal]--
            muutoksia++
            if (maarat[l.ordinal] <= 0) valittu = PalloLaji.NORMI
        }
        return l
    }

    fun teksti(): String = PalloLaji.entries.filter { maarat[it.ordinal] > 0 }.joinToString(";") { "${it.name}=${maarat[it.ordinal]}" }

    companion object {
        const val KOKO = 5

        fun lue(teksti: String?): Reppu {
            val r = Reppu()
            for (osa in teksti.orEmpty().split(';')) {
                val l = PalloLaji.entries.firstOrNull { it.name == osa.substringBefore('=') } ?: continue
                val n = osa.substringAfter('=', "").toIntOrNull() ?: continue
                if (l != PalloLaji.NORMI) r.maarat[l.ordinal] = n.coerceIn(0, KOKO - r.yhteensa)
            }
            return r
        }
    }
}

class Pallo(val id: Int, var p: V3, var v: V3, val sade: Float, val laji: PalloLaji = PalloLaji.NORMI) {
    /** Its special effect has gone off (the radar has pinged, the multi-ball has split). */
    var lauennut = false
    var tila = PallonTila.LENTAA
    /** Sideways acceleration from a curved swipe; ends at the first bounce. */
    var kaarre = V3.NOLLA
    var ika = 0f
    var tilaAika = 0f
    var pomppuja = 0
    /** Touched a figure, even if it did not catch it: such a throw is not a miss. */
    var osui = false
    /** The figure this ball caught. */
    var ukko: Ukko? = null
    /** Drawn size, 0…1 and a little over: balls pop when they catch and shrink when they fade. */
    var koko = 1f

    // Orientation, for the spin
    var x = V3.X
        private set
    var y = V3.Y
        private set
    var z = V3.Z
        private set

    fun vaihda(t: PallonTila) {
        tila = t
        tilaAika = 0f
    }

    fun pyorita(akseli: V3, kulma: Float) {
        x = kierra(x, akseli, kulma)
        y = kierra(y, akseli, kulma).tasolle(x).suunta(V3.Y)
        z = x cross y
    }

    /**
     * One physics step. Returns the speed of the hardest bounce during it, or 0.
     * Surfaces are one-sided: a ball only bounces off the side the normal points to.
     */
    fun askel(dt: Float, pinnat: List<Pinta>): Float {
        v += (PAINOVOIMA * laji.paino + kaarre) * dt
        val ed = p
        p += v * dt
        var isku = 0f
        for (pinta in pinnat) {
            val d = pinta.etaisyys(p)
            if (d >= sade || d < -sade * 2f - 0.02f) continue
            val vn = v dot pinta.normaali
            if (vn >= 0f || pinta.etaisyys(ed) < d) continue
            if (!pinta.sisalla(p, sade * 0.5f)) continue
            p += pinta.normaali * (sade - d)
            val pysty = pinta.normaali * vn
            val sivu = v - pysty
            // Lively off the first bounce, then quickly calmer; slow contacts just roll
            val kimmoisuus = if (-vn < 0.35f) 0f else laji.kimmoisuus
            v = sivu * (if (kimmoisuus == 0f) 1f - 2.5f * dt else 0.82f) - pysty * kimmoisuus
            if (-vn > isku) isku = -vn
            if (kimmoisuus > 0f) {
                pomppuja++
                kaarre = V3.NOLLA
            }
        }
        // Spin as if rolling in the direction of travel
        val vaaka = v.vaakaan()
        val vauhti = vaaka.pituus
        if (vauhti > 0.01f) pyorita((V3.Y cross vaaka).suunta(V3.X), vauhti / sade * dt * 0.6f)
        return isku
    }

    companion object {
        val PAINOVOIMA = V3(0f, -9.81f, 0f)
        /** A ball the size of a ping-pong ball suits figures 25 cm tall. */
        const val SADE = 0.024f
    }
}
