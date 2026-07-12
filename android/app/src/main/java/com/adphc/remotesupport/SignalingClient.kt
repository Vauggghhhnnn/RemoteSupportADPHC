package com.adphc.remotesupport

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Thin wrapper around Socket.IO that speaks the exact same events the
 * existing index.html "user" client uses, so the existing server.js and
 * the existing IT dashboard need ZERO changes.
 */
class SignalingClient(private val serverUrl: String) {

    var socket: Socket? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onItAnswer: ((JSONObject) -> Unit)? = null
    var onItIceCandidate: ((JSONObject) -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null

    fun connect() {
        val opts = IO.Options()
        opts.transports = arrayOf("websocket", "polling")
        socket = IO.socket(serverUrl, opts)

        socket?.on(Socket.EVENT_CONNECT) { onConnected?.invoke() }
        socket?.on(Socket.EVENT_DISCONNECT) { onDisconnected?.invoke() }
        socket?.on("it-answer") { args ->
            (args.getOrNull(0) as? JSONObject)?.let { onItAnswer?.invoke(it) }
        }
        socket?.on("it-ice-candidate") { args ->
            (args.getOrNull(0) as? JSONObject)?.let { onItIceCandidate?.invoke(it) }
        }
        socket?.on("session-ended") { onSessionEnded?.invoke() }

        socket?.connect()
    }

    fun joinAsUser(name: String) {
        socket?.emit("user-join", JSONObject().put("name", name))
    }

    fun sendOffer(sdpJson: JSONObject) {
        socket?.emit("user-offer", JSONObject().put("sdp", sdpJson))
    }

    fun sendIceCandidate(candidateJson: JSONObject) {
        socket?.emit("user-ice-candidate", JSONObject().put("candidate", candidateJson))
    }

    fun sendMicToggle(on: Boolean) {
        socket?.emit("user-mic-toggle", JSONObject().put("on", on))
    }

    fun leave() {
        socket?.emit("user-leave")
        socket?.disconnect()
    }
}

private fun Array<Any>.getOrNull(index: Int): Any? = if (index < size) this[index] else null
