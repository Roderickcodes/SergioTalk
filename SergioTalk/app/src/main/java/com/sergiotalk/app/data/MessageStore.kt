package com.sergiotalk.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * Tracks:
 *  - message IDs we've already seen (dedup, so flooding doesn't loop forever)
 *  - messages addressed to us (or broadcast) that should show in a chat UI
 *  - contacts: every sender/name we've ever heard from over the internet
 *    relay, whether or not their messages were addressed to us specifically.
 */
class MessageStore {
    private val seenIds = Collections.synchronizedSet(LinkedHashSet<String>())

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _contacts = MutableStateFlow<Map<String, String>>(emptyMap()) // id -> name
    val contacts: StateFlow<Map<String, String>> = _contacts

    /**
     * Call for every message that arrives (ours or relayed), regardless of
     * who it's addressed to. Returns true if this is the first time we've
     * seen this message ID (i.e. it should be relayed onward). Also learns
     * the sender as a contact, since anyone whose traffic reaches us is
     * someone we can potentially reply to.
     */
    fun offer(message: Message): Boolean {
        val isNew = seenIds.add(message.id)
        if (isNew) {
            if (seenIds.size > 4000) {
                val it = seenIds.iterator()
                repeat(1000) { if (it.hasNext()) { it.next(); it.remove() } }
            }
            noteContact(message.senderId, message.senderName)
        }
        return isNew
    }

    /** Call when a message should actually appear in the chat UI. */
    fun display(message: Message) {
        _messages.value = (_messages.value + message).sortedBy { it.timestamp }
    }

    fun noteContact(id: String, name: String) {
        if (id.isBlank() || id == "ALL") return
        if (_contacts.value[id] != name) {
            _contacts.value = _contacts.value + (id to name)
        }
    }
}
