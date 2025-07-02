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
import com.screenlink.newapp.WebRTCManager.SenderInfo

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
        senders = newSenders
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
            val status = if (sender.available) "可用" else "不可用"
            textView.text = "${sender.name} (ID: ${sender.id}) - $status"
            
            itemView.setOnClickListener {
                android.util.Log.d("ClientAdapter", "发送端项被点击: ${sender.name} (ID: ${sender.id})")
                onSenderClickListener?.invoke(sender)
            }
            
            // 设置背景色以显示可点击状态
            itemView.setBackgroundResource(android.R.drawable.list_selector_background)
        }
    }
} 