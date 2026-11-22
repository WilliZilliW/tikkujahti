package fi.otsosoft.tikkujahti.mesh

/**
 * A triangle mesh with a fixed topology: the first build decides the vertex count and the indices, and every
 * later build only rewrites positions and normals. Triangles are grouped into parts ([osia]), one per material;
 * part k uses indices [osaAlku]`[k]` until `osaAlku[k] + osaMaara[k]`. Winding is counter-clockwise seen from outside.
 */
abstract class Verkko(val osia: Int) {
    /** x, y, z per vertex. Only the first [pisteita] vertices are in use. */
    var paikat = FloatArray(3 * 1024)
        private set
    var normaalit = FloatArray(3 * 1024)
        private set
    var pisteita = 0
        private set
    var indeksit = IntArray(0)
        private set
    val osaAlku = IntArray(osia)
    val osaMaara = IntArray(osia)

    private var lukittu = false
    private var kolmiot: Array<ArrayList<Int>>? = Array(osia) { ArrayList() }
    protected var osa = 0
    protected val ensimmainen: Boolean get() = !lukittu

    protected fun aloita() {
        pisteita = 0
    }

    protected fun piste(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        val i = pisteita
        if (i * 3 + 3 > paikat.size) {
            check(!lukittu) { "Verkon pistemäärä muuttui" }
            paikat = paikat.copyOf(paikat.size * 2)
            normaalit = normaalit.copyOf(normaalit.size * 2)
        }
        val o = i * 3
        paikat[o] = x; paikat[o + 1] = y; paikat[o + 2] = z
        normaalit[o] = nx; normaalit[o + 1] = ny; normaalit[o + 2] = nz
        pisteita = i + 1
        return i
    }

    /** Adds a triangle to the current part. Does nothing after the first build. */
    protected fun kolmio(a: Int, b: Int, c: Int) {
        val k = kolmiot ?: return
        k[osa].add(a); k[osa].add(b); k[osa].add(c)
    }

    /** Ends a build. The first call freezes the topology. */
    protected fun valmis() {
        val k = kolmiot ?: return
        indeksit = IntArray(k.sumOf { it.size })
        var n = 0
        for (i in 0 until osia) {
            osaAlku[i] = n
            osaMaara[i] = k[i].size
            for (v in k[i]) indeksit[n++] = v
        }
        paikat = paikat.copyOf(pisteita * 3)
        normaalit = normaalit.copyOf(pisteita * 3)
        kolmiot = null
        lukittu = true
    }

    /**
     * Indices for a closed shell laid out as: a pole, [renkaita] rings of [sivuja] vertices, and a second pole,
     * starting at vertex [alku]. The rings advance along an axis T and turn from N towards T × N.
     */
    protected fun kuori(alku: Int, renkaita: Int, sivuja: Int) {
        if (!ensimmainen) return
        val eka = alku + 1
        val loppu = eka + renkaita * sivuja
        for (k in 0 until sivuja) {
            val k1 = (k + 1) % sivuja
            kolmio(alku, eka + k1, eka + k)
            for (i in 0 until renkaita - 1) {
                val a = eka + i * sivuja
                val b = a + sivuja
                kolmio(a + k, a + k1, b + k)
                kolmio(a + k1, b + k1, b + k)
            }
            val a = eka + (renkaita - 1) * sivuja
            kolmio(a + k, a + k1, loppu)
        }
    }
}
