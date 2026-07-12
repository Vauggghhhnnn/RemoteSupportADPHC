package com.adphc.remotesupport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.*

/**
 * CONFIG: point this at the same signaling server the web app already uses.
 */
private const val SERVER_URL = "https://remotesupportadphc.onrender.com"

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var nameInput: EditText
    private lateinit var joinButton: Button
    private lateinit var shareButton: Button
    private lateinit var micButton: Button
    private lateinit var leaveButton: Button

    private lateinit var signaling: SignalingClient
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var screenCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private var isSharing = false
    private var isMicOn = false
    private var userName = "User"

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startScreenShare(result.resultCode, result.data!!)
            } else {
                log("Screen share permission denied")
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableMicTrack() else log("Microphone permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        nameInput = findViewById(R.id.nameInput)
        joinButton = findViewById(R.id.joinButton)
        shareButton = findViewById(R.id.shareButton)
        micButton = findViewById(R.id.micButton)
        leaveButton = findViewById(R.id.leaveButton)

        requestNotificationPermissionIfNeeded()
        initWebRtc()

        joinButton.setOnClickListener { joinSession() }
        shareButton.setOnClickListener { toggleShare() }
        micButton.setOnClickListener { toggleMic() }
        leaveButton.setOnClickListener { leaveSession() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun initWebRtc() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // ─── SIGNALING ──────────────────────────────────────────────

    private fun joinSession() {
        userName = nameInput.text.toString().ifBlank { "User" }
        signaling = SignalingClient(SERVER_URL)

        signaling.onConnected = {
            runOnUiThread {
                statusText.text = "● connected"
                signaling.joinAsUser(userName)
                joinButton.isEnabled = false
                shareButton.isEnabled = true
                micButton.isEnabled = true
                leaveButton.isEnabled = true
                log("Joined as $userName")
            }
            createPeerConnection()
        }
        signaling.onDisconnected = {
            runOnUiThread { statusText.text = "● disconnected" }
        }
        signaling.onItAnswer = { data ->
            val sdpObj = data.getJSONObject("sdp")
            val sdp = SessionDescription(
                SessionDescription.Type.ANSWER, sdpObj.getString("sdp")
            )
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
        }
        signaling.onItIceCandidate = { data ->
            val c = data.getJSONObject("candidate")
            val candidate = IceCandidate(
                c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate")
            )
            peerConnection?.addIceCandidate(candidate)
        }
        signaling.onSessionEnded = {
            runOnUiThread {
                log("IT ended the session")
                leaveSession()
            }
        }
        signaling.connect()
        statusText.text = "● connecting"
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val json = JSONObject()
                        .put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    signaling.sendIceCandidate(json)
                }
            }
        )
    }

    // ─── SCREEN SHARE ───────────────────────────────────────────

    private fun toggleShare() {
        if (isSharing) {
            stopScreenShare()
        } else {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun startScreenShare(resultCode: Int, data: Intent) {
        // Foreground service must be CONFIRMED running BEFORE MediaProjection capture starts
        // (Android 14 / targetSdk 34 requirement). startForegroundService() only requests the
        // start and returns immediately — it does NOT wait for startForeground() to actually
        // run inside the service. Starting capture right after calling it is a race condition
        // that throws a SecurityException on Android 14. So we wait for the service's callback.
        ScreenCaptureService.onForegroundStarted = {
            runOnUiThread { beginCapture(data) }
        }
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
    }

    private fun beginCapture(data: Intent) {
        val capturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                runOnUiThread { stopScreenShare() }
            }
        })

        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(true)
        capturer.initialize(surfaceHelper, applicationContext, videoSource.capturerObserver)

        val metrics = resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)

        val track = peerConnectionFactory.createVideoTrack("screen0", videoSource)
        peerConnection?.addTrack(track, listOf("stream0"))

        screenCapturer = capturer
        videoTrack = track
        isSharing = true

        shareButton.text = "Stop Sharing"
        log("Screen sharing started")
        renegotiate()
    }

    private fun stopScreenShare() {
        ScreenCaptureService.onForegroundStarted = null
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null
        videoTrack?.let { track ->
            peerConnection?.senders?.firstOrNull { it.track() == track }?.let { peerConnection?.removeTrack(it) }
        }
        videoTrack = null
        isSharing = false
        shareButton.text = "Share Screen"
        stopService(Intent(this, ScreenCaptureService::class.java))
        log("Screen sharing stopped")
        renegotiate()
    }

    // ─── MIC ────────────────────────────────────────────────────

    private fun toggleMic() {
        if (isMicOn) {
            disableMicTrack()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                enableMicTrack()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun enableMicTrack() {
        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val track = peerConnectionFactory.createAudioTrack("mic0", audioSource)
        peerConnection?.addTrack(track, listOf("stream0"))
        audioTrack = track
        isMicOn = true
        micButton.text = "Mic: On"
        signaling.sendMicToggle(true)
        log("Mic on")
        renegotiate()
    }

    private fun disableMicTrack() {
        audioTrack?.let { track ->
            peerConnection?.senders?.firstOrNull { it.track() == track }?.let { peerConnection?.removeTrack(it) }
        }
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        isMicOn = false
        micButton.text = "Mic: Off"
        signaling.sendMicToggle(false)
        log("Mic off")
        renegotiate()
    }

    // ─── RENEGOTIATION ──────────────────────────────────────────

    private fun renegotiate() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SimpleSdpObserver() {
       override fun onCreateSuccess(desc: SessionDescription?) {
           if (desc == null) return
           pc.setLocalDescription(SimpleSdpObserver(), desc)
           val sdpJson = JSONObject().put("type", "offer").put("sdp", desc.description)
           signaling.sendOffer(sdpJson)
       }
   }, MediaConstraints())
    }

    // ─── LEAVE ──────────────────────────────────────────────────

    private fun leaveSession() {
        if (isSharing) stopScreenShare()
        if (isMicOn) disableMicTrack()
        peerConnection?.close()
        peerConnection = null
        if (::signaling.isInitialized) signaling.leave()

        joinButton.isEnabled = true
        shareButton.isEnabled = false
        micButton.isEnabled = false
        leaveButton.isEnabled = false
        statusText.text = "● left"
        log("Left session")
    }

    private fun log(msg: String) {
        runOnUiThread { logText.append("$msg\n") }
    }
}
