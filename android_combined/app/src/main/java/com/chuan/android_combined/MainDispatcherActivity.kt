package com.chuan.android_combined

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主调度界面，用于选择以发送端或接收端模式启动应用
 */
class MainDispatcherActivity : Activity() {
    
    companion object {
        private const val PREFS_NAME = "ScreenLinkPrefs"
        private const val KEY_LAST_MODE = "last_mode"
        private const val MODE_SENDER = "sender"
        private const val MODE_RECEIVER = "receiver"
        private const val TAG = "MainDispatcherActivity"
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1001
    }
    
    private lateinit var iconToggleButton: Button
    private lateinit var iconStatusText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 创建动态布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        
        // 添加标题
        val titleText = TextView(this).apply {
            text = "ScreenLink"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }
        layout.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加应用图标控制区域
        val iconControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }
        
        // 添加图标状态显示
        iconStatusText = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        iconControlLayout.addView(iconStatusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加隐藏/显示图标按钮
        iconToggleButton = Button(this).apply {
            textSize = 16f
            setPadding(0, 12, 0, 12)
            setOnClickListener {
                toggleAppIcon()
            }
        }
        iconControlLayout.addView(iconToggleButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 16)
        })
        
        // 添加图标控制说明
        val iconDescText = TextView(this).apply {
            text = "提示：隐藏图标后可通过拨号 *#*#12345#*#* 或创建 .showicon 文件恢复"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.GRAY)
        }
        iconControlLayout.addView(iconDescText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        layout.addView(iconControlLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加分隔线
        val separator = TextView(this).apply {
            text = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }
        layout.addView(separator, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加说明文本
        val descText = TextView(this).apply {
            text = "应用模式："
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(descText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加接收端按钮
        val receiverButton = Button(this).apply {
            text = "接收端 - 显示设备"
            textSize = 16f
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                saveLastMode(MODE_RECEIVER)
                startActivity(Intent(this@MainDispatcherActivity, ReceiverMainActivity::class.java))
            }
        }
        layout.addView(receiverButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 16)
        })
        
        // 添加版本信息
        val versionText = TextView(this).apply {
            text = "ScreenLink v1.0"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        layout.addView(versionText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        setContentView(layout)
        
        // 初始化图标状态
        updateIconStatus()
        
        // 检查是否已经在投屏状态，如果不是则自动开始投屏
        if (!ScreenCaptureService.isScreenSharing()) {
            startAutoScreenSharing()
        } else {
            Log.d(TAG, "检测到已在投屏状态，跳过自动投屏启动")
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到主界面时更新图标状态
        updateIconStatus()
    }
    
    /**
     * 切换应用图标显示状态
     */
    private fun toggleAppIcon() {
        try {
            Log.d(TAG, "用户点击了图标切换按钮")
            
            // 直接切换，不显示确认对话框
            performIconToggle()
            
        } catch (e: Exception) {
            Log.e(TAG, "切换应用图标时出错", e)
        }
    }
    
    /**
     * 执行图标切换操作
     */
    private fun performIconToggle() {
        try {
            val isHidden = AppIconUtils.toggleAppIconVisibility(this)
            updateIconStatus()
            
            if (isHidden) {
                // 隐藏图标时最小化应用
                minimizeApp()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行图标切换时出错", e)
        }
    }
    
    /**
     * 最小化应用
     */
    private fun minimizeApp() {
        try {
            // 将应用移到后台
            moveTaskToBack(true)
            Log.d(TAG, "应用已最小化")
        } catch (e: Exception) {
            Log.e(TAG, "最小化应用失败", e)
        }
    }
    
    /**
     * 更新图标状态显示
     */
    private fun updateIconStatus() {
        try {
            val isHidden = AppIconUtils.isAppIconHidden(this)
            val isScreenSharing = ScreenCaptureService.isScreenSharing()
            
            // 更新状态文本
            val statusText = when {
                isHidden && isScreenSharing -> "应用图标：隐藏 (屏幕共享中)"
                isHidden -> "应用图标：隐藏"
                isScreenSharing -> "应用图标：显示 (屏幕共享中)"
                else -> "应用图标：显示"
            }
            iconStatusText.text = statusText
            
            // 更新按钮文本
            iconToggleButton.text = if (isHidden) "显示应用图标" else "隐藏应用图标"
            
        } catch (e: Exception) {
            Log.e(TAG, "更新图标状态时出错", e)
            iconStatusText.text = "应用图标：状态未知"
            iconToggleButton.text = "切换图标状态"
        }
    }
    
    /**
     * 保存用户最后选择的模式
     */
    private fun saveLastMode(mode: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MODE, mode)
            .apply()
    }
    
    /**
     * 自动开始屏幕共享
     */
    private fun startAutoScreenSharing() {
        try {
            Log.d(TAG, "开始自动屏幕共享")
            
            // 启动后台保活服务
            BackgroundService.startService(this)
            
            // 请求屏幕录制权限
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
            
        } catch (e: Exception) {
            Log.e(TAG, "启动自动屏幕共享失败", e)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 启动屏幕捕获服务
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                startService(serviceIntent)
                
                // 连接到信令服务器（使用默认地址和名称）
                val defaultServerAddress = "192.168.168.102:6060"
                val defaultSenderName = "自动投屏"
                ScreenCaptureService.connectToSignalingServer(this, defaultServerAddress, defaultSenderName, data)
                
                Log.d(TAG, "自动投屏启动成功")
            } else {
                Log.d(TAG, "屏幕录制权限被拒绝")
            }
        }
    }
} 