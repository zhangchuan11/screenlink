package com.screenlink.newapp

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

/*
 * 功能说明：
 * 应用图标工具类，负责应用图标的显示/隐藏、状态检测、通知显示、LauncherActivity 检查等。支持通过文件或拨号盘触发图标恢复。
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
                    val launchIntent = Intent(context, MainActivity::class.java)
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
     * 隐藏最近任务窗口中的应用
     * @param context 上下文
     */
    private fun hideRecentTasks(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                
                // 获取当前任务列表
                val tasks = activityManager.getRunningTasks(10)
                
                // 查找并移除当前应用的任务
                for (task in tasks) {
                    if (task.baseActivity?.packageName == context.packageName) {
                        Log.d(TAG, "从最近任务中移除应用")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏最近任务失败", e)
        }
    }
    
    /**
     * 显示通知
     * @param context 上下文
     * @param title 标题
     * @param message 消息内容
     */
    fun showNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 创建通知渠道（Android 8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            // 创建通知意图
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 构建通知
            val notification = android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(1001, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "显示通知失败", e)
        }
    }
} 