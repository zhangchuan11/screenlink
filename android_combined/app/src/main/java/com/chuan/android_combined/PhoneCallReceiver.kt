package com.chuan.android_combined

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 电话广播接收器，用于监听拨号事件
 * 当用户拨打特定号码时，显示应用图标
 * 
 * 注意：从 Android 10 (API 29) 开始，应用无法再直接访问拨号意图
 * 因此我们需要使用特殊的拨号码格式，如 *#*#1234#*#*，这种格式不会实际拨出
 */
class PhoneCallReceiver : BroadcastReceiver() {
    
    // 用于显示应用图标的特定号码
    private val SHOW_APP_ICON_NUMBER = "*#*#12345#*#*"
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            Log.d(TAG, "收到广播: $action")
            
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                // 开机自启动处理
                Log.d(TAG, "系统启动完成，检查是否需要恢复应用")
                handleBootCompleted(context)
            } else if (action == Intent.ACTION_NEW_OUTGOING_CALL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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
                Log.d(TAG, "收到秘密代码广播")
                val uri = intent.data
                Log.d(TAG, "秘密代码 URI: $uri")
                
                if (uri != null) {
                    when (uri.host) {
                        "12345" -> {
                            Log.d(TAG, "匹配到秘密代码12345，准备显示应用图标")
                            showAppIcon(context)
                        }
                        else -> {
                            Log.d(TAG, "秘密代码不匹配，URI: $uri")
                        }
                    }
                } else {
                    Log.d(TAG, "秘密代码URI为空")
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
            Log.d(TAG, "开始显示应用图标")
            
            // 使用工具类显示应用图标，不自动启动应用
            val success = AppIconUtils.showAppIcon(context, autoLaunch = false)
            
            if (success) {
                Log.d(TAG, "应用图标显示成功")
                
                // 延迟启动应用，给用户一些时间看到提示
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val launchIntent = Intent(context, MainDispatcherActivity::class.java)
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                        Log.d(TAG, "应用启动成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "启动应用时出错", e)
                    }
                }, 1000) // 延迟1秒启动
            } else {
                Log.w(TAG, "应用图标显示失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示应用图标时出错", e)
        }
    }
    
    /**
     * 处理开机自启动
     */
    private fun handleBootCompleted(context: Context) {
        try {
            // 检查应用图标是否被隐藏
            if (AppIconUtils.isAppIconHidden(context)) {
                Log.d(TAG, "检测到应用图标被隐藏，尝试恢复")
                // 延迟一段时间后尝试恢复，确保系统完全启动
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        AppIconUtils.showAppIcon(context, autoLaunch = false)
                        Log.d(TAG, "开机自启动恢复应用图标成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "开机自启动恢复应用图标失败", e)
                    }
                }, 10000) // 延迟10秒
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理开机自启动时出错", e)
        }
    }
    
    companion object {
        private const val TAG = "PhoneCallReceiver"
    }
} 