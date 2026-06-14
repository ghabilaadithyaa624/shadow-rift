package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class HUD {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFFF") // Cool Cyan
        textSize = 40f
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A")
        alpha = 180
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C") // Soft sophisticated gray border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val redPaint = Paint().apply {
        color = Color.parseColor("#FF3B3B") // Sophisticated Vitality Red
        style = Paint.Style.FILL
    }

    private val greenPaint = Paint().apply {
        color = Color.parseColor("#00FF66") // Cyber Green
        style = Paint.Style.FILL
    }

    private val minimapPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    /**
     * Renders overlays like health gauges, progress tallies, level details, and the tactical minimap.
     */
    fun draw(
        canvas: Canvas,
        player: Player,
        floor: Int,
        kills: Int,
        totalSessionEssence: Int,
        screnWidth: Int,
        screenHeight: Int,
        dungeon: Array<IntArray>,
        tileSize: Int,
        enemies: List<Enemy>
    ) {
        // Safe check
        if (canvas.width <= 0 || canvas.height <= 0) return

        // --- 1. HEALTH BAR TOP-LEFT ---
        val hbX = 50f
        val hbY = 50f
        val hbW = 320f
        val hbH = 35f

        // Draw HUD container background for Health
        val mainBgRect = RectF(40f, 40f, 480f, 200f)
        canvas.drawRoundRect(mainBgRect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(mainBgRect, 12f, 12f, borderPaint)

        // Draw Health Gauge Border
        val frameRect = RectF(hbX, hbY, hbX + hbW, hbY + hbH)
        canvas.drawRect(frameRect, borderPaint)

        // Health Gauge Fill (percentage calculation)
        val hpPercentage = (player.currentHp / player.maxHp).coerceIn(0f, 1f)
        val fillW = hbW * hpPercentage
        val fillRect = RectF(hbX + 2f, hbY + 2f, hbX + fillW - 2f, hbY + hbH - 2f)
        
        paintHpGauge(canvas, fillRect, hpPercentage)

        // HP Label
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
        val hpText = "${player.currentHp.toInt()} / ${player.maxHp.toInt()} HP"
        canvas.drawText(hpText, hbX + 15f, hbY + 26f, textPaint)

        // --- 2. DECK DETAILS (Essence & Floor Counter) ---
        textPaint.textSize = 28f
        textPaint.color = Color.parseColor("#00FFFF")
        canvas.drawText("FLOOR: $floor", hbX, hbY + 75f, textPaint)

        textPaint.color = Color.parseColor("#FFD700") // Gold for Shards/Essences
        canvas.drawText("ESSENCE: +$totalSessionEssence", hbX, hbY + 115f, textPaint)

        textPaint.color = Color.WHITE
        canvas.drawText("KILLS: $kills", hbX + 240f, hbY + 75f, textPaint)

        if (player.rollCooldownTimer > 0) {
            textPaint.color = Color.GRAY
            canvas.drawText("DASH: COOLDOWN", hbX + 240f, hbY + 115f, textPaint)
        } else {
            textPaint.color = Color.parseColor("#00FF66")
            canvas.drawText("DASH: READY", hbX + 240f, hbY + 115f, textPaint)
        }

        // --- 3. MINIMAP OVERLAY TOP-RIGHT ---
        // Width of map representation
        val mapSideSize = 160f
        val mmX = canvas.width - mapSideSize - 60f
        val mmY = 50f

        val mapFrame = RectF(mmX, mmY, mmX + mapSideSize, mmY + mapSideSize)
        
        // Background for minimap
        val mapBgFrame = RectF(mmX - 5f, mmY - 5f, mmX + mapSideSize + 5f, mmY + mapSideSize + 5f)
        canvas.drawRoundRect(mapBgFrame, 8f, 8f, bgPaint)
        canvas.drawRoundRect(mapBgFrame, 8f, 8f, borderPaint)

        // Only draw minimap cells dynamically centered around the player (Visible 15x15 grids)
        val mapCols = dungeon[0].size
        val mapRows = dungeon.size
        val playerTileX = (player.x / tileSize).toInt()
        val playerTileY = (player.y / tileSize).toInt()

        val viewRadius = 9 // 19x19 tiles segment centered at player
        val cellW = mapSideSize / (viewRadius * 2 + 1)

        for (oy in -viewRadius..viewRadius) {
            for (ox in -viewRadius..viewRadius) {
                val tx = playerTileX + ox
                val ty = playerTileY + oy

                if (ty in 0 until mapRows && tx in 0 until mapCols) {
                    val tileType = dungeon[ty][tx]
                    val cellX = mmX + (ox + viewRadius) * cellW
                    val cellY = mmY + (oy + viewRadius) * cellW

                    if (tileType == DungeonGenerator.TILE_WALL) {
                        minimapPaint.color = Color.parseColor("#222222")
                    } else if (tileType == DungeonGenerator.TILE_STAIRS) {
                        minimapPaint.color = Color.BLUE
                    } else {
                        minimapPaint.color = Color.parseColor("#444444")
                    }
                    canvas.drawRect(cellX, cellY, cellX + cellW - 1f, cellY + cellW - 1f, minimapPaint)
                }
            }
        }

        // Draw Player Marker on center of Radar
        val centerCellX = mmX + viewRadius * cellW
        val centerCellY = mmY + viewRadius * cellW
        minimapPaint.color = Color.parseColor("#00FFFF")
        canvas.drawCircle(centerCellX + cellW / 2f, centerCellY + cellW / 2f, cellW / 1.5f, minimapPaint)

        // Draw Hostile Markers on radar
        for (enemy in enemies) {
            if (enemy.isActive && !enemy.isDead) {
                val etx = (enemy.x / tileSize).toInt()
                val ety = (enemy.y / tileSize).toInt()
                val ox = etx - playerTileX
                val oy = ety - playerTileY

                if (Math.abs(ox) <= viewRadius && Math.abs(oy) <= viewRadius) {
                    val cellX = mmX + (ox + viewRadius) * cellW
                    val cellY = mmY + (oy + viewRadius) * cellW
                    minimapPaint.color = Color.RED
                    canvas.drawCircle(cellX + cellW / 2f, cellY + cellW / 2f, cellW / 1.8f, minimapPaint)
                }
            }
        }
    }

    private fun paintHpGauge(canvas: Canvas, rect: RectF, hpPercentage: Float) {
        if (hpPercentage <= 0) return
        
        // Vitality gauge as styled with consistent deep Cyber Red #FF3B3B representing vitality
        redPaint.alpha = 255
        canvas.drawRect(rect, redPaint)
    }
}
