package com.chuan.android_combined

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 应用图标工具类，用于管理应用图标的显示和隐藏
 * 以及相关的UI反馈（Toast、Notification）
 */
object AppIconUtils {
    
    private const val TAG = "AppIconUtils"
    private const val LAUNCHER_ACTIVITY_NAME = "LauncherActivity"
    private const val NOTIFICATION_CHANNEL_ID = "screenlink_channel"
    private const val NOTIFICATION_CHANNEL_NAME = "ScreenLink 通知"
    
    /**
     * 切换应用图标显示状态
     * @param context 上下文
     * @return 切换后的状态：true表示隐藏，false表示显示
     */
    fun toggleAppIconVisibility(context: Context): Boolean {
        try {
            val packageManager = context.packageManager
            val componentName = ComponentName(context.packageName, context.packageName + "." + LAUNCHER_ACTIVITY_NAME)
            
            // 获取当前组件状态
            val currentState = try {
                packageManager.getComponentEnabledSetting(componentName)
            } catch (e: Exception) {
                Log.e(TAG, "获取组件状态失败", e)
                return false
            }
            
            // 根据当前状态切换
            val newState = if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || 
                currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                // 当前是隐藏状态，需要显示
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                // 当前是显示状态，需要隐藏
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            
            packageManager.setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP
            )
            
            val isHidden = newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            Log.d(TAG, "应用图标状态已切换: ${if (isHidden) "隐藏" else "显示"}")
            
            // 如果隐藏图标，同时隐藏后台任务窗口
            if (isHidden) {
                hideRecentTasks(context)
            }
            
            return isHidden
            
        } catch (e: Exception) {
            Log.e(TAG, "切换应用图标可见性时出错", e)
            return false
        }
    }
    
    /**
     * 检查应用图标当前状态
     * @param context 上下文
     * @return true表示隐藏，false表示显示
     */
    fun isAppIconHidden(context: Context): Boolean {
        try {
            val packageManager = context.packageManager
            val componentName = ComponentName(context.packageName, context.packageName + "." + LAUNCHER_ACTIVITY_NAME)
            
            val status = try {
                packageManager.getComponentEnabledSetting(componentName)
            } catch (e: Exception) {
                Log.e(TAG, "获取组件状态失败", e)
                return false
            }
            
            val isHidden = status == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            Log.d(TAG, "应用图标状态: ${if (isHidden) "隐藏" else "显示"}")
            return isHidden
            
        } catch (e: Exception) {
            Log.e(TAG, "检查应用图标状态时出错", e)
            return false
        }
    }
    
    /**
     * 显示应用图标（从隐藏状态恢复）
     * @param context 上下文
     * @param autoLaunch 是否自动启动应用，默认为true
     * @return 是否成功显示
     */
    fun showAppIcon(context: Context, autoLaunch: Boolean = true): Boolean {
        try {
            val packageManager = context.packageManager
            val componentName = ComponentName(context.packageName, context.packageName + "." + LAUNCHER_ACTIVITY_NAME)
            
            val currentState = packageManager.getComponentEnabledSetting(componentName)
            
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || 
                currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                // 当前是隐藏状态，需要显示
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                
                Log.d(TAG, "显示应用图标成功")
                
                // 只有在需要自动启动时才启动应用
                if (autoLaunch) {
                    val launchIntent = Intent(context, MainDispatcherActivity::class.java)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                }
                
                return true
            }
            
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "显示应用图标时出错", e)
            return false
        }
    }
    
    /**
     * 检查LauncherActivity别名是否存在
     * @param context 上下文
     * @return true表示存在，false表示不存在
     */
    fun checkLauncherActivityExists(context: Context): Boolean {
        try {
            val aliasComponentName = ComponentName(context.packageName, context.packageName + "." + LAUNCHER_ACTIVITY_NAME)
            val aliasExists = try {
                context.packageManager.getActivityInfo(aliasComponentName, 0)
                true
            } catch (e: Exception) {
                false
            }
            
            Log.d(TAG, "LauncherActivity 存在: $aliasExists")
            return aliasExists
            
        } catch (e: Exception) {
            Log.e(TAG, "检查启动组件时出错", e)
            return false
        }
    }
    
    /**
     * 调试方法：打印详细的组件信息
     * @param context 上下文
     */
    fun debugComponentInfo(context: Context) {
        try {
            val packageManager = context.packageManager
            val componentName = ComponentName(context.packageName, LAUNCHER_ACTIVITY_NAME)
            
            Log.d(TAG, "=== 组件调试信息 ===")
            Log.d(TAG, "包名: ${context.packageName}")
            Log.d(TAG, "组件名称: $LAUNCHER_ACTIVITY_NAME")
            Log.d(TAG, "完整组件名: $componentName")
            
            // 检查组件是否存在
            val exists = try {
                packageManager.getActivityInfo(componentName, 0)
                true
            } catch (e: Exception) {
                Log.e(TAG, "组件不存在: ${e.message}")
                false
            }
            Log.d(TAG, "组件存在: $exists")
            
            if (exists) {
                // 获取组件状态
                val status = packageManager.getComponentEnabledSetting(componentName)
                val statusText = when (status) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "DEFAULT"
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
                    else -> "UNKNOWN($status)"
                }
                Log.d(TAG, "组件状态: $statusText")
                
                // 检查是否为启动器组件
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
                
                var isLauncher = false
                for (resolveInfo in resolveInfoList) {
                    if (resolveInfo.activityInfo.name == LAUNCHER_ACTIVITY_NAME) {
                        isLauncher = true
                        break
                    }
                }
                Log.d(TAG, "是否为启动器组件: $isLauncher")
            }
            
            Log.d(TAG, "=== 调试信息结束 ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "调试组件信息时出错", e)
        }
    }
    
    /**
     * 安全地显示消息（Toast或Notification）
     * @param context 上下文
     * @param message 消息内容
     */
    fun showMessage(context: Context, message: String) {
        try {
            // 只记录日志，不显示任何弹窗
            Log.d(TAG, "消息: $message")
        } catch (e: Exception) {
            Log.e(TAG, "显示消息时出错", e)
        }
    }
    
    /**
     * 显示通知
     * @param context 上下文
     * @param message 消息内容
     */
    private fun showNotification(context: Context, message: String) {
        try {
            // 仅在 Android 8.0 及以上版本显示通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                // 创建通知渠道
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
                
                // 创建通知
                val intent = Intent(context, MainDispatcherActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                
                val notification = android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
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
     * @param context 上下文
     * @return true表示在前台，false表示在后台
     */
    private fun isAppInForeground(context: Context): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
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
    
    /**
     * 隐藏后台任务窗口
     */
    private fun hideRecentTasks(context: Context) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activityManager.appTasks.forEach { task ->
                    val basePkg = task.taskInfo.baseActivity?.packageName
                    if (basePkg == context.packageName) {
                        task.setExcludeFromRecents(true)
                    }
                }
            }
            Log.d(TAG, "后台任务窗口已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏后台任务窗口失败", e)
        }
    }
} 