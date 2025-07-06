package com.screenlink.newapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import org.webrtc.SurfaceViewRenderer

/*
 * 功能说明：
 * 主界面 Activity，整合了发送端和接收端的主要逻辑，负责 UI 初始化、WebRTC 管理器初始化、信令服务器连接、客户端列表展示与选择、权限请求等。是应用的主入口。
 */
class MainActivity : Activity() {
    
    // 管理器
    private var screenShareService: ScreenShareService? = null
    private var isServiceBound = false
    private lateinit var clientAdapter: ClientAdapter
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            Log.d("MainActivity", "[日志追踪] onServiceConnected 被调用")
            val binder = service as? ScreenShareService.LocalBinder
            screenShareService = binder?.getService()
            isServiceBound = true
            screenShareServiceInstance = screenShareService
            
            // 检查连接状态，避免重复连接
            if (screenShareService?.isConnected() != true) {
                Log.d("MainActivity", "服务未连接，开始连接到信令服务器")
                screenShareService?.connectToSignalingServer("192.168.1.2:6060")
            } else {
                Log.d("MainActivity", "服务已连接，跳过重复连接")
            }
            
            screenShareService?.setWebRTCListener(object : ScreenShareService.WebRTCListener {
                override fun onConnectionStateChanged(connected: Boolean) {
                    runOnUiThread {
                        val status = screenShareService?.getConnectionStatus() ?: "未知状态"
                        val msg = if (connected) "已连接服务器" else "已断开服务器"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "弹窗: $msg")
                        // 更新状态文本
                        findViewById<TextView>(R.id.tvStatus)?.text = "连接状态: $status"
                    }
                }
                override fun onSenderListReceived(senders: List<ScreenShareService.SenderInfo>) {
                    runOnUiThread {
                        Log.d(TAG, "收到发送端列表更新: ${senders.size} 个发送端")
                        
                        // 检查发送端状态变化
                        val availableSenders = senders.filter { it.available }
                        val unavailableSenders = senders.filter { !it.available }
                        
                        // 显示详细的状态信息
                        val statusText = buildString {
                            if (availableSenders.isNotEmpty()) {
                                append("🟢 可用发送端: ${availableSenders.joinToString(", ") { it.name }}")
                            }
                            if (unavailableSenders.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append("🔴 不可用发送端: ${unavailableSenders.joinToString(", ") { it.name }}")
                            }
                        }
                        
                        // 更新状态文本
                        findViewById<TextView>(R.id.tvStatus)?.text = statusText
                        
                        // 如果有可用发送端，显示通知
                        if (availableSenders.isNotEmpty()) {
                            val availableNames = availableSenders.joinToString(", ") { it.name }
                            Toast.makeText(this@MainActivity, "发现可用发送端: $availableNames", Toast.LENGTH_LONG).show()
                            Log.d(TAG, "弹窗: 发现可用发送端: $availableNames")
                        }
                        
                        for (sender in senders) {
                            Log.d(TAG, "发送端: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}, 时间戳=${sender.timestamp}")
                        }
                        clientAdapter.updateSenders(senders)
                        Log.d(TAG, "已调用 clientAdapter.updateSenders")
                    }
                }
                override fun onOfferReceived(sdp: String) {}
                override fun onAnswerReceived(sdp: String) {}
                override fun onIceCandidateReceived(candidate: String, sdpMLineIndex: Int, sdpMid: String) {}
                override fun onRequestOffer() {}
                override fun onClientListReceived(clients: List<ScreenShareService.ClientInfo>) {
                    runOnUiThread {
                        clientAdapter.updateClients(clients)
                    }
                }
                override fun onConnectRequestReceived(sourceClientId: Int) {}
                override fun onRemoteVideoTrackReceived(track: org.webrtc.VideoTrack) {
                    runOnUiThread {
                        Log.d(TAG, "收到远端视频轨道: ${track.id()}")
                        Toast.makeText(this@MainActivity, "收到远端视频轨道，准备跳转到全屏显示", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "弹窗: 收到远端视频轨道，准备跳转到全屏显示")
                        
                        // 直接跳转到全屏显示页面
                        startDisplayActivity()
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "弹窗: $error")
                        
                        // 检查是否是权限相关错误
                        if (error.contains("需要重新获取屏幕录制权限") || error.contains("MediaProjection数据无效")) {
                            Log.d(TAG, "检测到权限问题，准备重新请求权限")
                            // 重新启用发送端按钮
                            btnStartSender.isEnabled = true
                            findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 需要重新获取权限"
                        }
                    }
                }
            })

            // 设置屏幕采集监听器
            screenShareService?.setScreenCaptureListener(object : ScreenShareService.ScreenCaptureListener {
                override fun onScreenCaptureStarted() {
                    runOnUiThread {
                        Log.d(TAG, "屏幕采集已启动")
                        Toast.makeText(this@MainActivity, "屏幕采集已启动", Toast.LENGTH_SHORT).show()
                        findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 屏幕采集已启动"
                        btnStartSender.isEnabled = true // 重新启用按钮
                    }
                }
                
                override fun onScreenCaptureStopped() {
                    runOnUiThread {
                        Log.d(TAG, "屏幕采集已停止")
                        Toast.makeText(this@MainActivity, "屏幕采集已停止", Toast.LENGTH_SHORT).show()
                        findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 屏幕采集已停止"
                        btnStartSender.isEnabled = true // 重新启用按钮
                    }
                }
                
                override fun onScreenCaptureError(error: String) {
                    runOnUiThread {
                        Log.e(TAG, "屏幕采集错误: $error")
                        Toast.makeText(this@MainActivity, "屏幕采集错误: $error", Toast.LENGTH_LONG).show()
                        findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 采集错误: $error"
                        btnStartSender.isEnabled = true // 重新启用按钮
                        
                        // 如果是权限问题，提供重新请求的选项
                        if (error.contains("需要重新获取屏幕录制权限") || error.contains("MediaProjection数据无效")) {
                            Log.d(TAG, "检测到权限问题，准备重新请求权限")
                            // 可以在这里添加一个对话框让用户选择是否重新请求权限
                        }
                    }
                }
            })
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            screenShareService = null
            isServiceBound = false
        }
    }
    
    // 添加MediaProjection权限请求相关变量
    private val MEDIA_PROJECTION_REQUEST_CODE = 1001
    private var mediaProjectionManager: android.media.projection.MediaProjectionManager? = null
    // 新增：防止多次自动弹窗
    private var hasRequestedProjection = false
    
    // 添加按钮成员变量
    private lateinit var btnStartSender: Button
    private lateinit var btnToggleIcon: Button
    private lateinit var btnShowIcon: Button
    
    companion object {
        private const val TAG = "MainActivity"
        var screenShareServiceInstance: ScreenShareService? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "MainActivity onCreate 开始")
        
        // 启动后台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, ScreenShareService::class.java))
        } else {
            startService(Intent(this, ScreenShareService::class.java))
        }
        
        // 创建UI
        setContentView(R.layout.activity_main)
        Log.d(TAG, "布局已设置")

        // 初始化控件
        val tvSelectedClient = findViewById<TextView>(R.id.tvSelectedClient)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        btnStartSender = findViewById<Button>(R.id.btnStartSender)
        btnToggleIcon = findViewById<Button>(R.id.btnToggleIcon)
        btnShowIcon = findViewById<Button>(R.id.btnShowIcon)
        val recyclerViewClients = findViewById<RecyclerView>(R.id.recyclerViewClients)
        
        Log.d(TAG, "UI组件初始化完成: tvSelectedClient=${tvSelectedClient != null}, btnConnect=${btnConnect != null}, btnStartSender=${btnStartSender != null}, btnToggleIcon=${btnToggleIcon != null}, btnShowIcon=${btnShowIcon != null}, recyclerViewClients=${recyclerViewClients != null}")

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
                screenShareService?.selectSender(sender.id)
                tvStatus.text = "正在连接到发送端: ${sender.name}"
            } else {
                tvStatus.text = "发送端不可用: ${sender.name}"
                Toast.makeText(this@MainActivity, "发送端不可用", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "弹窗: 发送端不可用")
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
        // screenShareService?.connectToSignalingServer("192.168.1.2:6060")

        // 连接按钮显示连接状态
        btnConnect.setOnClickListener {
            Log.d(TAG, "连接按钮被点击")
            val status = if (screenShareService?.isConnected() == true) "已连接" else "未连接"
            Toast.makeText(this, "连接状态: $status", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "弹窗: 连接状态: $status")
            
            // 检查远端视频轨道并手动启动显示页面
            val remoteVideoTrack = screenShareService?.remoteVideoTrack
            if (remoteVideoTrack != null) {
                Log.d(TAG, "手动启动显示页面，远端视频轨道ID: ${remoteVideoTrack.id()}")
                startDisplayActivity()
            } else {
                Log.d(TAG, "远端视频轨道未获取，无法启动显示页面")
                Toast.makeText(this, "远端视频轨道未获取", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "弹窗: 远端视频轨道未获取")
            }
        }
        
        // 启动发送端服务按钮
        btnStartSender.setOnClickListener {
            Log.d(TAG, "启动发送端服务按钮被点击")
            hasRequestedProjection = true
            requestMediaProjectionPermission()
            btnStartSender.isEnabled = false // 禁用按钮
            // 不要在这里调用 startScreenCapture
            // Handler().postDelayed({ btnStartSender.isEnabled = true }, 2000) // 建议在采集真正开始后再启用
        }
        
        // 切换应用图标按钮
        btnToggleIcon.setOnClickListener {
            Log.d(TAG, "切换应用图标按钮被点击")
            val isHidden = AppIconUtils.toggleAppIconVisibility(this@MainActivity)
            updateIconStatus()
            val message = if (isHidden) "应用图标已隐藏" else "应用图标已显示"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "弹窗: $message")
        }
        
        // 显示应用图标按钮
        btnShowIcon.setOnClickListener {
            Log.d(TAG, "显示应用图标按钮被点击")
            val success = AppIconUtils.showAppIcon(this@MainActivity, autoLaunch = false)
            updateIconStatus()
            val message = if (success) "应用图标已恢复显示" else "应用图标已经是显示状态"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "弹窗: $message")
        }
        
        // 测试UI响应
        Log.d(TAG, "MainActivity onCreate 完成，显示测试Toast")
        Toast.makeText(this, "应用已启动，UI测试正常", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "弹窗: 应用已启动，UI测试正常")

        // 在 onCreate 或需要时启动并绑定服务
        val intent = android.content.Intent(this, ScreenShareService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
    }
    
    private fun updateIconStatus() {
        val isHidden = AppIconUtils.isAppIconHidden(this)
        val tvIconStatus = findViewById<TextView>(R.id.tvIconStatus)
        tvIconStatus.text = if (isHidden) "应用图标已隐藏" else "应用图标已显示"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy 开始")
        
        // 解绑服务
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        // 重置重连计数，避免下次启动时立即重连
        screenShareService?.resetReconnectAttempts()
        
        Log.d(TAG, "MainActivity onDestroy 完成")
    }
    
    private fun startDisplayActivity() {
        val intent = Intent(this, DisplayActivity::class.java)
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=${data != null}")
        
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "已设置发送端模式")
                
                // 将权限数据传递给服务
                screenShareService?.handlePermissionResult(requestCode, resultCode, data)
                
                // 启动屏幕采集
                screenShareService?.startScreenCapture(
                    screenShareService?.factory,
                    screenShareService?.eglBase
                )
                
                Toast.makeText(this, "录屏授权成功，已自动启动投屏服务", Toast.LENGTH_LONG).show()
                Log.d(TAG, "弹窗: 录屏授权成功，已自动启动投屏服务")
                
                // 更新UI状态
                findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 屏幕采集已启动"
                
            } else {
                Log.d(TAG, "录屏权限被拒绝")
                Toast.makeText(this, "录屏权限被拒绝，无法启动投屏服务", Toast.LENGTH_LONG).show()
                Log.d(TAG, "弹窗: 录屏权限被拒绝，无法启动投屏服务")
                
                // 更新UI状态
                findViewById<TextView>(R.id.tvStatus)?.text = "发送端模式 - 权限被拒绝"
            }
        }
    }

    private fun requestMediaProjectionPermission() {
        try {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(
                mediaProjectionManager!!.createScreenCaptureIntent(),
                MEDIA_PROJECTION_REQUEST_CODE
            )
            Log.d(TAG, "自动请求MediaProjection权限")
        } catch (e: Exception) {
            Log.e(TAG, "自动请求MediaProjection权限失败", e)
            Toast.makeText(this, "自动请求录屏权限失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "弹窗: 自动请求录屏权限失败: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 删除自动弹窗逻辑，不再自动申请MediaProjection权限
        // if (!hasRequestedProjection) {
        //     hasRequestedProjection = true
        //     requestMediaProjectionPermission()
        // }
    }
} 