/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.editor

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// A minimal WebSocket server, in the style of code I took from old projects...
class WebSocketServer(
    private val port: Int,
    private val bindAddress: String = "0.0.0.0",
    private val onMessage: (Connection, String) -> Unit,
    private val onOpen: (Connection) -> Unit = {},
    private val onClose: (Connection) -> Unit = {},
    private val authorize: (Request) -> Boolean = { true },
    private val negotiateProtocol: (Request) -> String? = { null },
    private val http: (Request) -> HttpResponse? = { null },
    private val tls: javax.net.ssl.SSLContext? = null,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val connections = Collections.synchronizedSet(mutableSetOf<Connection>())

    class Request(
        val method: String,
        val target: String,
        private val headers: Map<String, String>,
    ) {
        val path: String = target.substringBefore('?')

        val query: Map<String, String> = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }

        /** @param name must be lowercase. */
        fun header(name: String): String? = headers[name]

        val cookies: Map<String, String> by lazy {
            header("cookie")
                ?.split(';')
                ?.mapNotNull { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isEmpty() || !pair.contains('=')) null
                    else name to pair.substringAfter('=').trim()
                }
                ?.toMap()
                ?: emptyMap()
        }

        val isUpgrade: Boolean
            get() = header("upgrade")?.equals("websocket", ignoreCase = true) == true

        private companion object {
            fun decode(raw: String): String =
                runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
        }
    }

    class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun html(status: Int, markup: String) =
                HttpResponse(status, "text/html; charset=utf-8", markup.toByteArray(Charsets.UTF_8))
        }
    }

    class Connection(private val socket: Socket, private val output: OutputStream) {
        private val lock = Any()
        val isOpen: Boolean get() = !socket.isClosed

        fun send(text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            synchronized(lock) {
                try {
                    output.write(frame(OPCODE_TEXT, payload))
                    output.flush()
                } catch (_: Exception) {
                    close()
                }
            }
        }

        fun close() = runCatching { socket.close() }.let { }

        internal fun sendPong(payload: ByteArray) {
            synchronized(lock) {
                runCatching {
                    output.write(frame(OPCODE_PONG, payload))
                    output.flush()
                }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val bound = tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()
        bound.reuseAddress = true
        bound.bind(java.net.InetSocketAddress(bindAddress, port))
        server = bound

        // they really need to make thi a better lambda.
        Thread({
            while (running.get()) {
                val socket = try {
                    bound.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(socket) }, "shadr-editor-client").apply { isDaemon = true }.start()
            }
        }, "shadr-editor-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        synchronized(connections) { connections.toList() }.forEach { it.close() }
        connections.clear()
        runCatching { server?.close() }
        server = null
    }

    fun broadcast(text: String) {
        synchronized(connections) { connections.toList() }.forEach { it.send(text) }
    }

    val connectionCount: Int get() = connections.size

    private fun serve(socket: Socket) {
        var connection: Connection? = null
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = readRequest(input) ?: return

            if (!authorize(request)) {
                writeHttp(output, unauthorized())
                return
            }

            if (!request.isUpgrade) {
                writeHttp(output, http(request) ?: notFound())
                return
            }

            val key = request.header("sec-websocket-key") ?: return
            output.write(
                handshakeResponse(key, negotiateProtocol(request)).toByteArray(Charsets.US_ASCII),
            )
            output.flush()

            val established = Connection(socket, output)
            connection = established
            connections += established
            onOpen(established)

            readFrames(input, established)
        } catch (_: Exception) {
            // Ignore this, like my aspirations.
        } finally {
            connection?.let {
                connections -= it
                onClose(it)
            }
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val raw = StringBuilder()
        val buffer = ByteArray(1)
        while (!raw.endsWith("\r\n\r\n")) {
            if (input.read(buffer) < 0) return null
            raw.append(buffer[0].toInt().toChar())
            if (raw.length > MAX_HANDSHAKE_BYTES) return null
        }

        val lines = raw.toString().split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null

        val headers = lines.drop(1)
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

        return Request(method = requestLine[0], target = requestLine[1], headers = headers)
    }

    private fun handshakeResponse(key: String, protocol: String?): String {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
        )
        // Oooh magic, pray internet protocol doesn't change coodee.
        return buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            if (protocol != null) append("Sec-WebSocket-Protocol: $protocol\r\n")
            append("\r\n")
        }
    }

    private fun writeHttp(output: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            for ((name, value) in response.headers) append("$name: $value\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        304 -> "Not Modified"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private fun unauthorized() = HttpResponse.html(
        401,
        "<!doctype html><html><head><meta charset=utf-8><title>shadr editor</title></head>" +
        "<body style=\"margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;" +
        "font:14px system-ui;background:#0b0b0f;color:#e8e8ee\">" +
        "<h1 style=\"font-size:1rem;font-weight:500;margin:0\">Read the docs.</h1>" +
        "</body></html>",
    )

    private fun notFound() = HttpResponse.html(404, "<!doctype html><title>404</title>404")

    // This was NOT my code, but i can't credit it because I can't find where I originally found it..
    // Magic stuff...
    private fun readFrames(input: InputStream, connection: Connection) {
        val message = StringBuilder()
        while (connection.isOpen) {
            val first = input.read()
            if (first < 0) return
            val second = input.read()
            if (second < 0) return

            val fin = (first and 0x80) != 0
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0
            var length = (second and 0x7F).toLong()

            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            if (!masked || length > MAX_FRAME_BYTES) return

            val mask = ByteArray(4)
            if (input.readNBytes(mask, 0, 4) != 4) return
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val count = input.read(payload, read, payload.size - read)
                if (count < 0) return
                read += count
            }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                OPCODE_CLOSE -> return
                OPCODE_PING -> connection.sendPong(payload)
                OPCODE_PONG -> Unit
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    message.append(String(payload, Charsets.UTF_8))
                    if (fin) {
                        val complete = message.toString()
                        message.setLength(0)
                        runCatching { onMessage(connection, complete) }
                    }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        // It may possibly be hardcoded.
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val MAX_HANDSHAKE_BYTES = 8 * 1024

        const val MAX_FRAME_BYTES = 8L * 1024 * 1024

        fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val header = when {
                payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
                payload.size <= 65535 -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126,
                    (payload.size shr 8).toByte(), payload.size.toByte(),
                )
                else -> ByteArray(10).also {
                    it[0] = (0x80 or opcode).toByte()
                    it[1] = 127
                    for (i in 0 until 8) it[9 - i] = (payload.size.toLong() shr (8 * i)).toByte()
                }
            }
            return header + payload
        }
    }
}
