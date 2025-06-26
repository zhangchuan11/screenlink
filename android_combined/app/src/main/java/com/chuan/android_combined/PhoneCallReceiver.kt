package com.chuan.android_combined

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast

/**
 * 电话广播接收器，用于监听拨号事件
 * 当用户拨打特定号码时，显示应用图标
 * 
 * 注意：从 Android 10 (API 29) 开始，应用无法再直接访问拨号意图
 * 因此我们需要使用特殊的拨号码格式，如 *#*#1234#*#*，这种格式不会实际拨出
 */
class PhoneCallReceiver : BroadcastReceiver() {
    
    // 用于显示应用图标的特定号码
    private val SHOW_APP_ICON_NUMBER = "*#*#1234#*#*"
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            
            if (action == Intent.ACTION_NEW_OUTGOING_CALL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // 处理拨出电话 (仅适用于 Android 9 及以下版本)
                val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                Log.d(TAG, "拨出电话: $phoneNumber")
                
                if (phoneNumber == SHOW_APP_ICON_NUMBER) {
                    // 取消拨号
                    setResultData(null)
                    
                    // 显示应用图标
                    showAppIcon(context)
                }
            } else if (action == "android.provider.Telephony.SECRET_CODE") {
                // 处理秘密代码 (适用于所有 Android 版本)
                val uri = intent.data
                if (uri != null && uri.host == "1234") {
                    // 显示应用图标
                    showAppIcon(context)
                }
            } else if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                // 处理电话状态变化
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                
                if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                    // 电话接通
                    val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    Log.d(TAG, "电话接通: $phoneNumber")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理拨号事件时出错", e)
        }
    }
    
    /**
     * 显示应用图标
     */
    private fun showAppIcon(context: Context) {
        try {
            val packageName = context.packageName
            val componentName = ComponentName(packageName, packageName + ".LauncherActivity")
            
            val packageManager = context.packageManager
            val currentState = packageManager.getComponentEnabledSetting(componentName)
            
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || 
                currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                // 当前是隐藏状态，需要显示
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                
                // 安全地显示 Toast 消息
                showToastSafely(context, "应用图标已显示")
                Log.d(TAG, "通过拨号码显示应用图标成功")
                
                // 启动应用
                val launchIntent = Intent(context, MainDispatcherActivity::class.java)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示应用图标时出错", e)
        }
    }
    
    /**
     * 安全地显示 Toast 消息，检查应用是否在前台
     */
    private fun showToastSafely(context: Context, message: String) {
        try {
            // 检查应用是否在前台
            if (isAppInForeground(context)) {
                // 确保在主线程中显示 Toast
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "显示 Toast 时出错", e)
                    }
                }
            } else {
                Log.d(TAG, "应用不在前台，不显示 Toast: $message")
                
                // 如果应用不在前台，可以考虑使用通知来代替 Toast
                showNotification(context, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查应用状态时出错", e)
        }
    }
    
    /**
     * 显示通知
     */
    private fun showNotification(context: Context, message: String) {
        try {
            // 仅在 Android 8.0 及以上版本显示通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                // 创建通知渠道
                val channelId = "screenlink_channel"
                val channelName = "ScreenLink 通知"
                val channel = android.app.NotificationChannel(
                    channelId,
                    channelName,
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
                
                // 创建通知
                val intent = Intent(context, MainDispatcherActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                
                val notification = android.app.Notification.Builder(context, channelId)
                    .setContentTitle("ScreenLink")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
                
                // 显示通知
                notificationManager.notify(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示通知时出错", e)
        }
    }
    
    /**
     * 检查应用是否在前台
     */
    private fun isAppInForeground(context: Context): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val packageName = context.packageName
            
            // 获取正在运行的应用信息
            val appProcesses = activityManager.runningAppProcesses ?: return false
            
            // 检查当前进程是否在前台
            val myPid = android.os.Process.myPid()
            for (appProcess in appProcesses) {
                if (appProcess.pid == myPid && 
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查应用是否在前台时出错", e)
        }
        
        return false
    }
    
    companion object {
        private const val TAG = "PhoneCallReceiver"
    }
} 