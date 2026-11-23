package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3

/** The real room's depth as the platform last measured it from the camera (its depth image). */
fun interface Syvyyskartta {
    /**
     * How far from the camera, along the ray towards world point [p], the real room was measured to be, metres;
     * null where nothing was measured (or [p] is out of the image).
     */
    fun etaisyys(p: V3): Float?
}
