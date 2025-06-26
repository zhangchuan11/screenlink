package com.chuan.android_combined

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import android.content.Context
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo

class ReceiverMainActivity : Activity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var ipEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var infoTextView: Button
    private lateinit var hideIconButton: Button
    private lateinit var showDialCodeButton: Button
    private lateinit var hideAndMinimizeButton: Button
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var isConnected = false
    private var serverAddress = "192.168.1.3:6060" // 默认服务器地址
    private var isIconHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查启动组件
        checkLaunchComponent()
        
        // 检查 LauncherActivity 是否存在，并打印详细信息
        debugCheckLauncherActivity()
        
        // 启动后台服务
        BackgroundService.startService(this)
        
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
                    showToastSafely("请输入服务器地址")
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
        
        // 添加隐藏应用图标按钮
        hideIconButton = Button(this)
        updateHideIconButtonText()
        hideIconButton.setOnClickListener {
            toggleAppIconVisibility()
        }
        layout.addView(hideIconButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加隐藏图标并最小化按钮
        hideAndMinimizeButton = Button(this)
        hideAndMinimizeButton.text = "隐藏图标并最小化"
        hideAndMinimizeButton.id = View.generateViewId() // 生成唯一 ID
        hideAndMinimizeButton.setOnClickListener {
            hideIconAndMinimize()
        }
        layout.addView(hideAndMinimizeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加显示拨号码信息按钮
        showDialCodeButton = Button(this)
        showDialCodeButton.text = "如何通过拨号显示图标？"
        showDialCodeButton.setOnClickListener {
            showDialCodeInfo()
        }
        layout.addView(showDialCodeButton, LinearLayout.LayoutParams(
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
        
        // 检查应用图标当前状态
        checkAppIconStatus()
        
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
                    Toast.makeText(this@ReceiverMainActivity, "已连接到信令服务器", Toast.LENGTH_SHORT).show()
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
                    Log.e("ReceiverMainActivity", "信令处理错误", e)
                    runOnUiThread {
                        Toast.makeText(this@ReceiverMainActivity, "处理消息失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    infoTextView.text = "信令服务器连接已关闭"
                    Toast.makeText(this@ReceiverMainActivity, "连接已关闭", Toast.LENGTH_SHORT).show()
                    isConnected = false
                    connectButton.text = "开始接收"
                    showControls() // 连接关闭时显示控件
                }
            }
            
            override fun onError(ex: Exception?) {
                runOnUiThread {
                    infoTextView.text = "信令服务器连接错误"
                    Toast.makeText(this@ReceiverMainActivity, "连接错误: ${ex?.message}", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(this@ReceiverMainActivity, "设置本地描述失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, optimizedSessionDescription)
            }
            
            override fun onCreateFailure(error: String?) {
                runOnUiThread {
                    Toast.makeText(this@ReceiverMainActivity, "创建应答失败: $error", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun disconnectFromSignalingServer() {
        try {
            // 关闭 PeerConnection
            try {
                peerConnection?.close()
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "关闭 PeerConnection 时出错", e)
            }
            peerConnection = null
            
            // 关闭 WebSocket
            try {
                ws?.close()
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "关闭 WebSocket 时出错", e)
            }
            ws = null
            
            // 更新 UI
            runOnUiThread {
                try {
                    infoTextView.text = "已断开连接"
                    showControls() // 断开连接时显示控件
                } catch (e: Exception) {
                    Log.e("ReceiverMainActivity", "更新 UI 时出错", e)
                }
            }
            
            Log.d("ReceiverMainActivity", "已断开与信令服务器的连接")
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "断开连接时出错", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        try {
            // 先释放 WebRTC 相关资源
            disconnectFromSignalingServer()
            
            // 确保在主线程中释放视图资源
            runOnUiThread {
                try {
                    surfaceView.release()
                } catch (e: Exception) {
                    Log.e("ReceiverMainActivity", "释放 surfaceView 时出错", e)
                }
            }
            
            // 释放其他资源
            try {
                eglBase?.release()
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "释放 eglBase 时出错", e)
            }
            
            try {
                factory?.dispose()
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "释放 factory 时出错", e)
            }
            
            eglBase = null
            factory = null
            
            Log.d("ReceiverMainActivity", "所有资源已释放")
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "onDestroy 时出错", e)
        }
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
            try {
                ipEditText.visibility = View.GONE
                connectButton.visibility = View.GONE
                infoTextView.visibility = View.GONE
                hideIconButton.visibility = View.GONE
                showDialCodeButton.visibility = View.GONE
                hideAndMinimizeButton.visibility = View.GONE
                
                // 重新设置surfaceView为全屏
                val params = surfaceView.layoutParams as? LinearLayout.LayoutParams
                params?.let {
                    it.height = LinearLayout.LayoutParams.MATCH_PARENT
                    it.weight = 1f
                    surfaceView.layoutParams = it
                }
                
                Log.d("ReceiverMainActivity", "控件已隐藏")
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "隐藏控件时出错", e)
            }
        }
    }
    
    // 显示控件
    private fun showControls() {
        runOnUiThread {
            try {
                ipEditText.visibility = View.VISIBLE
                connectButton.visibility = View.VISIBLE
                infoTextView.visibility = View.VISIBLE
                hideIconButton.visibility = View.VISIBLE
                showDialCodeButton.visibility = View.VISIBLE
                hideAndMinimizeButton.visibility = View.VISIBLE
                
                // 恢复surfaceView的布局
                val params = surfaceView.layoutParams as? LinearLayout.LayoutParams
                params?.let {
                    it.height = 0
                    it.weight = 1f
                    surfaceView.layoutParams = it
                }
                
                Log.d("ReceiverMainActivity", "控件已显示")
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "显示控件时出错", e)
            }
        }
    }
    
    // 切换应用图标的可见性
    private fun toggleAppIconVisibility() {
        try {
            val packageManager = packageManager
            val componentName = ComponentName(packageName, packageName + ".LauncherActivity")
            
            // 获取当前组件状态
            val currentState = try {
                packageManager.getComponentEnabledSetting(componentName)
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "获取组件状态失败", e)
                showToastSafely("无法获取应用图标状态")
                return
            }
            
            // 根据当前状态切换
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || 
                currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                // 当前是隐藏状态，需要显示
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                isIconHidden = false
                Log.d("ReceiverMainActivity", "应用图标已显示")
                
                // 启动后台服务
                BackgroundService.startService(this)
            } else {
                // 当前是显示状态，需要隐藏
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                isIconHidden = true
                Log.d("ReceiverMainActivity", "应用图标已隐藏")
                
                // 确保后台服务仍在运行
                BackgroundService.startService(this)
            }
            
            updateHideIconButtonText()
            showToastSafely(if (isIconHidden) "应用图标已隐藏" else "应用图标已显示")
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "切换应用图标可见性时出错", e)
            showToastSafely("操作失败: ${e.message}")
        }
    }
    
    // 隐藏应用图标并最小化应用
    private fun hideIconAndMinimize() {
        try {
            // 先隐藏图标
            val packageManager = packageManager
            val componentName = ComponentName(packageName, packageName + ".LauncherActivity")
            
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            isIconHidden = true
            updateHideIconButtonText()
            
            // 确保后台服务在运行
            BackgroundService.startService(this)
            
            // 最小化应用
            moveTaskToBack(true)
            
            Log.d("ReceiverMainActivity", "应用图标已隐藏，应用已最小化")
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "隐藏图标并最小化时出错", e)
        }
    }
    
    override fun onBackPressed() {
        // 如果应用图标已隐藏，则只是最小化应用，不退出
        if (isIconHidden) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }
    
    // 安全地显示 Toast 消息
    private fun showToastSafely(message: String) {
        try {
            // 检查应用是否在前台
            if (isAppInForeground()) {
                // 确保在主线程中显示 Toast
                runOnUiThread {
                    try {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ReceiverMainActivity", "显示 Toast 时出错", e)
                    }
                }
            } else {
                Log.d("ReceiverMainActivity", "应用不在前台，不显示 Toast: $message")
            }
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "检查应用状态时出错", e)
        }
    }
    
    // 检查应用是否在前台
    private fun isAppInForeground(): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // 获取正在运行的应用信息
            val appProcesses = activityManager.runningAppProcesses ?: return false
            
            val myPid = android.os.Process.myPid()
            for (appProcess in appProcesses) {
                if (appProcess.pid == myPid && 
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "检查应用是否在前台时出错", e)
        }
        
        return false
    }
    
    // 检查应用图标当前状态
    private fun checkAppIconStatus() {
        try {
            val packageManager = packageManager
            val componentName = ComponentName(packageName, packageName + ".LauncherActivity")
            
            val status = try {
                packageManager.getComponentEnabledSetting(componentName)
            } catch (e: Exception) {
                Log.e("ReceiverMainActivity", "获取组件状态失败", e)
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            
            isIconHidden = status == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            Log.d("ReceiverMainActivity", "应用图标状态: ${if (isIconHidden) "隐藏" else "显示"}")
            updateHideIconButtonText()
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "检查应用图标状态时出错", e)
            isIconHidden = false
            updateHideIconButtonText()
        }
    }
    
    // 更新隐藏图标按钮的文本
    private fun updateHideIconButtonText() {
        hideIconButton.text = if (isIconHidden) "显示应用图标" else "隐藏应用图标"
    }

    // 检查应用是通过哪个组件启动的
    private fun checkLaunchComponent() {
        try {
            val componentName = intent?.component
            Log.d("ReceiverMainActivity", "应用通过组件启动: ${componentName?.className}")
            
            // 检查 LauncherActivity 是否存在
            val aliasComponentName = ComponentName(packageName, packageName + ".LauncherActivity")
            val aliasExists = try {
                packageManager.getActivityInfo(aliasComponentName, 0)
                true
            } catch (e: Exception) {
                false
            }
            
            Log.d("ReceiverMainActivity", "LauncherActivity 存在: $aliasExists")
            
            if (!aliasExists) {
                Toast.makeText(this, "警告：应用别名不存在，隐藏图标功能可能无法正常工作", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "检查启动组件时出错", e)
        }
    }

    // 显示拨号码信息
    private fun showDialCodeInfo() {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "当应用图标被隐藏时，可以拨打 *#*#1234#*#* 来显示应用图标"
        } else {
            "当应用图标被隐藏时，可以拨打 *#*#1234#*#* 来显示应用图标"
        }
        
        showToastSafely(message)
        
        // 显示更详细的信息对话框
        try {
            if (isAppInForeground()) {
                runOnUiThread {
                    try {
                        val dialog = android.app.AlertDialog.Builder(this)
                            .setTitle("通过拨号显示应用图标")
                            .setMessage("当应用图标被隐藏后，您可以通过以下方式重新显示图标：\n\n" +
                                    "1. 打开手机拨号键盘\n" +
                                    "2. 输入特殊代码：*#*#1234#*#*\n" +
                                    "3. 输入完成后，应用图标将自动显示\n\n" +
                                    "注意：这个操作不会实际拨打电话，也不会产生任何费用。")
                            .setPositiveButton("我知道了") { dialog, _ -> dialog.dismiss() }
                            .create()
                        
                        dialog.show()
                    } catch (e: Exception) {
                        Log.e("ReceiverMainActivity", "显示对话框时出错", e)
                    }
                }
            } else {
                Log.d("ReceiverMainActivity", "应用不在前台，不显示对话框")
            }
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "检查应用状态时出错", e)
        }
    }

    // 调试用：检查 LauncherActivity 是否存在，并打印详细信息
    private fun debugCheckLauncherActivity() {
        try {
            // 尝试获取 LauncherActivity 的信息
            val packageName = packageName
            Log.d("ReceiverMainActivity", "当前包名: $packageName")
            
            // 尝试不同的方式引用 LauncherActivity
            val componentNames = listOf(
                ComponentName(packageName, ".LauncherActivity"),
                ComponentName(packageName, "$packageName.LauncherActivity"),
                ComponentName(this, ".LauncherActivity"),
                ComponentName(this, "$packageName.LauncherActivity")
            )
            
            // 检查每种方式
            for ((index, component) in componentNames.withIndex()) {
                try {
                    val activityInfo = packageManager.getActivityInfo(component, 0)
                    Log.d("ReceiverMainActivity", "方式 $index: 成功找到 LauncherActivity")
                    Log.d("ReceiverMainActivity", "  - 组件名称: ${component.className}")
                    Log.d("ReceiverMainActivity", "  - 目标活动: ${activityInfo.targetActivity}")
                    Log.d("ReceiverMainActivity", "  - 包名: ${activityInfo.packageName}")
                } catch (e: Exception) {
                    Log.e("ReceiverMainActivity", "方式 $index: 无法找到 LauncherActivity", e)
                    Log.e("ReceiverMainActivity", "  - 组件名称: ${component.className}")
                    Log.e("ReceiverMainActivity", "  - 错误信息: ${e.message}")
                }
            }
            
            // 列出所有已安装的活动
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.`package` = packageName
            
            val activities = packageManager.queryIntentActivities(intent, 0)
            Log.d("ReceiverMainActivity", "已安装的启动器活动数量: ${activities.size}")
            
            for ((index, activity) in activities.withIndex()) {
                Log.d("ReceiverMainActivity", "活动 $index: ${activity.activityInfo.name}")
            }
        } catch (e: Exception) {
            Log.e("ReceiverMainActivity", "调试 LauncherActivity 时出错", e)
        }
    }
}