package com.chuan.android_receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 后台服务，确保应用可以在后台运行
 */
class BackgroundService : Service() {
    
    companion object {
        private const val TAG = "BackgroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenlink_service_channel"
        private const val CHANNEL_NAME = "ScreenLink 服务"
        
        // 前台服务类型常量 (Android 10+)
        private const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
        
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
            startForeground(NOTIFICATION_ID, createNotification(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
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
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建打开应用的 Intent
        val intent = Intent(this, MainActivity::class.java)
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
                .setPriority(Notification.PRIORITY_LOW)
        }
        
        return notificationBuilder
            .setContentTitle("ScreenLink 正在运行")
            .setContentText("点击打开应用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
} 