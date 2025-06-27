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
import org.webrtc.SurfaceViewRenderer

class SenderMainActivity : Activity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var ipEditText: EditText
    private lateinit var nameEditText: EditText
    private lateinit var connectButton: Button
    private var isConnected = false
    private var serverAddress = "192.168.168.102:6060"
    private var senderName = "发送端"
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
        
        // 调试：打印组件信息
        AppIconUtils.debugComponentInfo(this)
        
        // 创建动态布局
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        
        // 添加发送端名称输入框
        nameEditText = EditText(this)
        nameEditText.hint = "输入发送端名称 (例如：我的手机)"
        nameEditText.setText(senderName)
        layout.addView(nameEditText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
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
                senderName = nameEditText.text.toString()
                
                if (serverAddress.isEmpty()) {
                    Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (senderName.isEmpty()) {
                    Toast.makeText(this, "请输入发送端名称", Toast.LENGTH_SHORT).show()
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
    }
    
    private fun connectToSignalingServer() {
        // 启动后台保活服务
        BackgroundService.startService(this)
        startScreenCapture()
    }
    
    private fun disconnectFromSignalingServer() {
        // 停止屏幕捕获服务
        stopScreenCaptureService()
        // 停止后台保活服务
        BackgroundService.stopService(this)
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }
    
    private fun startScreenCapture() {
        // 请求屏幕捕获权限
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }
    
    private fun stopScreenCapture() {
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
        
        // 通过Service建立WebRTC连接
        screenCaptureService?.connectToSignalingServer(serverAddress, senderName, data, Activity.RESULT_OK)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不再在这里断开连接，让Service继续运行
        unbindService()
        
        surfaceView.release()
    }
    
    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1001
    }
}