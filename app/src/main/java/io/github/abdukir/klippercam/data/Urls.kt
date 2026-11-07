package io.github.abdukir.klippercam.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Urls {
    /** Normalises user input like `192.168.1.5`, `u1.local:80/` or `http://host` to an origin URL. */
    fun printerBase(address: String): HttpUrl? {
        var a = address.trim()
        if (a.isEmpty()) return null
        if (!a.contains("://")) a = "http://$a"
        val url = a.toHttpUrlOrNull() ?: return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    /** Base URL for Moonraker's HTTP API. */
    fun moonrakerBase(printer: Printer): HttpUrl? {
        val base = printerBase(printer.address) ?: return null
        return printer.moonrakerPort?.let { base.newBuilder().port(it).build() } ?: base
    }

    /**
     * Resolves a (possibly relative) webcam URL against the printer's web address, the same way
     * Mainsail/Fluidd do. Non-HTTP schemes (rtsp://, ws://) are returned unchanged.
     */
    fun resolve(printer: Printer, url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        val lower = u.lowercase()
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://") ||
            lower.startsWith("ws://") || lower.startsWith("wss://") ||
            lower.startsWith("http://") || lower.startsWith("https://")
        ) return u
        val base = printerBase(printer.address) ?: return null
        return base.resolve(u)?.toString()
    }

    fun host(printer: Printer): String? = printerBase(printer.address)?.host

    /** Host as written inside a URL (IPv6 literals in brackets). */
    fun urlHost(printer: Printer): String? = host(printer)?.let { if (it.contains(':')) "[$it]" else it }
}
