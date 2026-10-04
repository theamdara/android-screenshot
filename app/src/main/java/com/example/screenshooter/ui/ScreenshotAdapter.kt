package com.example.screenshooter.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.screenshooter.ScreenshotStore
import com.example.screenshooter.databinding.ItemScreenshotBinding

class ScreenshotAdapter(
    private val onClick: (Uri) -> Unit,
    private val onShare: (Uri) -> Unit,
    private val onDelete: (Uri) -> Unit
) : RecyclerView.Adapter<ScreenshotAdapter.VH>() {

    private val items = mutableListOf<ScreenshotStore.Shot>()

    fun submit(shots: List<ScreenshotStore.Shot>) {
        items.clear()
        items.addAll(shots)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemScreenshotBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemScreenshotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val shot = items[position]
        holder.binding.tvName.text = shot.displayName
        holder.binding.ivThumb.setImageURI(shot.uri)
        holder.binding.ivThumb.setOnClickListener { onClick(shot.uri) }
        holder.binding.btnShare.setOnClickListener { onShare(shot.uri) }
        holder.binding.btnDelete.setOnClickListener { onDelete(shot.uri) }
    }

    override fun getItemCount(): Int = items.size
}
