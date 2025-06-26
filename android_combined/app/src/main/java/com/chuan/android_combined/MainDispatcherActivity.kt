package com.chuan.android_combined

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        private const val AUTO_LAUNCH_ENABLED = "auto_launch_enabled"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查是否需要自动启动上次选择的模式
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoLaunchEnabled = prefs.getBoolean(AUTO_LAUNCH_ENABLED, false)
        
        if (autoLaunchEnabled) {
            val lastMode = prefs.getString(KEY_LAST_MODE, null)
            if (lastMode != null) {
                when (lastMode) {
                    MODE_SENDER -> {
                        startActivity(Intent(this, SenderMainActivity::class.java))
                        finish()
                        return
                    }
                    MODE_RECEIVER -> {
                        startActivity(Intent(this, ReceiverMainActivity::class.java))
                        finish()
                        return
                    }
                }
            }
        }
        
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
        
        // 添加说明文本
        val descText = TextView(this).apply {
            text = "请选择应用模式："
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(descText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // 添加发送端按钮
        val senderButton = Button(this).apply {
            text = "发送端 - 投屏设备"
            textSize = 16f
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                saveLastMode(MODE_SENDER)
                startActivity(Intent(this@MainDispatcherActivity, SenderMainActivity::class.java))
            }
        }
        layout.addView(senderButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 16)
        })
        
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
        
        // 添加自动启动选项按钮
        val autoLaunchButton = Button(this).apply {
            text = if (autoLaunchEnabled) "关闭自动启动上次模式" else "开启自动启动上次模式"
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                val newAutoLaunchState = !autoLaunchEnabled
                prefs.edit().putBoolean(AUTO_LAUNCH_ENABLED, newAutoLaunchState).apply()
                text = if (newAutoLaunchState) "关闭自动启动上次模式" else "开启自动启动上次模式"
            }
        }
        layout.addView(autoLaunchButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 32, 0, 16)
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
} 