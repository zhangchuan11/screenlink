package com.screenlink.newapp

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SimpleMainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SimpleMainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var remoteDisplayButton: Button
    private var mediaProjectionResultCode: Int = -1
    private var mediaProjectionData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main)

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        remoteDisplayButton = findViewById(R.id.remoteDisplayButton)

        startButton.setOnClickListener {
            requestScreenCapturePermission()
        }

        stopButton.setOnClickListener {
            stopScreenCapture()
        }
        
        remoteDisplayButton.setOnClickListener {
            startRemoteDisplay()
        }
    }

    private fun requestScreenCapturePermission() {
        Log.d(TAG, "请求屏幕录制权限")
        
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "屏幕录制权限获取成功")
                mediaProjectionResultCode = resultCode
                mediaProjectionData = data
                startScreenCapture()
            } else {
                Log.e(TAG, "屏幕录制权限被拒绝")
                Toast.makeText(this, "需要屏幕录制权限才能开始投屏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startScreenCapture() {
        Log.d(TAG, "启动屏幕捕获")
        
        val intent = Intent(this, SimpleScreenShareService::class.java).apply {
            action = "START_SCREEN_CAPTURE"
            putExtra("resultCode", mediaProjectionResultCode)
            putExtra("data", mediaProjectionData)
        }
        
        startService(intent)
        Toast.makeText(this, "屏幕共享已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenCapture() {
        Log.d(TAG, "停止屏幕捕获")
        
        val intent = Intent(this, SimpleScreenShareService::class.java).apply {
            action = "STOP_SCREEN_CAPTURE"
        }
        
        startService(intent)
        Toast.makeText(this, "屏幕共享已停止", Toast.LENGTH_SHORT).show()
    }
    
    private fun startRemoteDisplay() {
        Log.d(TAG, "启动远程显示")
        
        val intent = Intent(this, RemoteDisplayActivity::class.java)
        startActivity(intent)
    }
} 