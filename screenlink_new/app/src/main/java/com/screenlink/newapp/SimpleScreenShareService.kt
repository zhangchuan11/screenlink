package com.screenlink.newapp

import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

class SimpleScreenShareService : Service() {
    companion object {
        private const val TAG = "SimpleScreenShare"
    }

    private var mediaProjection: MediaProjection? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var screenCapturer: VideoCapturer? = null
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val executor = Executors.newSingleThreadExecutor()
    
    private lateinit var signalingClient: SignalingClient
    private var isScreenCapturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initSignalingClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SCREEN_CAPTURE" -> {
                val resultCode = intent.getIntExtra("resultCode", -1)
                val data = intent.getParcelableExtra<Intent>("data")
                startScreenCapture(resultCode, data)
            }
            "STOP_SCREEN_CAPTURE" -> {
                stopScreenCapture()
            }
        }
        return START_STICKY
    }

    private fun initSignalingClient() {
        signalingClient = SignalingClient()
        signalingClient.connect(object : SignalingClient.SignalingMessageListener {
            override fun onConnected() {
                Log.d(TAG, "信令服务器连接成功")
                // 注册为发送端
                registerAsSender()
            }

            override fun onDisconnected() {
                Log.d(TAG, "信令服务器连接断开")
            }

            override fun onSenderList(senders: List<SignalingClient.SenderInfo>) {
                // 作为发送端，不需要处理发送端列表
            }

            override fun onOffer(offer: String, senderId: Int) {
                // 作为发送端，不应该收到Offer
                Log.w(TAG, "发送端收到Offer，这是异常情况")
            }

            override fun onAnswer(answer: String) {
                Log.d(TAG, "收到Answer: $answer")
                executor.execute {
                    try {
                        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, answer)
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "Answer设置成功")
                            }
                            override fun onCreateFailure(error: String) {
                                Log.e(TAG, "创建描述失败: $error")
                            }
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "设置Answer失败: $error")
                            }
                        }, sessionDescription)
                    } catch (e: Exception) {
                        Log.e(TAG, "处理Answer失败: ${e.message}")
                    }
                }
            }

            override fun onIceCandidate(candidate: String) {
                Log.d(TAG, "收到ICE候选: $candidate")
                executor.execute {
                    try {
                        val iceCandidate = IceCandidate("", 0, candidate)
                        peerConnection?.addIceCandidate(iceCandidate)
                    } catch (e: Exception) {
                        Log.e(TAG, "添加ICE候选失败: ${e.message}")
                    }
                }
            }

            override fun onConnectRequest(sourceClientId: Int) {
                Log.d(TAG, "收到连接请求，来自客户端: $sourceClientId")
                // 为请求连接的接收端创建Offer
                createOfferForReceiver(sourceClientId)
            }

            override fun onError(message: String) {
                Log.e(TAG, "信令错误: $message")
            }
        })
    }

    private fun registerAsSender() {
        Log.d(TAG, "注册为发送端")
        signalingClient.registerAsSender("Android发送端")
    }

    private fun createOfferForReceiver(receiverId: Int) {
        Log.d(TAG, "为接收端${receiverId}创建Offer")
        
        if (!isScreenCapturing) {
            Log.e(TAG, "屏幕捕获未启动，无法创建Offer")
            return
        }
        
        executor.execute {
            try {
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        Log.d(TAG, "Offer创建成功")
                        
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "本地描述设置成功")
                                // 发送Offer给接收端
                                signalingClient.sendOffer(sdp.description, receiverId)
                            }
                            override fun onCreateFailure(error: String) {
                                Log.e(TAG, "Offer创建失败: $error")
                            }
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "本地描述设置失败: $error")
                            }
                        }, sdp)
                    }
                    
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Offer创建失败: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Offer设置失败: $error")
                    }
                }, MediaConstraints())
            } catch (e: Exception) {
                Log.e(TAG, "创建Offer失败: ${e.message}")
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent?) {
        Log.d(TAG, "开始屏幕捕获")
        
        try {
            // 初始化WebRTC
            initializeWebRTC()
            
            // 创建屏幕捕获器
            createScreenCapturer(resultCode, data)
            
            // 创建PeerConnection
            createPeerConnection()
            
            // 添加视频轨道
            addVideoTrack()
            
            isScreenCapturing = true
            Log.d(TAG, "屏幕捕获启动成功")
            
        } catch (e: Exception) {
            Log.e(TAG, "屏幕捕获启动失败: ${e.message}")
        }
    }

    private fun initializeWebRTC() {
        Log.d(TAG, "初始化WebRTC")
        
        // 初始化PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        
        // 创建EglBase
        eglBase = EglBase.create()
        
        // 创建PeerConnectionFactory
        val encoderFactory = org.webrtc.DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = org.webrtc.DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            
        Log.d(TAG, "WebRTC初始化完成")
    }

    private fun createScreenCapturer(resultCode: Int, data: Intent?) {
        Log.d(TAG, "创建屏幕捕获器")
        
        if (resultCode == -1 || data == null) {
            throw IllegalStateException("MediaProjection权限无效")
        }
        
        // 创建MediaProjection
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
        
        // 创建屏幕捕获器
        screenCapturer = createScreenCapturer(mediaProjection!!, data)
        
        Log.d(TAG, "屏幕捕获器创建成功")
    }

    private fun createScreenCapturer(mediaProjection: MediaProjection, data: Intent): VideoCapturer {
        return ScreenCapturerAndroid(
            data,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "屏幕捕获已停止")
                }
            }
        )
    }

    private fun createPeerConnection() {
        Log.d(TAG, "创建PeerConnection")
        
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        
        peerConnection = peerConnectionFactory!!.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "信令状态: $state")
                }
                
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE连接状态: $state")
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "生成ICE候选: ${candidate.sdp}")
                    // 发送ICE候选给接收端
                    // 这里需要知道接收端的ID，我们可以通过信令服务器转发
                    // 暂时先记录日志
                    Log.d(TAG, "需要发送ICE候选给接收端")
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                    Log.d(TAG, "移除ICE候选: ${candidates.size}个")
                }
                
                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "添加流: ${stream.id}")
                }
                
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
                override fun onTrack(transceiver: RtpTransceiver) {}
                override fun onRemoveTrack(receiver: RtpReceiver) {}
                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "连接状态: $newState")
                }
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            }
        )
        
        Log.d(TAG, "PeerConnection创建成功")
    }

    private fun addVideoTrack() {
        Log.d(TAG, "添加视频轨道")
        
        // 创建视频源
        val videoSource = peerConnectionFactory!!.createVideoSource(false)
        
        // 创建视频轨道
        videoTrack = peerConnectionFactory!!.createVideoTrack("screen_video", videoSource)
        
        // 启动屏幕捕获
        screenCapturer!!.initialize(
            SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext),
            this,
            videoSource.capturerObserver
        )
        screenCapturer!!.startCapture(1280, 720, 30)
        
        // 添加轨道到PeerConnection
        peerConnection!!.addTrack(videoTrack)
        
        Log.d(TAG, "视频轨道添加成功")
    }

    private fun stopScreenCapture() {
        Log.d(TAG, "停止屏幕捕获")
        
        try {
            isScreenCapturing = false
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            videoTrack?.dispose()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
            mediaProjection?.stop()
            eglBase?.release()
            
            Log.d(TAG, "屏幕捕获已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕捕获失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenCapture()
        signalingClient.disconnect()
        executor.shutdown()
    }
} 