package com.screenlink.newapp

import android.app.Activity
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.webrtc.*
import android.content.Context

/*
 * 功能说明：
 * 专门用于显示远端视频画面的 Activity。负责视频渲染控件的初始化、远端视频轨道绑定、返回主界面等。
 */
class DisplayActivity : Activity() {
    
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var backButton: Button
    private lateinit var mainLayout: LinearLayout
    private var screenShareService: ScreenShareService? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? ScreenShareService.LocalBinder
            screenShareService = binder?.getService()
            isServiceBound = true
            // 初始化视频显示
            initializeVideoDisplay()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            screenShareService = null
            isServiceBound = false
        }
    }
    
    companion object {
        private const val TAG = "DisplayActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display)
        surfaceView = findViewById(R.id.remote_surface)

        // 获取 Service 实例
        screenShareService = MainActivity.screenShareServiceInstance

        // 创建全屏显示界面
        createDisplayUI()
        setContentView(mainLayout)
        
        val intent = Intent(this, ScreenShareService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun createDisplayUI() {
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        // 视频显示区域
        surfaceView = SurfaceViewRenderer(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        mainLayout.addView(surfaceView)
        
        // 返回按钮（悬浮在视频上方）
        backButton = Button(this).apply {
            text = "返回主页"
            setOnClickListener {
                finish()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 50
                leftMargin = 50
            }
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            setTextColor(android.graphics.Color.WHITE)
        }
        mainLayout.addView(backButton)
    }
    
    private fun initializeVideoDisplay() {
        try {
            // 获取WebRTC管理器的EglBase
            val eglBase = screenShareService?.eglBase
            
            eglBase?.let { egl ->
                surfaceView.init(egl.eglBaseContext, null)
                surfaceView.setZOrderMediaOverlay(true)
                surfaceView.setEnableHardwareScaler(true)
                surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                surfaceView.setMirror(false)
                surfaceView.setKeepScreenOn(true)
                surfaceView.visibility = View.VISIBLE
                
                // 绑定远端视频轨道
                bindRemoteVideoTrack()
                
                Log.d(TAG, "视频显示初始化完成")
            } ?: run {
                Log.e(TAG, "EglBase未初始化")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化视频显示失败", e)
        }
    }
    
    private fun bindRemoteVideoTrack() {
        val videoTrack = screenShareService?.remoteVideoTrack
        Log.d(TAG, "尝试绑定远端视频轨道，videoTrack=${videoTrack != null}")
        if (videoTrack != null) {
            Log.d(TAG, "远端视频轨道详情: ID=${videoTrack.id()}, enabled=${videoTrack.enabled()}")
            videoTrack.addSink(surfaceView)
            Log.d(TAG, "已绑定远端视频轨道: ${videoTrack.id()}")
        } else {
            Log.e(TAG, "未获取到远端视频轨道，将在1秒后重试")
            // 延迟重试
            surfaceView.postDelayed({
                bindRemoteVideoTrack()
            }, 1000)
        }
    }
    
    /**
     * 获取SurfaceView用于显示视频
     */
    fun getSurfaceView(): SurfaceViewRenderer = surfaceView
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // 重要：在销毁前移除视频轨道的绑定
            screenShareService?.remoteVideoTrack?.removeSink(surfaceView)
            surfaceView.release()
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放SurfaceView失败", e)
        }
    }
} 