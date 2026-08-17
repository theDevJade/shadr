/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Serves the generated pack over HTTP on `/pack`.
 *
 * A server that generates its own pack cannot use a static CDN link, because the pack
 * changes whenever a page or an uploaded image does. So shadr hosts it: the JDK's built-in
 * [HttpServer] is enough for a handful of one-shot downloads per join, and it avoids
 * pulling a web framework into a Minecraft plugin.
 *
 * The payload is held in memory and swapped atomically, so a rebuild mid-session never
 * hands a client a half-written file.
 */
class PackHost {
    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var archive: PackArchive? = null

    private var boundAddress: String = ""
    private var boundPort: Int = -1

    /**
     * Start (or rebind) the server and publish [archive]. Returns the URL to send clients.
     *
     * Rebinding only happens when the address actually changes; swapping the payload on a
     * running server is the common case and costs nothing.
     */
    @Synchronized
    fun serve(archive: PackArchive, address: String, port: Int, publicHost: String = address): String {
        this.archive = archive
        if (server == null || boundAddress != address || boundPort != port) {
            stop()
            val created = HttpServer.create(InetSocketAddress(address, port), 0)
            created.createContext(ENDPOINT, ::handle)
            // A tiny fixed pool: downloads are short, and an unbounded pool would let a
            // burst of joins spawn a thread each.
            created.executor = Executors.newFixedThreadPool(2) { r ->
                Thread(r, "shadr-pack-host").apply { isDaemon = true }
            }
            created.start()
            server = created
            boundAddress = address
            boundPort = port
        }
        return "http://$publicHost:$port$ENDPOINT"
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        boundAddress = ""
        boundPort = -1
    }

    val isRunning: Boolean get() = server != null

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val payload = archive?.bytes
            if (payload == null) {
                exchange.sendResponseHeaders(503, -1)
                return@use
            }
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return@use
            }
            exchange.responseHeaders.add("Content-Type", "application/zip")
            // The URL is stable across rebuilds, so the hash is what tells a client the
            // pack changed. An ETag lets a proxy in between reach the same conclusion.
            archive?.sha1Hex?.let { exchange.responseHeaders.add("ETag", "\"$it\"") }
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
                return@use
            }
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
        }
    }

    private inline fun HttpExchange.use(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            runCatching { sendResponseHeaders(500, -1) }
        } finally {
            close()
        }
    }

    companion object {
        const val ENDPOINT = "/pack"
    }
}
