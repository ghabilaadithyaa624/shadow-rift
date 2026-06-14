package com.example

import android.graphics.Canvas
import android.graphics.Paint

class VirtualJoystick(
    val centerX: Float,
    val centerY: Float,
    val outerRadius: Float,
    val innerRadius: Float,
    accentColor: Int,
    backColor: Int
) {
    // Current positions of the inner stick knob
    var currentX: Float = centerX
    var currentY: Float = centerY

    // Vector values between 0.0 to 1.0 representing stick output
    var deltaX: Float = 0f
    var deltaY: Float = 0f

    // True if user is currently interacting with this joystick
    var isPressed: Boolean = false

    // Multi-touch tracking
    var pointerId: Int = -1

    // Paints
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 60
        style = Paint.Style.FILL
    }

    /**
     * Resets the stick position and output values to center.
     */
    fun reset() {
        currentX = centerX
        currentY = centerY
        deltaX = 0f
        deltaY = 0f
        isPressed = false
        pointerId = -1
    }

    /**
     * Updates the stick knob based on touch position, clipping it to the boundaries of the outer ring.
     */
    fun update(touchX: Float, touchY: Float) {
        val dx = touchX - centerX
        val dy = touchY - centerY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance < outerRadius) {
            currentX = touchX
            currentY = touchY
            deltaX = dx / outerRadius
            deltaY = dy / outerRadius
        } else {
            // Cap to maximum outer boundaries
            val angle = Math.atan2(dy.toDouble(), dx.toDouble())
            currentX = centerX + (Math.cos(angle) * outerRadius).toFloat()
            currentY = centerY + (Math.sin(angle) * outerRadius).toFloat()
            deltaX = (currentX - centerX) / outerRadius
            deltaY = (currentY - centerY) / outerRadius
        }
    }

    /**
     * Renders outer ring and inner knob.
     */
    fun draw(canvas: Canvas) {
        // Draw Outer Circle
        canvas.drawCircle(centerX, centerY, outerRadius, outerPaint)

        // Draw Inner Dial Glow
        if (isPressed) {
            canvas.drawCircle(currentX, currentY, innerRadius * 1.3f, innerGlowPaint)
        }

        // Draw Inner Knob Circle
        canvas.drawCircle(currentX, currentY, innerRadius, innerPaint)
    }
}
