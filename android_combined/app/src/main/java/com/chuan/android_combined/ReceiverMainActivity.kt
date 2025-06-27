package com.chuan.android_combined

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.webrtc.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import java.util.*

class ReceiverMainActivity : Activity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var senderListView: ListView
    private lateinit var statusTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var connectButton: Button
    private lateinit var serverAddressInput: EditText
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var ws: WebSocketClient? = null
    private var factory: PeerConnectionFactory? = null
    private var isConnected = false
    private var serverAddress = "192.168.168.102:6060" // 默认服务器地址
    private var reconnectHandler: Handler? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelay = 3000L // 3秒
    
    // 发送端列表相关
    private var senderList = mutableListOf<SenderInfo>()
    private var selectedSenderId: Int? = null
    private lateinit var senderAdapter: SenderListAdapter

    private var statsTimer: Timer? = null
    private var lastStatsTime = 0L
    private var lastBytesReceived = 0L
    private var previousFramesReceived = 0
    private var noFrameChangeCount = 0
    private var showDetailedStats = true
    private var lastFramesDecoded = 0
    private var statsTextView: TextView? = null

    companion object {
        private const val TAG = "ReceiverMainActivity"
        private const val DEFAULT_SIGNALING_SERVER = "192.168.168.102:6060"
    }
    
    // 发送端信息数据类
    data class SenderInfo(
        val id: Int,
        val name: String,
        val timestamp: Long,
        val available: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver_main)
        
        // 初始化UI组件
        surfaceView = findViewById(R.id.surface_view)
        statusTextView = findViewById(R.id.status_text)
        senderListView = findViewById(R.id.sender_list)
        connectButton = findViewById(R.id.connect_button)
        serverAddressInput = findViewById(R.id.server_address)
        
        // 初始化统计信息显示
        statsTextView = findViewById(R.id.stats_text)
        
        // 初始化发送端列表适配器
        senderList = ArrayList()
        senderAdapter = SenderListAdapter(this, senderList)
        senderListView.adapter = senderAdapter
        
        // 设置发送端列表点击事件
        senderListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val sender = senderList[position]
            val senderId = sender.id
            
            // 连接到选择的发送端
            if (senderId != null) {
                selectedSenderId = senderId
                statusTextView.text = "正在连接到: ${sender.name}"
                
                // 发送选择发送端的消息
                val json = JSONObject()
                json.put("type", "select_sender")
                json.put("senderId", senderId)
                ws?.send(json.toString())
                
                Log.d(TAG, "已选择发送端: $senderId")
            }
        }
        
        // 设置连接按钮点击事件
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnectFromSignalingServer()
                connectButton.text = "连接到服务器"
                statusTextView.text = "已断开连接"
                isConnected = false
            } else {
                val address = if (serverAddressInput.visibility == View.VISIBLE) {
                    serverAddressInput.text.toString()
                } else {
                    DEFAULT_SIGNALING_SERVER
                }
                
                connectToSignalingServer(address)
                connectButton.text = "断开连接"
            }
        }
        
        // 长按连接按钮显示服务器地址输入框
        connectButton.setOnLongClickListener {
            if (serverAddressInput.visibility == View.VISIBLE) {
                serverAddressInput.visibility = View.GONE
            } else {
                serverAddressInput.visibility = View.VISIBLE
                serverAddressInput.setText(DEFAULT_SIGNALING_SERVER)
            }
            true
        }
        
        // 初始化WebRTC
        initializeWebRTC()
        
        // 自动连接到默认服务器
        connectToSignalingServer()
        
        // 设置统计信息定时器
        setupStatsTimer()
    }

    private fun initializeWebRTC() {
        // 初始化EglBase
        eglBase = EglBase.create()
        surfaceView.init(eglBase!!.eglBaseContext, null)
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.setEnableHardwareScaler(true)
        surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        
        // 启用高质量渲染
        surfaceView.setMirror(false)
        surfaceView.setKeepScreenOn(true)
        
        // 确保SurfaceView可见
        surfaceView.visibility = View.VISIBLE
        
        // 设置SurfaceView布局参数，确保足够大
        val params = surfaceView.layoutParams
        if (params != null) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            surfaceView.layoutParams = params
        }
        
        Log.d(TAG, "SurfaceView初始化完成")
        
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
            .createPeerConnectionFactory()
            
        Log.d(TAG, "WebRTC初始化完成")
    }

    private fun initReconnectMechanism() {
        reconnectHandler = Handler(Looper.getMainLooper())
        reconnectRunnable = object : Runnable {
            override fun run() {
                if (reconnectAttempts < maxReconnectAttempts) {
                    Log.d(TAG, "尝试重连，第 ${reconnectAttempts + 1} 次")
                    reconnectAttempts++
                    connectToSignalingServer()
                } else {
                    Log.e(TAG, "重连失败，已达到最大重试次数")
                    runOnUiThread {
                        Toast.makeText(this@ReceiverMainActivity, "连接失败，请检查网络", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun connectToSignalingServer(address: String = DEFAULT_SIGNALING_SERVER) {
        serverAddress = address
        val wsUrl = "ws://$serverAddress"
        ws = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                runOnUiThread {
                    Log.d(TAG, "已连接到信令服务器")
                    statusTextView.text = "已连接到服务器，正在获取发送端列表..."
                    reconnectAttempts = 0 // 重置重连计数
                    requestSenderList()
                }
            }
            
            override fun onMessage(msg: String) {
                try {
                    Log.d(TAG, "收到WebSocket消息: $msg")
                    val json = JSONObject(msg)
                    Log.d(TAG, "收到消息类型: ${json.getString("type")}")
                    
                    when (json.getString("type")) {
                        "sender_list" -> {
                            handleSenderList(json)
                        }
                        "sender_list_update" -> {
                            handleSenderList(json)
                        }
                        "offer" -> {
                            Log.d(TAG, "收到offer，开始处理")
                            
                            // 如果还没有创建PeerConnection，先创建
                            if (peerConnection == null) {
                                Log.d(TAG, "创建新的PeerConnection")
                                createPeerConnection()
                            } else {
                                Log.d(TAG, "使用现有的PeerConnection")
                            }
                            
                            val sdp = SessionDescription(
                                SessionDescription.Type.OFFER, 
                                json.getString("sdp")
                            )
                            Log.d(TAG, "设置远程描述: ${sdp.description.substring(0, 100)}...")
                            
                            // 检查SDP中是否包含视频轨道
                            if (sdp.description.contains("m=video")) {
                                Log.d(TAG, "SDP中包含视频轨道")
                            } else {
                                Log.w(TAG, "SDP中不包含视频轨道")
                            }
                            
                            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                            
                            // 创建answer
                            val constraints = MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                            }
                            
                            Log.d(TAG, "开始创建answer")
                            peerConnection?.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                                    Log.d(TAG, "Answer创建成功，设置本地描述")
                                    Log.d(TAG, "Answer SDP: ${sessionDescription.description}")
                                    
                                    // 检查Answer SDP中是否包含视频轨道
                                    if (sessionDescription.description.contains("m=video")) {
                                        Log.d(TAG, "Answer SDP中包含视频轨道")
                                    } else {
                                        Log.w(TAG, "Answer SDP中不包含视频轨道")
                                    }
                                    
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onSetSuccess() {
                                            val answer = JSONObject()
                                            answer.put("type", "answer")
                                            answer.put("sdp", sessionDescription.description)
                                            answer.put("selectedSenderId", selectedSenderId)
                                            val answerJson = answer.toString()
                                            ws?.send(answerJson)
                                            Log.d(TAG, "Answer已发送，selectedSenderId: $selectedSenderId")
                                            Log.d(TAG, "Answer内容: ${answerJson.substring(0, Math.min(100, answerJson.length))}...")
                                        }
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onCreateFailure(p0: String?) {}
                                        override fun onSetFailure(p0: String?) {
                                            runOnUiThread {
                                                Log.e(TAG, "设置本地描述失败")
                                            }
                                        }
                                    }, sessionDescription)
                                }
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(error: String?) {
                                    runOnUiThread {
                                        Log.e(TAG, "创建answer失败: $error")
                                    }
                                }
                                override fun onSetFailure(error: String?) {}
                            }, constraints)
                        }
                        "candidate" -> {
                            Log.d(TAG, "收到candidate消息")
                            val candidate = IceCandidate(
                                json.getString("id"), 
                                json.getInt("label"), 
                                json.getString("candidate")
                            )
                            Log.d(TAG, "ICE候选: ${candidate.sdp}")
                            if (peerConnection != null) {
                                peerConnection?.addIceCandidate(candidate)
                                Log.d(TAG, "ICE候选已添加")
                            } else {
                                Log.w(TAG, "收到ICE候选但PeerConnection尚未创建，忽略")
                            }
                        }
                        "connection_confirm" -> {
                            Log.d(TAG, "收到连接确认消息")
                            // 发送回应确认连接状态
                            val confirm = JSONObject()
                            confirm.put("type", "connection_ack")
                            confirm.put("timestamp", System.currentTimeMillis())
                            ws?.send(confirm.toString())
                        }
                        "error" -> {
                            runOnUiThread {
                                val errorMsg = json.optString("message", "未知错误")
                                Toast.makeText(this@ReceiverMainActivity, "错误: $errorMsg", Toast.LENGTH_SHORT).show()
                                Log.e(TAG, "服务器错误: $errorMsg")
                            }
                        }
                        "info" -> {
                            Log.d(TAG, "服务器信息: ${json.optString("message", "")}")
                        }
                        else -> {
                            Log.d(TAG, "收到未处理的消息类型: ${json.getString("type")}")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Log.e(TAG, "处理消息失败: ${e.message}")
                    }
                }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    Log.d(TAG, "信令服务器连接已关闭: $reason")
                    statusTextView.text = "连接已断开"
                    isConnected = false
                    
                    // 如果不是主动关闭，尝试重连
                    if (remote && reconnectAttempts < maxReconnectAttempts) {
                        Log.d(TAG, "连接被服务器关闭，准备重连")
                        reconnectHandler?.postDelayed(reconnectRunnable!!, reconnectDelay)
                    }
                }
            }
            
            override fun onError(ex: Exception?) {
                runOnUiThread {
                    Log.e(TAG, "信令服务器连接失败: ${ex?.message}")
                    statusTextView.text = "连接失败"
                    isConnected = false
                    
                    // 连接错误时尝试重连
                    if (reconnectAttempts < maxReconnectAttempts) {
                        Log.d(TAG, "连接错误，准备重连")
                        reconnectHandler?.postDelayed(reconnectRunnable!!, reconnectDelay)
                    }
                }
            }
        }
        
        try {
            ws?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "连接WebSocket失败", e)
            // 连接失败时尝试重连
            if (reconnectAttempts < maxReconnectAttempts) {
                reconnectHandler?.postDelayed(reconnectRunnable!!, reconnectDelay)
            }
        }
    }

    private fun requestSenderList() {
        val request = JSONObject()
        request.put("type", "request_senders")
        ws?.send(request.toString())
        Log.d(TAG, "已请求发送端列表")
    }

    private fun handleSenderList(json: JSONObject) {
        try {
            val sendersArray = json.getJSONArray("senders")
            senderList.clear()
            val myId = getMyId() // 新增：获取本机ID
            
            for (i in 0 until sendersArray.length()) {
                val senderJson = sendersArray.getJSONObject(i)
                val id = senderJson.getInt("id")
                if (id == myId) continue // 跳过本机ID
                val sender = SenderInfo(
                    id = id,
                    name = senderJson.getString("name"),
                    timestamp = senderJson.getLong("timestamp"),
                    available = senderJson.getBoolean("available")
                )
                senderList.add(sender)
            }
            
            runOnUiThread {
                senderAdapter.notifyDataSetChanged()
                statusTextView.text = "发现 ${senderList.size} 个发送端"
                Log.d(TAG, "发送端列表已更新，共${senderList.size}个")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理发送端列表失败", e)
        }
    }

    // 获取本机ID（可根据实际情况实现，比如用设备唯一标识或配置）
    private fun getMyId(): Int {
        // TODO: 替换为实际的本机ID获取逻辑
        return -1 // 默认-1表示无效，不会过滤任何ID
    }

    private fun selectSender(senderId: Int) {
        selectedSenderId = senderId
        val sender = senderList.find { it.id == senderId }
        
        if (sender != null) {
            statusTextView.text = "正在连接发送端: ${sender.name}"
            Log.d(TAG, "开始选择发送端: ${sender.name} (ID: $senderId)")
            
            val selectRequest = JSONObject()
            selectRequest.put("type", "select_sender")
            selectRequest.put("senderId", senderId)
            ws?.send(selectRequest.toString())
            
            Log.d(TAG, "已发送选择发送端请求: $selectRequest")
        } else {
            Log.e(TAG, "未找到发送端: $senderId")
            Toast.makeText(this, "发送端不存在", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnectFromSignalingServer() {
        // 停止重连
        reconnectRunnable?.let { reconnectHandler?.removeCallbacks(it) }
        
        peerConnection?.close()
        peerConnection = null
        
        ws?.close()
        ws = null
        
        Log.d(TAG, "已断开连接")
        
        // 新增：断开后自动重连
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "断开后自动尝试重连...")
            connectToSignalingServer()
        }, 2000)
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "生成ICE候选: ${candidate.sdp}")
                val json = JSONObject()
                json.put("type", "candidate")
                json.put("label", candidate.sdpMLineIndex)
                json.put("id", candidate.sdpMid)
                json.put("candidate", candidate.sdp)
                ws?.send(json.toString())
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                val count = candidates?.size ?: 0
                Log.d(TAG, "ICE候选已移除: ${count}个")
            }
            
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "信令状态变化: $state")
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                state?.let { onIceConnectionStateChanged(it) }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE接收状态变化: $receiving")
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE收集状态: $state")
            }
            
            override fun onAddStream(stream: MediaStream?) {
                if (stream != null) {
                    Log.d(TAG, "添加媒体流: ${stream.id}")
                } else {
                    Log.d(TAG, "添加媒体流: null")
                }
            }
            
            override fun onRemoveStream(stream: MediaStream?) {
                if (stream != null) {
                    Log.d(TAG, "移除媒体流: ${stream.id}")
                } else {
                    Log.d(TAG, "移除媒体流: null")
                }
            }
            
            override fun onDataChannel(dataChannel: DataChannel?) {
                if (dataChannel != null) {
                    Log.d(TAG, "数据通道: ${dataChannel.label()}")
                } else {
                    Log.d(TAG, "数据通道: null")
                }
            }
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "需要重新协商")
            }
            
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val trackKind = receiver?.track()?.kind() ?: "null"
                val streamCount = streams?.size ?: 0
                Log.d(TAG, "添加轨道: $trackKind, 流数量: $streamCount")
            }
            
            override fun onTrack(transceiver: RtpTransceiver?) {
                val trackKind = transceiver?.receiver?.track()?.kind() ?: "null"
                Log.d(TAG, "收到轨道事件: $trackKind")
                runOnUiThread {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        Log.d(TAG, "开始添加视频轨道到SurfaceView")
                        Log.d(TAG, "视频轨道ID: ${track.id()}")
                        Log.d(TAG, "视频轨道状态: ${track.state()}")
                        
                        // 确保SurfaceView已经初始化
                        if (!surfaceView.isEnabled) {
                            Log.w(TAG, "SurfaceView未启用，尝试重新初始化")
                            surfaceView.init(eglBase!!.eglBaseContext, null)
                        }
                        
                        // 设置SurfaceView为最高优先级
                        surfaceView.setZOrderOnTop(false)
                        surfaceView.setZOrderMediaOverlay(true)
                        
                        // 调整视频渲染参数
                        surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        surfaceView.setEnableHardwareScaler(true)
                        
                        // 立即使SurfaceView可见并放大
                        surfaceView.visibility = View.VISIBLE
                        
                        // 添加视频轨道到SurfaceView
                        track.addSink(surfaceView)
                        Log.d(TAG, "视频流已接收并添加到SurfaceView")
                        
                        // 强制刷新SurfaceView
                        surfaceView.requestLayout()
                        surfaceView.invalidate()
                        
                        // 确保视频帧正常渲染
                        surfaceView.clearImage()  // 清除之前的图像
                        surfaceView.release()     // 释放旧资源
                        surfaceView.init(eglBase!!.eglBaseContext, null)  // 重新初始化
                        track.addSink(surfaceView)  // 重新添加视频轨道
                        
                        Log.d(TAG, "视频渲染已重置并重新配置")
                        statusTextView.text = "正在显示发送端画面"
                        
                    } else {
                        val kind = track?.kind() ?: "null"
                        Log.w(TAG, "收到非视频轨道: $kind")
                    }
                }
            }
        })
    }

    private fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        Log.d(TAG, "ICE连接状态变化: $state")
        
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                runOnUiThread {
                    Log.d(TAG, "ICE连接已建立，应该开始接收视频流")
                    checkAndResetVideoTrack()
                    
                    // 发送确认连接消息
                    val json = JSONObject()
                    json.put("type", "receiver_connected")
                    json.put("timestamp", System.currentTimeMillis())
                    json.put("senderId", selectedSenderId)
                    ws?.send(json.toString())
                    Log.d(TAG, "已发送连接确认消息")
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED, 
            PeerConnection.IceConnectionState.FAILED -> {
                runOnUiThread {
                    statusTextView.text = "连接已断开，正在尝试重连..."
                    scheduleReconnection()
                }
            }
            else -> {
                // 其他状态不处理
            }
        }
    }
    
    // 检查并重置视频轨道，确保视频正常显示
    private fun checkAndResetVideoTrack() {
        try {
            Log.d(TAG, "检查视频轨道状态...")
            
            val receivers = peerConnection?.getReceivers()
            val videoReceiver = receivers?.find { it.track()?.kind() == "video" }
            
            if (videoReceiver != null) {
                val videoTrack = videoReceiver.track() as? VideoTrack
                if (videoTrack != null) {
                    Log.d(TAG, "找到视频轨道: ${videoTrack.id()}, 状态: ${videoTrack.state()}")
                    
                    // 清理之前的渲染器
                    surfaceView.release()
                    
                    // 重新初始化渲染器
                    surfaceView.init(eglBase!!.eglBaseContext, null)
                    surfaceView.setZOrderMediaOverlay(true)
                    surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    surfaceView.setEnableHardwareScaler(true)
                    surfaceView.setMirror(false)
                    
                    // 确保SurfaceView大小正确
                    val params = surfaceView.layoutParams
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    surfaceView.layoutParams = params
                    
                    // 清除之前的图像
                    surfaceView.clearImage()
                    
                    // 将视频轨道连接到渲染器
                    videoTrack.addSink(surfaceView)
                    
                    // 强制刷新布局
                    surfaceView.requestLayout()
                    
                    Log.d(TAG, "已重新添加视频轨道到SurfaceView")
                    statusTextView.text = "正在接收视频流"
                } else {
                    Log.w(TAG, "找到接收器但没有视频轨道")
                    statusTextView.text = "视频轨道不可用"
                }
            } else {
                Log.w(TAG, "未找到视频接收器")
                statusTextView.text = "未找到视频接收器"
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查视频轨道时出错", e)
            statusTextView.text = "视频处理错误"
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
    
    // 发送端列表适配器
    inner class SenderListAdapter(
        context: Context,
        private val senders: List<SenderInfo>
    ) : ArrayAdapter<SenderInfo>(context, 0, senders) {
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            
            val sender = senders[position]
            val textView = view.findViewById<TextView>(android.R.id.text1)
            
            val status = if (sender.available) "可用" else "不可用"
            val color = if (sender.available) Color.GREEN else Color.RED
            
            textView.text = "${sender.name} (ID: ${sender.id}) - $status"
            textView.setTextColor(color)
            
            return view
        }
    }

    /**
     * 发送连接确认消息
     */
    private fun sendConnectionConfirm() {
        try {
            val confirm = JSONObject()
            confirm.put("type", "receiver_connected")
            confirm.put("timestamp", System.currentTimeMillis())
            confirm.put("senderId", selectedSenderId)
            ws?.send(confirm.toString())
            Log.d(TAG, "已发送连接确认消息")
        } catch (e: Exception) {
            Log.e(TAG, "发送连接确认失败", e)
        }
    }
    
    /**
     * 安排重新连接
     */
    private fun scheduleReconnection() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isConnected && selectedSenderId != null) {
                Log.d(TAG, "尝试重新连接到发送端")
                // 重新请求发送端列表
                requestSenderList()
                // 重新选择之前的发送端
                Handler(Looper.getMainLooper()).postDelayed({
                    if (senderList.any { it.id == selectedSenderId }) {
                        Log.d(TAG, "重新选择发送端: $selectedSenderId")
                        selectSender(selectedSenderId!!)
                    } else {
                        Log.d(TAG, "之前选择的发送端不可用")
                    }
                }, 1000) // 延迟1秒，等待发送端列表刷新
            }
        }, 3000) // 3秒后尝试重连
    }

    private fun setupStatsTimer() {
        // 每隔2秒更新一次统计信息
        statsTimer = Timer()
        statsTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (peerConnection != null && isConnected) {
                    peerConnection?.getStats { stats ->
                        runOnUiThread {
                            processStats(stats)
                        }
                    }
                }
            }
        }, 0, 2000)
    }
    
    private fun processStats(stats: RTCStatsReport) {
        try {
            var framesReceived = 0
            var framesDecoded = 0
            var packetsReceived = 0
            var packetsLost = 0
            var bytesReceived = 0L
            var jitter = 0.0
            var currentDelay = 0.0
            var timestamp = 0L
            
            for (stat in stats.statsMap.values) {
                val isVideo = try {
                    stat.members.containsKey("mediaType") && stat.members["mediaType"] == "video"
                } catch (e: Exception) {
                    false
                }
                
                if (isVideo) {
                    try {
                        if (stat.members.containsKey("framesReceived")) {
                            framesReceived = (stat.members["framesReceived"] as? Number)?.toInt() ?: 0
                        }
                        
                        if (stat.members.containsKey("framesDecoded")) {
                            framesDecoded = (stat.members["framesDecoded"] as? Number)?.toInt() ?: 0
                        }
                        
                        if (stat.members.containsKey("packetsReceived")) {
                            packetsReceived = (stat.members["packetsReceived"] as? Number)?.toInt() ?: 0
                        }
                        
                        if (stat.members.containsKey("packetsLost")) {
                            packetsLost = (stat.members["packetsLost"] as? Number)?.toInt() ?: 0
                        }
                        
                        if (stat.members.containsKey("bytesReceived")) {
                            bytesReceived = (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                        }
                        
                        if (stat.members.containsKey("jitter")) {
                            jitter = (stat.members["jitter"] as? Number)?.toDouble() ?: 0.0
                        }
                        
                        if (stat.members.containsKey("timestamp")) {
                            timestamp = (stat.members["timestamp"] as? Number)?.toLong() ?: 0L
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理视频统计数据出错", e)
                    }
                }
                
                if (stat.members.containsKey("currentRoundTripTime")) {
                    currentDelay = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()?.times(1000) ?: 0.0
                }
            }
            
            // 计算丢包率
            val packetLossRate = if (packetsReceived + packetsLost > 0) {
                (packetsLost.toDouble() / (packetsReceived + packetsLost)) * 100
            } else {
                0.0
            }
            
            // 计算比特率 (bps)
            val currentTime = System.currentTimeMillis()
            val timeDelta = currentTime - lastStatsTime
            val bitrate = if (lastBytesReceived > 0 && timeDelta > 0) {
                ((bytesReceived - lastBytesReceived) * 8 * 1000) / timeDelta
            } else {
                0L
            }
            
            // 更新上次的值
            lastBytesReceived = bytesReceived
            lastStatsTime = currentTime
            
            // 记录信息到日志
            Log.d(TAG, "视频统计: 帧接收=$framesReceived, 帧解码=$framesDecoded, 包接收=$packetsReceived, " +
                       "丢包=$packetsLost (${String.format("%.2f", packetLossRate)}%), " +
                       "比特率=${bitrate/1000}Kbps, 抖动=${jitter*1000}ms, 延迟=${String.format("%.1f", currentDelay)}ms")
            
            // 判断视频流是否实际接收中
            if (previousFramesReceived == framesReceived && framesReceived > 0) {
                noFrameChangeCount++
                if (noFrameChangeCount >= 3) { // 连续3次没有新帧
                    Log.w(TAG, "视频流停滞: 连续${noFrameChangeCount}次检测没有新帧")
                    // 可能需要重置连接
                    if (noFrameChangeCount == 3) {
                        resetVideoRenderer()
                    }
                }
            } else {
                noFrameChangeCount = 0
            }
            
            previousFramesReceived = framesReceived
            
            // 显示关键指标
            if (showDetailedStats) {
                val statsText = "帧率: ${framesDecoded - lastFramesDecoded}/2s\n" +
                                "比特率: ${bitrate/1000}Kbps\n" +
                                "延迟: ${String.format("%.1f", currentDelay)}ms\n" +
                                "丢包率: ${String.format("%.2f", packetLossRate)}%"
                                
                runOnUiThread {
                    // 如果有统计文本视图，更新它
                    statsTextView?.text = statsText
                }
                
                lastFramesDecoded = framesDecoded
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理统计数据时出错", e)
        }
    }
    
    // 重置视频渲染器
    private fun resetVideoRenderer() {
        try {
            Log.d(TAG, "重置视频渲染器")
            
            runOnUiThread {
                // 获取视频轨道
                val receivers = peerConnection?.getReceivers()
                val videoReceiver = receivers?.find { it.track()?.kind() == "video" }
                
                if (videoReceiver != null) {
                    val videoTrack = videoReceiver.track() as? VideoTrack
                    if (videoTrack != null) {
                        // 移除之前的接收器
                        videoTrack.removeSink(surfaceView)
                        
                        // 重置渲染器
                        surfaceView.clearImage()
                        surfaceView.release()
                        surfaceView.init(eglBase!!.eglBaseContext, null)
                        
                        // 重新配置渲染参数
                        surfaceView.setZOrderMediaOverlay(true)
                        surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        surfaceView.setEnableHardwareScaler(true)
                        
                        // 重新添加视频轨道
                        videoTrack.addSink(surfaceView)
                        
                        // 强制刷新
                        surfaceView.requestLayout()
                        
                        Log.d(TAG, "视频渲染器已重置")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "重置视频渲染器时出错", e)
        }
    }
}