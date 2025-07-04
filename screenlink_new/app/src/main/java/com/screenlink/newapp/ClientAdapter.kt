/*
 * 功能说明：
 * RecyclerView 适配器，用于展示在线发送端列表，支持点击事件回调，刷新数据等。配合主界面实现发送端选择。
 */
package com.screenlink.newapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screenlink.newapp.ScreenShareService.SenderInfo
import com.screenlink.newapp.ScreenShareService.ClientInfo

class ClientAdapter(
    private var senders: List<SenderInfo>,
    private var onSenderClickListener: ((SenderInfo) -> Unit)? = null
) : RecyclerView.Adapter<ClientAdapter.SenderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return SenderViewHolder(view)
    }

    override fun onBindViewHolder(holder: SenderViewHolder, position: Int) {
        holder.bind(senders[position])
    }

    override fun getItemCount(): Int = senders.size

    fun updateSenders(newSenders: List<SenderInfo>) {
        android.util.Log.d("ClientAdapter", "updateSenders 被调用，新发送端数量: ${newSenders.size}")
        for (sender in newSenders) {
            android.util.Log.d("ClientAdapter", "发送端: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}")
        }
        senders = newSenders
        android.util.Log.d("ClientAdapter", "调用 notifyDataSetChanged")
        notifyDataSetChanged()
        android.util.Log.d("ClientAdapter", "notifyDataSetChanged 完成")
    }

    fun updateClients(clients: List<ClientInfo>) {
        // 这里假设客户端和发送端共用同一个列表展示（如需分开可自行扩展）
        senders = clients.map { SenderInfo(it.id, it.name, 0L, true) }
        notifyDataSetChanged()
    }

    fun setOnSenderClickListener(listener: (SenderInfo) -> Unit) {
        onSenderClickListener = listener
    }

    fun getSenderAt(position: Int): SenderInfo? {
        return if (position >= 0 && position < senders.size) {
            senders[position]
        } else {
            null
        }
    }

    inner class SenderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)
        
        fun bind(sender: SenderInfo) {
            val status = if (sender.available) "🟢 可用" else "🔴 不可用"
            textView.text = "${sender.name} (ID: ${sender.id}) - $status"
            
            // 根据可用状态设置不同的背景色
            if (sender.available) {
                itemView.setBackgroundColor(android.graphics.Color.parseColor("#E8F5E8")) // 浅绿色背景
                textView.setTextColor(android.graphics.Color.parseColor("#2E7D32")) // 深绿色文字
            } else {
                itemView.setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE")) // 浅红色背景
                textView.setTextColor(android.graphics.Color.parseColor("#C62828")) // 深红色文字
            }
            
            itemView.setOnClickListener {
                android.util.Log.d("ClientAdapter", "发送端项被点击: ${sender.name} (ID: ${sender.id})")
                onSenderClickListener?.invoke(sender)
            }
        }
    }
} 