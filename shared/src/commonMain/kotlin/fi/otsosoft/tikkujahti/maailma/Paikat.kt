package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3
import kotlin.math.abs

enum class PaikanLaji(val nimi: String) {
    /** Where two walls meet on the floor. */
    NURKKA("nurkka"),
    /** On the floor under a table or shelf. */
    KOLO("kolo"),
    /** A dark spot (measured from the camera image where the platform can). */
    VARJO("varjoisa paikka"),
    /** On top of something tall. */
    KORKEA("korkea paikka"),
}

/** A spot in the room with some character: figures hide in these and special balls lie in them. */
class Paikka(val laji: PaikanLaji, val piste: V3) {
    /** On the floor (as opposed to up on some surface). */
    val lattialla: Boolean get() = laji != PaikanLaji.KORKEA
}

object Paikat {
    /** Works out the interesting spots of a room from its surfaces, plus the [varjot] the platform measured. */
    fun etsi(m: Maailma, varjot: List<V3>): List<Paikka> {
        val lattia = m.lattia ?: return emptyList()
        val ly = lattia.keskus.y
        val ulos = ArrayList<Paikka>()
        fun lisaa(laji: PaikanLaji, p: V3) {
            if (ulos.none { it.laji == laji && (it.piste - p).pituus < 0.35f }) ulos += Paikka(laji, p)
        }
        for (p in m.pinnat) {
            if (p.laji != PinnanLaji.POYTA || p.reuna.isEmpty()) continue
            val ca = p.reuna.sumOf { it.first.toDouble() }.toFloat() / p.reuna.size
            val cb = p.reuna.sumOf { it.second.toDouble() }.toFloat() / p.reuna.size
            val keski = p.pisteTasolta(ca, cb)
            val korkeus = keski.y - ly
            // High enough to crawl under, low enough to be furniture: there is a nook below
            if (korkeus in 0.5f..1.3f) {
                val alla = V3(keski.x, ly, keski.z)
                if (lattia.sisalla(alla)) lisaa(PaikanLaji.KOLO, alla)
            }
            if (korkeus > 0.8f) lisaa(PaikanLaji.KORKEA, keski)
        }
        // Corners: where two walls that are not parallel both come down to the floor
        val seinat = m.pinnat.filter { it.laji == PinnanLaji.SEINA }
        for (i in seinat.indices) for (j in i + 1 until seinat.size) {
            val a = seinat[i]
            val b = seinat[j]
            val na = a.normaali.vaakaan().suunta(V3.NOLLA)
            val nb = b.normaali.vaakaan().suunta(V3.NOLLA)
            val det = na.x * nb.z - na.z * nb.x
            if (abs(det) < 0.5f) continue
            // The floor point a short way out from both walls
            val da = (a.keskus dot na) + NURKASTA
            val db = (b.keskus dot nb) + NURKASTA
            val p = V3((da * nb.z - db * na.z) / det, ly, (na.x * db - nb.x * da) / det)
            val ylla = p + V3.Y * 0.15f
            if (lattia.sisalla(p) && a.sisalla(ylla, 0.15f) && b.sisalla(ylla, 0.15f)) lisaa(PaikanLaji.NURKKA, p)
        }
        for (v in varjot) {
            val p = V3(v.x, ly, v.z)
            if (abs(v.y - ly) < 0.25f && lattia.sisalla(p)) lisaa(PaikanLaji.VARJO, p)
        }
        return ulos
    }

    const val NURKASTA = 0.14f
}
