/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class PackHost {
    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var archive: PackArchive? = null

    private var boundAddress: String = ""
    private var boundPort: Int = -1

    @Synchronized
    fun serve(archive: PackArchive, address: String, port: Int, publicHost: String = address): String {
        this.archive = archive
        if (server == null || boundAddress != address || boundPort != port) {
            stop()
            val created = HttpServer.create(InetSocketAddress(address, port), 0)
            created.createContext(ENDPOINT, ::handle)
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
