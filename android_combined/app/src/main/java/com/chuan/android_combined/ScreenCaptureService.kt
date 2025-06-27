package com.chuan.android_combined

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.io.File
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

class ScreenCaptureService : Service() {
    private val binder = LocalBinder()
    
    // WebRTC相关变量
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var isConnected = false
    private var serverAddress = ""
    private var senderName = "发送端"
    private var mediaProjectionData: Intent? = null
    
    // 文件检测相关变量
    private var handler: Handler? = null
    private var fileCheckRunnable: Runnable? = null
    
    // 自适应质量调整相关变量
    private var currentBitrate = 2000 * 1000 // 当前比特率 2Mbps
    private var currentFramerate = 30 // 当前帧率
    private var qualityAdjustmentHandler: Handler? = null
    private var qualityAdjustmentRunnable: Runnable? = null
    
    // 心跳相关变量
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    
    // 视频状态检查相关变量
    private var videoStatusHandler: Handler? = null
    private var videoStatusRunnable: Runnable? = null
    
    // 视频编码参数相关变量
    private var currentBitrateBps = 4_000_000 // 当前比特率 4Mbps
    private var trackAdded = false
    
    // 新增成员变量
    private var resultCode: Int = 0
    private var data: Intent? = null
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val FILE_CHECK_INTERVAL = 5000L // 5秒
        private const val SHOW_ICON_FILE_PATH = "/sdcard/.showicon"
        
        // 添加静态变量来跟踪屏幕共享状态
        @Volatile
        private var isScreenSharingActive = false
        
        /**
         * 检查是否正在屏幕共享
         */
        fun isScreenSharing(): Boolean {
            return isScreenSharingActive
        }
        
        /**
         * 设置屏幕共享状态
         */
        fun setScreenSharingActive(active: Boolean) {
            isScreenSharingActive = active
        }
        
        /**
         * 从主界面启动连接
         */
        fun connectToSignalingServer(context: Context, address: String, name: String, projectionData: Intent) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            context.startService(serviceIntent)
            
            // 延迟一点时间确保服务启动
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val service = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val runningServices = service.getRunningServices(Integer.MAX_VALUE)
                    val screenCaptureService = runningServices.find { it.service.className == ScreenCaptureService::class.java.name }
                    
