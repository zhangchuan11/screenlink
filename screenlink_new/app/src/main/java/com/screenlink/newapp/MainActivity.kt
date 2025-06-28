package com.screenlink.newapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import org.webrtc.*

/**
 * 主活动，整合发送端和接收端功能
 */
class MainActivity : Activity() {
    
    // 管理器
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var peerConnectionManager: PeerConnectionManager
    private lateinit var clientAdapter: ClientAdapter
    private var isReceiverMode = true
    private var selectedClient: WebRTCManager.ClientInfo? = null
    
    // 模式相关
    private var selectedSenderId: Int? = null
    private var senderName = "发送端"
    
    companion object {
        private const val TAG = "MainActivity"
        
        // 静态WebRTC管理器实例，供DisplayActivity访问
        private var webRTCManagerInstance: WebRTCManager? = null
        
        fun getWebRTCManager(): WebRTCManager? = webRTCManagerInstance
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启动后台服务
        BackgroundService.startService(this)
        
        // 初始化管理器
        initializeManagers()
        
        // 创建UI
        setContentView(R.layout.activity_main)

        // 初始化控件
        val tvSelectedClient = findViewById<TextView>(R.id.tvSelectedClient)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnModeToggle = findViewById<Button>(R.id.btnModeToggle)
        val recyclerViewClients = findViewById<RecyclerView>(R.id.recyclerViewClients)

        // 初始化clientAdapter
        clientAdapter = ClientAdapter(emptyList())
        recyclerViewClients.adapter = clientAdapter
        recyclerViewClients.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // 检查应用图标状态
        updateIconStatus()
        
        // 设置模式切换按钮
        btnModeToggle.setOnClickListener {
            toggleMode()
        }
        
        // 设置默认模式为接收端模式
        isReceiverMode = true
        btnModeToggle.text = "切换到发送端模式"
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "接收端模式 - 正在连接服务器..."
        
        // 自动连接到默认服务器
        webRTCManager.connectToSignalingServer("192.168.1.3:6060")

        // 移除手动连接按钮的点击事件，改为自动连接
        btnConnect.setOnClickListener {
            // 连接按钮现在只用于显示状态，不执行连接操作
            Toast.makeText(this, "连接状态: ${if (webRTCManager.isConnected()) "已连接" else "未连接"}", Toast.LENGTH_SHORT).show()
        }

        // 客户端列表点击事件
        recyclerViewClients.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val position = rv.getChildAdapterPosition(child)
                    val client = clientAdapter.getClientAt(position)
                    if (client != null) {
                        webRTCManager.selectTargetClient(client.id)
                        tvSelectedClient.text = "已选择: ${client.name}"
                        
                        // 更新状态显示
                        val tvStatus = findViewById<TextView>(R.id.tvStatus)
                        tvStatus.text = "已选择客户端: ${client.name}"
                    }
                }
                return false
            }
            
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }
    
    private fun initializeManagers() {
        // 初始化WebRTC管理器
        webRTCManager = WebRTCManager(this)
        webRTCManagerInstance = webRTCManager  // 设置静态实例
        
        // 设置WebRTC监听器
        webRTCManager.setListener(object : WebRTCManager.WebRTCListener {
            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    val btnConnect = findViewById<Button>(R.id.btnConnect)
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    btnConnect.text = if (connected) "断开" else "连接"
                    tvStatus.text = if (connected) "状态: 已连接到服务器" else "状态: 连接已断开"
                    
                    // 连接成功后，如果是接收端模式，等待自动连接
                    if (connected && isReceiverMode) {
                        tvStatus.text = "已连接到服务器，等待发送端..."
                    }
                }
            }
            
            override fun onSenderListReceived(senders: List<WebRTCManager.SenderInfo>) {
                Log.d(TAG, "收到发送端列表，数量: ${senders.size}")
                for (sender in senders) {
                    Log.d(TAG, "发送端: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}")
                }
                
                runOnUiThread {
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    tvStatus.text = "状态: 发现 ${senders.size} 个发送端"
                    
                    // 如果是发送端模式，自动注册
                    if (!isReceiverMode) {
                        Log.d(TAG, "发送端模式，自动注册到服务器")
                        webRTCManager.sendRegistrationMessage("发送端设备")
                        tvStatus.text = "发送端模式 - 已注册到服务器"
                    }
                }
            }
            
            override fun onOfferReceived(sdp: String) {
                if (isReceiverMode) {
                    peerConnectionManager.setRemoteDescription(sdp, SessionDescription.Type.OFFER)
                }
            }
            
            override fun onAnswerReceived(sdp: String) {
                if (!isReceiverMode) {
                    peerConnectionManager.setRemoteDescription(sdp, SessionDescription.Type.ANSWER)
                }
            }
            
            override fun onIceCandidateReceived(candidate: String, sdpMLineIndex: Int, sdpMid: String) {
                peerConnectionManager.addIceCandidate(candidate, sdpMLineIndex, sdpMid)
            }
            
            override fun onRequestOffer() {
                if (!isReceiverMode) {
                    screenCaptureManager.startScreenCapture(webRTCManager.getFactory(), webRTCManager.getEglBase())
                    peerConnectionManager.createOffer()
                }
            }
            
            override fun onClientListReceived(clients: List<WebRTCManager.ClientInfo>) {
                runOnUiThread {
                    // 更新客户端列表
                    clientAdapter.updateClients(clients)
                    
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    val tvSelectedClient = findViewById<TextView>(R.id.tvSelectedClient)
                    tvStatus.text = "状态: 在线客户端数: ${clients.size}"
                    
                    // 自动选择第一个可用的发送端并连接
                    if (isReceiverMode && clients.isNotEmpty()) {
                        val firstClient = clients.first()
                        webRTCManager.selectTargetClient(firstClient.id)
                        tvSelectedClient.text = "已选择: ${firstClient.name}"
                        tvStatus.text = "自动选择客户端: ${firstClient.name}"
                        
                        // 自动开始连接
                        Log.d(TAG, "自动开始连接到客户端: ${firstClient.name} (ID: ${firstClient.id})")
                        val peerConnection = peerConnectionManager.createPeerConnection()
                        if (peerConnection != null) {
                            webRTCManager.sendConnectRequest(firstClient.id)
                            tvStatus.text = "正在连接到: ${firstClient.name}"
                        } else {
                            tvStatus.text = "创建连接失败"
                        }
                    }
                }
            }
        })
        
        // 初始化屏幕捕获管理器
        screenCaptureManager = ScreenCaptureManager(this)
        screenCaptureManager.setListener(object : ScreenCaptureManager.ScreenCaptureListener {
            override fun onScreenCaptureStarted() {
                Log.d(TAG, "屏幕捕获已开始")
            }
            
            override fun onScreenCaptureStopped() {
                Log.d(TAG, "屏幕捕获已停止")
            }
            
            override fun onScreenCaptureError(error: String) {
                runOnUiThread {
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    tvStatus.text = error
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        })
        
        // 初始化PeerConnection管理器
        peerConnectionManager = PeerConnectionManager()
        webRTCManager.setPeerConnectionManager(peerConnectionManager)
        peerConnectionManager.setListener(object : PeerConnectionManager.PeerConnectionListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                webRTCManager.sendIceCandidate(candidate)
            }
            
            override fun onOfferCreated(sdp: SessionDescription) {
                // 将Offer发送给目标客户端
                webRTCManager.sendOfferToTarget(sdp.description)
            }
            
            override fun onAnswerCreated(sdp: SessionDescription) {
                webRTCManager.sendAnswer(sdp.description)
            }
            
            override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "连接状态变化: $state")
                runOnUiThread {
                    updateConnectionStatus()
                    
                    // 当连接建立成功时，自动启动显示页面
                    if (state == PeerConnection.IceConnectionState.CONNECTED || 
                        state == PeerConnection.IceConnectionState.COMPLETED) {
                        Log.d(TAG, "WebRTC连接建立成功，自动启动显示页面")
                        startDisplayActivity()
                    }
                }
            }
        })
        
        // 初始化WebRTC
        webRTCManager.initialize()
        peerConnectionManager.setFactory(webRTCManager.getFactory())
    }
    
    private fun toggleMode() {
        isReceiverMode = !isReceiverMode
        
        val btnModeToggle = findViewById<Button>(R.id.btnModeToggle)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        
        if (isReceiverMode) {
            // 切换到接收端模式
            btnModeToggle.text = "切换到发送端模式"
            tvStatus.text = "接收端模式"
            
            // 清理发送端资源
            screenCaptureManager.cleanup()
            
            Log.d(TAG, "已切换到接收端模式")
        } else {
            // 切换到发送端模式
            btnModeToggle.text = "切换到接收端模式"
            tvStatus.text = "发送端模式 - 请求屏幕录制权限"
            
            // 请求屏幕录制权限
            screenCaptureManager.requestScreenCapturePermission(this)
            
            Log.d(TAG, "已切换到发送端模式")
        }
    }
    
    private fun toggleConnection() {
        if (webRTCManager.isConnected()) {
            webRTCManager.disconnectFromSignalingServer()
        } else {
            webRTCManager.connectToSignalingServer("192.168.1.3:6060")
        }
    }
    
    private fun toggleAppIcon() {
        val isHidden = AppIconUtils.toggleAppIconVisibility(this)
        updateIconStatus()
        
        val message = if (isHidden) "应用图标已隐藏" else "应用图标已显示"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun updateIconStatus() {
        val isHidden = AppIconUtils.isAppIconHidden(this)
        val tvIconStatus = findViewById<TextView>(R.id.tvIconStatus)
        tvIconStatus.text = if (isHidden) "应用图标已隐藏" else "应用图标已显示"
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (screenCaptureManager.handlePermissionResult(requestCode, resultCode, data)) {
            // 权限获取成功，开始屏幕捕获
            screenCaptureManager.startScreenCapture(webRTCManager.getFactory(), webRTCManager.getEglBase())
            
            // 发送注册消息
            webRTCManager.sendRegistrationMessage(senderName)
            
            // 更新状态
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            tvStatus.text = "发送端模式 - 屏幕捕获已开始"
            
            Log.d(TAG, "屏幕录制权限获取成功，已开始屏幕捕获")
        } else {
            // 权限被拒绝，切换回接收端模式
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            val btnModeToggle = findViewById<Button>(R.id.btnModeToggle)
            
            tvStatus.text = "屏幕录制权限被拒绝"
            isReceiverMode = true
            btnModeToggle.text = "切换到发送端模式"
            
            Log.d(TAG, "屏幕录制权限被拒绝，已切换回接收端模式")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // 清理资源
            screenCaptureManager.cleanup()
            peerConnectionManager.close()
            webRTCManager.cleanup()
            
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败", e)
        }
    }
    
    private fun updateConnectionStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val status = webRTCManager.getConnectionStatus()
        tvStatus.text = "状态: $status"
        Log.d(TAG, "连接状态: $status")
    }
    
    private fun startDisplayActivity() {
        val intent = Intent(this, DisplayActivity::class.java)
        startActivity(intent)
    }
} 