package com.screenlink.newapp

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * WebRTC管理器，负责WebRTC的初始化、连接管理和信令处理
 */
class WebRTCManager(private val context: Context) {
    
    // WebRTC相关变量
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var isConnected = false
    private var serverAddress = ""
    
    // 新增：所有在线客户端信息
    data class ClientInfo(val id: Int, val name: String)
    
    // 回调接口
    interface WebRTCListener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onSenderListReceived(senders: List<SenderInfo>)
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidate: String, sdpMLineIndex: Int, sdpMid: String)
        fun onRequestOffer()
        fun onClientListReceived(clients: List<ClientInfo>)
    }
    
    private var listener: WebRTCListener? = null
    
    // 发送端信息数据类
    data class SenderInfo(
        val id: Int,
        val name: String,
        val timestamp: Long,
        val available: Boolean
    )
    
    var myClientId: Int? = null
    var myClientName: String = android.os.Build.MODEL
    var selectedTargetClientId: Int? = null
    
    private var peerConnectionManager: PeerConnectionManager? = null
    
    // 连接状态监听器
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }
    
    private var connectionListener: ConnectionListener? = null
    
    companion object {
        private const val TAG = "WebRTCManager"
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: WebRTCListener) {
        this.listener = listener
    }
    
    /**
     * 设置连接状态监听器
     */
    fun setConnectionListener(listener: ConnectionListener) {
        this.connectionListener = listener
    }
    
    /**
     * 初始化WebRTC
     */
    fun initialize() {
        try {
            Log.d(TAG, "开始初始化WebRTC")
            
            // 初始化EglBase
            eglBase = EglBase.create()
            
            // 初始化PeerConnectionFactory
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            
            val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            
            factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(videoDecoderFactory)
                .setVideoEncoderFactory(videoEncoderFactory)
                .createPeerConnectionFactory()
                
            Log.d(TAG, "WebRTC初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化WebRTC失败", e)
        }
    }
    
    /**
     * 连接到信令服务器
     */
    fun connectToSignalingServer(address: String) {
        try {
            serverAddress = address
            Log.d(TAG, "开始连接到信令服务器: $address")
            
            val uri = URI("ws://$serverAddress")
            ws = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "WebSocket连接已建立")
                    isConnected = true
                    listener?.onConnectionStateChanged(true)
                    
                    // 连接成功后立即请求发送端列表
                    requestSenderList()
                    
                    // 连接成功后自动注册为在线客户端
                    registerAsClient("Android设备")
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "收到消息: $message")
                    handleSignalingMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket连接已关闭: $code, $reason")
                    isConnected = false
                    listener?.onConnectionStateChanged(false)
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket错误", ex)
                    isConnected = false
                    listener?.onConnectionStateChanged(false)
                }
            }
            
            ws?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
        }
    }
    
    /**
     * 断开信令服务器连接
     */
    fun disconnectFromSignalingServer() {
        try {
            ws?.close()
            ws = null
            isConnected = false
            Log.d(TAG, "已断开信令服务器连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开信令服务器连接失败", e)
        }
    }
    
    /**
     * 处理信令消息
     */
    private fun handleSignalingMessage(message: String?) {
        if (message == null) return
        
        try {
            val json = JSONObject(message)
            val type = json.getString("type")
            
            when (type) {
                "sender_list" -> {
                    handleSenderList(json)
                }
                "client_list" -> {
                    val clientsArray = json.getJSONArray("clients")
                    val clients = mutableListOf<ClientInfo>()
                    for (i in 0 until clientsArray.length()) {
                        val c = clientsArray.getJSONObject(i)
                        val id = c.getInt("id")
                        val name = c.getString("name")
                        clients.add(ClientInfo(id, name))
                        if (name == myClientName) {
                            myClientId = id
                        }
                    }
                    // 过滤掉自己
                    val filtered = myClientId?.let { id -> clients.filter { it.id != id } } ?: clients
                    listener?.onClientListReceived(filtered)
                }
                "connect_request" -> {
                    handleConnectRequest(json)
                }
                "offer" -> {
                    handleOffer(json)
                }
                "answer" -> {
                    handleAnswer(json)
                }
                "ice_candidate" -> {
                    handleIceCandidate(json)
                }
                "request_offer" -> {
                    listener?.onRequestOffer()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理信令消息失败", e)
        }
    }
    
    /**
     * 处理发送端列表
     */
    private fun handleSenderList(json: JSONObject) {
        try {
            Log.d(TAG, "开始处理发送端列表消息: ${json.toString()}")
            
            val sendersArray = json.getJSONArray("senders")
            val senders = mutableListOf<SenderInfo>()
            
            Log.d(TAG, "发送端数组长度: ${sendersArray.length()}")
            
            for (i in 0 until sendersArray.length()) {
                val senderJson = sendersArray.getJSONObject(i)
                val sender = SenderInfo(
                    id = senderJson.getInt("id"),
                    name = senderJson.getString("name"),
                    timestamp = senderJson.getLong("timestamp"),
                    available = senderJson.getBoolean("available")
                )
                senders.add(sender)
                Log.d(TAG, "解析发送端: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}")
            }
            
            Log.d(TAG, "发送端列表处理完成，共${senders.size}个发送端")
            listener?.onSenderListReceived(senders)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理发送端列表失败", e)
        }
    }
    
    /**
     * 处理Offer
     */
    private fun handleOffer(json: JSONObject) {
        try {
            val sdp = json.getString("sdp")
            listener?.onOfferReceived(sdp)
        } catch (e: Exception) {
            Log.e(TAG, "处理Offer失败", e)
        }
    }
    
    /**
     * 处理Answer
     */
    private fun handleAnswer(json: JSONObject) {
        try {
            val sdp = json.getString("sdp")
            listener?.onAnswerReceived(sdp)
        } catch (e: Exception) {
            Log.e(TAG, "处理Answer失败", e)
        }
    }
    
    /**
     * 处理ICE候选
     */
    private fun handleIceCandidate(json: JSONObject) {
        try {
            val candidate = json.getString("candidate")
            val sdpMLineIndex = json.getInt("sdpMLineIndex")
            val sdpMid = json.getString("sdpMid")
            
            listener?.onIceCandidateReceived(candidate, sdpMLineIndex, sdpMid)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理ICE候选失败", e)
        }
    }
    
    /**
     * 发送注册消息
     */
    fun sendRegistrationMessage(name: String) {
        try {
            val json = JSONObject()
            json.put("type", "register_sender")
            json.put("name", name)
            ws?.send(json.toString())
            Log.d(TAG, "注册消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送注册消息失败", e)
        }
    }
    
    /**
     * 请求发送端列表
     */
    fun requestSenderList() {
        try {
            val json = JSONObject()
            json.put("type", "request_senders")
            ws?.send(json.toString())
            Log.d(TAG, "请求发送端列表消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "请求发送端列表失败", e)
        }
    }
    
    /**
     * 发送Offer
     */
    fun sendOffer(sdp: String) {
        try {
            val json = JSONObject()
            json.put("type", "offer")
            json.put("sdp", sdp)
            ws?.send(json.toString())
            Log.d(TAG, "Offer已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送Offer失败", e)
        }
    }
    
    /**
     * 发送Answer
     */
    fun sendAnswer(sdp: String) {
        try {
            val json = JSONObject()
            json.put("type", "answer")
            json.put("sdp", sdp)
            ws?.send(json.toString())
            Log.d(TAG, "Answer已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送Answer失败", e)
        }
    }
    
    /**
     * 发送ICE候选
     */
    fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val json = JSONObject()
            json.put("type", "ice_candidate")
            json.put("candidate", candidate.sdp)
            json.put("sdpMLineIndex", candidate.sdpMLineIndex)
            json.put("sdpMid", candidate.sdpMid)
            ws?.send(json.toString())
            Log.d(TAG, "ICE候选已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送ICE候选失败", e)
        }
    }
    
    /**
     * 发送选择发送端消息
     */
    fun sendSelectSender(senderId: Int) {
        try {
            val json = JSONObject()
            json.put("type", "select_sender")
            json.put("senderId", senderId)
            ws?.send(json.toString())
            Log.d(TAG, "选择发送端消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送选择发送端消息失败", e)
        }
    }
    
    /**
     * 注册为在线客户端
     */
    fun registerAsClient(name: String) {
        try {
            val json = JSONObject()
            json.put("type", "register_client")
            json.put("name", name)
            ws?.send(json.toString())
            Log.d(TAG, "客户端注册消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送客户端注册消息失败", e)
        }
    }
    
    /**
     * 获取EglBase
     */
    fun getEglBase(): EglBase? = eglBase
    
    /**
     * 获取PeerConnectionFactory
     */
    fun getFactory(): PeerConnectionFactory? = factory
    
    /**
     * 获取PeerConnection
     */
    fun getPeerConnection(): PeerConnection? = peerConnection
    
    /**
     * 设置PeerConnection
     */
    fun setPeerConnection(peerConnection: PeerConnection?) {
        this.peerConnection = peerConnection
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * 获取连接状态详情
     */
    fun getConnectionStatus(): String {
        val wsStatus = when {
            ws == null -> "WebSocket: 未初始化"
            ws?.isOpen == true -> "WebSocket: 已连接"
            else -> "WebSocket: 连接断开"
        }
        
        return "$wsStatus, " +
               "PeerConnection: ${if (peerConnectionManager?.getPeerConnection() != null) "已创建" else "未创建"}, " +
               "远端视频轨道: ${if (getRemoteVideoTrack() != null) "已获取" else "未获取"}, " +
               "目标客户端: ${selectedTargetClientId ?: "未选择"}"
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            if (peerConnection != null) {
                peerConnection?.close()
                peerConnection = null
            }
            
            if (factory != null) {
                factory?.dispose()
                factory = null
            }
            
            if (eglBase != null) {
                eglBase?.release()
                eglBase = null
            }
            
            disconnectFromSignalingServer()
            
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败", e)
        }
    }
    
    /**
     * 获取远端视频轨道
     */
    fun getRemoteVideoTrack(): VideoTrack? {
        return peerConnectionManager?.getRemoteVideoTrack()
    }
    
    fun setPeerConnectionManager(manager: PeerConnectionManager) {
        this.peerConnectionManager = manager
    }
    
    /**
     * 发送连接请求给指定客户端
     */
    fun sendConnectRequest(targetClientId: Int) {
        try {
            val json = JSONObject()
            json.put("type", "connect_request")
            json.put("targetClientId", targetClientId)
            json.put("sourceClientId", myClientId)
            ws?.send(json.toString())
            Log.d(TAG, "连接请求已发送给客户端: $targetClientId")
        } catch (e: Exception) {
            Log.e(TAG, "发送连接请求失败", e)
        }
    }
    
    /**
     * 处理连接请求
     */
    private fun handleConnectRequest(json: JSONObject) {
        try {
            val sourceClientId = json.getInt("sourceClientId")
            Log.d(TAG, "收到来自客户端 $sourceClientId 的连接请求")
            
            // 创建PeerConnection
            val peerConnection = peerConnectionManager?.createPeerConnection()
            if (peerConnection != null) {
                // 创建Offer
                peerConnectionManager?.createOffer()
                Log.d(TAG, "已创建Offer响应连接请求")
            } else {
                Log.e(TAG, "创建PeerConnection失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理连接请求失败", e)
        }
    }
    
    /**
     * 发送Offer给目标客户端
     */
    fun sendOfferToTarget(sdp: String) {
        try {
            val json = JSONObject()
            json.put("type", "offer")
            json.put("sdp", sdp)
            json.put("targetClientId", selectedTargetClientId)
            ws?.send(json.toString())
            Log.d(TAG, "Offer已发送给目标客户端")
        } catch (e: Exception) {
            Log.e(TAG, "发送Offer失败", e)
        }
    }
    
    fun selectTargetClient(clientId: Int) {
        selectedTargetClientId = clientId
        Log.d(TAG, "Selected target client: $clientId")
    }
    
    fun connectToTarget() {
        if (selectedTargetClientId == null) {
            Log.e(TAG, "No target client selected")
            connectionListener?.onError("未选择目标客户端")
            return
        }
        
        Log.d(TAG, "Connecting to target client: $selectedTargetClientId")
        connectionListener?.onConnected()
        peerConnectionManager?.connectToTarget(selectedTargetClientId!!)
    }
    
    fun disconnect() {
        Log.d(TAG, "断开连接")
        connectionListener?.onDisconnected()
        peerConnectionManager?.close()
        selectedTargetClientId = null
    }
} 