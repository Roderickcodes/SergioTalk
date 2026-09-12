package com.sergiotalk.app.net

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.sergiotalk.app.call.CallState
import com.sergiotalk.app.data.Message
import com.sergiotalk.app.data.MessageStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import okio.toByteString
import org.json.JSONArray
import org.json.JSONObject

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val url: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Internet transport: both phones connect to a small relay server (see
 * /server in this project) over a normal WebSocket, using whatever internet
 * connection each phone already has - Wi-Fi, mobile data, different
 * networks entirely, doesn't matter. The relay server is the one thing both
 * sides need to be able to reach; there's no other requirement (no local
 * proximity, no shared network).
 *
 * Two things travel over this connection:
 *  - Chat: uses the shared [Message] model. Contacts are discovered
 *    automatically via the server's "presence" broadcasts (everyone
 *    currently connected to the same relay shows up), so there's no need to
 *    exchange ids by hand.
 *  - Calls: simple signaling (call-offer / call-answer / call-end) plus raw
 *    16-bit PCM audio frames sent as binary WebSocket messages, which the
 *    server forwards straight to whichever peer you're currently in a call
 *    with.
 *
 * Not in this prototype: encryption (the relay server sees everything that
 * passes through it in plain text), authentication (anyone who knows your
 * id can be impersonated), and reconnect-on-drop handling.
 */
class InternetManager(
    private val myId: String,
    private val myName: String,
    private val store: MessageStore
) {
    companion object {
        private const val TAG = "InternetManager"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 640 // 20ms at 16kHz/mono/16-bit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private var activePeerId: String? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var streamJob: Job? = null

    fun connect(serverUrl: String) {
        val url = serverUrl.trim()
        if (url.isEmpty()) return
        _connectionState.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        endCallLocal("Disconnected")
        webSocket?.close(1000, "bye")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Send (or bridge-forward) a chat message over the relay. No-op if not connected. */
    fun relay(message: Message) {
        val ws = webSocket ?: return
        val json = JSONObject()
            .put("type", "chat")
            .put("id", message.id)
            .put("to", message.destinationId)
            .put("text", message.text)
            .put("ts", message.timestamp)
        ws.send(json.toString())
    }

    fun callPeer(id: String) {
        webSocket?.send(JSONObject().put("type", "call-offer").put("to", id).toString())
        _callState.value = CallState.Connecting
    }

    fun answerCall(accept: Boolean) {
        val ringing = _callState.value as? CallState.Ringing ?: return
        webSocket?.send(
            JSONObject().put("type", "call-answer").put("to", ringing.fromId).put("accepted", accept).toString()
        )
        if (accept) {
            activePeerId = ringing.fromId
            _callState.value = CallState.InCall(ringing.fromName)
            startAudioStreaming()
        } else {
            _callState.value = CallState.Ended("Declined")
        }
    }

    fun hangUp() {
        activePeerId?.let { peer ->
            webSocket?.send(JSONObject().put("type", "call-end").put("to", peer).toString())
        }
        endCallLocal("Call ended")
    }

    fun toggleMute() {
        _muted.value = !_muted.value
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("type", "register").put("id", myId).put("name", myName).toString())
            _connectionState.value = ConnectionState.Connected(webSocket.request().url.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (e: Exception) { return }
            when (msg.optString("type")) {
                "presence" -> {
                    val online: JSONArray = msg.optJSONArray("online") ?: return
                    for (i in 0 until online.length()) {
                        val entry = online.getJSONObject(i)
                        val id = entry.optString("id")
                        if (id.isNotBlank() && id != myId) store.noteContact(id, entry.optString("name", id))
                    }
                }
                "chat" -> {
                    val incoming = Message(
                        id = msg.optString("id"),
                        senderId = msg.optString("from"),
                        senderName = msg.optString("name"),
                        destinationId = msg.optString("to"),
                        text = msg.optString("text"),
                        timestamp = msg.optLong("ts")
                    )
                    val isNew = store.offer(incoming)
                    if (isNew) {
                        if (incoming.destinationId == "ALL" || incoming.destinationId == myId) {
                            store.display(incoming)
                        }
                    }
                }
                "call-offer" -> {
                    _callState.value = CallState.Ringing(msg.optString("from"), msg.optString("name"))
                }
                "call-answer" -> {
                    if (msg.optBoolean("accepted")) {
                        val from = msg.optString("from")
                        activePeerId = from
                        _callState.value = CallState.InCall(from)
                        startAudioStreaming()
                    } else {
                        _callState.value = CallState.Ended("Declined")
                    }
                }
                "call-end" -> endCallLocal("Call ended")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (_callState.value is CallState.InCall) {
                val buf = bytes.toByteArray()
                audioTrack?.write(buf, 0, buf.size)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
            endCallLocal("Connection lost")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission") // Caller gates calling on RECORD_AUDIO grant
    private fun startAudioStreaming() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 4)
        )
        val trackMinBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(trackMinBuf, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track
        record.startRecording()
        track.play()

        streamJob = scope.launch {
            val buf = ByteArray(FRAME_BYTES)
            val silence = ByteArray(FRAME_BYTES)
            try {
                while (isActive) {
                    val read = record.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    val frame = if (_muted.value) silence else buf
                    webSocket?.send(frame.toByteString(0, read))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio send loop ended", e)
            }
        }
    }

    private fun endCallLocal(reason: String) {
        streamJob?.cancel()
        streamJob = null
        try { audioRecord?.stop() } catch (e: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null
        try { audioTrack?.stop() } catch (e: IllegalStateException) {}
        audioTrack?.release()
        audioTrack = null
        activePeerId = null
        _callState.value = CallState.Ended(reason)
    }
}
