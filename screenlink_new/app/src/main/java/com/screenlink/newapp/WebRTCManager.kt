/*
 * 功能说明：
 * WebRTC 管理器，负责 WebRTC 的初始化、信令服务器连接与重连、客户端/发送端列表管理、信令消息处理、心跳机制、回调接口等。是 WebRTC 相关的核心调度类。
 */
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
        fun onConnectRequestReceived(sourceClientId: Int)
        fun onError(error: String)
    }
    
    private var listener: WebRTCListener? = null
    
    // 发送端信息数据类
    data class SenderInfo(
        val id: Int,
        val name: String,
        val timestamp: Long,
        val available: Boolean
    )
    
    private var selectedTargetClientId: Int? = null
    private var myClientId: Int? = null
    private var mySenderId: Int? = null  // 添加senderId字段
    private var clientToSenderMap: MutableMap<Int, Int> = mutableMapOf()  // 添加映射表
    var myClientName: String = android.os.Build.MODEL
    
    private var peerConnectionManager: PeerConnectionManager? = null
    
    // 连接状态监听器
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }
    
    private var connectionListener: ConnectionListener? = null
    
    // 心跳定时器
    private var heartbeatTimer: java.util.Timer? = null
    
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
                    
                    // 启动心跳机制
                    startHeartbeat()
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "收到消息: $message")
                    handleSignalingMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket连接已关闭: $code, $reason")
                    isConnected = false
                    listener?.onConnectionStateChanged(false)
                    
                    // 停止心跳机制
                    stopHeartbeat()
                    // 自动重连
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "尝试自动重连信令服务器...")
                        connectToSignalingServer(serverAddress)
                    }, 3000)
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket错误", ex)
                    isConnected = false
                    listener?.onConnectionStateChanged(false)
                    // 自动重连
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "尝试自动重连信令服务器...")
                        connectToSignalingServer(serverAddress)
                    }, 3000)
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
            Log.d(TAG, "开始处理信令消息: $message")
            val json = JSONObject(message)
            val type = json.getString("type")
            Log.d(TAG, "消息类型: $type")
            
            when (type) {
                "sender_list" -> {
                    handleSenderList(json)
                }
                "sender_list_update" -> {
                    handleSenderList(json)
                }
                "sender_registered" -> {
                    // 处理发送端注册确认消息
                    if (json.has("senderId")) {
                        mySenderId = json.getInt("senderId")
                        val name = json.getString("name")
                        Log.d(TAG, "发送端注册成功，senderId: $mySenderId, 名称: $name")
                        // 注册成功后自动创建offer
                        if (peerConnectionManager != null) {
                            peerConnectionManager?.createOffer(object : PeerConnectionManager.PeerConnectionListener {
                                override fun onOfferCreated(sdp: SessionDescription) {
                                    sendOffer(sdp.description)
                                }
                                override fun onIceCandidate(candidate: IceCandidate) {}
                                override fun onAnswerCreated(sdp: SessionDescription) {}
                                override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {}
                            })
                        } else {
                            // 如果peerConnectionManager为null，通过listener通知自动生成offer
                            Log.d(TAG, "peerConnectionManager为null，通过listener通知自动生成offer")
                            listener?.onRequestOffer()
                        }
                    }
                }
                "client_list" -> {
                    val clientsArray = json.getJSONArray("clients")
                    val clients = mutableListOf<ClientInfo>()
                    Log.d(TAG, "处理客户端列表，我的客户端名称: $myClientName")
                    
                    for (i in 0 until clientsArray.length()) {
                        val c = clientsArray.getJSONObject(i)
                        val id = c.getInt("id")
                        val name = c.getString("name")
                        clients.add(ClientInfo(id, name))
                        Log.d(TAG, "客户端: ID=$id, 名称=$name")
                        
                        if (name == myClientName) {
                            myClientId = id
                            Log.d(TAG, "找到匹配的客户端，设置myClientId为: $id")
                        }
                    }
                    
                    if (myClientId == null) {
                        Log.w(TAG, "未找到匹配的客户端名称: $myClientName")
                        // 如果没有找到匹配的客户端，可以选择第一个客户端作为默认值
                        if (clients.isNotEmpty()) {
                            myClientId = clients.first().id
                            Log.d(TAG, "使用第一个客户端作为默认值，myClientId设置为: ${myClientId}")
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
                    Log.d(TAG, "收到Offer消息: ${json.toString()}")
                    handleOffer(json)
                }
                "answer" -> {
                    Log.d(TAG, "收到Answer消息: ${json.toString()}")
                    handleAnswer(json)
                }
                "ice_candidate" -> {
                    handleIceCandidate(json)
                }
                "request_offer" -> {
                    Log.d(TAG, "收到请求Offer消息")
                    listener?.onRequestOffer()
                }
                "heartbeat_ack" -> {
                    Log.d(TAG, "收到心跳确认消息")
                    // 心跳确认，无需特殊处理
                }
                "error" -> {
                    val errorMessage = json.getString("message")
                    Log.e(TAG, "收到错误消息: $errorMessage")
                    // 通知UI显示错误信息
                    listener?.onError(errorMessage)
                }
                else -> {
                    Log.w(TAG, "未知消息类型: $type")
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
            
            // 如果offer消息包含senderId，保存到映射表中
            if (json.has("senderId")) {
                val senderId = json.getInt("senderId")
                // 保存当前选择的发送端ID
                selectedTargetClientId = senderId
                Log.d(TAG, "收到offer，设置selectedTargetClientId为senderId: $senderId")
            }
            
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
            
            Log.d(TAG, "收到ICE候选: $candidate")
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
            // 只有当selectedTargetClientId不为null时才添加targetClientId字段
            if (selectedTargetClientId != null) {
                json.put("targetClientId", selectedTargetClientId)
            }
            ws?.send(json.toString())
            if (selectedTargetClientId != null) {
                Log.d(TAG, "Offer已发送给目标客户端: $selectedTargetClientId")
            } else {
                Log.d(TAG, "发送端Offer已发送到服务器")
            }
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
            // 直接使用selectedTargetClientId作为selectedSenderId
            val senderId = selectedTargetClientId ?: 0
            json.put("selectedSenderId", senderId)
            ws?.send(json.toString())
            Log.d(TAG, "Answer已发送给发送端: senderId=$senderId")
        } catch (e: Exception) {
            Log.e(TAG, "发送Answer失败", e)
        }
    }
    
    /**
     * 根据clientId获取对应的senderId
     * 这里需要维护一个clientId到senderId的映射
     */
    private fun getSenderIdForClient(clientId: Int?): Int {
        if (clientId == null) return 0
        
        // 从映射表中查找senderId
        val senderId = clientToSenderMap[clientId]
        if (senderId != null) {
            Log.d(TAG, "找到clientId $clientId 对应的senderId: $senderId")
            return senderId
        }
        
        // 如果没找到，使用clientId作为senderId（临时方案）
        Log.w(TAG, "未找到clientId $clientId 对应的senderId，使用clientId作为senderId")
        return clientId
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
            json.put("targetClientId", selectedTargetClientId)
            ws?.send(json.toString())
            Log.d(TAG, "ICE候选已发送给目标客户端: $selectedTargetClientId")
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
            if (myClientId == null) {
                Log.e(TAG, "发送连接请求失败：myClientId为null，请先注册客户端")
                return
            }
            
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
            if (!json.has("sourceClientId")) {
                Log.e(TAG, "处理连接请求失败：缺少sourceClientId字段")
                return
            }
            
            val sourceClientId = json.getInt("sourceClientId")
            Log.d(TAG, "收到来自客户端 $sourceClientId 的连接请求")
            
            // 设置目标客户端为发送连接请求的客户端
            selectedTargetClientId = sourceClientId
            Log.d(TAG, "设置目标客户端为: $sourceClientId")
            
            // 调用回调，让ScreenCaptureService处理连接请求
            listener?.onConnectRequestReceived(sourceClientId)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理连接请求失败", e)
        }
    }
    
    /**
     * 发送Offer给目标客户端
     */
    fun sendOfferToTarget(sdp: String) {
        try {
            if (selectedTargetClientId == null) {
                Log.e(TAG, "发送Offer失败：selectedTargetClientId为null")
                return
            }
            
            val json = JSONObject()
            json.put("type", "offer")
            json.put("sdp", sdp)
            json.put("targetClientId", selectedTargetClientId)
            ws?.send(json.toString())
            Log.d(TAG, "Offer已发送给目标客户端: $selectedTargetClientId")
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
    
    /**
     * 设置客户端名称
     */
    fun setClientName(name: String) {
        this.myClientName = name
        Log.d(TAG, "客户端名称已设置为: $name")
    }
    
    /**
     * 启动心跳机制
     */
    private fun startHeartbeat() {
        try {
            heartbeatTimer?.cancel()
            heartbeatTimer = java.util.Timer()
            heartbeatTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    sendHeartbeat()
                }
            }, 30000, 30000) // 30秒发送一次心跳
            Log.d(TAG, "心跳机制已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动心跳机制失败", e)
        }
    }
    
    /**
     * 发送心跳
     */
    private fun sendHeartbeat() {
        try {
            if (isConnected && ws?.isOpen == true) {
                val json = JSONObject()
                json.put("type", "heartbeat")
                // 发送端使用senderId，客户端使用clientId
                if (myClientId != null) {
                    json.put("clientId", myClientId)
                } else if (mySenderId != null) {
                    json.put("senderId", mySenderId)
                }
                ws?.send(json.toString())
                Log.d(TAG, "心跳已发送")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送心跳失败", e)
        }
    }
    
    /**
     * 停止心跳机制
     */
    private fun stopHeartbeat() {
        try {
            heartbeatTimer?.cancel()
            heartbeatTimer = null
            Log.d(TAG, "心跳机制已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止心跳机制失败", e)
        }
    }
    
    /**
     * 选择发送端
     */
    fun selectSender(senderId: Int) {
        try {
            Log.d(TAG, "选择发送端: $senderId")
            sendSelectSender(senderId)
        } catch (e: Exception) {
            Log.e(TAG, "选择发送端失败", e)
        }
    }
} 