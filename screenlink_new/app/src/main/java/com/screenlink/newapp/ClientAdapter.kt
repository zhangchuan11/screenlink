package com.screenlink.newapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screenlink.newapp.WebRTCManager.ClientInfo

class ClientAdapter(private var clients: List<ClientInfo>) : RecyclerView.Adapter<ClientAdapter.ClientViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClientViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return ClientViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClientViewHolder, position: Int) {
        holder.bind(clients[position])
    }

    override fun getItemCount(): Int = clients.size

    fun updateClients(newClients: List<ClientInfo>) {
        clients = newClients
        notifyDataSetChanged()
    }

    fun getClientAt(position: Int): ClientInfo? {
        return if (position >= 0 && position < clients.size) {
            clients[position]
        } else {
            null
        }
    }

    class ClientViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)
        fun bind(client: ClientInfo) {
            textView.text = client.name
        }
    }
} 