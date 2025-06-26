package com.chuan.android_combined

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.webrtc.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class SenderMainActivity : Activity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var ipEditText: EditText
    private lateinit var connectButton: Button
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var isConnected = false
    private var serverAddress = "192.168.1.3:6060"
    private var screenCaptureService: ScreenCaptureService? = null
    private var isServiceBound = false
    private var pendingMediaProjectionPermissionIntent: Intent? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ScreenCaptureService.LocalBinder
            screenCaptureService = binder.getService()
            isServiceBound = true
            
            // 如果有待处理的权限结果，处理它
            pendingMediaProjectionPermissionIntent?.let {
                setupWebRTCConnection(it)
                pendingMediaProjectionPermissionIntent = null
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName) {
            screenCaptureService = null
            isServiceBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 创建动态布局
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        
        // 添加IP输入框
        ipEditText = EditText(this)
        ipEditText.hint = "输入服务器地址 (例如：192.168.1.3:6060)"
        ipEditText.setText(serverAddress)
        layout.addView(ipEditText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加连接按钮
        connectButton = Button(this)
        connectButton.text = "开始投屏"
        connectButton.setOnClickListener {
            if (!isConnected) {
                serverAddress = ipEditText.text.toString()
                if (serverAddress.isEmpty()) {
                    Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                connectToSignalingServer()
                connectButton.text = "停止投屏"
                isConnected = true
            } else {
                disconnectFromSignalingServer()
                connectButton.text = "开始投屏"
                isConnected = false
            }
        }
        layout.addView(connectButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加预览视图
        surfaceView = SurfaceViewRenderer(this)
        val surfaceParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            0, 
            1f
        )
        layout.addView(surfaceView, surfaceParams)
        setContentView(layout)
        
        // 初始化WebRTC
        initializeWebRTC()
    }
    
    private fun initializeWebRTC() {
        eglBase = EglBase.create()
        surfaceView.init(eglBase!!.eglBaseContext, null)
        
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
    }
    
    private fun connectToSignalingServer() {
        val wsUrl = "ws://$serverAddress"
        ws = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                runOnUiThread { 
                    Toast.makeText(this@SenderMainActivity, "已连接到信令服务器", Toast.LENGTH_SHORT).show()
                    startScreenCapture()
                }
            }
            
            override fun onMessage(msg: String) {
                try {
                    val json = JSONObject(msg)
                    when (json.getString("type")) {
                        "answer" -> {
                            peerConnection?.setRemoteDescription(
                                SimpleSdpObserver(), 
                                SessionDescription(
                                    SessionDescription.Type.ANSWER, 
                                    json.getString("sdp")
                                )
                            )
                        }
                        "candidate" -> {
                            val candidate = IceCandidate(
                                json.getString("id"), 
                                json.getInt("label"), 
                                json.getString("candidate")
                            )
                            peerConnection?.addIceCandidate(candidate)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@SenderMainActivity, "处理消息失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    Toast.makeText(this@SenderMainActivity, "信令服务器连接已关闭", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onError(ex: Exception?) {
                runOnUiThread {
                    Toast.makeText(this@SenderMainActivity, "信令服务器连接失败: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    connectButton.text = "开始投屏"
                    isConnected = false
                }
            }
        }
        
        ws?.connect()
    }
    
    private fun disconnectFromSignalingServer() {
        stopScreenCapture()
        
        videoTrack?.dispose()
        videoTrack = null
        
        videoSource?.dispose()
        videoSource = null
        
        peerConnection?.close()
        peerConnection = null
        
        ws?.close()
        ws = null
        
        unbindService()
        
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }
    
    private fun startScreenCapture() {
        // 请求屏幕捕获权限
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }
    
    private fun stopScreenCapture() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        
        // 停止并解绑前台服务
        stopScreenCaptureService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 先启动服务
                startScreenCaptureService()
                
                // 存储权限结果待服务绑定完成后使用
                pendingMediaProjectionPermissionIntent = data
            } else {
                Toast.makeText(this, "未获得屏幕录制权限", Toast.LENGTH_SHORT).show()
                connectButton.text = "开始投屏"
                isConnected = false
            }
        }
    }

    private fun startScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun stopScreenCaptureService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        screenCaptureService = null
    }
    
    private fun unbindService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            screenCaptureService = null
        }
    }

    private fun setupWebRTCConnection(data: Intent) {
        if (!isServiceBound) {
            // 如果服务尚未绑定，将权限结果存储起来等待服务绑定完成
            pendingMediaProjectionPermissionIntent = data
            return
        }
        
        // 创建视频捕获器
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mediaProjectionManager.getMediaProjection(RESULT_OK, data)
        videoCapturer = ScreenCapturerAndroid(
            data,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    runOnUiThread {
                        Toast.makeText(this@SenderMainActivity, "屏幕捕获已停止", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        
        // 创建视频源和轨道
        videoSource = factory!!.createVideoSource(false)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource!!.capturerObserver)
        
        // 设置高质量的视频参数
        videoCapturer!!.startCapture(2400, 1080, 30)
        
        videoTrack = factory!!.createVideoTrack("video-track", videoSource)
        videoTrack!!.addSink(surfaceView)
        
        // 创建PeerConnection
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject()
                json.put("type", "candidate")
                json.put("label", candidate.sdpMLineIndex)
                json.put("id", candidate.sdpMid)
                json.put("candidate", candidate.sdp)
                ws?.send(json.toString())
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED, 
                    PeerConnection.IceConnectionState.FAILED, 
                    PeerConnection.IceConnectionState.CLOSED -> {
                        runOnUiThread {
                            Toast.makeText(this@SenderMainActivity, "连接状态: $state", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {}
                }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })
        
        // 使用 addTrack 替代 addStream
        peerConnection!!.addTrack(videoTrack)
        
        // 创建offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        // 设置视频编码参数，提高清晰度
        val videoConstraints = MediaConstraints().apply {
            // 设置更高的比特率，单位是kbps
            mandatory.add(MediaConstraints.KeyValuePair("maxBitrate", "8000"))
            mandatory.add(MediaConstraints.KeyValuePair("minBitrate", "3000"))
            mandatory.add(MediaConstraints.KeyValuePair("startBitrate", "5000"))
            // 设置更高质量的视频参数
            mandatory.add(MediaConstraints.KeyValuePair("googHighBitrate", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googVeryHighBitrate", "true"))
        }
        
        // 应用视频约束
        val videoSender = peerConnection!!.senders.find { it.track()?.kind() == "video" }
        videoSender?.setParameters(videoSender.parameters.apply {
            this.encodings.forEach { encoding ->
                encoding.maxBitrateBps = 10000 * 1000 // 10Mbps
                encoding.minBitrateBps = 2000 * 1000 // 2Mbps
            }
        })
        
        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                // 设置本地描述
                peerConnection!!.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // 发送offer到信令服务器
                        val offer = JSONObject()
                        offer.put("type", "offer")
                        offer.put("sdp", sessionDescription.description)
                        ws?.send(offer.toString())
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        runOnUiThread {
                            Toast.makeText(this@SenderMainActivity, "设置本地描述失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, sessionDescription)
            }
            
            override fun onSetSuccess() {}
            
            override fun onCreateFailure(error: String?) {
                runOnUiThread {
                    Toast.makeText(this@SenderMainActivity, "创建offer失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromSignalingServer()
        
        // 确保服务被停止
        stopScreenCaptureService()
        
        surfaceView.release()
        eglBase?.release()
        factory?.dispose()
        
        eglBase = null
        factory = null
    }
    
    class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    
    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1001
    }
}