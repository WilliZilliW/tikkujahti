package fi.otsosoft.tikkujahti.mesh

import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta

/**
 * Invisible stand-ins for the real walls and table tops the game has recognised. They are drawn into the depth
 * buffer only, before the figures, so a figure behind a real wall — in the next room, say — is hidden by it,
 * as the real wall would hide it. This works also where the phone gives no depth image; where it does, the
 * depth image hides figures behind everything else too.
 */
object Peittajat {
    /**
     * How far behind the real surface the stand-in lies, metres: a figure climbing a wall or sitting on a table
     * is in front of it and stays visible, one in the next room is well behind it.
     */
    const val TAAKSE = 0.05f

    /** Walls and table tops hide what is behind them. (Not the floor: the game never puts anything under it.) */
    fun peittaa(p: Pinta) = p.laji == PinnanLaji.SEINA || p.laji == PinnanLaji.POYTA

    /**
     * The outline of [p] as a triangle fan around its middle, [TAAKSE] behind the surface: positions (x, y, z for
     * each point) and triangle indices. Outlines are convex, so the fan covers them exactly. Draw both sides.
     */
    fun kolmiot(p: Pinta): Pair<FloatArray, IntArray> {
        val n = p.reuna.size
        val paikat = FloatArray((n + 1) * 3)
        val siirto = p.normaali * -TAAKSE
        var ka = 0f
        var kb = 0f
        for ((a, b) in p.reuna) {
            ka += a; kb += b
        }
        val keski = p.pisteTasolta(ka / n, kb / n) + siirto
        paikat[0] = keski.x; paikat[1] = keski.y; paikat[2] = keski.z
        for ((i, k) in p.reuna.withIndex()) {
            val w = p.pisteTasolta(k.first, k.second) + siirto
            paikat[(i + 1) * 3] = w.x; paikat[(i + 1) * 3 + 1] = w.y; paikat[(i + 1) * 3 + 2] = w.z
        }
        val indeksit = IntArray(n * 3)
        for (i in 0 until n) {
            indeksit[i * 3] = 0
            indeksit[i * 3 + 1] = 1 + i
            indeksit[i * 3 + 2] = 1 + (i + 1) % n
        }
        return paikat to indeksit
    }
}
