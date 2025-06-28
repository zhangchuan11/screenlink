package com.screenlink.newapp

import android.content.Context
import android.view.View
import android.widget.*
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup

/**
 * UI管理器，负责界面创建和状态管理
 */
class UIManager(private val context: Context) {
    
    // UI组件
    private lateinit var clientListView: ListView
    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var serverAddressInput: EditText
    private lateinit var modeToggleButton: Button
    private lateinit var iconToggleButton: Button
    private lateinit var iconStatusText: TextView
    private lateinit var mainLayout: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var startDisplayButton: Button
    
    // 客户端列表相关
    private var clientList = mutableListOf<WebRTCManager.ClientInfo>()
    private lateinit var clientAdapter: ClientListAdapter
    
    // 回调接口
    interface UIListener {
        fun onModeToggle()
        fun onConnectionToggle()
        fun onAppIconToggle()
        fun onClientSelected(client: WebRTCManager.ClientInfo)
        fun onStartDisplay()
    }
    
    private var listener: UIListener? = null
    
    companion object {
        private const val DEFAULT_SIGNALING_SERVER = "192.168.1.3:6060"
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: UIListener) {
        this.listener = listener
    }
    
    /**
     * 创建主界面
     */
    fun createMainUI(): View {
        mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // 标题
        val titleText = TextView(context).apply {
            text = "ScreenLink 屏幕共享"
            textSize = 24f
            setPadding(0, 0, 0, 16)
            gravity = android.view.Gravity.CENTER
        }
        mainLayout.addView(titleText)
        
        // 模式切换按钮
        modeToggleButton = Button(context).apply {
            text = "切换到发送端模式"
            setOnClickListener { listener?.onModeToggle() }
            setPadding(0, 8, 0, 8)
        }
        mainLayout.addView(modeToggleButton)
        
        // 应用图标控制
        iconToggleButton = Button(context).apply {
            text = "隐藏应用图标"
            setOnClickListener { listener?.onAppIconToggle() }
            setPadding(0, 8, 0, 8)
        }
        mainLayout.addView(iconToggleButton)
        
        // 图标状态文本
        iconStatusText = TextView(context).apply {
            text = "应用图标状态：显示中"
            setPadding(0, 4, 0, 16)
            gravity = android.view.Gravity.CENTER
        }
        mainLayout.addView(iconStatusText)
        
        // 控制面板
        controlPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        // 服务器地址输入框
        serverAddressInput = EditText(context).apply {
            hint = "服务器地址"
            setText(DEFAULT_SIGNALING_SERVER)
            setPadding(0, 8, 0, 8)
        }
        controlPanel.addView(serverAddressInput)
        
        // 连接按钮
        connectButton = Button(context).apply {
            text = "连接到服务器"
            setOnClickListener { listener?.onConnectionToggle() }
            setPadding(0, 8, 0, 8)
        }
        controlPanel.addView(connectButton)
        
        // 状态文本
        statusTextView = TextView(context).apply {
            text = "准备连接..."
            setPadding(0, 8, 0, 8)
            gravity = android.view.Gravity.CENTER
        }
        controlPanel.addView(statusTextView)
        
        // 启动显示页面按钮
        startDisplayButton = Button(context).apply {
            text = "启动显示页面"
            setOnClickListener { listener?.onStartDisplay() }
            setPadding(0, 8, 0, 8)
            visibility = View.GONE  // 默认隐藏
        }
        controlPanel.addView(startDisplayButton)
        
        // 客户端列表标题
        val clientListTitle = TextView(context).apply {
            text = "在线客户端列表"
            textSize = 18f
            setPadding(0, 16, 0, 8)
            gravity = android.view.Gravity.CENTER
        }
        controlPanel.addView(clientListTitle)
        
        // 客户端列表
        clientListView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                300
            )
            setPadding(0, 8, 0, 8)
        }
        controlPanel.addView(clientListView)
        
        mainLayout.addView(controlPanel)
        
        // 初始化客户端列表
        clientList = ArrayList()
        clientAdapter = ClientListAdapter(context, clientList)
        clientListView.adapter = clientAdapter
        
        // 设置客户端列表点击事件
        clientListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val client = clientList[position]
            listener?.onClientSelected(client)
        }
        
        return mainLayout
    }
    
    /**
     * 初始化接收端UI
     */
    fun initializeReceiverUI(eglBase: org.webrtc.EglBase?) {
        // 现在视频显示在DisplayActivity中，这里不需要初始化
    }
    
    /**
     * 清理接收端UI
     */
    fun cleanupReceiverUI() {
        // 现在视频显示在DisplayActivity中，这里不需要清理
    }
    
    /**
     * 更新模式按钮文本
     */
    fun updateModeButtonText(isReceiverMode: Boolean) {
        modeToggleButton.text = if (isReceiverMode) "切换到发送端模式" else "切换到接收端模式"
    }
    
    /**
     * 更新状态文本
     */
    fun updateStatusText(text: String) {
        statusTextView.text = text
    }
    
    /**
     * 更新连接按钮文本
     */
    fun updateConnectButtonText(connected: Boolean) {
        connectButton.text = if (connected) "断开连接" else "连接到服务器"
    }
    
    /**
     * 更新应用图标状态
     */
    fun updateIconStatus(isHidden: Boolean) {
        iconStatusText.text = "应用图标状态：${if (isHidden) "隐藏中" else "显示中"}"
        iconToggleButton.text = if (isHidden) "显示应用图标" else "隐藏应用图标"
    }
    
    /**
     * 更新客户端列表
     */
    fun updateClientList(clients: List<WebRTCManager.ClientInfo>) {
        clientList.clear()
        clientList.addAll(clients)
        clientAdapter.notifyDataSetChanged()
    }
    
    /**
     * 获取服务器地址
     */
    fun getServerAddress(): String = serverAddressInput.text.toString()
    
    /**
     * 获取客户端列表
     */
    fun getClientList(): ListView = clientListView
    
    /**
     * 显示启动显示页面按钮
     */
    fun showStartDisplayButton(show: Boolean) {
        startDisplayButton.visibility = if (show) View.VISIBLE else View.GONE
    }
}

// 客户端列表适配器
class ClientListAdapter(
    private val context: Context,
    private val clientList: List<WebRTCManager.ClientInfo>
) : BaseAdapter() {
    override fun getCount(): Int = clientList.size
    override fun getItem(position: Int): WebRTCManager.ClientInfo = clientList[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        val client = getItem(position)
        textView.text = "${client.name} (ID: ${client.id})"
        textView.setTextColor(Color.BLUE)
        return view
    }
} 