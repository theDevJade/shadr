/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.editor

import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Builds the editor's TLS context from a keystore on disk.
 */
object EditorTls {

    /**
     * @return the context, or the reason of failure.
     */
    sealed interface Result {
        data class Ready(val context: SSLContext) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * @param keystore a PKCS12 or JKS file.
     * @param password the store password, also tried as the key password.
     * @param keyPassword the key password, when it differs from the store's.
     */
    fun load(keystore: File, password: String, keyPassword: String? = null): Result {
        if (!keystore.isFile) return Result.Failed("no keystore at ${keystore.path}")

        return runCatching {
            val type = if (keystore.extension.lowercase() in setOf("jks", "keystore")) "JKS" else "PKCS12"
            val store = KeyStore.getInstance(type)
            keystore.inputStream().use { store.load(it, password.toCharArray()) }

            if (!store.aliases().asSequence().any { store.isKeyEntry(it) }) {
                return Result.Failed(
                    "${keystore.name} holds no private key",
                )
            }

            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(store, (keyPassword ?: password).toCharArray())

            val context = SSLContext.getInstance("TLSv1.2")
            context.init(keys.keyManagers, null, null)
            Result.Ready(context)
        }.getOrElse {
            Result.Failed("could not load ${keystore.name}: ${it.message ?: it::class.simpleName}")
        }
    }
}
