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
        enemies: List<Enemy>,
        playerXp: Int,
        playerLevel: Int
    ) {
        // Safe check
        if (canvas.width <= 0 || canvas.height <= 0) return

        // --- 1. HEALTH AND XP BAR TOP-LEFT ---
        val hbX = 50f
        val hbY = 50f
        val hbW = 320f
        val hbH = 32f

        val xpX = 50f
        val xpY = 95f
        val xpW = 320f
        val xpH = 14f

        // Draw HUD container background expanded for Health and XP
        val mainBgRect = RectF(40f, 40f, 480f, 290f)
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
        textPaint.textSize = 21f
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val hpText = "${player.currentHp.toInt()} / ${player.maxHp.toInt()} HP"
        canvas.drawText(hpText, hbX + 15f, hbY + 24f, textPaint)

        // Draw XP Gauge Border
        val xpFrameRect = RectF(xpX, xpY, xpX + xpW, xpY + xpH)
        canvas.drawRect(xpFrameRect, borderPaint)

        // XP Gauge Fill: Cyber Light-Blue
        val xpPercentage = (playerXp / 100f).coerceIn(0f, 1f)
        val xpFillW = xpW * xpPercentage
        if (xpFillW > 4f) {
            val xpPaint = Paint().apply {
                color = Color.parseColor("#00BFFF") // Glowing light blue
                style = Paint.Style.FILL
            }
            canvas.drawRect(RectF(xpX + 2f, xpY + 2f, xpX + xpFillW - 2f, xpY + xpH - 2f), xpPaint)
        }

        // XP Text / Level Text overlay
        textPaint.color = Color.parseColor("#CCCCCC")
        textPaint.textSize = 18f
        canvas.drawText("LVL $playerLevel", hbX, xpY + 34f, textPaint)
        canvas.drawText("XP: $playerXp / 100", hbX + 160f, xpY + 34f, textPaint)

        // --- 2. DECK DETAILS (Essence & Floor Counter) ---
        textPaint.textSize = 26f
        textPaint.color = Color.parseColor("#00FFFF")
        canvas.drawText("FLOOR: $floor", hbX, xpY + 80f, textPaint)

        textPaint.color = Color.parseColor("#FFD700") // Gold for Shards/Essences
        canvas.drawText("ESSENCE: +$totalSessionEssence", hbX, xpY + 120f, textPaint)

        textPaint.color = Color.WHITE
        canvas.drawText("KILLS: $kills", hbX + 240f, xpY + 80f, textPaint)

        if (player.rollCooldownTimer > 0) {
            textPaint.color = Color.GRAY
            canvas.drawText("DASH: COOLDOWN", hbX + 215f, xpY + 120f, textPaint)
        } else {
            textPaint.color = Color.parseColor("#00FF66")
            canvas.drawText("DASH: READY", hbX + 215f, xpY + 120f, textPaint)
        }

        // --- 3. SCREEN-WIDE RED BOSS HEALTH BAR AT TOP OF SCREEN ---
        val boss = enemies.firstOrNull { it.isActive && !it.isDead && it.type == EnemyType.BOSS }
        if (boss != null) {
            val paddingX = 180f
            val bX = paddingX
            val bY = 40f
            val bW = canvas.width - paddingX * 2
            val bH = 30f

            // Background shadow
            val bossBgRect = RectF(bX - 4f, bY - 4f, bX + bW + 4f, bY + bH + 4f)
            val bossPanelPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                alpha = 200
            }
            canvas.drawRoundRect(bossBgRect, 6f, 6f, bossPanelPaint)
            
            // Outer red border
            val bossBorderPaint = Paint().apply {
                color = Color.parseColor("#FF003C")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(bossBgRect, 6f, 6f, bossBorderPaint)

            // Boss HP Fill (vibrant red)
            val bossHpPct = (boss.currentHp / boss.maxHp).coerceIn(0f, 1f)
            val bossFillW = bW * bossHpPct
            if (bossFillW > 4f) {
                val bossFillPaint = Paint().apply {
                    color = Color.parseColor("#E61C1C")
                    style = Paint.Style.FILL
                }
                canvas.drawRect(RectF(bX + 2f, bY + 2f, bX + bossFillW - 2f, bY + bH - 2f), bossFillPaint)
            }

            // Boss Name Text overlay
            val bossNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 22f
                typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.15f
            }
            canvas.drawText("RIFT OVERLORD (PHASE ${boss.bossPhase})", canvas.width / 2f, bY - 12f, bossNamePaint)
            
            // Boss HP Value numerical overlay
            val bossValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("${boss.currentHp.toInt()} / ${boss.maxHp.toInt()} ENCRYPTED HP", canvas.width / 2f, bY + bH - 7f, bossValPaint)
        }

        // --- 4. MINIMAP OVERLAY TOP-RIGHT ---
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
