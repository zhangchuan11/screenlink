package com.screenlink.newapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast

/*
 * 功能说明：
 * 电话监听广播接收器，监听来电、去电、开机等事件。在通话时自动暂停屏幕共享，通话结束后自动恢复，并支持通过拨号盘输入特定代码恢复应用图标。
 */
class PhoneCallReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PhoneCallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var lastPhoneNumber = ""
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        val action = intent.action
        Log.d(TAG, "收到广播: $action")
        
        when (action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                handlePhoneStateChanged(context, intent)
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                handleOutgoingCall(context, intent)
            }
            "android.provider.Telephony.SECRET_CODE" -> {
                handleSecretCode(context, intent)
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context, intent)
            }
        }
    }
    
    /**
     * 处理电话状态变化
     */
    private fun handlePhoneStateChanged(context: Context, intent: Intent) {
        try {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
            
            Log.d(TAG, "电话状态变化: $state, 号码: $phoneNumber")
            
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // 来电响铃
                    if (lastState != TelephonyManager.CALL_STATE_RINGING || lastPhoneNumber != phoneNumber) {
                        Log.d(TAG, "检测到来电: $phoneNumber")
                        lastPhoneNumber = phoneNumber
                        
                        // 检查是否正在屏幕共享
                        if (ScreenShareService.isScreenSharing()) {
                            Log.d(TAG, "正在屏幕共享中，暂停共享")
                            pauseScreenSharing(context)
                        }
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // 接听电话
                    if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                        Log.d(TAG, "接听来电: $phoneNumber")
                        
                        // 如果正在屏幕共享，暂停共享
                        if (ScreenShareService.isScreenSharing()) {
                            Log.d(TAG, "接听电话，暂停屏幕共享")
                            pauseScreenSharing(context)
                        }
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // 挂断电话
                    if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                        Log.d(TAG, "电话结束")
                        
                        // 延迟恢复屏幕共享
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!ScreenShareService.isScreenSharing()) {
                                Log.d(TAG, "电话结束，恢复屏幕共享")
                                resumeScreenSharing(context)
                            }
                        }, 2000) // 延迟2秒恢复
                    }
                }
            }
            
            lastState = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> TelephonyManager.CALL_STATE_IDLE
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理电话状态变化时出错", e)
        }
    }
    
    /**
     * 处理拨出电话
     */
    private fun handleOutgoingCall(context: Context, intent: Intent) {
        try {
            val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
            Log.d(TAG, "检测到拨出电话: $phoneNumber")
            
            // 如果正在屏幕共享，暂停共享
            if (ScreenShareService.isScreenSharing()) {
                Log.d(TAG, "拨出电话，暂停屏幕共享")
                pauseScreenSharing(context)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理拨出电话时出错", e)
        }
    }
    
    /**
     * 处理秘密代码
     */
    private fun handleSecretCode(context: Context, intent: Intent) {
        try {
            val host = intent.data?.host
            Log.d(TAG, "收到秘密代码: $host")
            
            if (host == "12345") {
                Log.d(TAG, "触发秘密代码12345，显示应用图标")
                
                // 显示应用图标
                val success = AppIconUtils.showAppIcon(context, autoLaunch = true)
                
                if (success) {
                    Toast.makeText(context, "应用图标已显示", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "应用图标显示失败", Toast.LENGTH_SHORT).show()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理秘密代码时出错", e)
        }
    }
    
    /**
     * 处理开机完成
     */
    private fun handleBootCompleted(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "系统启动完成，启动后台服务")
            
            // 启动后台服务
            BackgroundService.startService(context)
            
            // 显示通知
            AppIconUtils.showNotification(
                context,
                "ScreenLink 已启动",
                "应用已在后台运行，拨号盘输入*#12345#可显示应用图标"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "处理开机完成时出错", e)
        }
    }
    
    /**
     * 暂停屏幕共享
     */
    private fun pauseScreenSharing(context: Context) {
        try {
            // 发送暂停屏幕共享的广播
            val intent = Intent("ACTION_PAUSE_SCREEN_SHARING")
            context.sendBroadcast(intent)
            
            // 显示通知
            AppIconUtils.showNotification(
                context,
                "屏幕共享已暂停",
                "检测到来电，屏幕共享已自动暂停"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "暂停屏幕共享时出错", e)
        }
    }
    
    /**
     * 恢复屏幕共享
     */
    private fun resumeScreenSharing(context: Context) {
        try {
            // 发送恢复屏幕共享的广播
            val intent = Intent("ACTION_RESUME_SCREEN_SHARING")
            context.sendBroadcast(intent)
            
            // 显示通知
            AppIconUtils.showNotification(
                context,
                "屏幕共享已恢复",
                "电话结束，屏幕共享已自动恢复"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "恢复屏幕共享时出错", e)
        }
    }
} 