package com.chin.stockanalysis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.agent.Agent
import com.chin.stockanalysis.databinding.ItemAgentBinding

class AgentListAdapter(
    private val onAgentClick: (Agent) -> Unit,
    private val onAgentMoreClick: (Agent, View) -> Unit
) : ListAdapter<Agent, AgentListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAgentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onAgentClick, onAgentMoreClick)
    }

    class VH(private val b: ItemAgentBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(agent: Agent, onClick: (Agent) -> Unit, onMoreClick: (Agent, View) -> Unit) {
            b.tvAgentIcon.text = agent.icon
            b.tvAgentName.text = agent.name
            b.tvAgentDesc.text = agent.description.ifBlank { "暂无描述" }
            b.tvUsageCount.text = "${agent.usageCount}次"
            b.root.setOnClickListener { onClick(agent) }
            b.btnAgentMore.setOnClickListener { onMoreClick(agent, it) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Agent>() {
            override fun areItemsTheSame(o: Agent, n: Agent) = o.id == n.id
            override fun areContentsTheSame(o: Agent, n: Agent) =
                o.name == n.name && o.description == n.description &&
                    o.usageCount == n.usageCount && o.icon == n.icon && o.enabled == n.enabled
        }
    }
}