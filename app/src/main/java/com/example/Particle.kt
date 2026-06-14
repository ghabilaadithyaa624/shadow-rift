package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class Particle : Poolable {
    var x: Float = 0f
    var y: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var radius: Float = 6f
    var color: Int = Color.CYAN
    var alpha: Int = 255
    var lifeTime: Float = 0.5f // Lives for 500ms max
    var currentLife: Float = 0f
    var isActive: Boolean = false

    override fun reset() {
        x = 0f
        y = 0f
        vx = 0f
        vy = 0f
        radius = 6f
        color = Color.CYAN
        alpha = 255
        lifeTime = 0.5f
        currentLife = 0f
        isActive = false
    }

    fun configure(spawnX: Float, spawnY: Float, speedX: Float, speedY: Float, size: Float, col: Int, duration: Float) {
        x = spawnX
        y = spawnY
        vx = speedX
        vy = speedY
        radius = size
        color = col
        lifeTime = duration
        currentLife = duration
        alpha = 255
        isActive = true
    }

    fun update(dtSec: Float) {
        if (!isActive) return
        x += vx * dtSec
        y += vy * dtSec
        
        // Slow down slightly (friction)
        vx *= 0.95f
        vy *= 0.95f

        currentLife -= dtSec
        if (currentLife <= 0f) {
            isActive = false
        } else {
            // Fade out
            alpha = ((currentLife / lifeTime) * 255f).toInt().coerceIn(0, 255)
        }
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (!isActive) return
        paint.color = color
        paint.alpha = alpha
        canvas.drawCircle(x, y, radius, paint)
        paint.alpha = 255
    }
}
