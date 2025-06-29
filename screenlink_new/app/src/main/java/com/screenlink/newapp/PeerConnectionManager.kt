package com.screenlink.newapp

import android.util.Log
import org.webrtc.*

/**
 * PeerConnection管理器，负责WebRTC连接和SDP处理
 */
class PeerConnectionManager {
    
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var remoteVideoTrack: VideoTrack? = null
    
    // 回调接口
    interface PeerConnectionListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onOfferCreated(sdp: SessionDescription)
        fun onAnswerCreated(sdp: SessionDescription)
        fun onConnectionStateChanged(state: PeerConnection.IceConnectionState)
    }
    
    private var listener: PeerConnectionListener? = null
    
    companion object {
        private const val TAG = "PeerConnectionManager"
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: PeerConnectionListener) {
        this.listener = listener
    }
    
    /**
     * 设置工厂
     */
    fun setFactory(factory: PeerConnectionFactory?) {
        this.factory = factory
    }
    
    /**
     * 创建PeerConnection
     */
    fun createPeerConnection(): PeerConnection? {
        try {
            // 清理旧的连接
            close()
            
            // 配置STUN服务器 - 简化配置
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                // 设置ICE传输策略 - 优先本地网络
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                // 设置RTCP复用策略
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                // 设置SDP语义
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            
            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { 
                        Log.d(TAG, "生成ICE候选: ${candidate.sdp}")
                        listener?.onIceCandidate(it) 
                    }
                }
                
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "信令状态变化: $state")
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE连接状态变化: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.NEW -> Log.d(TAG, "ICE连接: 新建状态")
                        PeerConnection.IceConnectionState.CHECKING -> {
                            Log.d(TAG, "ICE连接: 检查中...")
                            // 设置超时检查
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.CHECKING) {
                                    Log.w(TAG, "ICE连接检查超时，尝试重启连接")
                                    // 可以在这里添加重连逻辑
                                }
                            }, 10000) // 10秒超时
                        }
                        PeerConnection.IceConnectionState.CONNECTED -> Log.d(TAG, "ICE连接: 已连接 ✅")
                        PeerConnection.IceConnectionState.COMPLETED -> Log.d(TAG, "ICE连接: 已完成 ✅")
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE连接: 失败 ❌")
                            // 连接失败时尝试重启
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                Log.d(TAG, "尝试重启ICE连接")
                                peerConnection?.restartIce()
                            }, 2000)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> Log.w(TAG, "ICE连接: 断开连接")
                        PeerConnection.IceConnectionState.CLOSED -> Log.d(TAG, "ICE连接: 已关闭")
                        else -> Log.d(TAG, "ICE连接: 未知状态 $state")
                    }
                    state?.let { listener?.onConnectionStateChanged(it) }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE连接接收状态变化: $receiving")
                }
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE收集状态变化: $state")
                }
                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "添加媒体流: ${stream?.id}")
                }
                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "移除媒体流: ${stream?.id}")
                }
                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "数据通道: ${channel?.label()}")
                }
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "需要重新协商")
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    Log.d(TAG, "onAddTrack被调用，轨道类型: ${track?.kind()}, 轨道ID: ${track?.id()}")
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        Log.d(TAG, "远端视频轨道已添加: ${track.id()}")
                        Log.d(TAG, "远端视频轨道状态: enabled=${track.enabled()}")
                    } else {
                        Log.d(TAG, "收到非视频轨道: ${track?.kind()}")
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "ICE候选已移除: ${candidates?.size} 个")
                }
            })
            
            Log.d(TAG, "PeerConnection创建成功，配置了 ${iceServers.size} 个ICE服务器")
            return peerConnection
            
        } catch (e: Exception) {
            Log.e(TAG, "创建PeerConnection失败", e)
            return null
        }
    }
    
    /**
     * 添加视频轨道
     */
    fun addVideoTrack(videoTrack: VideoTrack?): Boolean {
        try {
            if (videoTrack != null && peerConnection != null) {
                peerConnection?.addTrack(videoTrack)
                Log.d(TAG, "视频轨道已添加")
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "添加视频轨道失败", e)
            return false
        }
    }
    
    /**
     * 创建Offer，支持回调
     */
    fun createOffer(listener: PeerConnectionListener) {
        this.listener = listener
        createOffer()
    }
    
    /**
     * 创建Offer
     */
    fun createOffer() {
        try {
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d(TAG, "Offer创建成功")
                    sdp?.let { listener?.onOfferCreated(it) }
                    
                    // 设置本地描述
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "本地描述设置成功")
                        }
                        override fun onCreateFailure(p0: String?) {
                            Log.e(TAG, "创建本地描述失败: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e(TAG, "设置本地描述失败: $p0")
                        }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "创建Offer失败: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "设置Offer失败: $p0")
                }
            }, MediaConstraints())
            
        } catch (e: Exception) {
            Log.e(TAG, "创建Offer失败", e)
        }
    }
    
    /**
     * 创建Answer
     */
    fun createAnswer() {
        try {
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d(TAG, "Answer创建成功")
                    sdp?.let { listener?.onAnswerCreated(it) }
                    
                    // 设置本地描述
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "本地描述设置成功")
                        }
                        override fun onCreateFailure(p0: String?) {
                            Log.e(TAG, "创建本地描述失败: $p0")
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e(TAG, "设置本地描述失败: $p0")
                        }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "创建Answer失败: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "设置Answer失败: $p0")
                }
            }, MediaConstraints())
            
        } catch (e: Exception) {
            Log.e(TAG, "创建Answer失败", e)
        }
    }
    
    /**
     * 设置远程描述
     */
    fun setRemoteDescription(sdp: String, type: SessionDescription.Type) {
        try {
            // 如果PeerConnection不存在，先创建它
            if (peerConnection == null) {
                Log.d(TAG, "PeerConnection不存在，先创建PeerConnection")
                createPeerConnection()
            }
            
            val sessionDescription = SessionDescription(type, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "远程描述设置成功")
                    if (type == SessionDescription.Type.OFFER) {
                        createAnswer()
                    }
                }
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "创建描述失败: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "设置描述失败: $p0")
                }
            }, sessionDescription)
            
        } catch (e: Exception) {
            Log.e(TAG, "设置远程描述失败", e)
        }
    }
    
    /**
     * 添加ICE候选
     */
    fun addIceCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String) {
        try {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
            Log.d(TAG, "ICE候选已添加")
        } catch (e: Exception) {
            Log.e(TAG, "添加ICE候选失败", e)
        }
    }
    
    /**
     * 获取PeerConnection
     */
    fun getPeerConnection(): PeerConnection? = peerConnection
    
    /**
     * 获取远端视频轨道
     */
    fun getRemoteVideoTrack(): VideoTrack? = remoteVideoTrack
    
    /**
     * 连接到目标客户端
     */
    fun connectToTarget(targetClientId: Int) {
        try {
            Log.d(TAG, "开始连接到目标客户端: $targetClientId")
            
            // 创建PeerConnection
            createPeerConnection()
            
            // 创建Offer
            createOffer()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接到目标客户端失败", e)
        }
    }
    
    /**
     * 关闭连接
     */
    fun close() {
        try {
            // 清理远端视频轨道
            remoteVideoTrack = null
            
            peerConnection?.close()
            peerConnection = null
            Log.d(TAG, "PeerConnection已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭PeerConnection失败", e)
        }
    }
} 