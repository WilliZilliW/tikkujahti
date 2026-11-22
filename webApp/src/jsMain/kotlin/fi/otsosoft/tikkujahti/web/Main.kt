package fi.otsosoft.tikkujahti.web

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

fun main() {
    val body = document.body as HTMLElement
    when (body.getAttribute("data-sivu")) {
        "labra" -> Labra(body)
        else -> PeliSivu(body)
    }
}
