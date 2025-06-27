package com.chuan.android_combined

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

/**
 * 后台服务，确保应用可以在后台运行
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
                            val launchIntent = Intent(this, MainDispatcherActivity::class.java)
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
     * 更新通知
     */
    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "更新通知时出错", e)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0 及以上版本需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // 提高重要性
            )
            channel.setShowBadge(true)
            channel.enableVibration(false)
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建打开应用的 Intent
        val intent = Intent(this, MainDispatcherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        // 创建通知
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_HIGH) // 提高优先级
        }
        
        return notificationBuilder
            .setContentTitle("ScreenLink 正在运行")
            .setContentText("投屏服务正在后台运行，点击打开应用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
} 