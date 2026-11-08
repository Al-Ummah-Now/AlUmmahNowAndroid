package net.alummahnow.core.bridge.auth

import java.util.concurrent.ConcurrentHashMap
import net.alummahnow.core.SecureStorage

class MemoryStorage : SecureStorage {
    val items = ConcurrentHashMap<String, ByteArray>()
    override fun get(key: String): ByteArray? = items[key]
    override fun set(key: String, value: ByteArray) {
        items[key] = value
    }
    override fun delete(key: String) {
        items.remove(key)
    }
    override fun list(prefix: String): List<String> = items.keys.filter { it.startsWith(prefix) }.sorted()
}
