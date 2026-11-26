package com.example.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.data.FreeTierQuotaManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

interface WebRtcClientCallbacks {
    fun onLocalOfferCreated(sdp: String)
    fun onLocalAnswerCreated(sdp: String)
    fun onLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun onPeerConnected()
    fun onPeerDisconnected()
    fun onRemoteVideoTrack(track: VideoTrack?)
    fun onError(message: String)
}

/**
 * Lightweight bandwidth-optimized 1-to-1 WebRTC client for audio and video calls.
 * Caps video capture & bitrates to conserve mobile data and stay comfortably within
 * the 20 GB/month free Metered TURN tier.
 */
class WebRtcCallClient(
    context: Context,
    private val isVideoCall: Boolean,
    private val callbacks: WebRtcClientCallbacks
) {
    companion object {
        private const val TAG = "WebRtcCallClient"
        const val MAX_VIDEO_WIDTH = 480
        const val MAX_VIDEO_HEIGHT = 360
        const val MAX_VIDEO_FPS = 18
        const val MAX_VIDEO_BITRATE_BPS = 450_000 // 450 kbps bandwidth-saving cap
        const val MAX_AUDIO_BITRATE_BPS = 32_000  // 32 kbps voice cap
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val disposed = AtomicBoolean(false)

    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localVideoSink: VideoSink? = null
    private var remoteVideoSink: VideoSink? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var usingRelayConnection = false
    private var callStartedAtMs = 0L

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startSession(iceServers: List<PeerConnection.IceServer>) {
        if (disposed.get()) return
        configureAudioRouting(speakerOn = isVideoCall)
        createLocalMediaTracks()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        if (callStartedAtMs == 0L) callStartedAtMs = System.currentTimeMillis()
                        callbacks.onPeerConnected()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        callbacks.onError("Call connection failed. Please check your network and try again.")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        callbacks.onPeerDisconnected()
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null || disposed.get()) return
                if (candidate.sdp.contains(" typ relay ", ignoreCase = true)) {
                    usingRelayConnection = true
                }
                callbacks.onLocalIceCandidate(
                    sdpMid = candidate.sdpMid ?: "0",
                    sdpMLineIndex = candidate.sdpMLineIndex.coerceIn(0, 16),
                    candidate = candidate.sdp
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null) {
                    attachRemoteTrack(track)
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                if (track != null) {
                    attachRemoteTrack(track)
                }
            }
        })

        if (pc == null) {
            callbacks.onError("Could not initialize WebRTC peer connection.")
            return
        }
        peerConnection = pc

        val streamIds = listOf("mychat_stream")
        localAudioTrack?.let { audio ->
            val sender = pc.addTrack(audio, streamIds)
            applyBitrateCap(sender, MAX_AUDIO_BITRATE_BPS)
        }
        localVideoTrack?.let { video ->
            val sender = pc.addTrack(video, streamIds)
            applyBitrateCap(sender, MAX_VIDEO_BITRATE_BPS)
        }
    }

    private fun attachRemoteTrack(track: VideoTrack) {
        remoteVideoTrack = track
        remoteVideoSink?.let { sink ->
            runCatching { track.addSink(sink) }
        }
        callbacks.onRemoteVideoTrack(track)
    }

    fun attachLocalRenderer(sink: VideoSink?) {
        localVideoSink?.let { prev ->
            runCatching { localVideoTrack?.removeSink(prev) }
        }
        localVideoSink = sink
        if (sink != null) {
            runCatching { localVideoTrack?.addSink(sink) }
        }
    }

    fun attachRemoteRenderer(sink: VideoSink?) {
        remoteVideoSink?.let { prev ->
            runCatching { remoteVideoTrack?.removeSink(prev) }
        }
        remoteVideoSink = sink
        if (sink != null) {
            runCatching { remoteVideoTrack?.addSink(sink) }
        }
    }

    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null || disposed.get()) return
                pc.setLocalDescription(simpleSdpObserver("setLocalOffer"), desc)
                callbacks.onLocalOfferCreated(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                callbacks.onError("Offer creation failed: ${err ?: "unknown"}")
            }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(offerSdp: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                if (disposed.get()) return
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
                }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc == null || disposed.get()) return
                        pc.setLocalDescription(simpleSdpObserver("setLocalAnswer"), answerDesc)
                        callbacks.onLocalAnswerCreated(answerDesc.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        callbacks.onError("Answer creation failed: ${err ?: "unknown"}")
                    }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {
                callbacks.onError("Failed to apply incoming call offer: ${err ?: "unknown"}")
            }
        }, remoteDesc)
    }

    fun handleRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(simpleSdpObserver("setRemoteAnswer"), remoteDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val pc = peerConnection ?: return
        runCatching {
            pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        configureAudioRouting(enabled)
    }

    private fun configureAudioRouting(speakerOn: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (speakerOn) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val target = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                if (target != null) {
                    audioManager.setCommunicationDevice(target)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerOn
            }
        }
    }

    private fun createLocalMediaTracks() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("mychat_audio_0", audioSource)
        localAudioTrack?.setEnabled(true)

        if (!isVideoCall) return

        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("MyChatCaptureThread", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, appContext, videoSource?.capturerObserver)
        runCatching {
            capturer.startCapture(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT, MAX_VIDEO_FPS)
        }.onFailure { err ->
            Log.w(TAG, "Camera capture start error: ${err.message}")
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack("mychat_video_0", videoSource)
        localVideoTrack?.setEnabled(true)
        localVideoSink?.let { sink ->
            runCatching { localVideoTrack?.addSink(sink) }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(false)
        }
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
        if (front != null) return front

        return enumerator.deviceNames.firstNotNullOfOrNull { name ->
            enumerator.createCapturer(name, null)
        }
    }

    private fun applyBitrateCap(sender: RtpSender?, maxBitrateBps: Int) {
        if (sender == null) return
        runCatching {
            val params: RtpParameters = sender.parameters
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = maxBitrateBps
            }
            sender.parameters = params
        }
    }

    private fun simpleSdpObserver(label: String): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {
            Log.w(TAG, "$label create failure: $err")
        }
        override fun onSetFailure(err: String?) {
            Log.w(TAG, "$label set failure: $err")
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return

        // Record conservative estimated relay transfer only if TURN relay was used
        if (usingRelayConnection && callStartedAtMs > 0L) {
            val elapsedSec = ((System.currentTimeMillis() - callStartedAtMs) / 1000L).coerceAtLeast(1L)
            val estimatedBytesPerSec = if (isVideoCall) (MAX_VIDEO_BITRATE_BPS / 8L) else (MAX_AUDIO_BITRATE_BPS / 8L)
            FreeTierQuotaManager.recordTurnBytes(elapsedSec * estimatedBytesPerSec)
        }

        runCatching {
            localVideoSink?.let { localVideoTrack?.removeSink(it) }
            remoteVideoSink?.let { remoteVideoTrack?.removeSink(it) }
        }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { localVideoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        runCatching { peerConnectionFactory.dispose() }
        runCatching { eglBase.release() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }
}
