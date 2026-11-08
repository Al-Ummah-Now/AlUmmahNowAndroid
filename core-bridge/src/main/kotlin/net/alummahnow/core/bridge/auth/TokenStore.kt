package net.alummahnow.core.bridge.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.alummahnow.core.SecureStorage

@Serializable
data class StoredTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

/** Persists tokens through the core's [SecureStorage] platform adapter (Keystore-backed on Android). */
class TokenStore(private val storage: SecureStorage, private val key: String = "auth.tokens") {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): StoredTokens? = storage.get(key)?.let {
        runCatching { json.decodeFromString<StoredTokens>(it.decodeToString()) }.getOrNull()
    }

    fun save(tokens: StoredTokens) = storage.set(key, json.encodeToString(tokens).encodeToByteArray())

    fun clear() = storage.delete(key)
}
