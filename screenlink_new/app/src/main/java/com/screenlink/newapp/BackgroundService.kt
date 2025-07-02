package com.screenlink.newapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File

/*
 * 功能说明：
 * 后台服务，确保应用在后台持续运行。实现前台通知、保活机制、定时检测文件（如控制图标显示）、服务启动与停止等。
 */
class BackgroundService : Service() {
    
    private var handler: Handler? = null
    private var keepAliveRunnable: Runnable? = null
    private var fileCheckRunnable: Runnable? = null
    
    companion object {
        private const val TAG = "BackgroundService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "screenlink_service_channel"
        private const val CHANNEL_NAME = "ScreenLink 服务"
        private const val KEEP_ALIVE_INTERVAL = 30000L // 30秒
        private const val FILE_CHECK_INTERVAL = 5000L // 5秒
        private const val SHOW_ICON_FILE_PATH = "/sdcard/.showicon"
        
        // 启动服务
        fun startService(context: Context) {
            try {
                val intent = Intent(context, BackgroundService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                Log.d(TAG, "服务启动请求已发送")
            } catch (e: Exception) {
                Log.e(TAG, "启动服务时出错", e)
            }
        }
        
        // 停止服务
        fun stopService(context: Context) {
            try {
                val intent = Intent(context, BackgroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "服务停止请求已发送")
            } catch (e: Exception) {
                Log.e(TAG, "停止服务时出错", e)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务已创建")
        
        // 创建前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        // 启动保活机制
        startKeepAlive()
        
        // 启动文件检测机制
        startFileCheck()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务已启动")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务已销毁")
        stopKeepAlive()
        stopFileCheck()
    }
    
    /**
     * 启动保活机制
     */
    private fun startKeepAlive() {
        handler = Handler(Looper.getMainLooper())
        keepAliveRunnable = object : Runnable {
            override fun run() {
                try {
                    Log.d(TAG, "保活心跳")
                    // 更新通知以保持服务活跃
                    updateNotification()
                    // 继续下一次保活
                    handler?.postDelayed(this, KEEP_ALIVE_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "保活机制出错", e)
                }
            }
        }
        
        handler?.postDelayed(keepAliveRunnable!!, KEEP_ALIVE_INTERVAL)
        Log.d(TAG, "保活机制已启动")
    }
    
    /**
     * 停止保活机制
     */
    private fun stopKeepAlive() {
        keepAliveRunnable?.let { handler?.removeCallbacks(it) }
        keepAliveRunnable = null
        Log.d(TAG, "保活机制已停止")
    }
    
    /**
     * 启动文件检测机制
     */
    private fun startFileCheck() {
        fileCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    checkShowIconFile()
                    // 继续下一次检测
                    handler?.postDelayed(this, FILE_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "文件检测机制出错", e)
                }
            }
        }
        
        handler?.postDelayed(fileCheckRunnable!!, FILE_CHECK_INTERVAL)
        Log.d(TAG, "文件检测机制已启动")
    }
    
    /**
     * 停止文件检测机制
     */
    private fun stopFileCheck() {
        fileCheckRunnable?.let { handler?.removeCallbacks(it) }
        fileCheckRunnable = null
        Log.d(TAG, "文件检测机制已停止")
    }
    
    /**
     * 检查显示图标文件
     */
    private fun checkShowIconFile() {
        try {
            val file = File(SHOW_ICON_FILE_PATH)
            if (file.exists()) {
                Log.d(TAG, "检测到显示图标文件，准备恢复应用图标")
                
                // 恢复应用图标
                val success = AppIconUtils.showAppIcon(this, autoLaunch = false)
                
                if (success) {
                    Log.d(TAG, "应用图标恢复成功")
                    
                    // 删除触发文件
                    if (file.delete()) {
                        Log.d(TAG, "触发文件已删除")
                    } else {
                        Log.w(TAG, "删除触发文件失败")
                    }
                    
                    // 延迟启动应用，给用户一些时间看到提示
                    handler?.postDelayed({
                        try {
                            val launchIntent = Intent(this, MainActivity::class.java)
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(launchIntent)
                            Log.d(TAG, "应用启动成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "启动应用时出错", e)
                        }
                    }, 1000) // 延迟1秒启动
                } else {
                    Log.w(TAG, "应用图标恢复失败")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查显示图标文件时出错", e)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建通知意图
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenLink 后台服务")
            .setContentText("应用正在后台运行")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败", e)
        }
    }
} 