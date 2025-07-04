package com.screenlink.newapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject

class ScreenShareService : Service() {
    // ================= 屏幕采集相关 =================
    // 采集相关变量
    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: org.webrtc.VideoTrack? = null
    private var videoCapturer: org.webrtc.VideoCapturer? = null
    private var isScreenCaptureActive = false
    private var trackAdded = false
    // MediaProjection相关
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var isScreenCapturing = false

    // 屏幕采集回调接口
    interface ScreenCaptureListener {
        fun onScreenCaptureStarted()
        fun onScreenCaptureStopped()
        fun onScreenCaptureError(error: String)
    }
    private var screenCaptureListener: ScreenCaptureListener? = null

    fun setScreenCaptureListener(listener: ScreenCaptureListener) {
        this.screenCaptureListener = listener
    }

    fun requestScreenCapturePermission(activity: android.app.Activity) {
        val mediaProjectionManager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        activity.startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            1001 // MEDIA_PROJECTION_REQUEST_CODE
        )
    }

    fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == 1001) {
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                this.resultCode = resultCode
                this.resultData = data
                return true
            } else {
                screenCaptureListener?.onScreenCaptureError("屏幕录制权限被拒绝")
                this.resultCode = 0
                this.resultData = null
                return false
            }
        }
        return false
    }

    fun startScreenCapture(factory: org.webrtc.PeerConnectionFactory?, eglBase: org.webrtc.EglBase?) {
        android.util.Log.d("ScreenShareService", "[诊断] startScreenCapture 被调用")
        android.util.Log.d("ScreenShareService", "[诊断] 当前状态: isScreenCapturing=$isScreenCapturing, isConnected=$isConnected, ws=${ws != null}")
        android.util.Log.d("ScreenShareService", "[诊断] 参数检查: factory=${factory != null}, eglBase=${eglBase != null}")
        android.util.Log.d("ScreenShareService", "[诊断] 当前线程: ${Thread.currentThread().name}")
        
        if (isScreenCapturing) {
            android.util.Log.e("ScreenShareService", "采集已在进行中，禁止重复启动")
            return
        }
        
        // 检查参数
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null || factory == null || eglBase == null) {
            android.util.Log.e("ScreenShareService", "MediaProjection数据无效，无法开始屏幕捕获")
            return
        }
        
        // 检查WebSocket连接状态
        if (!isConnected || ws == null) {
            android.util.Log.e("ScreenShareService", "WebSocket连接未建立，无法开始屏幕采集")
            return
        }
        
        android.util.Log.d("ScreenShareService", "准备开始采集，WebSocket连接状态: $isConnected")
        
        isScreenCapturing = true
        
        try {
            videoCapturer = createScreenCapturer()
            if (videoCapturer == null) {
                android.util.Log.e("ScreenShareService", "无法创建视频采集器")
                isScreenCapturing = false
                return
            }
            
            videoSource = factory.createVideoSource(false)
            videoCapturer?.initialize(
                org.webrtc.SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext),
                applicationContext,
                videoSource?.capturerObserver
            )
            videoCapturer?.startCapture(1280, 720, 30)
            isScreenCaptureActive = true
            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
            
            // 只在PeerConnection不存在时创建，避免重新创建导致连接断开
            if (peerConnection == null) {
                createPeerConnection()
                android.util.Log.d("ScreenShareService", "PeerConnection 已创建")
            } else {
                android.util.Log.d("ScreenShareService", "使用现有的 PeerConnection")
            }
            
            val sender = peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
            android.util.Log.d("ScreenShareService", "addTrack 返回: $sender")
            android.util.Log.d("ScreenShareService", "视频轨道状态: ID=${videoTrack?.id()}, enabled=${videoTrack?.enabled()}")
            android.util.Log.d("ScreenShareService", "PeerConnection 状态: ${peerConnection?.connectionState()}")
            android.util.Log.d("ScreenShareService", "ICE 连接状态: ${peerConnection?.iceConnectionState()}")
            android.util.Log.d("ScreenShareService", "信令状态: ${peerConnection?.signalingState()}")
            
            android.util.Log.d("ScreenShareService", "屏幕采集和推流已启动")
            
            // 采集成功后发送发送端注册消息
            try {
                if (isConnected && ws != null && ws!!.isOpen) {
                    val registerMsg = org.json.JSONObject()
                    registerMsg.put("type", "register_sender")
                    registerMsg.put("name", myClientName)
                    ws?.send(registerMsg.toString())
                    android.util.Log.d("ScreenShareService", "采集启动后发送注册消息: ${registerMsg}")
                } else {
                    android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过注册消息发送")
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenShareService", "发送注册消息失败", e)
            }
            
            // 采集成功后立即清空，强制下次必须重新授权
            resultData = null
            resultCode = 0
            
            videoTrack?.addSink(object : org.webrtc.VideoSink {
                override fun onFrame(frame: org.webrtc.VideoFrame) {
                    // android.util.Log.d("ScreenShareService", "采集端 onFrame: ${frame.buffer.width}x${frame.buffer.height}")
                }
            })
            
            // 采集启动成功后，如果是发送端模式，立即创建offer
            if (isActingAsSender) {
                android.util.Log.d("ScreenShareService", "发送端模式，采集启动后立即创建offer")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    createOffer()
                }, 1000) // 延迟1秒确保采集稳定
            }
            
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "启动屏幕采集失败", e)
            stopScreenCapture()
        }
    }

    fun stopScreenCapture() {
        try {
            android.util.Log.d("ScreenShareService", "开始停止屏幕捕获")
            
            // 1. 停止视频采集
            if (isScreenCaptureActive) {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                isScreenCaptureActive = false
                android.util.Log.d("ScreenShareService", "视频采集器已停止")
            }
            
            // 2. 清理视频轨道和源
            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null
            
            // 3. 停止并清理 MediaProjection
            mediaProjection?.stop()
            mediaProjection = null
            
            // 4. 重置状态
            isScreenCapturing = false
            trackAdded = false
            
            // 5. 清空 resultData，强制下次重新授权
            resultData = null
            resultCode = 0
            
            android.util.Log.d("ScreenShareService", "屏幕捕获已完全停止")
            screenCaptureListener?.onScreenCaptureStopped()
            
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "停止屏幕捕获失败", e)
            // 即使出错也要重置状态
            isScreenCapturing = false
            isScreenCaptureActive = false
            resultData = null
            resultCode = 0
        }
    }

    private fun stopScreenCaptureWithoutClearingResultData() {
        try {
            android.util.Log.d("ScreenShareService", "开始停止屏幕捕获（保留resultData）")
            
            // 1. 停止视频采集
            if (isScreenCaptureActive) {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                isScreenCaptureActive = false
                android.util.Log.d("ScreenShareService", "视频采集器已停止")
            }
            
            // 2. 清理视频轨道和源
            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null
            
            // 3. 停止并清理 MediaProjection
            mediaProjection?.stop()
            mediaProjection = null
            
            // 4. 重置状态（但不清理 resultData）
            isScreenCapturing = false
            trackAdded = false
            
            android.util.Log.d("ScreenShareService", "屏幕捕获已完全停止（保留resultData）")
            screenCaptureListener?.onScreenCaptureStopped()
            
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "停止屏幕捕获失败", e)
            // 即使出错也要重置状态
            isScreenCapturing = false
            isScreenCaptureActive = false
        }
    }

    private fun createScreenCapturer(): org.webrtc.VideoCapturer? {
        android.util.Log.d("ScreenShareService", "[诊断] createScreenCapturer 被调用")
        android.util.Log.d("ScreenShareService", "[诊断] 当前线程: ${Thread.currentThread().name}")
        android.util.Log.d("ScreenShareService", "[诊断] WebSocket状态: isConnected=$isConnected, ws=${ws != null}")
        
        try {
            if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
                android.util.Log.e("ScreenShareService", "MediaProjection数据无效")
                // 失败时清空
                resultData = null
                resultCode = 0
                return null
            }
            
            // 确保之前的 MediaProjection 完全清理
            mediaProjection?.stop()
            mediaProjection = null
            
            // 强制垃圾回收，确保之前的实例被清理
            System.gc()
            
            val mediaProjectionManager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
            if (mediaProjection == null) {
                android.util.Log.e("ScreenShareService", "获取MediaProjection失败")
                // 失败时清空
                resultData = null
                resultCode = 0
                return null
            }
            
            android.util.Log.d("ScreenShareService", "创建新的 MediaProjection 实例")
            
            return org.webrtc.ScreenCapturerAndroid(resultData!!, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    android.util.Log.d("ScreenShareService", "MediaProjection已停止，自动调用stopScreenCapture")
                    stopScreenCapture()
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "创建屏幕捕获器失败", e)
            // 失败时清空
            resultData = null
            resultCode = 0
            return null
        }
    }

    fun getVideoTrack(): org.webrtc.VideoTrack? = videoTrack
    fun isScreenCaptureActive(): Boolean = isScreenCaptureActive
    fun isTrackAdded(): Boolean = trackAdded
    fun setTrackAdded(added: Boolean) { trackAdded = added }
    fun cleanupScreenCapture() { stopScreenCapture() }

    // ================= WebRTC 相关 =================
    // WebRTC相关变量
    var eglBase: org.webrtc.EglBase? = null
    private var peerConnection: org.webrtc.PeerConnection? = null
    private var ws: org.java_websocket.client.WebSocketClient? = null
    var factory: org.webrtc.PeerConnectionFactory? = null
    private var isConnected = false
    private var serverAddress = ""
    private var myClientId: Int? = null
    private var mySenderId: Int? = null
    private var myClientName: String = android.os.Build.MODEL
    private var selectedTargetClientId: Int? = null
    private var clientToSenderMap: MutableMap<Int, Int> = mutableMapOf()
    private var heartbeatTimer: java.util.Timer? = null
    private var isActingAsSender = false
    private var selectedSenderId: Int? = null  // 添加变量存储当前选择的发送端ID
    
    // 添加连接状态管理
    private var isConnecting = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_DELAY = 5000L // 5秒

    // WebRTC回调接口
    interface WebRTCListener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onSenderListReceived(senders: List<SenderInfo>)
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidate: String, sdpMLineIndex: Int, sdpMid: String)
        fun onRequestOffer()
        fun onClientListReceived(clients: List<ClientInfo>)
        fun onConnectRequestReceived(sourceClientId: Int)
        fun onRemoteVideoTrackReceived(track: org.webrtc.VideoTrack)
        fun onError(error: String)
    }
    private var webRTCListener: WebRTCListener? = null

    data class ClientInfo(val id: Int, val name: String)
    data class SenderInfo(val id: Int, val name: String, val timestamp: Long, val available: Boolean)

    fun setWebRTCListener(listener: WebRTCListener) {
        this.webRTCListener = listener
    }

    fun initializeWebRTC() {
        try {
            android.util.Log.d("ScreenShareService", "开始初始化WebRTC")
            eglBase = org.webrtc.EglBase.create()
            val options = org.webrtc.PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            org.webrtc.PeerConnectionFactory.initialize(options)
            val videoDecoderFactory = org.webrtc.DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            val videoEncoderFactory = org.webrtc.DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            factory = org.webrtc.PeerConnectionFactory.builder()
                .setVideoDecoderFactory(videoDecoderFactory)
                .setVideoEncoderFactory(videoEncoderFactory)
                .createPeerConnectionFactory()
            android.util.Log.d("ScreenShareService", "WebRTC初始化完成")
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "初始化WebRTC失败", e)
        }
    }

    fun connectToSignalingServer(address: String) {
        try {
            android.util.Log.d("ScreenShareService", "[日志追踪] connectToSignalingServer 被调用, address=$address")
            
            // 防止重复连接
            if (isConnecting) {
                android.util.Log.d("ScreenShareService", "正在连接中，跳过重复连接请求")
                return
            }
            
            if (isConnected && ws != null && ws!!.isOpen) {
                android.util.Log.d("ScreenShareService", "WebSocket已连接，跳过重复连接")
                return
            }
            
            // 清理之前的连接
            disconnectFromSignalingServer()
            
            serverAddress = address
            isConnecting = true
            android.util.Log.d("ScreenShareService", "开始连接到信令服务器: $address")
            val uri = java.net.URI("ws://$serverAddress")
            ws = object : org.java_websocket.client.WebSocketClient(uri) {
                override fun onOpen(handshakedata: org.java_websocket.handshake.ServerHandshake?) {
                    android.util.Log.d("ScreenShareService", "[日志追踪] WebSocket连接已建立 onOpen")
                    android.util.Log.d("ScreenShareService", "[日志追踪] 连接详情: handshakedata=${handshakedata?.httpStatus}, ${handshakedata?.httpStatusMessage}")
                    android.util.Log.d("ScreenShareService", "[日志追踪] 当前线程: ${Thread.currentThread().name}")
                    isConnected = true
                    isConnecting = false
                    reconnectAttempts = 0 // 连接成功后重置重连次数
                    webRTCListener?.onConnectionStateChanged(true)
                    
                    // 注册为发送端（客户端就是发送端）
                    try {
                        if (isConnected && ws != null && ws!!.isOpen) {
                            val registerMsg = org.json.JSONObject()
                            registerMsg.put("type", "register_sender")
                            registerMsg.put("name", myClientName)
                            ws?.send(registerMsg.toString())
                            android.util.Log.d("ScreenShareService", "已发送发送端注册消息: ${registerMsg}")
                        } else {
                            android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过注册消息发送")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ScreenShareService", "发送注册消息失败", e)
                    }
                    
                    // 如果不是发送端模式，则注册为接收端并请求发送端列表
                    try {
                        if (isConnected && ws != null && ws!!.isOpen) {
                            val requestMsg = org.json.JSONObject()
                            requestMsg.put("type", "request_sender_list")
                            ws?.send(requestMsg.toString())
                            android.util.Log.d("ScreenShareService", "已发送请求发送端列表消息: ${requestMsg}")
                        } else {
                            android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过请求发送端列表")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ScreenShareService", "发送请求发送端列表消息失败", e)
                    }
                    
                    // 启动心跳机制
                    startHeartbeat()
                }
                override fun onMessage(message: String?) {
                    android.util.Log.d("ScreenShareService", "收到消息: $message")
                    handleSignalingMessage(message)
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    android.util.Log.d("ScreenShareService", "[日志追踪] WebSocket连接已关闭 onClose: $code, $reason, remote=$remote")
                    android.util.Log.d("ScreenShareService", "[日志追踪] 连接关闭时的状态: isConnected=$isConnected, ws=${ws != null}")
                    android.util.Log.d("ScreenShareService", "[日志追踪] 当前线程: ${Thread.currentThread().name}")
                    isConnected = false
                    isConnecting = false
                    webRTCListener?.onConnectionStateChanged(false)
                    stopHeartbeat()
                    
                    // 智能重连：只有在非主动关闭且重连次数未超限时才重连
                    if (remote && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        android.util.Log.d("ScreenShareService", "尝试自动重连信令服务器... (第${reconnectAttempts}次)")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            connectToSignalingServer(serverAddress)
                        }, RECONNECT_DELAY)
                    } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        android.util.Log.w("ScreenShareService", "重连次数已达上限($MAX_RECONNECT_ATTEMPTS)，停止自动重连")
                    }
                }
                override fun onError(ex: Exception?) {
                    android.util.Log.e("ScreenShareService", "[日志追踪] WebSocket错误 onError", ex)
                    isConnected = false
                    isConnecting = false
                    webRTCListener?.onConnectionStateChanged(false)
                    
                    // 智能重连：只有在重连次数未超限时才重连
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        android.util.Log.d("ScreenShareService", "WebSocket错误，尝试自动重连信令服务器... (第${reconnectAttempts}次)")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            connectToSignalingServer(serverAddress)
                        }, RECONNECT_DELAY)
                    } else {
                        android.util.Log.w("ScreenShareService", "重连次数已达上限($MAX_RECONNECT_ATTEMPTS)，停止自动重连")
                    }
                }
            }
            android.util.Log.d("ScreenShareService", "[日志追踪] ws.connect() 即将执行")
            ws?.connect()
            android.util.Log.d("ScreenShareService", "[日志追踪] ws.connect() 已调用")
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "[日志追踪] 连接信令服务器失败", e)
            isConnecting = false
        }
    }

    fun disconnectFromSignalingServer() {
        try {
            android.util.Log.d("ScreenShareService", "开始断开信令服务器连接")
            stopHeartbeat()
            ws?.close()
            ws = null
            isConnected = false
            isConnecting = false
            reconnectAttempts = 0 // 主动断开时重置重连次数
            android.util.Log.d("ScreenShareService", "已断开信令服务器连接")
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "断开信令服务器连接失败", e)
        }
    }

    private fun handleSignalingMessage(message: String?) {
        if (message == null) return
        try {
            android.util.Log.d("ScreenShareService", "开始处理信令消息: $message")
            val json = org.json.JSONObject(message)
            val type = json.optString("type")
            when (type) {
                "offer" -> {
                    val sdp = json.optString("sdp")
                    val senderName = json.optString("senderName")
                    val senderId = json.optInt("senderId")
                    android.util.Log.d("ScreenShareService", "收到 offer 消息，SDP: ${sdp.substring(0, 100)}...")
                    android.util.Log.d("ScreenShareService", "发送端信息: 名称=$senderName, ID=$senderId")
                    
                    // 分析SDP中是否包含视频轨道
                    if (sdp.contains("m=video")) {
                        android.util.Log.d("ScreenShareService", "✅ SDP中包含视频轨道")
                    } else {
                        android.util.Log.d("ScreenShareService", "❌ SDP中不包含视频轨道")
                    }
                    
                    android.util.Log.d("ScreenShareService", "当前信令状态: ${peerConnection?.signalingState()}")
                    android.util.Log.d("ScreenShareService", "当前角色: ${if (isActingAsSender) "发送端" else "接收端"}")
                    
                    if (peerConnection == null) {
                        android.util.Log.d("ScreenShareService", "PeerConnection 为空，先创建 PeerConnection")
                        createPeerConnection()
                    } else {
                        android.util.Log.d("ScreenShareService", "PeerConnection 已存在，直接使用")
                    }
                    
                    android.util.Log.d("ScreenShareService", "开始设置远程描述...")
                    val sessionDescription = org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp)
                    peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            android.util.Log.d("ScreenShareService", "远程描述设置成功，开始创建 answer")
                            createAnswer()
                        }
                        override fun onCreateFailure(p0: String?) {
                            android.util.Log.e("ScreenShareService", "创建远程描述失败: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            android.util.Log.e("ScreenShareService", "设置远程描述失败: $p0")
                        }
                    }, sessionDescription)
                    
                    webRTCListener?.onOfferReceived(sdp)
                }
                "answer" -> {
                    val sdp = json.optString("sdp")
                    setRemoteDescription(sdp, org.webrtc.SessionDescription.Type.ANSWER)
                    webRTCListener?.onAnswerReceived(sdp)
                }
                "ice" -> {
                    val candidate = json.optString("candidate")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex")
                    val sdpMid = json.optString("sdpMid")
                    addIceCandidate(candidate, sdpMLineIndex, sdpMid)
                    webRTCListener?.onIceCandidateReceived(candidate, sdpMLineIndex, sdpMid)
                }
                "sender_list" -> {
                    val sendersArray = json.optJSONArray("senders")
                    val senders = mutableListOf<SenderInfo>()
                    android.util.Log.d("ScreenShareService", "收到 sender_list 消息，sendersArray=${sendersArray != null}")
                    if (sendersArray != null) {
                        android.util.Log.d("ScreenShareService", "sendersArray 长度: ${sendersArray.length()}")
                        for (i in 0 until sendersArray.length()) {
                            val s = sendersArray.getJSONObject(i)
                            val senderInfo = SenderInfo(
                                s.optInt("id"),
                                s.optString("name"),
                                s.optLong("timestamp"),
                                s.optBoolean("available")
                            )
                            senders.add(senderInfo)
                            android.util.Log.d("ScreenShareService", "解析发送端: ID=${senderInfo.id}, 名称=${senderInfo.name}, 可用=${senderInfo.available}")
                        }
                    } else {
                        android.util.Log.d("ScreenShareService", "sendersArray 为 null")
                    }
                    android.util.Log.d("ScreenShareService", "准备调用 webRTCListener?.onSenderListReceived，发送端数量: ${senders.size}")
                    webRTCListener?.onSenderListReceived(senders)
                    android.util.Log.d("ScreenShareService", "已调用 webRTCListener?.onSenderListReceived")
                }
                "sender_list_update" -> {
                    val sendersArray = json.optJSONArray("senders")
                    val senders = mutableListOf<SenderInfo>()
                    android.util.Log.d("ScreenShareService", "收到 sender_list_update 消息，sendersArray=${sendersArray != null}")
                    if (sendersArray != null) {
                        android.util.Log.d("ScreenShareService", "sendersArray 长度: ${sendersArray.length()}")
                        for (i in 0 until sendersArray.length()) {
                            val s = sendersArray.getJSONObject(i)
                            val senderInfo = SenderInfo(
                                s.optInt("id"),
                                s.optString("name"),
                                s.optLong("timestamp"),
                                s.optBoolean("available")
                            )
                            senders.add(senderInfo)
                            android.util.Log.d("ScreenShareService", "解析发送端: ID=${senderInfo.id}, 名称=${senderInfo.name}, 可用=${senderInfo.available}")
                        }
                    } else {
                        android.util.Log.d("ScreenShareService", "sendersArray 为 null")
                    }
                    android.util.Log.d("ScreenShareService", "准备调用 webRTCListener?.onSenderListReceived，发送端数量: ${senders.size}")
                    webRTCListener?.onSenderListReceived(senders)
                    android.util.Log.d("ScreenShareService", "已调用 webRTCListener?.onSenderListReceived")
                }
                "client_list" -> {
                    val clientsArray = json.optJSONArray("clients")
                    val clients = mutableListOf<ClientInfo>()
                    if (clientsArray != null) {
                        for (i in 0 until clientsArray.length()) {
                            val c = clientsArray.getJSONObject(i)
                            clients.add(ClientInfo(c.optInt("id"), c.optString("name")))
                        }
                    }
                    webRTCListener?.onClientListReceived(clients)
                }
                "connect_request" -> {
                    val sourceClientId = json.optInt("sourceClientId")
                    webRTCListener?.onConnectRequestReceived(sourceClientId)
                }
                "request_offer" -> {
                    webRTCListener?.onRequestOffer()
                }
                "info" -> {
                    val infoMsg = json.optString("message")
                    android.util.Log.i("ScreenShareService", "服务器信息: $infoMsg")
                    webRTCListener?.onError("服务器信息: $infoMsg")
                }
                "sender_registered" -> {
                    val senderId = json.optInt("senderId")
                    val name = json.optString("name")
                    android.util.Log.d("ScreenShareService", "发送端注册成功: ID=$senderId, 名称=$name")
                    mySenderId = senderId
                    // 可以在这里添加注册成功的回调
                    webRTCListener?.onError("发送端注册成功: $name (ID: $senderId)")
                    
                    // 注册成功后自动创建并发送 offer
                    try {
                        android.util.Log.d("ScreenShareService", "发送端注册成功，开始创建 offer")
                        // 延迟一点时间确保注册完成
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            createOffer()
                        }, 500)
                    } catch (e: Exception) {
                        android.util.Log.e("ScreenShareService", "自动创建 offer 失败", e)
                    }
                }
                "request_sender_list_response" -> {
                    val sendersArray = json.optJSONArray("senders")
                    val senders = mutableListOf<SenderInfo>()
                    if (sendersArray != null) {
                        for (i in 0 until sendersArray.length()) {
                            val s = sendersArray.getJSONObject(i)
                            senders.add(SenderInfo(
                                s.optInt("id"),
                                s.optString("name"),
                                s.optLong("timestamp"),
                                s.optBoolean("available")
                            ))
                        }
                    }
                    android.util.Log.d("ScreenShareService", "收到发送端列表响应: ${senders.size} 个发送端")
                    webRTCListener?.onSenderListReceived(senders)
                }
                "heartbeat_ack" -> {
                    val ts = json.optLong("timestamp")
                    android.util.Log.d("ScreenShareService", "收到心跳响应，时间戳: $ts")
                }
                "connection_status" -> {
                    val senderId = json.optInt("senderId")
                    val status = json.optString("status")
                    val timestamp = json.optLong("timestamp")
                    android.util.Log.d("ScreenShareService", "收到连接状态更新: 发送端${senderId} - ${status}")
                    webRTCListener?.onError("连接状态: 发送端${senderId} - ${status}")
                }
                else -> {
                    android.util.Log.d("ScreenShareService", "未知信令类型: $type")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "处理信令消息失败", e)
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTimer = java.util.Timer()
        heartbeatTimer?.schedule(object : java.util.TimerTask() {
            override fun run() {
                try {
                    if (isConnected && ws != null && ws!!.isOpen) {
                        val heartbeatMsg = org.json.JSONObject()
                        heartbeatMsg.put("type", "heartbeat")
                        ws?.send(heartbeatMsg.toString())
                        android.util.Log.d("ScreenShareService", "发送心跳包")
                    } else {
                        android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过心跳发送")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ScreenShareService", "发送心跳包失败", e)
                }
            }
        }, 0, 30000) // 30秒间隔
    }
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    // ================= PeerConnection 相关 =================
    // PeerConnection相关变量
    var remoteVideoTrack: org.webrtc.VideoTrack? = null

    // PeerConnection回调接口
    interface PeerConnectionListener {
        fun onIceCandidate(candidate: org.webrtc.IceCandidate)
        fun onOfferCreated(sdp: org.webrtc.SessionDescription)
        fun onAnswerCreated(sdp: org.webrtc.SessionDescription)
        fun onConnectionStateChanged(state: org.webrtc.PeerConnection.IceConnectionState)
    }
    private var peerConnectionListener: PeerConnectionListener? = null

    fun setPeerConnectionListener(listener: PeerConnectionListener) {
        this.peerConnectionListener = listener
    }

    private fun resetPeerConnection() {
        try {
            android.util.Log.d("ScreenShareService", "重置 PeerConnection")
            peerConnection?.close()
            peerConnection = null
            // 清理相关的轨道引用
            remoteVideoTrack = null
            // 重置发送端标志
            isActingAsSender = false
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "重置 PeerConnection 失败", e)
        }
    }

    private fun isReadyForRemoteOffer(): Boolean {
        return peerConnection?.signalingState() == org.webrtc.PeerConnection.SignalingState.STABLE ||
               peerConnection?.signalingState() == org.webrtc.PeerConnection.SignalingState.HAVE_REMOTE_OFFER
    }

    fun createPeerConnection() : org.webrtc.PeerConnection? {
        try {
            // 配置STUN服务器
            val iceServers = listOf(
                org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                org.webrtc.PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            val rtcConfig = org.webrtc.PeerConnection.RTCConfiguration(iceServers).apply {
                iceTransportsType = org.webrtc.PeerConnection.IceTransportsType.ALL
                rtcpMuxPolicy = org.webrtc.PeerConnection.RtcpMuxPolicy.REQUIRE
                sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            peerConnection = factory?.createPeerConnection(rtcConfig, object : org.webrtc.PeerConnection.Observer {
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                    candidate?.let { 
                        android.util.Log.d("ScreenShareService", "生成 ICE 候选: $candidate")
                        // 发送 ICE 候选到服务器
                        try {
                            // 检查WebSocket连接状态
                            if (isConnected && ws != null && ws!!.isOpen) {
                                val iceMsg = org.json.JSONObject()
                                iceMsg.put("type", "ice")
                                iceMsg.put("candidate", candidate.sdp)
                                iceMsg.put("sdpMLineIndex", candidate.sdpMLineIndex)
                                iceMsg.put("sdpMid", candidate.sdpMid)
                                ws?.send(iceMsg.toString())
                                android.util.Log.d("ScreenShareService", "已发送 ICE 候选到服务器: $iceMsg")
                            } else {
                                android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过ICE候选发送")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ScreenShareService", "发送 ICE 候选失败", e)
                        }
                        peerConnectionListener?.onIceCandidate(it) 
                    }
                }
                override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState?) {
                    android.util.Log.d("ScreenShareService", "信令状态变化: $state")
                }
                override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState?) {
                    android.util.Log.d("ScreenShareService", "ICE 连接状态变化: $state")
                    when (state) {
                        org.webrtc.PeerConnection.IceConnectionState.CONNECTED -> {
                            android.util.Log.d("ScreenShareService", "✅ ICE连接已建立，视频流应该开始传输")
                        }
                        org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                            android.util.Log.d("ScreenShareService", "❌ ICE连接失败，无法建立P2P连接")
                        }
                        org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> {
                            android.util.Log.d("ScreenShareService", "⚠️ ICE连接断开")
                        }
                        org.webrtc.PeerConnection.IceConnectionState.CHECKING -> {
                            android.util.Log.d("ScreenShareService", "🔄 ICE连接检查中...")
                        }
                        org.webrtc.PeerConnection.IceConnectionState.NEW -> {
                            android.util.Log.d("ScreenShareService", "🆕 ICE连接新建")
                        }
                        else -> {
                            android.util.Log.d("ScreenShareService", "ICE连接状态: $state")
                        }
                    }
                    state?.let { peerConnectionListener?.onConnectionStateChanged(it) }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    android.util.Log.d("ScreenShareService", "ICE 接收状态变化: $receiving")
                }
                override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState?) {
                    android.util.Log.d("ScreenShareService", "ICE 收集状态变化: $state")
                }
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                    val track = receiver?.track()
                    android.util.Log.d("ScreenShareService", "收到远端轨道: ${track?.kind()}, ID: ${track?.id()}")
                    android.util.Log.d("ScreenShareService", "接收器信息: ${receiver?.id()}")
                    android.util.Log.d("ScreenShareService", "流数量: ${streams?.size}")
                    streams?.forEachIndexed { index, stream ->
                        android.util.Log.d("ScreenShareService", "流 $index: ID=${stream.id}, 视频轨道数=${stream.videoTracks.size}, 音频轨道数=${stream.audioTracks.size}")
                    }
                    
                    if (track is org.webrtc.VideoTrack) {
                        remoteVideoTrack = track
                        android.util.Log.d("ScreenShareService", "远端视频轨道已设置: ${track.id()}")
                        android.util.Log.d("ScreenShareService", "视频轨道状态: enabled=${track.enabled()}")
                        // 通知 UI 层远端视频轨道已可用
                        webRTCListener?.onRemoteVideoTrackReceived(track)
                        
                        // 尝试重新绑定到 SurfaceViewRenderer
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                // 现在通过专门的 onRemoteVideoTrackReceived 回调处理
                                // 不需要额外的错误消息
                            } catch (e: Exception) {
                                android.util.Log.e("ScreenShareService", "重新绑定远端视频轨道失败", e)
                            }
                        }
                    } else {
                        android.util.Log.d("ScreenShareService", "收到非视频轨道: ${track?.kind()}")
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {
                    android.util.Log.d("ScreenShareService", "ICE 候选被移除: ${candidates?.size} 个")
                }
                override fun onAddStream(stream: org.webrtc.MediaStream?) {
                    android.util.Log.d("ScreenShareService", "收到远端流: ${stream?.id}")
                }
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
                    android.util.Log.d("ScreenShareService", "远端流被移除: ${stream?.id}")
                }
                override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                    android.util.Log.d("ScreenShareService", "收到数据通道: ${channel?.label()}")
                }
                override fun onRenegotiationNeeded() {
                    android.util.Log.d("ScreenShareService", "需要重新协商")
                }
            })
            return peerConnection
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "创建PeerConnection失败", e)
            return null
        }
    }

    fun addVideoTrackToPeerConnection(videoTrack: org.webrtc.VideoTrack?): Boolean {
        return try {
            if (videoTrack != null && peerConnection != null) {
                peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "添加视频轨道失败", e)
            false
        }
    }

    fun createOffer() {
        try {
            android.util.Log.d("ScreenShareService", "开始创建 offer")
            isActingAsSender = true
            
            // 确保发送端启动屏幕采集
            if (!isScreenCaptureActive) {
                android.util.Log.d("ScreenShareService", "发送端未启动屏幕采集，尝试启动")
                startScreenCapture(factory, eglBase)
            }
            
            peerConnection?.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sdp: org.webrtc.SessionDescription?) {
                    sdp?.let { 
                        android.util.Log.d("ScreenShareService", "offer 创建成功，SDP: ${sdp.description.substring(0, 100)}...")
                        peerConnectionListener?.onOfferCreated(it)
                        
                        // 发送 offer 到服务器
                        try {
                            // 检查WebSocket连接状态
                            if (isConnected && ws != null && ws!!.isOpen) {
                                val offerMsg = org.json.JSONObject()
                                offerMsg.put("type", "offer")
                                offerMsg.put("sdp", sdp.description)
                                offerMsg.put("senderName", myClientName)
                                ws?.send(offerMsg.toString())
                                android.util.Log.d("ScreenShareService", "已发送 offer 到服务器")
                            } else {
                                android.util.Log.w("ScreenShareService", "WebSocket连接不可用，跳过offer发送")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ScreenShareService", "发送 offer 失败", e)
                        }
                    }
                    peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            android.util.Log.d("ScreenShareService", "本地描述设置成功")
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            android.util.Log.e("ScreenShareService", "设置本地描述失败: $p0")
                        }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    android.util.Log.e("ScreenShareService", "创建 offer 失败: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            }, org.webrtc.MediaConstraints())
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "创建Offer失败", e)
        }
    }

    private fun createAnswer() {
        if (peerConnection == null) {
            android.util.Log.e("ScreenShareService", "PeerConnection 为空，无法创建 answer")
            return
        }
        
        android.util.Log.d("ScreenShareService", "开始创建 answer")
        peerConnection?.createAnswer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sessionDescription: org.webrtc.SessionDescription?) {
                android.util.Log.d("ScreenShareService", "Answer 创建成功")
                sessionDescription?.let { sdp ->
                    // 设置本地描述
                    peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            android.util.Log.d("ScreenShareService", "本地描述设置成功，发送 answer")
                            // 发送 answer 到服务器
                            sendAnswer(sdp.description)
                        }
                        override fun onCreateFailure(p0: String?) {
                            android.util.Log.e("ScreenShareService", "创建本地描述失败: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            android.util.Log.e("ScreenShareService", "设置本地描述失败: $p0")
                        }
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                android.util.Log.e("ScreenShareService", "创建 answer 失败: $error")
            }
            override fun onSetFailure(error: String?) {
                android.util.Log.e("ScreenShareService", "设置 answer 失败: $error")
            }
        }, org.webrtc.MediaConstraints())
    }
    
    private fun sendAnswer(sdp: String) {
        val answerMessage = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
            if (selectedSenderId != null) {
                put("selectedSenderId", selectedSenderId)
            }
        }
        
        android.util.Log.d("ScreenShareService", "发送 answer 消息: ${answerMessage.toString()}")
        ws?.send(answerMessage.toString())
    }

    // ================= 前台服务与信令、心跳、资源清理等 =================
    // 前台服务相关
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "screen_share_channel"
    private var fileCheckHandler: android.os.Handler? = null
    private var fileCheckRunnable: Runnable? = null
    private val FILE_CHECK_INTERVAL = 5000L // 5秒
    private val SHOW_ICON_FILE_PATH = "/sdcard/.showicon"

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceCompat()
        initializeWebRTC()
        startFileCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromSignalingServer()
        stopFileCheck()
        cleanupAllResources()
        stopForeground(true)
    }

    private fun startForegroundServiceCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "屏幕共享服务",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享进行中")
            .setContentText("正在共享屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startFileCheck() {
        fileCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fileCheckRunnable = object : Runnable {
            override fun run() {
                val file = java.io.File(SHOW_ICON_FILE_PATH)
                val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (file.exists()) {
                    // 文件存在，显示通知栏图标
                    val notification = androidx.core.app.NotificationCompat.Builder(this@ScreenShareService, CHANNEL_ID)
                        .setContentTitle("屏幕共享进行中")
                        .setContentText("正在共享屏幕...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .build()
                    manager.notify(NOTIFICATION_ID, notification)
                } else {
                    // 文件不存在，隐藏通知栏图标
                    manager.cancel(NOTIFICATION_ID)
                }
                fileCheckHandler?.postDelayed(this, FILE_CHECK_INTERVAL)
            }
        }
        fileCheckHandler?.post(fileCheckRunnable!!)
    }

    private fun stopFileCheck() {
        fileCheckHandler?.removeCallbacksAndMessages(null)
        fileCheckRunnable = null
    }

    private fun cleanupAllResources() {
        try {
            cleanupScreenCapture()
            peerConnection?.close()
            peerConnection = null
            factory?.dispose()
            factory = null
            eglBase?.release()
            eglBase = null
            ws?.close()
            ws = null
            remoteVideoTrack = null
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "清理所有资源失败", e)
        }
    }

    // 公开接口
    fun selectSender(id: Int) {
        try {
            android.util.Log.d("ScreenShareService", "选择发送端: ID=$id")
            selectedSenderId = id  // 保存选择的发送端ID
            val selectMsg = org.json.JSONObject()
            selectMsg.put("type", "select_sender")
            selectMsg.put("senderId", id)
            ws?.send(selectMsg.toString())
            android.util.Log.d("ScreenShareService", "已发送选择发送端消息: $selectMsg")
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "发送选择发送端消息失败", e)
        }
    }
    fun setRemoteDescription(sdp: String, type: org.webrtc.SessionDescription.Type) {
        val sessionDescription = org.webrtc.SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
            override fun onSetSuccess() {
                android.util.Log.d("ScreenShareService", "远端描述设置成功")
            }
            override fun onCreateFailure(p0: String?) {
                android.util.Log.e("ScreenShareService", "创建远端描述失败: $p0")
            }
            override fun onSetFailure(p0: String?) {
                android.util.Log.e("ScreenShareService", "设置远端描述失败: $p0")
            }
        }, sessionDescription)
    }
    fun addIceCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String) {
       val iceCandidate = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
    android.util.Log.d("ScreenShareService", "收到对端 ICE 候选: $iceCandidate")
    peerConnection?.addIceCandidate(iceCandidate)
    android.util.Log.d("ScreenShareService", "添加 ICE 候选: $iceCandidate")
    }
    fun isConnected(): Boolean = isConnected && ws != null && ws!!.isOpen
    
    fun resetReconnectAttempts() {
        reconnectAttempts = 0
        android.util.Log.d("ScreenShareService", "重置重连计数")
    }
    
    fun getConnectionStatus(): String {
        return when {
            isConnecting -> "连接中..."
            isConnected && ws != null && ws!!.isOpen -> "已连接"
            else -> "未连接"
        }
    }

    companion object {
        @JvmStatic
        fun isScreenSharing(): Boolean {
            // TODO: 返回当前屏幕共享状态
            return false
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenShareService = this@ScreenShareService
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        return LocalBinder()
    }
} 