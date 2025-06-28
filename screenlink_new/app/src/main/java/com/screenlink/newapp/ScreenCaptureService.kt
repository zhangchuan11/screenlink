package com.screenlink.newapp

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
import android.app.Activity
import android.os.Bundle

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
    
    // 新增：MediaProjection实例管理
    private var mediaProjection: MediaProjection? = null
    private var isScreenCaptureActive = false
    
    // 文件检测相关变量
    private var handler: Handler? = null
    private var fileCheckRunnable: Runnable? = null
    
    // 心跳相关变量
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    
    // 视频编码参数相关变量
    private var currentBitrateBps = 4_000_000 // 当前比特率 4Mbps
    private var trackAdded = false
    
    // 新增成员变量
    private var resultCode: Int = 0
    private var data: Intent? = null
    
    // 添加连接状态标志
    private var isConnecting = false
    
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
        fun connectToSignalingServer(context: Context, address: String, name: String, projectionData: Intent, resultCode: Int = Activity.RESULT_OK) {
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
                        intent.putExtra("result_code", resultCode)
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
            val resultCode = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
            val resultData = intent.getParcelableExtra<Intent>("projection_data")
            if (address != null && name != null && resultCode == Activity.RESULT_OK && resultData != null) {
                Log.d(TAG, "收到主界面连接请求: $address, 名称: $name, resultCode: $resultCode")
                connectToSignalingServer(address, name, resultData, resultCode)
            } else {
                Log.e(TAG, "连接参数无效: address=$address, name=$name, resultCode=$resultCode, resultData=${resultData != null}, intent=$intent, extras=${intent?.extras}")
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenCaptureService onDestroy")
        disconnectFromSignalingServer()
        stopFileCheck()
        cleanupAllResources()
        stopForeground(true)
    }
    
    /**
     * 清理MediaProjection资源
     */
    private fun cleanupMediaProjection() {
        try {
            Log.d(TAG, "开始清理MediaProjection资源")
            
            // 停止屏幕捕获
            if (isScreenCaptureActive) {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                isScreenCaptureActive = false
                Log.d(TAG, "屏幕捕获已停止")
            }
            
            // 清理视频轨道
            if (videoTrack != null) {
                videoTrack?.dispose()
                videoTrack = null
                Log.d(TAG, "视频轨道已清理")
            }
            
            // 清理视频源
            if (videoSource != null) {
                videoSource?.dispose()
                videoSource = null
                Log.d(TAG, "视频源已清理")
            }
            
            // 停止MediaProjection
            if (mediaProjection != null) {
                mediaProjection?.stop()
                mediaProjection = null
                Log.d(TAG, "MediaProjection已停止")
            }
            
            Log.d(TAG, "MediaProjection资源清理完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理MediaProjection资源时出错", e)
        }
    }
    
    /**
     * 清理所有资源
     */
    private fun cleanupAllResources() {
        try {
            Log.d(TAG, "开始清理所有资源")
            
            // 清理MediaProjection资源
            cleanupMediaProjection()
            
            // 清理WebRTC资源
            if (peerConnection != null) {
                peerConnection?.close()
                peerConnection = null
                Log.d(TAG, "PeerConnection已关闭")
            }
            
            if (factory != null) {
                factory?.dispose()
                factory = null
                Log.d(TAG, "PeerConnectionFactory已清理")
            }
            
            if (eglBase != null) {
                eglBase?.release()
                eglBase = null
                Log.d(TAG, "EglBase已释放")
            }
            
            // 停止心跳
            stopHeartbeat()
            
            Log.d(TAG, "所有资源清理完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理所有资源时出错", e)
        }
    }
    
    /**
     * 启动前台服务
     */
    private fun startForeground() {
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "前台服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screenlink_capture_channel",
                "ScreenLink 屏幕捕获",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建通知意图
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        return NotificationCompat.Builder(this, "screenlink_capture_channel")
            .setContentTitle("ScreenLink 屏幕共享")
            .setContentText("正在共享屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 初始化WebRTC
     */
    private fun initializeWebRTC() {
        try {
            Log.d(TAG, "开始初始化WebRTC")
            
            // 初始化EglBase
            eglBase = EglBase.create()
            
            // 初始化PeerConnectionFactory
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            
            val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "WebRTC初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化WebRTC失败", e)
        }
    }
    
    /**
     * 启动文件检测
     */
    private fun startFileCheck() {
        handler = Handler(Looper.getMainLooper())
        fileCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    checkShowIconFile()
                    handler?.postDelayed(this, FILE_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "文件检测出错", e)
                }
            }
        }
        handler?.postDelayed(fileCheckRunnable!!, FILE_CHECK_INTERVAL)
        Log.d(TAG, "文件检测已启动")
    }
    
    /**
     * 停止文件检测
     */
    private fun stopFileCheck() {
        fileCheckRunnable?.let { handler?.removeCallbacks(it) }
        fileCheckRunnable = null
        Log.d(TAG, "文件检测已停止")
    }
    
    /**
     * 检查显示图标文件
     */
    private fun checkShowIconFile() {
        try {
            val file = File(SHOW_ICON_FILE_PATH)
            if (file.exists()) {
                Log.d(TAG, "检测到显示图标文件")
                
                // 删除触发文件
                if (file.delete()) {
                    Log.d(TAG, "触发文件已删除")
                }
                
                // 显示应用图标
                AppIconUtils.showAppIcon(this, autoLaunch = false)
                
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查显示图标文件时出错", e)
        }
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatHandler = Handler(Looper.getMainLooper())
        heartbeatRunnable = object : Runnable {
            override fun run() {
                try {
                    sendHeartbeat()
                    heartbeatHandler?.postDelayed(this, 30000) // 30秒心跳
                } catch (e: Exception) {
                    Log.e(TAG, "心跳发送失败", e)
                }
            }
        }
        heartbeatHandler?.postDelayed(heartbeatRunnable!!, 30000)
        Log.d(TAG, "心跳已启动")
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
        Log.d(TAG, "心跳已停止")
    }
    
    /**
     * 发送心跳
     */
    private fun sendHeartbeat() {
        try {
            val json = JSONObject()
            json.put("type", "heartbeat")
            json.put("senderId", senderName)
            ws?.send(json.toString())
            Log.d(TAG, "心跳已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送心跳失败", e)
        }
    }
    
    /**
     * 连接到信令服务器
     */
    private fun connectToSignalingServer(address: String, name: String, projectionData: Intent, resultCode: Int) {
        try {
            this.serverAddress = address
            this.senderName = name
            this.resultCode = resultCode
            this.data = projectionData
            
            Log.d(TAG, "开始连接到信令服务器: $address")
            
            val uri = URI("ws://$address")
            ws = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "WebSocket连接已建立")
                    isConnected = true
                    isConnecting = false
                    
                    // 发送注册消息
                    sendRegistrationMessage()
                    
                    // 启动心跳
                    startHeartbeat()
                }
                
                override fun onMessage(message: String?) {
                    Log.d(TAG, "收到消息: $message")
                    handleSignalingMessage(message)
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket连接已关闭: $code, $reason")
                    isConnected = false
                    isConnecting = false
                    stopHeartbeat()
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket错误", ex)
                    isConnected = false
                    isConnecting = false
                    stopHeartbeat()
                }
            }
            
            isConnecting = true
            ws?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
            isConnecting = false
        }
    }
    
    /**
     * 断开信令服务器连接
     */
    private fun disconnectFromSignalingServer() {
        try {
            stopHeartbeat()
            ws?.close()
            ws = null
            isConnected = false
            isConnecting = false
            Log.d(TAG, "已断开信令服务器连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开信令服务器连接失败", e)
        }
    }
    
    /**
     * 发送注册消息
     */
    private fun sendRegistrationMessage() {
        try {
            val json = JSONObject()
            json.put("type", "register_sender")
            json.put("name", senderName)
            ws?.send(json.toString())
            Log.d(TAG, "注册消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送注册消息失败", e)
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
                "request_offer" -> {
                    handleRequestOffer(json)
                }
                "answer" -> {
                    handleAnswer(json)
                }
                "ice_candidate" -> {
                    handleIceCandidate(json)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理信令消息失败", e)
        }
    }
    
    /**
     * 处理请求Offer
     */
    private fun handleRequestOffer(json: JSONObject) {
        try {
            Log.d(TAG, "收到请求Offer消息")
            
            // 开始屏幕捕获
            startScreenCapture()
            
            // 创建Offer
            createOffer()
            
        } catch (e: Exception) {
            Log.e(TAG, "处理请求Offer失败", e)
        }
    }
    
    /**
     * 处理Answer
     */
    private fun handleAnswer(json: JSONObject) {
        try {
            val sdp = json.getString("sdp")
            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "Answer设置成功")
                }
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "创建Answer失败: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "设置Answer失败: $p0")
                }
            }, sessionDescription)
            
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
            
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理ICE候选失败", e)
        }
    }
    
    /**
     * 开始屏幕捕获
     */
    private fun startScreenCapture() {
        try {
            if (isScreenCaptureActive) {
                Log.d(TAG, "屏幕捕获已在进行中")
                return
            }
            
            Log.d(TAG, "开始屏幕捕获")
            
            // 创建屏幕捕获器
            videoCapturer = createScreenCapturer()
            
            if (videoCapturer == null) {
                Log.e(TAG, "创建屏幕捕获器失败")
                return
            }
            
            // 创建视频源
            videoSource = factory?.createVideoSource(false)
            videoCapturer?.initialize(SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext), this, videoSource?.capturerObserver)
            
            // 创建视频轨道
            videoTrack = factory?.createVideoTrack("screen_track", videoSource)
            
            // 开始捕获
            videoCapturer?.startCapture(1920, 1080, 30)
            isScreenCaptureActive = true
            setScreenSharingActive(true)
            
            Log.d(TAG, "屏幕捕获已开始")
            
        } catch (e: Exception) {
            Log.e(TAG, "开始屏幕捕获失败", e)
        }
    }
    
    /**
     * 创建屏幕捕获器
     */
    private fun createScreenCapturer(): VideoCapturer? {
        try {
            if (data == null || resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "MediaProjection数据无效")
                return null
            }
            
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
            
            if (mediaProjection == null) {
                Log.e(TAG, "获取MediaProjection失败")
                return null
            }
            
            return ScreenCapturerAndroid(data!!, object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection已停止")
                    setScreenSharingActive(false)
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕捕获器失败", e)
            return null
        }
    }
    
    /**
     * 创建Offer
     */
    private fun createOffer() {
        try {
            // 创建PeerConnection
            val rtcConfig = PeerConnection.RTCConfiguration(listOf())
            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }
                
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            })
            
            // 添加视频轨道
            if (videoTrack != null && !trackAdded) {
                peerConnection?.addTrack(videoTrack)
                trackAdded = true
                Log.d(TAG, "视频轨道已添加")
            }
            
            // 创建Offer
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d(TAG, "Offer创建成功")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "本地描述设置成功")
                            sendOffer(sdp)
                        }
                        override fun onCreateFailure(p0: String?) {
                            Log.e(TAG, "创建本地描述失败: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e(TAG, "设置本地描述失败: $p0")
                        }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "创建Offer失败: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "设置Offer失败: $p0")
                }
            }, MediaConstraints())
            
        } catch (e: Exception) {
            Log.e(TAG, "创建Offer失败", e)
        }
    }
    
    /**
     * 发送Offer
     */
    private fun sendOffer(sdp: SessionDescription?) {
        try {
            val json = JSONObject()
            json.put("type", "offer")
            json.put("sdp", sdp?.description)
            ws?.send(json.toString())
            Log.d(TAG, "Offer已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送Offer失败", e)
        }
    }
    
    /**
     * 发送ICE候选
     */
    private fun sendIceCandidate(candidate: IceCandidate) {
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
} 