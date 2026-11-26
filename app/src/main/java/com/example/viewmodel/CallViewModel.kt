package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CallRepository
import com.example.data.FreeTierQuotaManager
import com.example.data.friendlyFirestoreMessage
import com.example.model.CallHistoryEntry
import com.example.model.CallOutcome
import com.example.model.CallSession
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.model.UserAccount
import com.example.notifications.ChatNotificationHelper
import com.example.webrtc.MeteredIceAndSignalingService
import com.example.webrtc.WebRtcCallClient
import com.example.webrtc.WebRtcClientCallbacks
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

enum class CallStage {
    IDLE,
    RINGING_OUTGOING,
    RINGING_INCOMING,
    CONNECTING,
    CONNECTED
}

data class ActiveCallUiState(
    val session: CallSession? = null,
    val stage: CallStage = CallStage.IDLE,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val elapsedSeconds: Long = 0L,
    val iceStatusNote: String = "Free Google STUN",
    val errorBanner: String? = null
)

class CallViewModel(
    private val callRepository: CallRepository,
    private val appContext: Context,
    private val currentUserId: String
) : ViewModel() {

    val callHistoryState: StateFlow<UiState<List<CallHistoryEntry>>> =
        callRepository.observeCallHistory(currentUserId)
            .map<List<CallHistoryEntry>, UiState<List<CallHistoryEntry>>> { UiState.Success(it) }
            .catch { error ->
                val fsEx = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsEx == null && error is CancellationException) throw error
                emit(UiState.Error(friendlyFirestoreMessage(fsEx ?: error, "Failed to load call history.")))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    private val _incomingCall = MutableStateFlow<CallSession?>(null)
    val incomingCall: StateFlow<CallSession?> = _incomingCall.asStateFlow()

    private val _activeCallState = MutableStateFlow(ActiveCallUiState())
    val activeCallState: StateFlow<ActiveCallUiState> = _activeCallState.asStateFlow()

    private val _hasRemoteVideo = MutableStateFlow(false)
    val hasRemoteVideo: StateFlow<Boolean> = _hasRemoteVideo.asStateFlow()

    private var webRtcClient: WebRtcCallClient? = null
    private var realtimeSocket: WebSocket? = null
    private var sessionObserverJob: Job? = null
    private var iceObserverJob: Job? = null
    private var durationTimerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private val addedCandidateIds = mutableSetOf<String>()
    private var appliedAnswer = false
    private var appliedOffer = false

    init {
        viewModelScope.launch {
            callRepository.observeIncomingCalls(currentUserId)
                .catch { /* Keep passive observer resilient */ }
                .collect { incomingList ->
                    val currentActiveId = _activeCallState.value.session?.id
                    val nextIncoming = incomingList.firstOrNull { it.id != currentActiveId }
                    _incomingCall.value = nextIncoming
                }
        }
    }

    fun getEglBaseContext(): EglBase.Context? = webRtcClient?.eglBase?.eglBaseContext

    fun attachLocalRenderer(sink: VideoSink?) {
        webRtcClient?.attachLocalRenderer(sink)
    }

    fun attachRemoteRenderer(sink: VideoSink?) {
        webRtcClient?.attachRemoteRenderer(sink)
    }

    fun startOutgoingCall(
        caller: UserAccount,
        callee: PublicProfile,
        callType: CallType
    ) {
        if (_activeCallState.value.session != null) return
        val check = FreeTierQuotaManager.canPerformFirestoreWrite(2L)
        if (check.isFailure) {
            _activeCallState.value = ActiveCallUiState(
                errorBanner = check.exceptionOrNull()?.message
            )
            return
        }

        viewModelScope.launch {
            val created = callRepository.createCallSession(caller, callee, callType)
            created.onFailure { err ->
                _activeCallState.value = ActiveCallUiState(
                    errorBanner = friendlyFirestoreMessage(err, "Unable to place call.")
                )
            }.onSuccess { session ->
                appliedOffer = false
                appliedAnswer = false
                addedCandidateIds.clear()
                _hasRemoteVideo.value = false
                _activeCallState.value = ActiveCallUiState(
                    session = session,
                    stage = CallStage.RINGING_OUTGOING,
                    isSpeakerOn = callType == CallType.VIDEO,
                    isCameraEnabled = callType == CallType.VIDEO
                )
                setupWebRtcAndSignaling(session, isCaller = true)
                startRingTimeout(session)
            }
        }
    }

    fun acceptIncomingCall(session: CallSession) {
        if (_activeCallState.value.session?.id == session.id) return
        ChatNotificationHelper.dismissCallNotification(appContext, session.id)
        _incomingCall.value = null
        appliedOffer = false
        appliedAnswer = false
        addedCandidateIds.clear()
        _hasRemoteVideo.value = false

        _activeCallState.value = ActiveCallUiState(
            session = session,
            stage = CallStage.CONNECTING,
            isSpeakerOn = session.parsedType == CallType.VIDEO,
            isCameraEnabled = session.parsedType == CallType.VIDEO
        )
        setupWebRtcAndSignaling(session, isCaller = false)
    }

    fun rejectIncomingCall(session: CallSession) {
        ChatNotificationHelper.dismissCallNotification(appContext, session.id)
        if (_incomingCall.value?.id == session.id) {
            _incomingCall.value = null
        }
        viewModelScope.launch {
            callRepository.updateCallTerminalState(
                session = session,
                newStatus = CallStatus.REJECTED,
                durationSeconds = 0L,
                outcome = CallOutcome.REJECTED
            )
        }
    }

    fun endActiveCall() {
        val current = _activeCallState.value
        val session = current.session ?: return
        val wasConnected = current.stage == CallStage.CONNECTED
        val isCaller = session.callerId == currentUserId
        val newStatus = if (wasConnected || !isCaller) CallStatus.ENDED else CallStatus.MISSED
        val outcome = when {
            wasConnected -> CallOutcome.COMPLETED
            isCaller -> CallOutcome.CANCELLED
            else -> CallOutcome.MISSED
        }
        cleanupActiveCall()
        viewModelScope.launch {
            callRepository.updateCallTerminalState(
                session = session,
                newStatus = newStatus,
                durationSeconds = current.elapsedSeconds,
                outcome = outcome
            )
        }
    }

    fun toggleMute() {
        val next = !_activeCallState.value.isMuted
        webRtcClient?.setMicMuted(next)
        _activeCallState.value = _activeCallState.value.copy(isMuted = next)
    }

    fun toggleSpeaker() {
        val next = !_activeCallState.value.isSpeakerOn
        webRtcClient?.setSpeakerEnabled(next)
        _activeCallState.value = _activeCallState.value.copy(isSpeakerOn = next)
    }

    fun toggleCamera() {
        val next = !_activeCallState.value.isCameraEnabled
        webRtcClient?.setCameraEnabled(next)
        _activeCallState.value = _activeCallState.value.copy(isCameraEnabled = next)
    }

    fun switchCamera() {
        webRtcClient?.switchCamera()
    }

    fun clearErrorBanner() {
        _activeCallState.value = _activeCallState.value.copy(errorBanner = null)
    }

    private fun setupWebRtcAndSignaling(session: CallSession, isCaller: Boolean) {
        viewModelScope.launch {
            val iceResult = MeteredIceAndSignalingService.resolveFreeIceServers()
            if (_activeCallState.value.session?.id != session.id) return@launch

            _activeCallState.value = _activeCallState.value.copy(iceStatusNote = iceResult.statusNote)

            val isVideo = session.parsedType == CallType.VIDEO
            val client = WebRtcCallClient(
                context = appContext,
                isVideoCall = isVideo,
                callbacks = object : WebRtcClientCallbacks {
                    override fun onLocalOfferCreated(sdp: String) {
                        viewModelScope.launch {
                            callRepository.publishOffer(session.id, sdp)
                        }
                    }

                    override fun onLocalAnswerCreated(sdp: String) {
                        viewModelScope.launch {
                            callRepository.acceptCallAndPublishAnswer(session.id, sdp)
                        }
                    }

                    override fun onLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                        viewModelScope.launch {
                            callRepository.addIceCandidate(
                                callId = session.id,
                                participantIds = session.participantIds,
                                sdpMid = sdpMid,
                                sdpMLineIndex = sdpMLineIndex,
                                candidate = candidate
                            )
                        }
                    }

                    override fun onPeerConnected() {
                        ringTimeoutJob?.cancel()
                        if (_activeCallState.value.stage != CallStage.CONNECTED) {
                            _activeCallState.value = _activeCallState.value.copy(stage = CallStage.CONNECTED)
                            startDurationTimer()
                        }
                    }

                    override fun onPeerDisconnected() {
                        if (_activeCallState.value.session?.id == session.id) {
                            endActiveCall()
                        }
                    }

                    override fun onRemoteVideoTrack(track: VideoTrack?) {
                        _hasRemoteVideo.value = track != null
                    }

                    override fun onError(message: String) {
                        _activeCallState.value = _activeCallState.value.copy(errorBanner = message)
                    }
                }
            )
            webRtcClient = client
            client.startSession(iceResult.iceServers)

            realtimeSocket = MeteredIceAndSignalingService.openRealtimeSignalingChannel(
                roomId = session.id,
                onSignalReceived = { /* Supplementary free signaling hook */ }
            )

            if (isCaller) {
                client.createOffer()
            } else if (!session.offerSdp.isNullOrBlank()) {
                appliedOffer = true
                client.handleRemoteOfferAndCreateAnswer(session.offerSdp)
            }

            observeActiveCallDocument(session.id, isCaller)
            observeRemoteIceCandidates(session.id)
        }
    }

    private fun observeActiveCallDocument(callId: String, isCaller: Boolean) {
        sessionObserverJob?.cancel()
        sessionObserverJob = viewModelScope.launch {
            callRepository.observeCallSession(callId)
                .catch { /* Resilient listener */ }
                .collect { updated ->
                    if (updated == null) return@collect
                    val current = _activeCallState.value
                    if (current.session?.id != callId) return@collect

                    _activeCallState.value = current.copy(session = updated)

                    if (updated.parsedStatus.isTerminal) {
                        cleanupActiveCall()
                        return@collect
                    }

                    if (!isCaller && !appliedOffer && !updated.offerSdp.isNullOrBlank()) {
                        appliedOffer = true
                        webRtcClient?.handleRemoteOfferAndCreateAnswer(updated.offerSdp)
                    }

                    if (isCaller && !appliedAnswer && !updated.answerSdp.isNullOrBlank()) {
                        appliedAnswer = true
                        ringTimeoutJob?.cancel()
                        _activeCallState.value = _activeCallState.value.copy(stage = CallStage.CONNECTING)
                        webRtcClient?.handleRemoteAnswer(updated.answerSdp)
                    }
                }
        }
    }

    private fun observeRemoteIceCandidates(callId: String) {
        iceObserverJob?.cancel()
        iceObserverJob = viewModelScope.launch {
            callRepository.observeIceCandidates(callId, currentUserId)
                .catch { /* Resilient listener */ }
                .collect { candidates ->
                    candidates.forEach { entry ->
                        if (addedCandidateIds.add(entry.id)) {
                            webRtcClient?.addRemoteIceCandidate(
                                sdpMid = entry.sdpMid,
                                sdpMLineIndex = entry.sdpMLineIndex,
                                candidate = entry.candidate
                            )
                        }
                    }
                }
        }
    }

    private fun startRingTimeout(session: CallSession) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = viewModelScope.launch {
            delay(45_000L)
            val current = _activeCallState.value
            if (current.session?.id == session.id && current.stage == CallStage.RINGING_OUTGOING) {
                cleanupActiveCall()
                callRepository.updateCallTerminalState(
                    session = session,
                    newStatus = CallStatus.MISSED,
                    durationSeconds = 0L,
                    outcome = CallOutcome.MISSED
                )
            }
        }
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _activeCallState.value = _activeCallState.value.copy(
                    elapsedSeconds = _activeCallState.value.elapsedSeconds + 1L
                )
            }
        }
    }

    private fun cleanupActiveCall() {
        sessionObserverJob?.cancel()
        iceObserverJob?.cancel()
        durationTimerJob?.cancel()
        ringTimeoutJob?.cancel()
        runCatching { realtimeSocket?.close(1000, "Call ended") }
        realtimeSocket = null
        webRtcClient?.dispose()
        webRtcClient = null
        _hasRemoteVideo.value = false
        _activeCallState.value = ActiveCallUiState()
    }

    override fun onCleared() {
        super.onCleared()
        cleanupActiveCall()
    }
}
