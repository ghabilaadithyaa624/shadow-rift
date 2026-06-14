package com.example

import android.view.SurfaceHolder

class GameLoop(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread() {

    companion object {
        // Target updates per second (physics tickrate)
        private const val TARGET_UPS = 60.0
        private const val TIME_STEP_NS = (1_000_000_000.0 / TARGET_UPS).toLong()
        private const val MAX_FRAME_SKIPS = 5
    }

    @Volatile
    private var isPlaying = false
    
    // Average UPS tracker for performance analytics if needed
    var averageUPS: Double = 0.0
        private set
    var averageFPS: Double = 0.0
        private set

    fun startLoop() {
        isPlaying = true
        super.start() // Spawns execution thread
    }

    fun stopLoop() {
        isPlaying = false
        try {
            join() // Joins the thread execution back to caller safely
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun run() {
        var lastUpdateTime = System.nanoTime()
        var accumulatedTimeNs = 0L

        // Statistics monitors
        var lastStatTime = System.currentTimeMillis()
        var updateCount = 0
        var frameCount = 0

        while (isPlaying) {
            val currentTime = System.nanoTime()
            val elapsed = currentTime - lastUpdateTime
            lastUpdateTime = currentTime

            accumulatedTimeNs += elapsed

            // Run physics updates at a fixed timestep
            var simulationSteps = 0
            while (accumulatedTimeNs >= TIME_STEP_NS && simulationSteps < MAX_FRAME_SKIPS) {
                // Fixed 60 UPS delta time = 0.016667 seconds
                gameView.update(0.0166667f)
                accumulatedTimeNs -= TIME_STEP_NS
                simulationSteps++
                updateCount++
            }

            // Render updates at variable framerate (locks SurfaceView canvas)
            val canvas = surfaceHolder.lockCanvas()
            if (canvas != null) {
                try {
                    synchronized(surfaceHolder) {
                        gameView.draw(canvas)
                    }
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                    frameCount++
                }
            }

            // Calculate and display performance averages every second
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStatTime >= 1000) {
                val secElapsed = (nowMs - lastStatTime) / 1000.0
                averageUPS = updateCount / secElapsed
                averageFPS = frameCount / secElapsed

                updateCount = 0
                frameCount = 0
                lastStatTime = nowMs
            }
        }
    }
}
