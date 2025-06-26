package com.chuan.android_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 启动时自动启动服务的广播接收器
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            Log.d(TAG, "收到广播: $action")
            
            if (action == Intent.ACTION_BOOT_COMPLETED || 
                action == "android.intent.action.QUICKBOOT_POWERON" || 
                action == "com.htc.intent.action.QUICKBOOT_POWERON") {
                
                Log.d(TAG, "设备已启动，启动服务")
                
                // 延迟几秒钟启动服务，确保系统已经完全启动
                Thread {
                    try {
                        Thread.sleep(5000)
                        BackgroundService.startService(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "延迟启动服务时出错", e)
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理启动广播时出错", e)
        }
    }
} 