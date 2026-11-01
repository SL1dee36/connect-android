package com.connect.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.connect.app.ConnectApplication
import com.connect.app.data.socket.SocketManager
import com.connect.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.util.LinkedList

enum class CallState {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTED,
    ENDED
}

class WebRtcCallManager(
    private val context: Context,
    private val socketManager: SocketManager
) {
    private val tag = "WebRtcCallManager"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    val rootEglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null

    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val queuedIceCandidates = LinkedList<IceCandidate>()

    private var durationTimerJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Observable states
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _peerUsername = MutableStateFlow("")
    val peerUsername: StateFlow<String> = _peerUsername.asStateFlow()

    private val _peerDisplayName = MutableStateFlow("")
    val peerDisplayName: StateFlow<String> = _peerDisplayName.asStateFlow()

    private val _currentRoom = MutableStateFlow("")
    val currentRoom: StateFlow<String> = _currentRoom.asStateFlow()

    private val _callType = MutableStateFlow("audio")
    val callType: StateFlow<String> = _callType.asStateFlow()

    private val _callDuration = MutableStateFlow(0)
    val callDuration: StateFlow<Int> = _callDuration.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _localTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localTrackFlow: StateFlow<VideoTrack?> = _localTrackFlow.asStateFlow()

    private val _remoteTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val remoteTrackFlow: StateFlow<VideoTrack?> = _remoteTrackFlow.asStateFlow()

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(targetUser: String): PeerConnection? {
        peerConnection?.close()
        queuedIceCandidates.clear()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val json = JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                    socketManager.sendIceCandidate(targetUser, json)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(tag, "PeerConnection state changed: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _callState.value = CallState.CONNECTED
                        startDurationTimer()
                        NotificationHelper.cancelCallNotification(context)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        endCall(notifyServer = false)
                    }
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    Log.d(tag, "Received remote VideoTrack")
                    _remoteTrackFlow.value = track
                } else if (track is AudioTrack) {
                    Log.d(tag, "Received remote AudioTrack")
                    remoteAudioTrack = track
                    track.setEnabled(true)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
        return peerConnection
    }

    private fun setupMediaTracks(includeVideo: Boolean) {
        val f = factory ?: return

        // 1. Audio
        val audioConstraints = MediaConstraints()
        audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("local_audio_${System.currentTimeMillis()}", audioSource)
        localAudioTrack?.setEnabled(!_isMicMuted.value)

        // 2. Video
        if (includeVideo) {
            val enumerator = Camera2Enumerator(context)
            val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()

            if (frontCamera != null) {
                videoCapturer = enumerator.createCapturer(frontCamera, null)
                val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
                videoSource = f.createVideoSource(videoCapturer!!.isScreencast)
                videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                videoCapturer?.startCapture(1280, 720, 30)

                localVideoTrack = f.createVideoTrack("local_video_${System.currentTimeMillis()}", videoSource)
                localVideoTrack?.setEnabled(true)
                _localTrackFlow.value = localVideoTrack
            }
        }
    }

    fun startCall(targetUser: String, targetDisplayName: String, callType: String, room: String) {
        if (_callState.value != CallState.IDLE) return

        _peerUsername.value = targetUser
        _peerDisplayName.value = targetDisplayName.ifBlank { targetUser }
        _currentRoom.value = room
        _callType.value = callType
        _callState.value = CallState.OUTGOING
        _callDuration.value = 0

        acquireAudioSettings(isSpeaker = callType == "video")

        val pc = createPeerConnection(targetUser) ?: return
        val isVideo = callType == "video"
        setupMediaTracks(isVideo)

        localAudioTrack?.let { pc.addTrack(it, listOf("media_stream")) }
        localVideoTrack?.let { pc.addTrack(it, listOf("media_stream")) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val sdpJson = JSONObject().apply {
                            put("type", desc.type.canonicalForm())
                            put("sdp", desc.description)
                        }
                        val myName = ConnectApplication.instance.prefs.getCurrentUser()?.displayName
                            ?: ConnectApplication.instance.prefs.getCurrentUser()?.username
                            ?: ""
                        socketManager.initiateCall(
                            userToCall = targetUser,
                            signalData = sdpJson,
                            callType = callType,
                            room = room,
                            name = myName
                        )
                    }

                    override fun onSetFailure(p0: String?) {
                        Log.e(tag, "Failed to set local desc offer: $p0")
                        endCall()
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(tag, "Failed to create offer: $p0")
                endCall()
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun handleIncomingCall(data: JSONObject) {
        val caller = data.optString("from", data.optString("caller", ""))
        val callerName = data.optString("name", data.optString("callerName", caller))
        val room = data.optString("room", "")
        val type = data.optString("callType", "audio")
        val signalObj = data.optJSONObject("signal")

        if (caller.isBlank() || signalObj == null) return

        if (_callState.value != CallState.IDLE) {
            socketManager.rejectCall(caller, room, type)
            return
        }

        _peerUsername.value = caller
        _peerDisplayName.value = callerName
        _currentRoom.value = room
        _callType.value = type
        _callState.value = CallState.INCOMING
        _callDuration.value = 0

        NotificationHelper.showIncomingCallNotification(
            context = context,
            caller = caller,
            callerName = callerName,
            callType = type,
            room = room
        )

        // Store remote offer
        val sdp = signalObj.optString("sdp")
        val sdpType = signalObj.optString("type", "offer")
        val pc = createPeerConnection(caller) ?: return

        val sessionDesc = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpType), sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                drainIceCandidates()
            }

            override fun onSetFailure(p0: String?) {
                Log.e(tag, "Failed to set remote offer: $p0")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDesc)
    }

    fun answerCall() {
        val caller = _peerUsername.value
        val pc = peerConnection
        if (caller.isBlank() || pc == null || _callState.value != CallState.INCOMING) return

        NotificationHelper.cancelCallNotification(context)

        val isVideo = _callType.value == "video"
        acquireAudioSettings(isSpeaker = isVideo)
        setupMediaTracks(isVideo)

        localAudioTrack?.let { pc.addTrack(it, listOf("media_stream")) }
        localVideoTrack?.let { pc.addTrack(it, listOf("media_stream")) }

        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val sdpJson = JSONObject().apply {
                            put("type", desc.type.canonicalForm())
                            put("sdp", desc.description)
                        }
                        socketManager.answerCall(
                            caller = caller,
                            signalData = sdpJson,
                            room = _currentRoom.value,
                            callType = _callType.value
                        )
                        _callState.value = CallState.CONNECTED
                        startDurationTimer()
                    }

                    override fun onSetFailure(p0: String?) {
                        Log.e(tag, "Failed to set local desc answer: $p0")
                        endCall()
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(tag, "Failed to create answer: $p0")
                endCall()
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun handleCallAccepted(data: JSONObject) {
        val signalObj = data.optJSONObject("signal") ?: return
        val sdp = signalObj.optString("sdp")
        val sdpType = signalObj.optString("type", "answer")
        val pc = peerConnection ?: return

        val sessionDesc = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpType), sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(tag, "Call accepted remote desc set successfully")
                _callState.value = CallState.CONNECTED
                startDurationTimer()
                drainIceCandidates()
            }

            override fun onSetFailure(p0: String?) {
                Log.e(tag, "Failed to set remote answer: $p0")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDesc)
    }

    fun handleRemoteIceCandidate(candidateJson: JSONObject) {
        val sdp = candidateJson.optString("candidate")
        val sdpMid = candidateJson.optString("sdpMid")
        val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0)

        if (sdp.isBlank()) return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)

        val pc = peerConnection
        if (pc != null && pc.remoteDescription != null) {
            pc.addIceCandidate(candidate)
        } else {
            queuedIceCandidates.add(candidate)
        }
    }

    private fun drainIceCandidates() {
        val pc = peerConnection ?: return
        while (queuedIceCandidates.isNotEmpty()) {
            val candidate = queuedIceCandidates.poll()
            if (candidate != null) {
                pc.addIceCandidate(candidate)
            }
        }
    }

    fun toggleMute() {
        val newMute = !_isMicMuted.value
        _isMicMuted.value = newMute
        localAudioTrack?.setEnabled(!newMute)
    }

    fun toggleVideo() {
        val newVideo = !_isVideoEnabled.value
        _isVideoEnabled.value = newVideo
        localVideoTrack?.setEnabled(newVideo)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        audioManager.isSpeakerphoneOn = newSpeaker
    }

    fun rejectIncomingCall() {
        val caller = _peerUsername.value
        val room = _currentRoom.value
        val type = _callType.value
        if (caller.isNotBlank()) {
            socketManager.rejectCall(caller, room, type)
        }
        NotificationHelper.cancelCallNotification(context)
        endCall(notifyServer = false)
    }

    fun endCall(notifyServer: Boolean = true) {
        NotificationHelper.cancelCallNotification(context)
        val peer = _peerUsername.value
        val room = _currentRoom.value
        val type = _callType.value
        val duration = _callDuration.value

        if (notifyServer && peer.isNotBlank()) {
            if (_callState.value == CallState.OUTGOING) {
                socketManager.cancelCall(peer, room, type)
            } else if (_callState.value == CallState.CONNECTED) {
                socketManager.endCall(peer, room, type, duration)
            }
        }

        cleanup()
        _callState.value = CallState.ENDED

        scope.launch {
            delay(1000)
            _callState.value = CallState.IDLE
        }
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        _callDuration.value = 0
        durationTimerJob = scope.launch {
            while (isActive && _callState.value == CallState.CONNECTED) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    private fun acquireAudioSettings(isSpeaker: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = isSpeaker
            _isSpeakerOn.value = isSpeaker
        } catch (_: Exception) {}
    }

    private fun releaseAudioSettings() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}
    }

    private fun cleanup() {
        durationTimerJob?.cancel()
        durationTimerJob = null

        releaseAudioSettings()

        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        _localTrackFlow.value = null

        videoSource?.dispose()
        videoSource = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        audioSource?.dispose()
        audioSource = null

        remoteAudioTrack = null
        remoteVideoTrack = null
        _remoteTrackFlow.value = null

        peerConnection?.close()
        peerConnection = null

        queuedIceCandidates.clear()
    }
}
