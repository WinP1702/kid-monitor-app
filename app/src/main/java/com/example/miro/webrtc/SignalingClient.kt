package com.example.miro.webrtc

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class SignalingClient(private val deviceId: String) {

    private val db = Firebase.firestore
    private val TAG = "SignalingClient"

    suspend fun registerDevice() {
        val deviceData = hashMapOf(
            "online" to true,
            "lastSeen" to System.currentTimeMillis()
        )
        db.collection("devices").document(deviceId).set(deviceData).await()
    }

    suspend fun sendOffer(targetDeviceId: String, sdp: String) {
        Log.d(TAG, "Sending OFFER to $targetDeviceId")
        val data = hashMapOf(
            "type" to "OFFER",
            "sdp" to sdp,
            "sender" to deviceId,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("signals").document(targetDeviceId).set(data).await()
    }

    suspend fun sendAnswer(targetDeviceId: String, sdp: String) {
        Log.d(TAG, "Sending ANSWER to $targetDeviceId")
        val data = hashMapOf(
            "type" to "ANSWER",
            "sdp" to sdp,
            "sender" to deviceId,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("signals").document(targetDeviceId).set(data).await()
    }

    suspend fun sendIceCandidate(targetDeviceId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "Sending ICE candidate to $targetDeviceId: $sdpMid")
        val data = hashMapOf(
            "type" to "CANDIDATE",
            "sdp" to sdp,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
            "sender" to deviceId,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("signals").document(targetDeviceId).collection("candidates").add(data).await()
    }

    fun observeSignals(): Flow<SignalingMessage> = callbackFlow {
        val startTime = System.currentTimeMillis() - 60000 // 1 minute buffer for clock desync
        val registration = db.collection("signals").document(deviceId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val type = snapshot.getString("type") ?: ""
                    val sdp = snapshot.getString("sdp") ?: ""
                    val sender = snapshot.getString("sender") ?: ""
                    val timestamp = snapshot.getLong("timestamp") ?: 0L

                    if (timestamp > startTime) {
                        Log.d(TAG, "Received signal: $type from $sender")
                        when (type) {
                            "OFFER" -> trySend(SignalingMessage.Offer(sender, sdp))
                            "ANSWER" -> trySend(SignalingMessage.Answer(sender, sdp))
                        }
                    }
                }
            }

        val candidatesRegistration = db.collection("signals").document(deviceId).collection("candidates")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Candidates listen failed: ${e.message}")
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { dc ->
                    if (dc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = dc.document
                        // Do not filter candidates by timestamp - we need every possible path
                        val sdp = doc.getString("sdp") ?: ""
                        val sdpMid = doc.getString("sdpMid") ?: ""
                        val sdpMLineIndex = doc.getLong("sdpMLineIndex")?.toInt() ?: 0
                        val sender = doc.getString("sender") ?: ""

                        Log.d(TAG, "Received ICE candidate from $sender: $sdpMid")
                        trySend(SignalingMessage.IceCandidate(sender, sdp, sdpMid, sdpMLineIndex))
                    }
                }
            }

        awaitClose {
            registration.remove()
            candidatesRegistration.remove()
        }
    }

    suspend fun clearSignals() {
        db.collection("signals").document(deviceId).delete().await()
        
        // Delete candidates collection items
        val candidates = db.collection("signals").document(deviceId).collection("candidates").get().await()
        for (doc in candidates.documents) {
            doc.reference.delete().await()
        }
    }
}

sealed class SignalingMessage {
    data class Offer(val sender: String, val sdp: String) : SignalingMessage()
    data class Answer(val sender: String, val sdp: String) : SignalingMessage()
    data class IceCandidate(val sender: String, val sdp: String, val sdpMid: String, val sdpMLineIndex: Int) : SignalingMessage()
}
