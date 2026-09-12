package com.sergiotalk.app.data

import java.util.UUID

/**
 * A mesh message. Flooding-based routing: every phone that receives a message
 * it hasn't seen before (by [id]) rebroadcasts it to all its current peers,
 * decrementing [ttl] each hop, until ttl hits 0 or the destination is reached.
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val destinationId: String, // "ALL" for broadcast/public channel
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 6 // max hops before a message is dropped
) {
    /** Serialize to a compact string for sending over a BLE characteristic. */
    fun encode(): String =
        listOf(id, senderId, senderName, destinationId, timestamp.toString(), ttl.toString(), text)
            .joinToString("\u0001") // unit separator, unlikely to appear in chat text

    fun withDecrementedTtl(): Message = copy(ttl = ttl - 1)

    companion object {
        fun decode(raw: String): Message? {
            val parts = raw.split("\u0001")
            if (parts.size != 7) return null
            return try {
                Message(
                    id = parts[0],
                    senderId = parts[1],
                    senderName = parts[2],
                    destinationId = parts[3],
                    timestamp = parts[4].toLong(),
                    ttl = parts[5].toInt(),
                    text = parts[6]
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
