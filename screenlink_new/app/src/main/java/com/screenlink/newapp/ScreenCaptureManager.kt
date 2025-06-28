package com.screenlink.newapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import org.webrtc.*

/**
 * 屏幕捕获管理器，负责屏幕录制权限和视频捕获
 */
class ScreenCaptureManager(private val context: Context) {
    
    // 屏幕捕获相关变量
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var isScreenCaptureActive = false
    private var trackAdded = false
    
    // MediaProjection相关
    private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    
    // 回调接口
    interface ScreenCaptureListener {
        fun onScreenCaptureStarted()
        fun onScreenCaptureStopped()
        fun onScreenCaptureError(error: String)
    }
    
    private var listener: ScreenCaptureListener? = null
    
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MEDIA_PROJECTION_REQUEST_CODE = 1001
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: ScreenCaptureListener) {
        this.listener = listener
    }
    
    /**
     * 请求屏幕录制权限
     */
    fun requestScreenCapturePermission(activity: Activity) {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQUEST_CODE
        )
    }
    
    /**
     * 处理权限结果
     */
    fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                this.resultCode = resultCode
                this.resultData = data
                return true
            } else {
                listener?.onScreenCaptureError("屏幕录制权限被拒绝")
                return false
            }
        }
        return false
    }
    
    /**
     * 开始屏幕捕获
     */
    fun startScreenCapture(factory: PeerConnectionFactory?, eglBase: EglBase?) {
        Log.d(TAG, "开始屏幕捕获")
        
        // 通过ScreenCaptureService来创建MediaProjection
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            ScreenCaptureService.connectToSignalingServer(
                context, 
                "192.168.1.3:6060", 
                "发送端", 
                resultData!!, 
                resultCode
            )
        } else {
            Log.e(TAG, "MediaProjection数据无效，无法开始屏幕捕获")
        }
    }
    
    /**
     * 停止屏幕捕获
     */
    fun stopScreenCapture() {
        try {
            if (isScreenCaptureActive) {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                isScreenCaptureActive = false
                Log.d(TAG, "屏幕捕获已停止")
            }
            
            if (videoTrack != null) {
                videoTrack?.dispose()
                videoTrack = null
            }
            
            if (videoSource != null) {
                videoSource?.dispose()
                videoSource = null
            }
            
            if (mediaProjection != null) {
                mediaProjection?.stop()
                mediaProjection = null
            }
            
            trackAdded = false
            ScreenCaptureService.setScreenSharingActive(false)
            listener?.onScreenCaptureStopped()
            
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕捕获失败", e)
        }
    }
    
    /**
     * 创建屏幕捕获器
     */
    private fun createScreenCapturer(): VideoCapturer? {
        try {
            if (resultData == null || resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "MediaProjection数据无效")
                return null
            }
            
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
            
            if (mediaProjection == null) {
                Log.e(TAG, "获取MediaProjection失败")
                return null
            }
            
            return ScreenCapturerAndroid(resultData!!, object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection已停止")
                    ScreenCaptureService.setScreenSharingActive(false)
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕捕获器失败", e)
            return null
        }
    }
    
    /**
     * 获取视频轨道
     */
    fun getVideoTrack(): VideoTrack? = videoTrack
    
    /**
     * 检查是否正在捕获
     */
    fun isScreenCaptureActive(): Boolean = isScreenCaptureActive
    
    /**
     * 检查轨道是否已添加
     */
    fun isTrackAdded(): Boolean = trackAdded
    
    /**
     * 设置轨道已添加状态
     */
    fun setTrackAdded(added: Boolean) {
        trackAdded = added
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopScreenCapture()
    }
} 