package com.sergiotalk.app.call

/** Call state for internet-relayed voice calls. */
sealed class CallState {
    object Idle : CallState()
    object Connecting : CallState()
    data class Ringing(val fromId: String, val fromName: String) : CallState() // incoming call, not yet answered
    data class InCall(val peerName: String) : CallState()
    data class Ended(val reason: String) : CallState()
}
