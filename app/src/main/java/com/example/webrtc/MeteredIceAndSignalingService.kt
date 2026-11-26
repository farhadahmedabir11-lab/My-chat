package com.example.webrtc

import android.util.Log
import com.example.BuildConfig
import com.example.data.FreeTierQuotaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class IceResolutionResult(
    val iceServers: List<PeerConnection.IceServer>,
    val usingTurnRelay: Boolean,
    val statusNote: String
)

/**
 * Provides free-tier ICE (Google STUN + optional Metered Open Relay free TURN 20 GB/mo)
 * and optional Metered Realtime signaling without hardcoding secrets or enabling paid billing.
 */
object MeteredIceAndSignalingService {
    private const val TAG = "MeteredIceService"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun configuredValue(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.startsWith("MY_", ignoreCase = true)) return null
        return trimmed
    }

    fun isMeteredTurnConfigured(): Boolean {
        val domain = configuredValue(BuildConfig.METERED_APP_DOMAIN)
        val apiKey = configuredValue(BuildConfig.METERED_API_KEY)
        val manualUrl = configuredValue(BuildConfig.TURN_SERVER_URL)
        return (domain != null && apiKey != null) || manualUrl != null
    }

    fun isMeteredRealtimeConfigured(): Boolean =
        configuredValue(BuildConfig.METERED_REALTIME_API_KEY) != null

    fun defaultFreeStunServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    suspend fun resolveFreeIceServers(): IceResolutionResult = withContext(Dispatchers.IO) {
        val servers = defaultFreeStunServers().toMutableList()

        if (FreeTierQuotaManager.canUseTurnServer().isFailure) {
            return@withContext IceResolutionResult(
                iceServers = servers,
                usingTurnRelay = false,
                statusNote = "Free STUN only (monthly 20 GB TURN quota reached)"
            )
        }

        val domain = configuredValue(BuildConfig.METERED_APP_DOMAIN)
        val apiKey = configuredValue(BuildConfig.METERED_API_KEY)
        if (domain != null && apiKey != null) {
            val cleanDomain = domain
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
            val url = "https://$cleanDomain/api/v1/turn/credentials?apiKey=$encodedKey"
            val request = Request.Builder().url(url).get().build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 402 || response.code == 403 || response.code == 429) {
                        FreeTierQuotaManager.markTurnQuotaExceeded(
                            "Metered TURN free quota limit reached (HTTP ${response.code}). Relay stopped; using free STUN."
                        )
                        return@withContext IceResolutionResult(
                            iceServers = servers,
                            usingTurnRelay = false,
                            statusNote = "Free STUN only (Metered TURN quota reached)"
                        )
                    }

                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val parsed = parseTurnArray(body)
                        if (parsed.isNotEmpty()) {
                            servers.addAll(parsed)
                            return@withContext IceResolutionResult(
                                iceServers = servers,
                                usingTurnRelay = true,
                                statusNote = "Free STUN + Metered Open Relay TURN active"
                            )
                        }
                    } else {
                        Log.w(TAG, "Metered TURN lookup returned HTTP ${response.code}; falling back.")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Metered TURN lookup error: ${e.message}")
            }
        }

        // Optional standalone TURN configuration via Secrets panel
        val manualUrl = configuredValue(BuildConfig.TURN_SERVER_URL)
        val manualUser = configuredValue(BuildConfig.TURN_USERNAME)
        val manualCred = configuredValue(BuildConfig.TURN_CREDENTIAL)
        if (manualUrl != null) {
            val builder = PeerConnection.IceServer.builder(manualUrl)
            if (manualUser != null && manualCred != null) {
                builder.setUsername(manualUser).setPassword(manualCred)
            }
            servers.add(builder.createIceServer())
            return@withContext IceResolutionResult(
                iceServers = servers,
                usingTurnRelay = true,
                statusNote = "Free STUN + configured TURN active"
            )
        }

        IceResolutionResult(
            iceServers = servers,
            usingTurnRelay = false,
            statusNote = "Free STUN P2P active (add Metered secrets for relay fallback)"
        )
    }

    private fun parseTurnArray(jsonString: String): List<PeerConnection.IceServer> {
        if (jsonString.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(jsonString)
            val result = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val urlsVal = obj.opt("urls")
                val urlList = when (urlsVal) {
                    is String -> listOf(urlsVal)
                    is JSONArray -> (0 until urlsVal.length()).mapNotNull { idx ->
                        urlsVal.optString(idx).takeIf { it.isNotBlank() }
                    }
                    else -> emptyList()
                }
                if (urlList.isEmpty()) continue
                val username = obj.optString("username", "")
                val credential = obj.optString("credential", "")
                val builder = PeerConnection.IceServer.builder(urlList)
                if (username.isNotBlank() && credential.isNotBlank()) {
                    builder.setUsername(username).setPassword(credential)
                }
                result.add(builder.createIceServer())
            }
            result
        }.getOrDefault(emptyList())
    }

    /**
     * Optional Metered Realtime WebSocket signaling channel when METERED_REALTIME_API_KEY
     * is configured in Secrets. Automatically stops on quota errors (402/403/429).
     */
    fun openRealtimeSignalingChannel(
        roomId: String,
        onSignalReceived: (JSONObject) -> Unit
    ): WebSocket? {
        val realtimeKey = configuredValue(BuildConfig.METERED_REALTIME_API_KEY) ?: return null
        if (FreeTierQuotaManager.canUseMeteredRealtime().isFailure) return null

        val encodedRoom = URLEncoder.encode("mychat_$roomId", Charsets.UTF_8.name())
        val encodedKey = URLEncoder.encode(realtimeKey, Charsets.UTF_8.name())
        val wsUrl = "wss://realtime.metered.ca/ws?room=$encodedRoom&apiKey=$encodedKey"
        val request = Request.Builder().url(wsUrl).build()

        return try {
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    FreeTierQuotaManager.recordRealtimeMessage(1L)
                    runCatching { JSONObject(text) }.onSuccess { json ->
                        onSignalReceived(json)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    if (code == 402 || code == 403 || code == 429) {
                        FreeTierQuotaManager.markRealtimeQuotaExceeded(
                            "Metered Realtime free quota reached (HTTP $code). Using Firestore signaling."
                        )
                    } else {
                        Log.d(TAG, "Metered Realtime WS closed/unavailable: ${t.message}")
                    }
                }
            })
        } catch (_: Exception) {
            null
        }
    }
}
