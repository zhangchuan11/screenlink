package com.chuan.android_receiver

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.webrtc.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class MainActivity : Activity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var ipEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var infoTextView: Button
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var isConnected = false
    private var serverAddress = "192.168.1.3:6060" // 默认服务器地址

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置全屏
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        
        // 创建动态布局
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        
        // 添加点击监听器，切换控件显示状态
        layout.setOnClickListener {
            if (isConnected && ipEditText.visibility == View.GONE) {
                showControls()
            } else if (isConnected) {
                hideControls()
            }
        }
        
        // 添加IP输入框
        ipEditText = EditText(this)
        ipEditText.hint = "输入服务器地址 (例如: 192.168.1.3:6060)"
        ipEditText.setText(serverAddress)
        layout.addView(ipEditText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加连接按钮
        connectButton = Button(this)
        connectButton.text = "开始接收"
        connectButton.setOnClickListener {
            if (!isConnected) {
                serverAddress = ipEditText.text.toString()
                if (serverAddress.isEmpty()) {
                    Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                connectToSignalingServer()
                connectButton.text = "断开连接"
                isConnected = true
            } else {
                disconnectFromSignalingServer()
                connectButton.text = "开始接收"
                isConnected = false
            }
        }
        layout.addView(connectButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加状态信息文本
        infoTextView = Button(this)
        infoTextView.isEnabled = false
        infoTextView.text = "等待连接..."
        layout.addView(infoTextView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加视图渲染器
        surfaceView = SurfaceViewRenderer(this)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        layout.addView(surfaceView, params)
        
        setContentView(layout)
        
        // 初始化WebRTC
        initializeWebRTC()
        
        // 自动连接
        serverAddress = ipEditText.text.toString()
        if (serverAddress.isNotEmpty()) {
            connectToSignalingServer()
            connectButton.text = "断开连接"
            isConnected = true
        }
    }

    private fun initializeWebRTC() {
        // 初始化EglBase
        eglBase = EglBase.create()
        surfaceView.init(eglBase!!.eglBaseContext, null)
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.setEnableHardwareScaler(true)
        surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        
        // 启用高质量渲染
        surfaceView.setMirror(false)
        surfaceView.setKeepScreenOn(true)
        
        // 初始化PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(videoDecoderFactory)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                // 启用硬件加速解码
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    private fun setupPeerConnection() {
        // 配置PeerConnection
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
            
            override fun onTrack(transceiver: RtpTransceiver?) {
                val remoteVideoTrack = transceiver?.receiver?.track() as? VideoTrack
                remoteVideoTrack?.let { videoTrack ->
                    runOnUiThread {
                        videoTrack.addSink(surfaceView)
                        infoTextView.text = "视频流已连接"
                        // 视频连接成功后隐藏控件
                        hideControls()
                    }
                }
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            infoTextView.text = "已连接"
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED, 
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            infoTextView.text = "连接状态: $state"
                            showControls() // 连接断开时显示控件
                        }
                        else -> {}
                    }
                }
            }
            
            // 其它必要的实现
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        
        // 创建音频轨道的约束条件
        val audioConstraints = MediaConstraints()
        
        // 创建音频源和轨道
        val audioSource = factory!!.createAudioSource(audioConstraints)
        val localAudioTrack = factory!!.createAudioTrack("audio-track", audioSource)
        
        // 添加轨道到PeerConnection
        peerConnection!!.addTrack(localAudioTrack)
    }

    private fun connectToSignalingServer() {
        val wsUrl = "ws://$serverAddress"
        ws = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                runOnUiThread {
                    infoTextView.text = "已连接到信令服务器"
                    Toast.makeText(this@MainActivity, "已连接到信令服务器", Toast.LENGTH_SHORT).show()
                }
                setupPeerConnection()
            }
            
            override fun onMessage(msg: String) {
                try {
                    val json = JSONObject(msg)
                    when (json.getString("type")) {
                        "offer" -> {
                            val sessionDescription = SessionDescription(
                                SessionDescription.Type.OFFER,
                                json.getString("sdp")
                            )
                            
                            // 优化SDP以接收高清视频
                            val optimizedSdp = sessionDescription.description
                                .replace("useinbandfec=1", "useinbandfec=1; stereo=1; maxaveragebitrate=4000000")
                                .replace("x-google-min-bitrate=0", "x-google-min-bitrate=1500")
                                .replace("x-google-start-bitrate=0", "x-google-start-bitrate=2500")
                                .replace("x-google-max-bitrate=0", "x-google-max-bitrate=4000")
                            
                            val optimizedSessionDescription = SessionDescription(
                                SessionDescription.Type.OFFER,
                                optimizedSdp
                            )
                            
                            peerConnection?.setRemoteDescription(SimpleSdpObserver(), optimizedSessionDescription)
                            createAnswer()
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
                    Log.e("MainActivity", "信令处理错误", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "处理消息失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    infoTextView.text = "信令服务器连接已关闭"
                    Toast.makeText(this@MainActivity, "连接已关闭", Toast.LENGTH_SHORT).show()
                    isConnected = false
                    connectButton.text = "开始接收"
                    showControls() // 连接关闭时显示控件
                }
            }
            
            override fun onError(ex: Exception?) {
                runOnUiThread {
                    infoTextView.text = "信令服务器连接错误"
                    Toast.makeText(this@MainActivity, "连接错误: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    isConnected = false
                    connectButton.text = "开始接收"
                    showControls() // 连接错误时显示控件
                }
            }
        }
        
        ws?.connect()
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            // 添加高清视频约束
            optional.add(MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                // 优化SDP以支持高清视频
                val optimizedSdp = sessionDescription.description
                    .replace("useinbandfec=1", "useinbandfec=1; stereo=1; maxaveragebitrate=4000000")
                    .replace("x-google-min-bitrate=0", "x-google-min-bitrate=1500")
                    .replace("x-google-start-bitrate=0", "x-google-start-bitrate=2500")
                    .replace("x-google-max-bitrate=0", "x-google-max-bitrate=4000")
                
                val optimizedSessionDescription = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    optimizedSdp
                )
                
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val answer = JSONObject()
                        answer.put("type", "answer")
                        answer.put("sdp", optimizedSessionDescription.description)
                        ws?.send(answer.toString())
                    }
                    
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    
                    override fun onSetFailure(error: String?) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "设置本地描述失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, optimizedSessionDescription)
            }
            
            override fun onCreateFailure(error: String?) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "创建应答失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun disconnectFromSignalingServer() {
        peerConnection?.close()
        peerConnection = null
        
        ws?.close()
        ws = null
        
        runOnUiThread {
            infoTextView.text = "已断开连接"
            showControls() // 断开连接时显示控件
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromSignalingServer()
        
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

    // 在视频连接成功后隐藏控件，只显示视频
    private fun hideControls() {
        runOnUiThread {
            ipEditText.visibility = View.GONE
            connectButton.visibility = View.GONE
            infoTextView.visibility = View.GONE
            
            // 重新设置surfaceView为全屏
            val params = surfaceView.layoutParams as LinearLayout.LayoutParams
            params.height = LinearLayout.LayoutParams.MATCH_PARENT
            params.weight = 1f
            surfaceView.layoutParams = params
        }
    }
    
    // 显示控件
    private fun showControls() {
        runOnUiThread {
            ipEditText.visibility = View.VISIBLE
            connectButton.visibility = View.VISIBLE
            infoTextView.visibility = View.VISIBLE
            
            // 恢复surfaceView的布局
            val params = surfaceView.layoutParams as LinearLayout.LayoutParams
            params.height = 0
            params.weight = 1f
            surfaceView.layoutParams = params
        }
    }
}