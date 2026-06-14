package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

enum class LootRarity {
    COMMON,  // Gray/White: small essences +5
    RARE,    // Purple: medium essences +25
    EPIC     // Yellow Gold: large rare essences +100
}

class LootDrop : Poolable {
    var x: Float = 0f
    var y: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    val friction: Float = 0.92f
    var value: Int = 5
    var rarity: LootRarity = LootRarity.COMMON
    var radius: Float = 14f
    var name: String = "Sparks"
    var isActive: Boolean = false

    override fun reset() {
        x = 0f
        y = 0f
        vx = 0f
        vy = 0f
        value = 5
        rarity = LootRarity.COMMON
        radius = 14f
        name = "Sparks"
        isActive = false
    }

    fun configure(spawnX: Float, spawnY: Float, rollChance: Double) {
        x = spawnX
        y = spawnY
        vx = 0f
        vy = 0f
        isActive = true

        // Roll rarity percentages (2% Epic, 18% Rare, 80% Common)
        when {
            rollChance < 0.05 -> {
                rarity = LootRarity.EPIC
                value = 100
                name = "Astra Shard"
                radius = 18f
            }
            rollChance < 0.25 -> {
                rarity = LootRarity.RARE
                value = 30
                name = "Nexus Core"
                radius = 15f
            }
            else -> {
                rarity = LootRarity.COMMON
                value = 5
                name = "Drift Residue"
                radius = 12f
            }
        }
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (!isActive) return

        // Set colors matching rarity tiers
        val colorHex = when (rarity) {
            LootRarity.COMMON -> "#D3D3D3" // Silver white
            LootRarity.RARE -> "#A020F0"   // Tech Purple
            LootRarity.EPIC -> "#FFD700"   // Neon Gold
        }

        // Drop shadow
        paint.color = Color.BLACK
        paint.alpha = 100
        canvas.drawCircle(x, y + 4f, radius * 0.9f, paint)

        // Draw outer glow pulse
        paint.color = Color.parseColor(colorHex)
        paint.alpha = 80
        val pulseRadius = radius * (1.2f + 0.15f * Math.sin(System.currentTimeMillis() * 0.01).toFloat())
        canvas.drawCircle(x, y, pulseRadius, paint)

        // Draw inner core shape
        paint.alpha = 255
        canvas.drawCircle(x, y, radius, paint)

        // Dark center core
        paint.color = Color.parseColor("#0A0A0A")
        canvas.drawCircle(x, y, radius * 0.5f, paint)

        // Inner glowing seed
        paint.color = Color.parseColor(colorHex)
        canvas.drawCircle(x, y, radius * 0.25f, paint)
    }
}
