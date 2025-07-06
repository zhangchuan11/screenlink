package com.screenlink.newapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.*
import java.util.concurrent.Executors
import org.json.JSONObject

class RemoteDisplayActivity : AppCompatActivity(), SignalingClient.SignalingMessageListener {
    companion object {
        private const val TAG = "RemoteDisplay"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var remoteVideoTrack: VideoTrack? = null
    private val executor = Executors.newSingleThreadExecutor()
    
    private lateinit var signalingClient: SignalingClient
    private var selectedSenderId: Int? = null
    private var availableSenders = listOf<SignalingClient.SenderInfo>()
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_display)
        
        initViews()
        initWebRTC()
        initSignalingClient()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        
        connectButton.setOnClickListener {
            if (selectedSenderId != null) {
                connectToSender(selectedSenderId!!)
            } else {
                Toast.makeText(this, "请先选择一个发送端", Toast.LENGTH_SHORT).show()
            }
        }
        
        disconnectButton.setOnClickListener {
            disconnectFromSender()
        }
        
        updateStatus("准备连接...")
    }

    private fun initWebRTC() {
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

    private fun initSignalingClient() {
        signalingClient = SignalingClient()
        signalingClient.connect(this)
    }

    override fun onConnected() {
        runOnUiThread {
            updateStatus("已连接到信令服务器")
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateStatus("与信令服务器断开连接")
        }
    }

    override fun onSenderList(senders: List<SignalingClient.SenderInfo>) {
        availableSenders = senders
        runOnUiThread {
            if (senders.isEmpty()) {
                updateStatus("没有可用的发送端")
            } else {
                val senderNames = senders.joinToString(", ") { it.name }
                updateStatus("可用发送端: $senderNames")
                
                // 自动选择第一个可用的发送端
                selectedSenderId = senders.firstOrNull { it.available }?.id
                if (selectedSenderId != null) {
                    updateStatus("已选择发送端: ${senders.find { it.id == selectedSenderId }?.name}")
                }
            }
        }
    }

    override fun onOffer(offer: String, senderId: Int) {
        Log.d(TAG, "收到Offer: $offer")
        executor.execute {
            try {
                // 设置远程描述
                val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offer)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "远程描述设置成功")
                        createAnswer(senderId)
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "创建描述失败: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "设置远程描述失败: $error")
                    }
                }, sessionDescription)
            } catch (e: Exception) {
                Log.e(TAG, "处理Offer失败: ${e.message}")
            }
        }
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

    override fun onError(message: String) {
        Log.e(TAG, "信令错误: $message")
        runOnUiThread {
            updateStatus("错误: $message")
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToSender(senderId: Int) {
        executor.execute {
            try {
                updateStatus("正在连接到发送端...")
                
                // 创建PeerConnection
                createPeerConnection()
                
                // 发送连接请求给发送端
                sendConnectRequest(senderId)
                
                runOnUiThread {
                    updateStatus("等待发送端响应...")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "连接发送端失败: ${e.message}")
                runOnUiThread {
                    updateStatus("连接发送端失败: ${e.message}")
                }
            }
        }
    }

    private fun sendConnectRequest(senderId: Int) {
        Log.d(TAG, "发送连接请求给发送端: $senderId")
        signalingClient.sendConnectRequest(senderId)
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
                    runOnUiThread {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                updateStatus("连接已建立")
                                isConnected = true
                                connectButton.isEnabled = false
                                disconnectButton.isEnabled = true
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                updateStatus("连接已断开")
                                isConnected = false
                                connectButton.isEnabled = true
                                disconnectButton.isEnabled = false
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                updateStatus("连接失败")
                                isConnected = false
                                connectButton.isEnabled = true
                                disconnectButton.isEnabled = false
                            }
                            else -> {
                                updateStatus("连接状态: $state")
                            }
                        }
                    }
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "生成ICE候选: ${candidate.sdp}")
                    // 发送ICE候选给发送端
                    selectedSenderId?.let { senderId ->
                        signalingClient.sendIceCandidate(candidate.sdp, senderId)
                    }
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                    Log.d(TAG, "移除ICE候选: ${candidates.size}个")
                }
                
                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "添加流: ${stream.id}")
                    if (stream.videoTracks.isNotEmpty()) {
                        remoteVideoTrack = stream.videoTracks[0]
                        runOnUiThread {
                            setupRemoteVideo()
                        }
                    }
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

    private fun createAnswer(senderId: Int) {
        Log.d(TAG, "创建Answer")
        
        peerConnection!!.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer创建成功")
                
                peerConnection!!.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "本地描述设置成功")
                        // 发送Answer给发送端
                        signalingClient.sendAnswer(sdp.description, senderId)
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Answer创建失败: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "本地描述设置失败: $error")
                    }
                }, sdp)
            }
            
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Answer创建失败: $error")
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Answer设置失败: $error")
            }
        }, MediaConstraints())
    }

    private fun setupRemoteVideo() {
        val remoteVideoView = findViewById<SurfaceViewRenderer>(R.id.remoteVideoView)
        
        // 初始化SurfaceViewRenderer
        remoteVideoView.init(eglBase!!.eglBaseContext, null)
        remoteVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setMirror(false)
        
        // 添加远程视频轨道
        remoteVideoTrack?.addSink(remoteVideoView)
        
        Log.d(TAG, "远程视频显示设置完成")
        runOnUiThread {
            updateStatus("正在显示远程屏幕")
        }
    }

    private fun disconnectFromSender() {
        executor.execute {
            try {
                Log.d(TAG, "断开连接")
                
                remoteVideoTrack?.dispose()
                peerConnection?.close()
                peerConnectionFactory?.dispose()
                
                runOnUiThread {
                    updateStatus("已断开连接")
                    isConnected = false
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "断开连接失败: ${e.message}")
            }
        }
    }

    private fun updateStatus(message: String) {
        Log.d(TAG, message)
        statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromSender()
        signalingClient.disconnect()
        executor.shutdown()
    }

    override fun onConnectRequest(sourceClientId: Int) {
        // 作为接收端，不需要处理连接请求
        Log.d(TAG, "接收端收到onConnectRequest回调，忽略")
    }
} 