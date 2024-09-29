package com.mif.mediaattachment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.lang.Math.abs
import java.util.Random

class AudioWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var amplitudes = floatArrayOf()
    private var spikes = mutableListOf<RectF>()
    private var progress = 0f
    private var currentTime = 0L

    init {
        paint.color = Color.CYAN
    }

    fun setWaveform(amplitudes: FloatArray) {
        this.amplitudes = amplitudes
        createSpikes()
        invalidate()
    }

    private fun createSpikes() {
        spikes.clear()
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        val totalSpikes = amplitudes.size
        val spikeWidth = width / totalSpikes - 1 // Subtract 1 for gap

        for (i in 0 until totalSpikes) {
            val left = i * (spikeWidth + 1) // Add 1 pixel gap
            val amplitude = amplitudes[i] * height * 0.35f
            val top = centerY - amplitude
            val bottom = centerY + amplitude
            spikes.add(RectF(left, top, left + spikeWidth, bottom))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val progressWidth = width * progress
        spikes.forEachIndexed { index, spike ->
            val spikeProgress = spike.left / width.toFloat()
            paint.color = if (spikeProgress <= progress) Color.CYAN else Color.LTGRAY
            canvas.drawRoundRect(spike, 2f, 2f, paint)
        }
    }

    fun setProgress(currentTimeMs: Long, durationMs: Long) {
        this.currentTime = currentTimeMs
        this.progress = if (durationMs > 0) currentTimeMs.toFloat() / durationMs.toFloat() else 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createSpikes()
    }
}