                    if (screenCaptureService != null) {
                        // 服务已启动，直接调用连接方法
                        val intent = Intent(context, ScreenCaptureService::class.java)
                        intent.action = "CONNECT"
                        intent.putExtra("address", address)
                        intent.putExtra("name", name)
                        intent.putExtra("projection_data", projectionData)
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "启动连接失败", e)
                }
            }, 1000)
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenCaptureService onCreate")
        startForeground()
        initializeWebRTC()
        
        // 启动文件检测机制
        startFileCheck()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenCaptureService onStartCommand")
        startForeground()
        
        // 处理从主界面发来的连接意图
        if (intent?.action == "CONNECT") {
            val address = intent.getStringExtra("address")
            val name = intent.getStringExtra("name")
            val projectionData = intent.getParcelableExtra<Intent>("projection_data")
            
            if (address != null && name != null && projectionData != null) {
                Log.d(TAG, "收到主界面连接请求: $address, 名称: $name")
                connectToSignalingServer(address, name, projectionData, 0)
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenCaptureService onDestroy")
        disconnectFromSignalingServer()
        stopFileCheck()
        stopForeground(true)
    }
    
    /**
     * 启动文件检测机制
     */
    private fun startFileCheck() {
        handler = Handler(Looper.getMainLooper())
        fileCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    checkShowIconFile()
                    // 继续下一次检测
                    handler?.postDelayed(this, FILE_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "文件检测机制出错", e)
                }
            }
        }
        
        handler?.postDelayed(fileCheckRunnable!!, FILE_CHECK_INTERVAL)
        Log.d(TAG, "文件检测机制已启动")
    }
    
    /**
     * 停止文件检测机制
     */
    private fun stopFileCheck() {
        fileCheckRunnable?.let { handler?.removeCallbacks(it) }
        fileCheckRunnable = null
        handler = null
        Log.d(TAG, "文件检测机制已停止")
    }
    
    /**
     * 检查显示图标文件
     */
    private fun checkShowIconFile() {
        try {
            val file = File(SHOW_ICON_FILE_PATH)
            if (file.exists()) {
                Log.d(TAG, "检测到显示图标文件，准备恢复应用图标")
                
                // 恢复应用图标
                val success = AppIconUtils.showAppIcon(this, autoLaunch = false)
                
                if (success) {
                    Log.d(TAG, "应用图标恢复成功")
                    
                    // 删除触发文件
                    if (file.delete()) {
                        Log.d(TAG, "触发文件已删除")
                    } else {
                        Log.w(TAG, "删除触发文件失败")
                    }
                    
                    // 延迟启动应用，给用户一些时间看到提示
                    handler?.postDelayed({
                        try {
                            val launchIntent = Intent(this, MainDispatcherActivity::class.java)
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(launchIntent)
                            Log.d(TAG, "应用启动成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "启动应用时出错", e)
                        }
                    }, 1000) // 延迟1秒启动
                } else {
                    Log.w(TAG, "应用图标恢复失败")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查显示图标文件时出错", e)
        }
    }
    
    /**
     * 初始化WebRTC
     */
    private fun initializeWebRTC() {
        try {
            eglBase = EglBase.create()
            
            // 初始化PeerConnectionFactory
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            
            // 优化编码器配置
            val videoEncoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext, 
                true,  // enableIntelVp8Encoder
                true   // enableH264HighProfile
            )
            
            val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
                
            Log.d(TAG, "WebRTC初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC初始化失败", e)
        }
    }
    
    /**
     * 连接到信令服务器
     */
    fun connectToSignalingServer(address: String, name: String, projectionData: Intent, resultCode: Int) {
        try {
            serverAddress = address
            senderName = name
            mediaProjectionData = projectionData
            this.resultCode = resultCode
            
            val wsUrl = "ws://$serverAddress"
            ws = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "WebSocket连接已建立")
                    startScreenCapture()
                    startHeartbeat()
                }
                
                override fun onMessage(msg: String) {
                    try {
                        Log.d(TAG, "收到WebSocket消息: $msg")
                        val json = JSONObject(msg)
                        when (json.getString("type")) {
                            "answer" -> {
                                Log.d(TAG, "收到answer消息，设置远程描述")
                                val sdp = SessionDescription(
                                    SessionDescription.Type.ANSWER, 
                                    json.getString("sdp")
                                )
                                Log.d(TAG, "Answer SDP内容: ${sdp.description.substring(0, 100)}...")
                                if (sdp.description.contains("m=video")) {
                                    Log.d(TAG, "Answer SDP中包含视频轨道")
                                } else {
                                    Log.w(TAG, "Answer SDP中不包含视频轨道")
                                }
                                peerConnection?.setRemoteDescription(
                                    SimpleSdpObserver(), 
                                    sdp
                                )
                            }
                            "candidate" -> {
                                Log.d(TAG, "收到candidate消息")
                                val candidate = IceCandidate(
                                    json.getString("id"), 
                                    json.getInt("label"), 
                                    json.getString("candidate")
                                )
                                Log.d(TAG, "ICE候选信息: ${candidate.sdp}")
                                peerConnection?.addIceCandidate(candidate)
                                Log.d(TAG, "ICE候选已添加")
                            }
                            "heartbeat_ack" -> {
                                Log.d(TAG, "收到心跳确认")
                            }
                            else -> {
                                Log.d(TAG, "收到未处理的消息类型: ${json.getString("type")}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理WebSocket消息失败", e)
                    }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket连接已关闭")
                    stopHeartbeat()
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket连接错误", ex)
                    stopHeartbeat()
                }
            }
            
            ws?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
        }
    }
    
    /**
     * 开始屏幕捕获
     */
    private fun startScreenCapture() {
        // 屏幕截图请求
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
        
        if (mediaProjection == null) {
            Log.e(TAG, "无法获取MediaProjection")
            return
        }
        
        Log.d(TAG, "开始获取屏幕画面")
        
        // 配置视频源
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer = createScreenCapturer()
        val videoSource = factory!!.createVideoSource(videoCapturer!!.isScreencast)
        
        // 使用更高的分辨率和帧率
        val videoWidth = 1920
        val videoHeight = 1080
        val fps = 30
        
        // 初始化视频捕获器
        videoCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
        
        // 使用高分辨率开始捕获
        videoCapturer!!.startCapture(videoWidth, videoHeight, fps)
        Log.d(TAG, "屏幕捕获已启动，分辨率: ${videoWidth}x${videoHeight}, 帧率: $fps")
        
        // 创建视频轨道并添加到PeerConnection
        videoTrack = factory!!.createVideoTrack("video-track", videoSource)
        
        // 设置高优先级以保证视频流畅
        videoTrack?.setEnabled(true)
        
        // 创建PeerConnection
        if (peerConnection == null) {
            // 创建STUN/TURN服务器配置
            val rtcConfig = PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                )
            )
            
            // 设置ICE配置
            rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            rtcConfig.enableDtlsSrtp = true
            rtcConfig.enableCpuOveruseDetection = true
            
            // 创建PeerConnection
            peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    // 省略现有代码
                }
                
                // 其他回调实现
                override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(mediaStream: MediaStream?) {}
                override fun onRemoveStream(mediaStream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            })
        }
        
        // 确保正确添加视频轨道
        if (videoTrack != null && !trackAdded) {
            peerConnection?.addTrack(videoTrack!!)
            trackAdded = true
            Log.d(TAG, "视频轨道已添加到PeerConnection")
            
            // 创建offer
            val constraints = MediaConstraints()
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    Log.d(TAG, "创建offer成功")
                    
                    // 使用增强的SDP (增加分辨率和比特率参数)
                    var sdpString = sessionDescription.description
                    
                    // 设置高分辨率
                    sdpString = sdpString.replace("a=fmtp:96", "a=fmtp:96 max-fs=8160;max-fr=30")
                    
                    // 设置为屏幕共享优化模式
                    if (!sdpString.contains("content:slides")) {
                        sdpString = sdpString.replace("m=video", "a=content:slides\r\nm=video")
                    }
                    
                    // 创建新的SDP对象
                    val enhancedSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                    
                    // 设置本地描述
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "设置本地描述成功")
                            val offer = JSONObject()
                            offer.put("type", "offer")
                            offer.put("sdp", enhancedSdp.description)
                            offer.put("senderName", senderName)
                            ws?.send(offer.toString())
                            Log.d(TAG, "offer已发送")
                        }
                        
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e(TAG, "设置本地描述失败: $p0")
                        }
                    }, enhancedSdp)
                }
                
                override fun onSetSuccess() {}
                
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "创建offer失败: $error")
                }
                
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
        
        // 启动视频状态监控
        startVideoStatusMonitoring()
        
        // 启动自适应质量调整
        startQualityAdjustment()
        
        // 启动视频编码器配置
        setVideoEncodingParameters(videoWidth, videoHeight, fps)
        
        // 设置屏幕共享状态为true
        setScreenSharingActive(true)
    }
    
    // 设置视频编码参数
    private fun setVideoEncodingParameters(width: Int, height: Int, fps: Int) {
        // 获取所有发送器
        val senders = peerConnection?.senders
        if (senders.isNullOrEmpty()) {
            Log.w(TAG, "没有可用的发送器来配置视频参数")
            return
        }
        
        // 查找视频发送器
        val videoSender = senders.find { it.track()?.kind() == "video" }
        if (videoSender == null) {
            Log.w(TAG, "未找到视频发送器")
            return
        }
        
        // 配置RTP参数
        val parameters = videoSender.parameters
        if (parameters.encodings.isEmpty()) {
            Log.w(TAG, "视频发送器没有编码参数")
            return
        }
        
        // 设置高质量编码参数
        for (encoding in parameters.encodings) {
            // 设置较高的最大比特率 (2Mbps)
            encoding.maxBitrateBps = 4_000_000
            
            // 设置较高的帧率
            encoding.maxFramerate = fps
            
            // 设置活跃状态和优先级
            encoding.active = true
            encoding.networkPriority = 2
            
            // 不进行缩放
            encoding.scaleResolutionDownBy = 1.0
        }
        
        // 应用参数
        val result = videoSender.setParameters(parameters)
        Log.d(TAG, "视频发送参数已设置: $result, 分辨率: ${width}x${height}, 帧率: $fps, 最大比特率: 4Mbps")
        
        // 保存当前比特率值以便状态检查
        currentBitrateBps = 4_000_000
    }
    
    /**
     * 断开信令服务器连接
     */
    private fun disconnectFromSignalingServer() {
        try {
            isConnected = false
            
            // 设置屏幕共享状态为false
            setScreenSharingActive(false)
            
            // 停止自适应质量调整
            stopQualityAdjustment()
            
            // 停止视频状态监控
            stopVideoStatusMonitoring()
            
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            
            videoTrack?.dispose()
            videoTrack = null
            
            videoSource?.dispose()
            videoSource = null
            
            peerConnection?.close()
            peerConnection = null
            
            ws?.close()
            ws = null
            
            factory?.dispose()
            factory = null
            
            eglBase?.release()
            eglBase = null
            
            Log.d(TAG, "WebRTC连接已断开")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接时出错", e)
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected
    
    private fun startForeground() {
        val channelId = createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕共享")
            .setContentText("正在共享您的屏幕")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel(): String {
        val channelId = "screen_capture_service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "屏幕共享服务"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于支持屏幕共享功能的通知"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        return channelId
    }
    
    class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    
    /**
     * 启动自适应质量调整
     */
    private fun startQualityAdjustment() {
        qualityAdjustmentHandler = Handler(Looper.getMainLooper())
        qualityAdjustmentRunnable = object : Runnable {
            override fun run() {
                try {
                    adjustVideoQuality()
                    // 每10秒调整一次质量
                    qualityAdjustmentHandler?.postDelayed(this, 10000)
                } catch (e: Exception) {
                    Log.e(TAG, "质量调整出错", e)
                }
            }
        }
        
        qualityAdjustmentHandler?.postDelayed(qualityAdjustmentRunnable!!, 10000)
        Log.d(TAG, "自适应质量调整已启动")
    }
    
    /**
     * 停止自适应质量调整
     */
    private fun stopQualityAdjustment() {
        qualityAdjustmentRunnable?.let { qualityAdjustmentHandler?.removeCallbacks(it) }
        qualityAdjustmentRunnable = null
        qualityAdjustmentHandler = null
        Log.d(TAG, "自适应质量调整已停止")
    }
    
    /**
     * 根据网络状况调整视频质量
     */
    private fun adjustVideoQuality() {
        try {
            val videoSender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
            if (videoSender != null) {
                val parameters = videoSender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    val encoding = parameters.encodings[0]
                    
                    // 根据当前网络状况调整比特率
                    // 这里可以根据实际的网络质量指标来调整
                    // 暂时使用简单的自适应逻辑
                    val newBitrate = when {
                        currentBitrateBps > 3000 * 1000 -> {
                            // 如果当前比特率过高，降低
                            currentBitrateBps - 500 * 1000
                        }
                        currentBitrateBps < 1000 * 1000 -> {
                            // 如果当前比特率过低，适当提高
                            currentBitrateBps + 200 * 1000
                        }
                        else -> currentBitrateBps
                    }
                    
                    if (newBitrate != currentBitrateBps) {
                        currentBitrateBps = newBitrate
                        encoding.maxBitrateBps = currentBitrateBps
                        encoding.minBitrateBps = currentBitrateBps / 4
                        
                        videoSender.setParameters(parameters)
                        Log.d(TAG, "视频质量已调整: 比特率=${currentBitrateBps/1000}Kbps")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "调整视频质量失败", e)
        }
    }
    
    /**
     * 启动心跳机制
     */
    private fun startHeartbeat() {
        heartbeatHandler = Handler(Looper.getMainLooper())
        heartbeatRunnable = object : Runnable {
            override fun run() {
                try {
                    val heartbeat = JSONObject()
                    heartbeat.put("type", "heartbeat")
                    heartbeat.put("timestamp", System.currentTimeMillis())
                    ws?.send(heartbeat.toString())
                    
                    // 每30秒发送一次心跳
                    heartbeatHandler?.postDelayed(this, 30000)
                } catch (e: Exception) {
                    Log.e(TAG, "发送心跳失败", e)
                }
            }
        }
        
        heartbeatHandler?.postDelayed(heartbeatRunnable!!, 30000)
        Log.d(TAG, "心跳机制已启动")
    }
    
    /**
     * 停止心跳机制
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatHandler = null
        Log.d(TAG, "心跳机制已停止")
    }
    
    /**
     * 启动视频状态监控
     */
    private fun startVideoStatusMonitoring() {
        videoStatusHandler = Handler(Looper.getMainLooper())
        videoStatusRunnable = object : Runnable {
            override fun run() {
                try {
                    checkVideoStatus()
                    // 每5秒检查一次视频状态
                    videoStatusHandler?.postDelayed(this, 5000)
                } catch (e: Exception) {
                    Log.e(TAG, "视频状态检查出错", e)
                }
            }
        }
        
        videoStatusHandler?.postDelayed(videoStatusRunnable!!, 5000)
        Log.d(TAG, "视频状态监控已启动")
    }
    
    /**
     * 停止视频状态监控
     */
    private fun stopVideoStatusMonitoring() {
        videoStatusRunnable?.let { videoStatusHandler?.removeCallbacks(it) }
        videoStatusRunnable = null
        videoStatusHandler = null
        Log.d(TAG, "视频状态监控已停止")
    }
    
    /**
     * 检查视频状态
     */
    private fun checkVideoStatus() {
        try {
            Log.d(TAG, "视频状态检查:")
            Log.d(TAG, "- 视频捕获器状态: ${if (videoCapturer != null) "活跃" else "未初始化"}")
            Log.d(TAG, "- 视频源状态: ${if (videoSource != null) "活跃" else "未初始化"}")
            Log.d(TAG, "- 视频轨道状态: ${videoTrack?.state()}")
            Log.d(TAG, "- PeerConnection状态: ${peerConnection?.connectionState()}")
            Log.d(TAG, "- ICE连接状态: ${peerConnection?.iceConnectionState()}")
            
            // 检查是否有视频发送器
            val videoSender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
            if (videoSender != null) {
                Log.d(TAG, "- 视频发送器: 已找到")
                val parameters = videoSender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    val encoding = parameters.encodings[0]
                    val bitrate = encoding.maxBitrateBps ?: 0
                    Log.d(TAG, "- 当前比特率: ${bitrate / 1000}Kbps")
                }
            } else {
                Log.w(TAG, "- 视频发送器: 未找到")
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查视频状态失败", e)
        }
    }
    
    /**
     * 发送一个空的ICE候选以确保连接可靠
     */
    private fun sendDummyCandidateIfNeeded() {
        try {
            if (peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED) {
                Log.d(TAG, "连接已建立，发送确认消息")
                val json = JSONObject()
                json.put("type", "connection_confirm")
                json.put("timestamp", System.currentTimeMillis())
                ws?.send(json.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送确认消息失败", e)
        }
    }
    
    /**
     * 安排重新连接
     */
    private fun scheduleReconnection() {
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                if (peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.d(TAG, "尝试重新协商连接")
                    restartIce()
                }
            }, 3000) // 3秒后尝试重连
        } catch (e: Exception) {
            Log.e(TAG, "安排重连失败", e)
        }
    }
    
    /**
     * 重启ICE连接
     */
    private fun restartIce() {
        try {
            Log.d(TAG, "重启ICE连接")
            // 创建新的offer，带ICE重启标志
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
            
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    Log.d(TAG, "ICE重启Offer创建成功")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            val offer = JSONObject()
                            offer.put("type", "offer")
                            offer.put("sdp", sessionDescription.description)
                            offer.put("senderName", senderName)
                            offer.put("iceRestart", true)
                            ws?.send(offer.toString())
                            Log.d(TAG, "ICE重启Offer已发送")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e(TAG, "ICE重启设置本地描述失败")
                        }
                    }, sessionDescription)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "ICE重启创建offer失败: $error")
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "重启ICE失败", e)
        }
    }
    
    private fun createScreenCapturer(): VideoCapturer {
        // 这里假设使用 WebRTC 的 ScreenCapturerAndroid
        return ScreenCapturerAndroid(data, object : MediaProjection.Callback() {})
    }
} 