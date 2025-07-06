package com.screenlink.newapp

import android.util.Log
import okhttp3.*
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient {
    companion object {
        private const val TAG = "SignalingClient"
        private const val SERVER_URL = "ws://192.168.1.2:6060" // 根据你的服务器IP修改
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var messageListener: SignalingMessageListener? = null
    private var isConnected = false

    interface SignalingMessageListener {
        fun onConnected()
        fun onDisconnected()
        fun onSenderList(senders: List<SenderInfo>)
        fun onOffer(offer: String, senderId: Int)
        fun onAnswer(answer: String)
        fun onIceCandidate(candidate: String)
        fun onConnectRequest(sourceClientId: Int)
        fun onError(message: String)
    }

    data class SenderInfo(
        val id: Int,
        val name: String,
        val available: Boolean
    )

    fun connect(listener: SignalingMessageListener) {
        this.messageListener = listener
        
        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接已建立")
                isConnected = true
                messageListener?.onConnected()
                
                // 连接成功后请求发送端列表
                requestSenderList()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到消息: $text")
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket连接已关闭: $code - $reason")
                isConnected = false
                messageListener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.message}")
                isConnected = false
                messageListener?.onError("连接失败: ${t.message}")
            }
        })
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            when (type) {
                "sender_list" -> {
                    val sendersArray = json.optJSONArray("senders")
                    val senders = mutableListOf<SenderInfo>()
                    
                    if (sendersArray != null) {
                        for (i in 0 until sendersArray.length()) {
                            val sender = sendersArray.getJSONObject(i)
                            senders.add(
                                SenderInfo(
                                    id = sender.optInt("id"),
                                    name = sender.optString("name"),
                                    available = sender.optBoolean("available", true)
                                )
                            )
                        }
                    }
                    
                    messageListener?.onSenderList(senders)
                }
                
                "connect_request" -> {
                    val sourceClientId = json.optInt("sourceClientId", -1)
                    messageListener?.onConnectRequest(sourceClientId)
                }
                
                "offer" -> {
                    val offer = json.optString("sdp")
                    val senderId = json.optInt("senderId", -1)
                    messageListener?.onOffer(offer, senderId)
                }
                
                "answer" -> {
                    val answer = json.optString("sdp")
                    messageListener?.onAnswer(answer)
                }
                
                "ice_candidate" -> {
                    val candidate = json.optString("candidate")
                    messageListener?.onIceCandidate(candidate)
                }
                
                "error" -> {
                    val errorMessage = json.optString("message", "未知错误")
                    messageListener?.onError(errorMessage)
                }
                
                "info" -> {
                    Log.d(TAG, "服务器信息: ${json.optString("message")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: ${e.message}")
            messageListener?.onError("消息解析失败: ${e.message}")
        }
    }

    fun requestSenderList() {
        val message = JSONObject().apply {
            put("type", "request_senders")
        }
        sendMessage(message.toString())
    }

    fun registerAsSender(name: String) {
        val message = JSONObject().apply {
            put("type", "register_sender")
            put("name", name)
        }
        sendMessage(message.toString())
    }

    fun sendAnswer(answer: String, selectedSenderId: Int) {
        val message = JSONObject().apply {
            put("type", "answer")
            put("sdp", answer)
            put("selectedSenderId", selectedSenderId)
        }
        sendMessage(message.toString())
    }

    fun sendIceCandidate(candidate: String, selectedSenderId: Int) {
        val message = JSONObject().apply {
            put("type", "ice_candidate")
            put("candidate", candidate)
            put("selectedSenderId", selectedSenderId)
        }
        sendMessage(message.toString())
    }

    fun sendOffer(offer: String, targetReceiverId: Int? = null) {
        val message = JSONObject().apply {
            put("type", "offer")
            put("sdp", offer)
            if (targetReceiverId != null) {
                put("targetReceiverId", targetReceiverId)
            }
        }
        sendMessage(message.toString())
    }

    fun sendConnectRequest(targetSenderId: Int) {
        val message = JSONObject().apply {
            put("type", "connect_request")
            put("sourceClientId", 0) // 接收端ID，这里用0表示
            put("targetClientId", targetSenderId)
        }
        sendMessage(message.toString())
    }

    private fun sendMessage(message: String) {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "发送消息: $message")
            webSocket?.send(message)
        } else {
            Log.e(TAG, "WebSocket未连接，无法发送消息")
            messageListener?.onError("WebSocket未连接")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "正常关闭")
        webSocket = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected
} 