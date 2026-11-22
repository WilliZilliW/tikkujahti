package fi.otsosoft.tikkujahti.ukko

/** RGB colour, 0…1 per channel. */
data class Vari(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(rgb: Int) = Vari(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
    }
}

/** Body measurements in metres for a figure of size 1 (about 25 cm tall). */
class Mitat(val koko: Float) {
    val reisi = 0.047f * koko
    val saari = 0.046f * koko
    val nilkka = 0.008f * koko
    val jalkatera = 0.024f * koko
    val lonkkaLeveys = 0.012f * koko
    val olkaLeveys = 0.017f * koko
    val vartalo = 0.066f * koko
    val kaula = 0.011f * koko
    val paaSade = 0.036f * koko
    val olkavarsi = 0.043f * koko
    val kyynarvarsi = 0.041f * koko
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

enum class Harvinaisuus(val nimi: String) { TAVALLINEN("tavallinen"), HARVINAINEN("harvinainen"), TOSI_HARVINAINEN("tosi harvinainen") }

/**
 * The kinds of stick figure.
 *
 * @param vihje shown in the album until the first one is caught
 * @param osumia hits it takes to catch one
 * @param pisteet points for catching one
 * @param vauhti walking and running speed multiplier
 */
enum class Tyyppi(
    val nimi: String,
    val vihje: String,
    val harvinaisuus: Harvinaisuus,
    val osumia: Int = 1,
    val pisteet: Int = 1,
    val vauhti: Float = 1f,
) {
    TAVALLINEN("Tikku-ukko", "Kuljeskelee joka lattialla.", Harvinaisuus.TAVALLINEN),
    JUOKSIJA("Nopea juoksija", "Pieni vilistäjä, joka ei pysy hetkeäkään paikallaan.", Harvinaisuus.HARVINAINEN, pisteet = 3, vauhti = 1.5f),
    ISO("Iso ukko", "Niin iso, ettei yksi pallo riitä.", Harvinaisuus.HARVINAINEN, osumia = 3, pisteet = 5, vauhti = 0.75f),
    KULTAINEN("Kultainen tikku-ukko", "Kimaltaa. Vain kultainen pallo kelpaa sille.", Harvinaisuus.TOSI_HARVINAINEN, pisteet = 20, vauhti = 1.2f),
    KIIPEILIJA("Kiipeilijä", "Lattialla kömpelö, seinällä vikkelä.", Harvinaisuus.HARVINAINEN, pisteet = 4),
    LASKUVARJO("Laskuvarjo-ukko", "Leijuu alas korkealta. Ehditkö osua ennen kuin se laskeutuu?", Harvinaisuus.HARVINAINEN, pisteet = 4),
    NINJA("Ninja", "Torjuu jokaisen pallon. Tämä ukko pelkää verkkoja.", Harvinaisuus.TOSI_HARVINAINEN, pisteet = 12, vauhti = 1.4f),
    KILPI("Kilpi-ukko", "Kilpi pysäyttää pallot edestä. Entä takaa tai pompun kautta?", Harvinaisuus.HARVINAINEN, pisteet = 6, vauhti = 0.9f),
    PIILO("Piiloukko", "Näkymätön: vain silmät vilkkuvat. Tutka paljastaa sen.", Harvinaisuus.TOSI_HARVINAINEN, pisteet = 12),
    LAUMA("Lauma-ukko", "Liikkuu kolmen laumassa. Yhdellä pallolla saa vain yhden, ja muut karkaavat.", Harvinaisuus.HARVINAINEN, pisteet = 3, vauhti = 1.1f),
    KATTO("Kattoukko", "Roikkuu katossa pää alaspäin, korkealla ulottumattomissa.", Harvinaisuus.TOSI_HARVINAINEN, pisteet = 10),
}

/** Looks and personality of one stick figure. */
data class Ulkonako(
    val vari: Vari,
    val koko: Float = 1f,
    val luonne: Luonne = Luonne(),
    /** Voice pitch multiplier. */
    val aani: Float = 1f,
    val tyyppi: Tyyppi = Tyyppi.TAVALLINEN,
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
        val KULTA = Vari.hex(0xF4BE1E)
        val NINJA = Vari.hex(0x34323F)
        val HAALEA = Vari.hex(0xC9D3DE)

        /** The colour of each kind's accessory: shield, parachute, headband. */
        fun varusteVari(t: Tyyppi) = when (t) {
            Tyyppi.KILPI -> Vari.hex(0x8FA3BA)
            Tyyppi.LASKUVARJO -> Vari.hex(0xFF7B6B)
            Tyyppi.NINJA -> Vari.hex(0xE0304F)
            else -> Vari.hex(0xFFFFFF)
        }
        val SILMAVALKO = Vari.hex(0xFBFBF7)
        val TUMMA = Vari.hex(0x1D1A22)
        val SUU = Vari.hex(0x5A1E24)
        val KIELI = Vari.hex(0xF27A8A)
    }
}
