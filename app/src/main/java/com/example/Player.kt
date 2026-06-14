package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class Player {
    // Spatial coordinates (tiles/pixels scale)
    var x: Float = 0f
    var y: Float = 0f
    var radius: Float = 22f

    // Standard attributes (scaled by meta progression)
    var maxHp: Float = 100f
    var currentHp: Float = 100f
    var moveSpeed: Float = 280f // Pixels per second
    var baseDamage: Float = 15f
    var critChance: Float = 0.05f

    // Dodge Roll variables
    var isRolling: Boolean = false
    var rollTimer: Float = 0f
    private val rollDuration: Float = 0.25f // 250ms of dodge duration
    private val rollCooldown: Float = 0.8f // 800ms between rolls
    var rollCooldownTimer: Float = 0f
    private var rollVx: Float = 0f
    private var rollVy: Float = 0f

    // Damage recovery invincibility
    var iframeTimer: Float = 0f
    private val hitIframeDuration: Float = 0.5f // 500ms iframe on hit

    // Shoot controls
    var shootCooldownTimer: Float = 0f
    var baseShootCooldown: Float = 0.3f // Firing interval in seconds (every 300ms)

    // Direction tracking for animation/drawing
    var aimAngle: Float = 0f
    var facingAngle: Float = 0f
    
    // Skill bonuses applied at genesis
    fun applySkillUpgrades(skills: List<SkillNode>) {
        // Reset base values
        maxHp = 100f
        moveSpeed = 280f
        baseDamage = 15f
        critChance = 0.05f

        for (skill in skills) {
            when (skill.skillId) {
                "hp_boost" -> maxHp += skill.totalBonus
                "speed" -> moveSpeed *= (1f + skill.totalBonus)
                "damage" -> baseDamage *= (1f + skill.totalBonus)
                "crit" -> critChance += skill.totalBonus
            }
        }
        currentHp = maxHp
    }

    /**
     * Updates the player's position, dodge state, and cooldowns.
     */
    fun update(
        dtSec: Float,
        moveX: Float,
        moveY: Float,
        aimX: Float,
        aimY: Float,
        dungeon: Array<IntArray>,
        tileSize: Int,
        bulletPool: ObjectPool<Bullet>,
        activeBullets: MutableList<Bullet>,
        speedOverride: Float? = null
    ) {
        // Decrease iframe timers
        if (iframeTimer > 0) iframeTimer -= dtSec
        if (rollCooldownTimer > 0) rollCooldownTimer -= dtSec

        if (isRolling) {
            // Apply roll velocity
            moveAndResolveCollisions(rollVx * dtSec, rollVy * dtSec, dungeon, tileSize)
            rollTimer -= dtSec
            if (rollTimer <= 0) {
                isRolling = false
            }
        } else {
            // Standard move input
            val speed = speedOverride ?: moveSpeed
            val inputLength = Math.hypot(moveX.toDouble(), moveY.toDouble()).toFloat()
            if (inputLength > 0.05f) {
                val vx = (moveX / inputLength) * speed
                val vy = (moveY / inputLength) * speed
                facingAngle = Math.atan2(moveY.toDouble(), moveX.toDouble()).toFloat()
                moveAndResolveCollisions(vx * dtSec, vy * dtSec, dungeon, tileSize)
            }
        }

        // Deal with aiming and shooting
        val aimLength = Math.hypot(aimX.toDouble(), aimY.toDouble()).toFloat()
        if (aimLength > 0.15f) {
            aimAngle = Math.atan2(aimY.toDouble(), aimX.toDouble()).toFloat()
            if (!isRolling) {
                facingAngle = aimAngle
            }

            // Fire projectile
            if (shootCooldownTimer <= 0) {
                val bullet = bulletPool.obtain()
                val isCritHit = Math.random() < critChance
                val dmg = if (isCritHit) baseDamage * 2f else baseDamage
                
                // Spawn projectile matching player's aiming trajectory
                val cos = Math.cos(aimAngle.toDouble()).toFloat()
                val sin = Math.sin(aimAngle.toDouble()).toFloat()
                
                bullet.x = x + cos * radius
                bullet.y = y + sin * radius
                bullet.vx = cos * 720f
                bullet.vy = sin * 720f
                bullet.radius = 8f
                bullet.damage = dmg
                bullet.isPlayerOwned = true
                bullet.isCrit = isCritHit
                bullet.isActive = true
                bullet.rangeLeft = 700f
                
                activeBullets.add(bullet)
                shootCooldownTimer = baseShootCooldown
            }
        }

        if (shootCooldownTimer > 0) {
            shootCooldownTimer -= dtSec
        }
    }

    /**
     * Trigger a quick direction-based dodge roll providing invicibility frames.
     */
    fun startDodgeRoll(dx: Float, dy: Float) {
        if (isRolling || rollCooldownTimer > 0) return

        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val rollSpeedMultiplier = 1.8f
        
        if (len > 0.05f) {
            rollVx = (dx / len) * moveSpeed * rollSpeedMultiplier
            rollVy = (dy / len) * moveSpeed * rollSpeedMultiplier
        } else {
            // Roll in facing direction if no active movement joystick input
            rollVx = Math.cos(facingAngle.toDouble()).toFloat() * moveSpeed * rollSpeedMultiplier
            rollVy = Math.sin(facingAngle.toDouble()).toFloat() * moveSpeed * rollSpeedMultiplier
        }

        isRolling = true
        rollTimer = rollDuration
        rollCooldownTimer = rollCooldown
        iframeTimer = rollDuration // Invincible throughout the dodge
    }

    /**
     * Checks if the player is currently invincible (either due to rolling or hit recovery).
     */
    val isInvincible: Boolean
        get() = isRolling || iframeTimer > 0

    /**
     * Takes damage with hit feedback. Returns true if hit successfully (not invincible).
     */
    fun takeDamage(amount: Float): Boolean {
        if (isInvincible || currentHp <= 0) return false
        currentHp = (currentHp - amount).coerceAtLeast(0f)
        iframeTimer = hitIframeDuration
        return true
    }

    /**
     * Swept axis-aligned box and circle collision resolution with tile walls.
     */
    private fun moveAndResolveCollisions(dx: Float, dy: Float, dungeon: Array<IntArray>, tileSize: Int) {
        val nextX = x + dx
        val nextY = y + dy

        // Step-wise collision check (first X-axis then Y-axis)
        // X Collision
        if (canMoveTo(nextX, y, dungeon, tileSize)) {
            x = nextX
        } else {
            // Stop movement or slide
        }

        // Y Collision
        if (canMoveTo(x, nextY, dungeon, tileSize)) {
            y = nextY
        } else {
            // Stop movement or slide
        }
    }

    private fun canMoveTo(px: Float, py: Float, dungeon: Array<IntArray>, tileSize: Int): Boolean {
        val rows = dungeon.size
        val cols = dungeon[0].size

        // Probe multiple bounds points of the player circle
        val checkPoints = arrayOf(
            Pair(-radius, 0f),
            Pair(radius, 0f),
            Pair(0f, -radius),
            Pair(0f, radius),
            Pair(-radius * 0.7f, -radius * 0.7f),
            Pair(radius * 0.7f, -radius * 0.7f),
            Pair(-radius * 0.7f, radius * 0.7f),
            Pair(radius * 0.7f, radius * 0.7f)
        )

        for (pt in checkPoints) {
            val cx = px + pt.first
            val cy = py + pt.second
            val tx = (cx / tileSize).toInt()
            val ty = (cy / tileSize).toInt()

            if (ty !in 0 until rows || tx !in 0 until cols) {
                return false // Out of map bounds is wall
            }

            if (dungeon[ty][tx] == DungeonGenerator.TILE_WALL) {
                return false
            }
        }
        return true
    }

    /**
     * Draw Cyan Tactical Agent on Surface Canvas.
     */
    fun draw(canvas: Canvas, paint: Paint) {
        // Drawing shadow
        paint.color = Color.BLACK
        paint.alpha = 100
        canvas.drawCircle(x, y + 8f, radius * 0.9f, paint)
        paint.alpha = 255

        // Player Outer Core color based on state (Cyan accent vs Invincible flashing vs Death)
        if (isRolling) {
            paint.color = Color.parseColor("#0088CC") // Blueish cyber trail
        } else if (iframeTimer > 0) {
            paint.color = Color.WHITE // Visual flash reaction
        } else {
            paint.color = Color.parseColor("#00FFFF") // Cyber cyan
        }
        canvas.drawCircle(x, y, radius, paint)

        // Player Inner core
        paint.color = Color.parseColor("#0A0A0A") // Deep cyber background in core
        canvas.drawCircle(x, y, radius * 0.6f, paint)

        // Draw aiming weapon visor
        paint.color = Color.parseColor("#00FFFF")
        paint.strokeWidth = 5f
        val cos = Math.cos(facingAngle.toDouble()).toFloat()
        val sin = Math.sin(facingAngle.toDouble()).toFloat()
        val visorX = x + cos * radius * 0.9f
        val visorY = y + sin * radius * 0.9f
        canvas.drawCircle(visorX, visorY, 6f, paint)
    }
}
