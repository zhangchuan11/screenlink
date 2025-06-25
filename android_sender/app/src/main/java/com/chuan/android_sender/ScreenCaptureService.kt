package com.chuan.android_sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        startForeground()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        return START_STICKY
    }
    
    private fun startForeground() {
        val channelId = createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕共享")
            .setContentText("正在共享您的屏幕")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel(): String {
        val channelId = "screen_capture_service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "屏幕共享服务"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "用于支持屏幕共享功能的通知"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        return channelId
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
    }
} 