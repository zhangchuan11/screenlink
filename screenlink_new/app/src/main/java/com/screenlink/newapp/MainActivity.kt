package com.screenlink.newapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
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
    private lateinit var peerConnectionManager: PeerConnectionManager
    private lateinit var clientAdapter: ClientAdapter
    
    // 添加MediaProjection权限请求相关变量
    private val MEDIA_PROJECTION_REQUEST_CODE = 1001
    private var mediaProjectionManager: android.media.projection.MediaProjectionManager? = null
    
    companion object {
        private const val TAG = "MainActivity"
        
        // 静态WebRTC管理器实例，供DisplayActivity访问
        private var webRTCManagerInstance: WebRTCManager? = null
        
        fun getWebRTCManager(): WebRTCManager? = webRTCManagerInstance
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "MainActivity onCreate 开始")
        
        // 启动后台服务
        BackgroundService.startService(this)
        
        // 初始化管理器
        initializeManagers()
        
        // 创建UI
        setContentView(R.layout.activity_main)
        Log.d(TAG, "布局已设置")

        // 初始化控件
        val tvSelectedClient = findViewById<TextView>(R.id.tvSelectedClient)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnStartSender = findViewById<Button>(R.id.btnStartSender)
        val recyclerViewClients = findViewById<RecyclerView>(R.id.recyclerViewClients)
        
        Log.d(TAG, "UI组件初始化完成: tvSelectedClient=${tvSelectedClient != null}, btnConnect=${btnConnect != null}, btnStartSender=${btnStartSender != null}, recyclerViewClients=${recyclerViewClients != null}")

        // 获取状态文本视图
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        
        // 初始化发送端列表适配器
        clientAdapter = ClientAdapter(emptyList()) { sender ->
            Log.d(TAG, "发送端被点击: ${sender.name} (ID: ${sender.id})")
            val tvSelectedClient = findViewById<TextView>(R.id.tvSelectedClient)
            tvSelectedClient.text = "已选择发送端: ${sender.name}"
            
            // 如果发送端可用，尝试连接
            if (sender.available) {
                // 这里需要实现选择发送端的逻辑
                webRTCManager.selectSender(sender.id)
                tvStatus.text = "正在连接到发送端: ${sender.name}"
            } else {
                tvStatus.text = "发送端不可用: ${sender.name}"
                Toast.makeText(this@MainActivity, "发送端不可用", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerViewClients.adapter = clientAdapter
        recyclerViewClients.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        Log.d(TAG, "ClientAdapter初始化完成")

        // 检查应用图标状态
        updateIconStatus()
        
        // 设置默认模式为接收端模式
        tvStatus.text = "接收端模式 - 正在连接服务器..."
        
        // 自动连接到默认服务器
        webRTCManager.connectToSignalingServer("192.168.1.3:6060")

        // 连接按钮显示连接状态
        btnConnect.setOnClickListener {
            Log.d(TAG, "连接按钮被点击")
            val status = if (webRTCManager.isConnected()) "已连接" else "未连接"
            Toast.makeText(this, "连接状态: $status", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "显示连接状态Toast: $status")
            
            // 检查远端视频轨道并手动启动显示页面
            val remoteVideoTrack = webRTCManager.getRemoteVideoTrack()
            if (remoteVideoTrack != null) {
                Log.d(TAG, "手动启动显示页面，远端视频轨道ID: ${remoteVideoTrack.id()}")
                startDisplayActivity()
            } else {
                Log.d(TAG, "远端视频轨道未获取，无法启动显示页面")
                Toast.makeText(this, "远端视频轨道未获取", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 启动发送端服务按钮
        btnStartSender.setOnClickListener {
            Log.d(TAG, "启动发送端服务按钮被点击")
            try {
                // 请求MediaProjection权限
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                startActivityForResult(
                    mediaProjectionManager!!.createScreenCaptureIntent(),
                    MEDIA_PROJECTION_REQUEST_CODE
                )
                Log.d(TAG, "已请求MediaProjection权限")
            } catch (e: Exception) {
                Log.e(TAG, "请求MediaProjection权限失败", e)
                Toast.makeText(this, "请求录屏权限失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 测试UI响应
        Log.d(TAG, "MainActivity onCreate 完成，显示测试Toast")
        Toast.makeText(this, "应用已启动，UI测试正常", Toast.LENGTH_SHORT).show()
    }
    
    private fun initializeManagers() {
        // 初始化WebRTC管理器
        webRTCManager = WebRTCManager(this)
        webRTCManagerInstance = webRTCManager  // 设置静态实例
        
        // 设置客户端名称
        val deviceName = android.os.Build.MODEL
        webRTCManager.setClientName(deviceName)
        Log.d(TAG, "设置客户端名称为: $deviceName")
        
        // 设置WebRTC监听器
        webRTCManager.setListener(object : WebRTCManager.WebRTCListener {
            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    val btnConnect = findViewById<Button>(R.id.btnConnect)
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    btnConnect.text = if (connected) "已连接" else "未连接"
                    tvStatus.text = if (connected) "状态: 已连接到服务器，等待客户端列表..." else "状态: 连接已断开"
                }
            }
            
            override fun onSenderListReceived(senders: List<WebRTCManager.SenderInfo>) {
                Log.d(TAG, "收到发送端列表，数量: ${senders.size}")
                for (sender in senders) {
                    Log.d(TAG, "发送端: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}")
                }
                
                runOnUiThread {
                    // 更新发送端列表
                    clientAdapter.updateSenders(senders)
                    Log.d(TAG, "发送端列表已更新到UI")
                    
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    tvStatus.text = "状态: 在线发送端数: ${senders.size}，点击发送端开始投屏"
                    
                    if (senders.isEmpty()) {
                        tvStatus.text = "状态: 暂无在线发送端"
                    }
                }
            }
            
            override fun onOfferReceived(sdp: String) {
                peerConnectionManager.setRemoteDescription(sdp, SessionDescription.Type.OFFER)
            }
            
            override fun onAnswerReceived(sdp: String) {
                // 接收端不需要处理Answer
            }
            
            override fun onIceCandidateReceived(candidate: String, sdpMLineIndex: Int, sdpMid: String) {
                peerConnectionManager.addIceCandidate(candidate, sdpMLineIndex, sdpMid)
            }
            
            override fun onRequestOffer() {
                // 接收端不需要处理Offer请求
            }
            
            override fun onConnectRequestReceived(sourceClientId: Int) {
                // 接收端不需要处理连接请求
                Log.d(TAG, "接收端收到连接请求，但不需要处理: $sourceClientId")
            }
            
            override fun onClientListReceived(clients: List<WebRTCManager.ClientInfo>) {
                // 接收端不需要处理客户端列表
                Log.d(TAG, "接收端收到客户端列表，但不需要处理，数量: ${clients.size}")
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "WebRTC错误: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "连接错误: $error", Toast.LENGTH_LONG).show()
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    tvStatus.text = "状态: 连接错误 - $error"
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
                // 发送Offer给目标客户端
                webRTCManager.sendOfferToTarget(sdp.description)
                Log.d(TAG, "Offer已发送给目标客户端")
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
                        
                        // 检查远端视频轨道
                        val remoteVideoTrack = webRTCManager.getRemoteVideoTrack()
                        Log.d(TAG, "远端视频轨道状态: ${if (remoteVideoTrack != null) "已获取" else "未获取"}")
                        
                        if (remoteVideoTrack != null) {
                            Log.d(TAG, "远端视频轨道ID: ${remoteVideoTrack.id()}")
                            startDisplayActivity()
                        } else {
                            Log.w(TAG, "远端视频轨道未获取，等待轨道添加...")
                            // 延迟启动显示页面，等待视频轨道
                            runOnUiThread {
                                val handler = Handler(android.os.Looper.getMainLooper())
                                handler.postDelayed({
                                    val track = webRTCManager.getRemoteVideoTrack()
                                    if (track != null) {
                                        Log.d(TAG, "延迟后获取到远端视频轨道，启动显示页面")
                                        startDisplayActivity()
                                    } else {
                                        Log.e(TAG, "延迟后仍未获取到远端视频轨道")
                                    }
                                }, 2000)
                            }
                        }
                    }
                }
            }
        })
        
        // 初始化WebRTC
        webRTCManager.initialize()
        peerConnectionManager.setFactory(webRTCManager.getFactory())
    }
    
    private fun updateIconStatus() {
        val isHidden = AppIconUtils.isAppIconHidden(this)
        val tvIconStatus = findViewById<TextView>(R.id.tvIconStatus)
        tvIconStatus.text = if (isHidden) "应用图标已隐藏" else "应用图标已显示"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // 清理资源
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection权限已获得")
                Toast.makeText(this, "录屏权限已获得，正在启动发送端服务...", Toast.LENGTH_SHORT).show()
                
                // 启动ScreenCaptureService并传递MediaProjection数据
                try {
                    ScreenCaptureService.connectToSignalingServer(
                        this,
                        "192.168.1.3:6060",
                        "发送端",
                        data,
                        resultCode
                    )
                    Log.d(TAG, "发送端服务启动成功")
                } catch (e: Exception) {
                    Log.e(TAG, "启动发送端服务失败", e)
                    Toast.makeText(this, "启动发送端服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.w(TAG, "MediaProjection权限被拒绝")
                Toast.makeText(this, "录屏权限被拒绝，无法启动发送端服务", Toast.LENGTH_SHORT).show()
            }
        }
    }
} 