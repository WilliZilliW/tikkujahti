package fi.otsosoft.tikkujahti.ukko

/** RGB colour, 0…1 per channel. */
data class Vari(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(rgb: Int) = Vari(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
    }
}

/** Body measurements in metres for a figure of size 1 (about 25 cm tall). */
class Mitat(val koko: Float) {
    val reisi = 0.055f * koko
    val saari = 0.054f * koko
    val nilkka = 0.008f * koko
    val jalkatera = 0.024f * koko
    val lonkkaLeveys = 0.011f * koko
    val olkaLeveys = 0.017f * koko
    val vartalo = 0.058f * koko
    val kaula = 0.011f * koko
    val paaSade = 0.033f * koko
    val olkavarsi = 0.040f * koko
    val kyynarvarsi = 0.038f * koko
    val kasiSade = 0.0078f * koko
    val raajaSade = 0.0058f * koko
    val vartaloSade = 0.0095f * koko

    val jalanPituus = reisi + saari
    val kadenPituus = olkavarsi + kyynarvarsi
    /** Pelvis height when standing relaxed, knees slightly bent. */
    val lantioSeisten = jalanPituus * 0.955f + nilkka
    val pituus = lantioSeisten + vartalo + kaula + paaSade * 1.85f
}

/** Personality: how the figure behaves. All 0…1. */
data class Luonne(
    /** Shy figures keep their distance and hide; bold ones come close. */
    val ujous: Float = 0.3f,
    /** Playful figures wave and tease. */
    val leikkisyys: Float = 0.5f,
    /** Chance of dodging a ball it sees coming in time. */
    val vaistotaito: Float = 0.3f,
    /** Walking speed multiplier. */
    val vilkkaus: Float = 0.5f,
)

/** Looks and personality of one stick figure. */
data class Ulkonako(
    val vari: Vari,
    val koko: Float = 1f,
    val luonne: Luonne = Luonne(),
    /** Voice pitch multiplier. */
    val aani: Float = 1f,
) {
    val mitat = Mitat(koko)

    companion object {
        val VARIT = listOf(
            Vari.hex(0xF08A24), // oranssi
            Vari.hex(0x2BB3A3), // turkoosi
            Vari.hex(0x8E6BE8), // violetti
            Vari.hex(0x7BC043), // lime
            Vari.hex(0xE85D75), // vadelma
            Vari.hex(0x3D8BFF), // sininen
        )
        val SILMAVALKO = Vari.hex(0xFBFBF7)
        val TUMMA = Vari.hex(0x1D1A22)
        val SUU = Vari.hex(0x5A1E24)
        val KIELI = Vari.hex(0xF27A8A)
    }
}
