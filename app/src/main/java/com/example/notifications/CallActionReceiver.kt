package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.R
import com.example.model.CallOutcome
import com.example.model.CallStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChatNotificationHelper.ACTION_REJECT_CALL) return
        val callId = intent.getStringExtra(ChatNotificationHelper.EXTRA_CALL_ID) ?: return
        ChatNotificationHelper.dismissCallNotification(context, callId)

        if (Firebase.auth.currentUser == null) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val now = FieldValue.serverTimestamp()
                db.collection("calls").document(callId).update(
                    mapOf(
                        "status" to CallStatus.REJECTED.name,
                        "endedAt" to now,
                        "durationSeconds" to 0L,
                        "updatedAt" to now
                    )
                ).await()
                runCatching {
                    db.collection("callHistory").document(callId).update(
                        mapOf(
                            "outcome" to CallOutcome.REJECTED.name,
                            "updatedAt" to now
                        )
                    ).await()
                }
            } catch (_: Exception) {
                // Best-effort decline from notification shade
            } finally {
                pendingResult.finish()
            }
        }
    }
}
