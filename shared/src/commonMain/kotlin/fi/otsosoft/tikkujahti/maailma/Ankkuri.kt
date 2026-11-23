package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3

/** A rigid transform: a rotation (the world directions of the local x, y and z axes) and a translation. */
data class Muunnos(val siirto: V3, val x: V3, val y: V3, val z: V3) {
    fun maailmaan(p: V3) = siirto + x * p.x + y * p.y + z * p.z
    fun suuntaMaailmaan(d: V3) = x * d.x + y * d.y + z * d.z
    fun paikalliseksi(p: V3): V3 {
        val d = p - siirto
        return V3(d dot x, d dot y, d dot z)
    }
    fun suuntaPaikalliseksi(d: V3) = V3(d dot x, d dot y, d dot z)

    companion object {
        val YKSIKKO = Muunnos(V3.NOLLA, V3.X, V3.Y, V3.Z)
        fun siirto(p: V3) = Muunnos(p, V3.X, V3.Y, V3.Z)
    }
}

/**
 * A spot the platform keeps fixed to the real world (an ARCore or WebXR anchor). Tracking corrects itself
 * now and then — after looking around a room, the phone may realise it has drifted — and then the world
 * coordinates of everything real shift. What is stored relative to an anchor shifts with it.
 */
interface Ankkuri {
    /** Where the anchor is now, or null while the platform is not tracking it. */
    val muunnos: Muunnos?
    /** Lets the platform stop tracking the anchor. */
    fun irrota()
}

/** Makes anchors at world points; returns null when the platform cannot make one just now. */
fun interface Ankkurit {
    fun luo(paikka: V3): Ankkuri?
}
