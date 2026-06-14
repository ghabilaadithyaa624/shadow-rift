package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

data class DamageNumber(
    var x: Float,
    var y: Float,
    val text: String,
    val color: Int,
    var lifeTime: Float = 0.8f,
    val maxLife: Float = 0.8f
)

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // Interfaces for Game Status notification
    interface GameListener {
        fun onPlayerDeath(floorReached: Int, kills: Int, essenceEarned: Int)
        fun onVictory()
        fun onLevelUp(level: Int, onStatSelected: (statType: Int) -> Unit)
    }

    var gameListener: GameListener? = null

    // Run-time Game State
    private var gameLoop: GameLoop? = null
    var gameFloor = 1
    var sessionKills = 0
    var sessionEssence = 0
    
    // XP and level progression
    var playerXp = 0
    var playerLevel = 1
    val xpNeeded = 100

    fun addPlayerXp(amount: Int) {
        playerXp += amount
        if (playerXp >= xpNeeded) {
            playerXp -= xpNeeded
            playerLevel++
            
            // Pauses the execution loop
            pause()
            
            // Fires UI dialogue thread-safely
            gameListener?.onLevelUp(playerLevel) { statSelected ->
                when (statSelected) {
                    0 -> { // +15% Damage / ATK
                        player.baseDamage *= 1.15f
                    }
                    1 -> { // +20 Max HP
                        player.maxHp += 20f
                        player.currentHp = (player.currentHp + 20f).coerceAtMost(player.maxHp)
                    }
                    2 -> { // +10% Speed
                        player.moveSpeed *= 1.10f
                    }
                }
                resume()
            }
        }
    }
    
    // Level grid properties
    val tileSize = 70 // Grid tile size in pixels
    private val dungeonWidth = 45
    private val dungeonHeight = 45
    private val chunkSize = 15 // Grid tiles per lazy-loaded chunk (45x45 splits into 3x3 of 15x15 chunks)

    // Core Game Entities
    val player = Player()
    private val dungeonGenerator = DungeonGenerator(dungeonWidth, dungeonHeight)
    private var dungeonResult = dungeonGenerator.generate(gameFloor)

    // Object Pooling Systems (Fully Pre-allocated at Genesis for 0 garbage collection cycles)
    val bulletPool = ObjectPool(120) { Bullet() }
    val enemyPool = ObjectPool(60) { Enemy() }
    val particlePool = ObjectPool(400) { Particle() }
    val lootPool = ObjectPool(60) { LootDrop() }

    val activeBullets = mutableListOf<Bullet>()
    val activeEnemies = mutableListOf<Enemy>()
    val activeParticles = mutableListOf<Particle>()
    val activeLoot = mutableListOf<LootDrop>()

    // Camera variables
    private var cameraX = 0f
    private var cameraY = 0f

    // Standard Paints
    private val paint = Paint()
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        textSize = 30f
    }

    // Modern android haptics
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Vibration Effects
    private fun triggerImpactHaptics() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(120)
            }
        }
    }

    private fun triggerKillHaptics() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(40, 180)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        }
    }

    // PerformanceHintManager for CPU Boost (Android 12+)
    private var hintSession: Any? = null // Using Any for type-safety across different SDK configurations
    private fun requestPerformanceCpuBoost() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val phm = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                if (phm != null && hintSession == null) {
                    val myThreadId = Process.myTid()
                    // Create CPU performance session anticipating 16.6 milliseconds (60 FPS) target
                    hintSession = phm.createHintSession(intArrayOf(myThreadId), 16_666_667L)
                }
                
                // Signal boost during intense engagements (combat or phase transitions)
                val session = hintSession as? PerformanceHintManager.Session
                session?.reportActualWorkDuration(18_000_000L) // Instruct scheduler to scale clock speed up
            } catch (e: Exception) {
                // Failsafe on customized ROMs or restricted systems
            }
        }
    }

    // LRU Sprite/Texture Pre-Render Image Cache based on memory tier
    private val maxCacheBytes = PerformanceManager.spriteCacheSizeMb * 1024 * 1024
    private val spriteCache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    // Multi-touch virtual joysticks
    private var moveJoystick: VirtualJoystick? = null
    private var shootJoystick: VirtualJoystick? = null

    // Control Mode configuration (Joystick toggle capability)
    var isJoystickEnabled = true

    // Tap-to-move variables
    private var hasMoveTarget = false
    private var targetMoveX = 0f
    private var targetMoveY = 0f
    private var tapPointerId = -1

    // Double-tap tracker for dodge rolling
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // Auto-attack scanner interval properties
    private var autoAttackTimer = 0f
    private val autoAttackInterval = 0.5f // 500ms

    // Damage numbers and contact damage visuals
    private val activeDamageNumbers = mutableListOf<DamageNumber>()
    private var contactDamageTextCooldown = 0f

    // Overlay visual states
    private val hud = HUD()
    private var overlayMessage: String? = null
    private var messageTimer: Float = 0f

    // Screen Shake state (when damaging/clearing bosses)
    private var shakeDuration: Float = 0f
    private var shakeIntensity: Float = 0f

    init {
        holder.addCallback(this)
        isFocusable = true
        setupLevel(gameFloor)
    }

    /**
     * Set up pre-rendered procedurally generated bitmaps inside the LRU cache.
     */
    private fun preRenderBitmaps() {
        // Floor tile pre-rendered texture
        val floorTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvasFloor = Canvas(floorTile)
        canvasFloor.drawColor(Color.parseColor("#0D0D0D")) // Dark floor #0D0D0D
        val tilePaint = Paint().apply {
            color = Color.parseColor("#151515") // Subtle grid border
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvasFloor.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), tilePaint)
        // Draw a glowing cyber cyan dot in the center of each floor tile for a grid-dot style
        tilePaint.color = Color.parseColor("#00FFFF")
        tilePaint.style = Paint.Style.FILL
        tilePaint.alpha = 60
        canvasFloor.drawCircle(tileSize / 2f, tileSize / 2f, 2.5f, tilePaint)
        spriteCache.put("tile_floor", floorTile)

        // Wall tile pre-rendered texture
        val wallTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvasWall = Canvas(wallTile)
        canvasWall.drawColor(Color.parseColor("#1A1A2E")) // Dark gray walls #1A1A2E
        val wallPaint = Paint().apply {
            color = Color.parseColor("#2A2A44") // Slightly lighter modern grid-gray outlines
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvasWall.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), wallPaint)
        // Additional sleek interior segments for depth
        canvasWall.drawRect(6f, 6f, (tileSize - 6).toFloat(), (tileSize - 6).toFloat(), wallPaint)
        spriteCache.put("tile_wall", wallTile)

        // Stairs level exit: glowing cyan EXIT portal
        val stairsTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvasStairs = Canvas(stairsTile)
        canvasStairs.drawColor(Color.parseColor("#0D0D0D")) // Renders inline with floor background
        val stairsPaint = Paint().apply {
            color = Color.parseColor("#00FFFF") // Cyan color
            isAntiAlias = true
        }
        
        // Outer pulsing ring aura
        stairsPaint.style = Paint.Style.STROKE
        stairsPaint.strokeWidth = 4f
        stairsPaint.alpha = 90
        canvasStairs.drawCircle(tileSize / 2f, tileSize / 2f, tileSize * 0.4f, stairsPaint)

        // Inner ring
        stairsPaint.strokeWidth = 3f
        stairsPaint.alpha = 170
        canvasStairs.drawCircle(tileSize / 2f, tileSize / 2f, tileSize * 0.28f, stairsPaint)

        // Core shining energy vortex
        stairsPaint.style = Paint.Style.FILL
        stairsPaint.alpha = 255
        canvasStairs.drawCircle(tileSize / 2f, tileSize / 2f, tileSize * 0.15f, stairsPaint)
        spriteCache.put("tile_stairs", stairsTile)
    }

    /**
     * Applies meta progression stats to Player and sets up the chosen floor map structure.
     */
    fun startNewGame(unlockedSkills: List<SkillNode>) {
        gameFloor = 1
        sessionKills = 0
        sessionEssence = 0
        playerXp = 0
        playerLevel = 1
        player.applySkillUpgrades(unlockedSkills)
        setupLevel(gameFloor)
    }

    private fun setupLevel(floor: Int) {
        requestPerformanceCpuBoost()
        preRenderBitmaps()

        // Generate procedural maze map
        dungeonResult = dungeonGenerator.generate(floor)
        
        // Relocate player to floor start point
        player.x = (dungeonResult.spawnX * tileSize) + (tileSize / 2f)
        player.y = (dungeonResult.spawnY * tileSize) + (tileSize / 2f)

        // Recycle old active objects to keep the pools clean
        while (activeBullets.isNotEmpty()) {
            bulletPool.recycle(activeBullets.removeAt(0))
        }
        while (activeEnemies.isNotEmpty()) {
            enemyPool.recycle(activeEnemies.removeAt(0))
        }
        while (activeParticles.isNotEmpty()) {
            particlePool.recycle(activeParticles.removeAt(0))
        }
        while (activeLoot.isNotEmpty()) {
            lootPool.recycle(activeLoot.removeAt(0))
        }

        // Spawn hostiles
        spawnEntitiesForFloor(floor)

        showTemporaryMessage("FLOOR $floor: PENETRATE THE RIFT", 3.0f)
    }

    private fun spawnEntitiesForFloor(floor: Int) {
        if (floor % 5 == 0) {
            // Level is multiple of 5: Spawns the Phase-Based Rift Overlord Boss
            val boss = enemyPool.obtain()
            val bx = (dungeonResult.stairsX * tileSize) + (tileSize / 2f)
            val by = (dungeonResult.stairsY * tileSize) + (tileSize / 2f)
            boss.configure(EnemyType.BOSS, bx, by, floor)
            activeEnemies.add(boss)

            showTemporaryMessage("RIFT RULER AWAKENS! DANGER!", 4.0f)
        } else {
            // Place enemies randomly depending on the active Memory/CPU Tier configuration bounds
            for (roomIndex in dungeonResult.rooms.indices) {
                val room = dungeonResult.rooms[roomIndex]
                val enemyCount = when (PerformanceManager.selectedTier) {
                    MemoryTier.LOW -> (2..3).random()
                    MemoryTier.MID -> (3..5).random()
                    MemoryTier.HIGH -> (4..8).random()
                }
                for (i in 0 until enemyCount) {
                    var spawned = false
                    var attempts = 0
                    while (!spawned && attempts < 50) {
                        attempts++
                        val rx = (room.x until room.x + room.w).random()
                        val ry = (room.y until room.y + room.h).random()
                        
                        // Check distance to spawn point
                        val dx = rx - dungeonResult.spawnX
                        val dy = ry - dungeonResult.spawnY
                        val dist = Math.hypot(dx.toDouble(), dy.toDouble())
                        
                        // If it's starting room, don't spawn within 3 tiles of spawn point
                        if (roomIndex == 0 && dist < 3.0) {
                            continue
                        }
                        
                        val enemy = enemyPool.obtain()
                        val type = if (Math.random() < 0.4) EnemyType.RANGED else EnemyType.MELEE
                        enemy.configure(
                            type,
                            (rx * tileSize) + tileSize / 2f,
                            (ry * tileSize) + tileSize / 2f,
                            floor
                        )
                        activeEnemies.add(enemy)
                        spawned = true
                    }
                }
            }
        }
    }

    fun triggerScreenShake(duration: Float, intensity: Float) {
        if (!PerformanceManager.isScreenShakeEnabled) return
        shakeDuration = duration
        shakeIntensity = intensity * PerformanceManager.screenShakeIntensityMultiplier
    }

    fun showTemporaryMessage(msg: String, duration: Float) {
        overlayMessage = msg
        messageTimer = duration
    }

    /**
     * Game Frame Update logic runs under a strict 60 UPS.
     */
    fun update(dtSec: Float) {
        if (player.currentHp <= 0) return

        if (messageTimer > 0) messageTimer -= dtSec

        // Handle Screen Shake depletion
        if (shakeDuration > 0) {
            shakeDuration -= dtSec
        }

        // Update virtual joysticks output
        var finalMoveX = 0f
        var finalMoveY = 0f
        var speedOverride: Float? = null

        if (isJoystickEnabled && moveJoystick?.isPressed == true) {
            val rawX = moveJoystick?.deltaX ?: 0f
            val rawY = moveJoystick?.deltaY ?: 0f
            val len = Math.hypot(rawX.toDouble(), rawY.toDouble()).toFloat()
            if (len > 0.15f) {
                // Left joystick (bottom-left): 8-directional player movement
                val angle = Math.atan2(rawY.toDouble(), rawX.toDouble())
                val sector = Math.round(angle / (Math.PI / 4.0))
                val snappedAngle = sector * (Math.PI / 4.0)
                finalMoveX = Math.cos(snappedAngle).toFloat()
                finalMoveY = Math.sin(snappedAngle).toFloat()
                // Player moves toward left joystick direction at 5px/frame (300px/s)
                speedOverride = 300f
            }
            hasMoveTarget = false
        } else if (hasMoveTarget) {
            // Tap-to-move walks toward tap position
            val dx = targetMoveX - player.x
            val dy = targetMoveY - player.y
            val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist > 8f) {
                finalMoveX = dx / dist
                finalMoveY = dy / dist
            } else {
                hasMoveTarget = false
            }
        }

        var aimX = 0f
        var aimY = 0f
        if (isJoystickEnabled && shootJoystick?.isPressed == true) {
            aimX = shootJoystick?.deltaX ?: 0f
            aimY = shootJoystick?.deltaY ?: 0f
        }

        // Player updates
        player.update(
            dtSec, finalMoveX, finalMoveY, aimX, aimY,
            dungeonResult.grid, tileSize, bulletPool, activeBullets, speedOverride
        )

        // Auto-attack nearest enemy within 150px range every 500ms if not manual aiming
        if (player.currentHp > 0 && !player.isRolling && (aimX == 0f && aimY == 0f)) {
            autoAttackTimer -= dtSec
            if (autoAttackTimer <= 0f) {
                autoAttackTimer = autoAttackInterval
                var nearestEnemy: Enemy? = null
                var minDist = 150f
                for (enemy in activeEnemies) {
                    if (enemy.isActive && !enemy.isDead) {
                        val dist = Math.hypot((enemy.x - player.x).toDouble(), (enemy.y - player.y).toDouble()).toFloat()
                        if (dist < minDist) {
                            minDist = dist
                            nearestEnemy = enemy
                        }
                    }
                }
                if (nearestEnemy != null) {
                    val dx = nearestEnemy.x - player.x
                    val dy = nearestEnemy.y - player.y
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist > 0.1f) {
                        val cos = dx / dist
                        val sin = dy / dist
                        val bullet = bulletPool.obtain()
                        val isCritHit = Math.random() < player.critChance
                        val dmg = if (isCritHit) player.baseDamage * 2f else player.baseDamage
                        
                        bullet.x = player.x + cos * player.radius
                        bullet.y = player.y + sin * player.radius
                        bullet.vx = cos * 720f
                        bullet.vy = sin * 720f
                        bullet.radius = 8f
                        bullet.damage = dmg
                        bullet.isPlayerOwned = true
                        bullet.isCrit = isCritHit
                        bullet.isActive = true
                        bullet.rangeLeft = 700f
                        activeBullets.add(bullet)
                        
                        player.facingAngle = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
                    }
                }
            }
        }

        // Smooth camera targeting centering player
        cameraX += (player.x - cameraX) * 5f * dtSec
        cameraY += (player.y - cameraY) * 5f * dtSec

        // Update Projectiles
        val bulletIterator = activeBullets.iterator()
        while (bulletIterator.hasNext()) {
            val bullet = bulletIterator.next()
            bullet.update(dtSec)

            // If bullet hitting map walls, terminate it
            val btx = (bullet.x / tileSize).toInt()
            val bty = (bullet.y / tileSize).toInt()
            if (bty !in 0 until dungeonHeight || btx !in 0 until dungeonWidth ||
                dungeonResult.grid[bty][btx] == DungeonGenerator.TILE_WALL) {
                bullet.isActive = false
            }

            // Bullet vs Player/Enemy collision checks
            if (bullet.isActive) {
                if (bullet.isPlayerOwned) {
                    for (enemy in activeEnemies) {
                        if (enemy.isActive && !enemy.isDead) {
                            val dist = Math.hypot((bullet.x - enemy.x).toDouble(), (bullet.y - enemy.y).toDouble())
                            if (dist < (bullet.radius + enemy.radius)) {
                                bullet.isActive = false
                                
                                // Standard enemies die after 3 hits of player projectiles
                                val damageToDeal = if (enemy.type == EnemyType.BOSS) {
                                    bullet.damage
                                } else {
                                    enemy.maxHp / 3f
                                }
                                val died = enemy.takeDamage(damageToDeal)
                                triggerImpactHaptics()
                                requestPerformanceCpuBoost()

                                // Spray visual blood spark particles
                                createDeathSparks(enemy.x, enemy.y, if (enemy.type == EnemyType.BOSS) Color.RED else Color.GREEN)

                                // Floating damage number for enemy (+red)
                                if (PerformanceManager.isFloatingDamageNumbersEnabled) {
                                    val displayDamage = if (enemy.type == EnemyType.BOSS) bullet.damage.toInt() else (enemy.maxHp / 3f).toInt()
                                    activeDamageNumbers.add(
                                        DamageNumber(
                                            enemy.x + (Math.random() * 20 - 10).toFloat(),
                                            enemy.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                            "+$displayDamage",
                                            Color.RED
                                        )
                                    )
                                }

                                if (died) {
                                    sessionKills++
                                    triggerKillHaptics()
                                    
                                    val xpGained = if (enemy.type == EnemyType.BOSS) 100 else 25
                                    addPlayerXp(xpGained)

                                    if (enemy.type == EnemyType.BOSS) {
                                        triggerScreenShake(1.5f, 25f)
                                        showTemporaryMessage("FLOOR CLEARED", 4.0f)
                                        
                                        // Spawn a highly animated fountain of 12 loot drops scattering in all directions!
                                        for (i in 0 until 12) {
                                            val loot = lootPool.obtain()
                                            val rChance = Math.random()
                                            loot.configure(enemy.x, enemy.y, rChance)
                                            
                                            val angle = (2 * Math.PI * Math.random())
                                            val blastSpeed = (300f + 250f * Math.random()).toFloat()
                                            loot.vx = Math.cos(angle).toFloat() * blastSpeed
                                            loot.vy = Math.sin(angle).toFloat() * blastSpeed
                                            activeLoot.add(loot)
                                        }
                                    } else {
                                        // Standard simple single loot drop
                                        val loot = lootPool.obtain()
                                        loot.configure(enemy.x, enemy.y, Math.random())
                                        activeLoot.add(loot)
                                    }
                                }
                                break
                            }
                        }
                    }
                } else {
                    // Hostile bullets vs player
                    val dist = Math.hypot((bullet.x - player.x).toDouble(), (bullet.y - player.y).toDouble())
                    if (dist < (bullet.radius + player.radius)) {
                        bullet.isActive = false
                        val damaged = player.takeDamage(bullet.damage)
                        if (damaged) {
                            triggerImpactHaptics()
                            triggerScreenShake(0.2f, 15f) // Screen shake for 200ms when player takes damage

                            // Floating damage number for player (-red)
                            if (PerformanceManager.isFloatingDamageNumbersEnabled) {
                                activeDamageNumbers.add(
                                    DamageNumber(
                                        player.x + (Math.random() * 20 - 10).toFloat(),
                                        player.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                        "-${bullet.damage.toInt()}",
                                        Color.RED
                                    )
                                )
                            }

                            if (player.currentHp <= 0) {
                                triggerScreenShake(2.0f, 35f)
                                createDeathSparks(player.x, player.y, Color.parseColor("#00FFFF"))
                                gameListener?.onPlayerDeath(gameFloor, sessionKills, sessionEssence)
                            }
                        }
                    }
                }
            }

            if (!bullet.isActive) {
                bulletIterator.remove()
                bulletPool.recycle(bullet)
            }
        }

        // Update Hostiles
        val enemyIterator = activeEnemies.iterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            enemy.update(dtSec, player, dungeonResult.grid, tileSize, bulletPool, activeBullets) {
                // Shake screen callback on Boss rage shift
                triggerScreenShake(1.0f, 20f)
                showTemporaryMessage("BOSS ENTERS CORRUPTED PHASE II!", 3.0f)
            }

            if (enemy.isDead) {
                enemyIterator.remove()
                enemyPool.recycle(enemy)
            }
        }

        // Contact damage checks (1 HP per frame touching enemy)
        if (player.currentHp > 0) {
            for (enemy in activeEnemies) {
                if (enemy.isActive && !enemy.isDead) {
                    val dist = Math.hypot((player.x - enemy.x).toDouble(), (player.y - enemy.y).toDouble())
                    if (dist < (player.radius + enemy.radius)) {
                        // Player loses 1 HP this frame
                        player.currentHp = (player.currentHp - 1f).coerceAtLeast(0f)
                        
                        // Screen shake for 200ms when player takes damage
                        if (shakeDuration <= 0f) {
                            triggerScreenShake(0.2f, 10f)
                        }
                        
                        // Floating damage number for player contact damage (throttled to 100ms / 0.1s to avoid overcrowding)
                        contactDamageTextCooldown -= dtSec
                        if (contactDamageTextCooldown <= 0f) {
                            contactDamageTextCooldown = 0.1f
                            if (PerformanceManager.isFloatingDamageNumbersEnabled) {
                                activeDamageNumbers.add(
                                    DamageNumber(
                                        player.x + (Math.random() * 30 - 15).toFloat(),
                                        player.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                        "-1",
                                        Color.RED
                                    )
                                )
                            }
                        }
                        
                        if (player.currentHp <= 0) {
                            triggerScreenShake(2.0f, 35f)
                            createDeathSparks(player.x, player.y, Color.parseColor("#00FFFF"))
                            gameListener?.onPlayerDeath(gameFloor, sessionKills, sessionEssence)
                        }
                        break // Handle single contact overlap per frame
                    }
                }
            }
        }

        // Update Floating Damage Numbers
        val numIterator = activeDamageNumbers.iterator()
        while (numIterator.hasNext()) {
            val num = numIterator.next()
            num.y -= 80f * dtSec // Floating upwards
            num.lifeTime -= dtSec
            if (num.lifeTime <= 0f) {
                numIterator.remove()
            }
        }

        // Update Particle Sparks
        val particleIterator = activeParticles.iterator()
        while (particleIterator.hasNext()) {
            val particle = particleIterator.next()
            particle.update(dtSec)
            if (!particle.isActive) {
                particleIterator.remove()
                particlePool.recycle(particle)
            }
        }

        // Keep active particle count strictly within selected tier limits
        val curMaxParticles = PerformanceManager.maxParticles
        while (activeParticles.size > curMaxParticles) {
            val oldest = activeParticles.removeAt(0)
            particlePool.recycle(oldest)
        }

        // Update Loot Drops Collection
        val lootIterator = activeLoot.iterator()
        while (lootIterator.hasNext()) {
            val loot = lootIterator.next()
            
            // Apply physics momentum slide
            loot.x += loot.vx * dtSec
            loot.y += loot.vy * dtSec
            loot.vx *= loot.friction
            loot.vy *= loot.friction

            val dist = Math.hypot((player.x - loot.x).toDouble(), (player.y - loot.y).toDouble())
            if (dist < (player.radius + loot.radius)) {
                sessionEssence += loot.value
                loot.isActive = false
                triggerImpactHaptics()
                showTemporaryMessage("+${loot.value} ESSENCE", 1.0f)
            }

            if (!loot.isActive) {
                lootIterator.remove()
                lootPool.recycle(loot)
            }
        }

        // Check if player hit the Stairs Tile rifting them to next floor
        val ptx = (player.x / tileSize).toInt()
        val pty = (player.y / tileSize).toInt()
        if (pty in 0 until dungeonHeight && ptx in 0 until dungeonWidth) {
            if (dungeonResult.grid[pty][ptx] == DungeonGenerator.TILE_STAIRS) {
                gameFloor++
                setupLevel(gameFloor)
            }
        }
    }

    private fun createDeathSparks(sx: Float, sy: Float, col: Int) {
        val maxAllowed = PerformanceManager.maxParticles
        val currentCount = activeParticles.size
        if (currentCount >= maxAllowed) return

        val sparkVolume = if (PerformanceManager.selectedTier == MemoryTier.LOW) 8 else 24
        val spawnCount = sparkVolume.coerceAtMost(maxAllowed - currentCount)
        for (i in 0 until spawnCount) {
            val p = particlePool.obtain()
            val angle = (2 * Math.PI * Math.random())
            val magnitude = (100f + 300f * Math.random()).toFloat()
            p.configure(
                sx, sy,
                Math.cos(angle).toFloat() * magnitude,
                Math.sin(angle).toFloat() * magnitude,
                (4f + 8f * Math.random()).toFloat(),
                col,
                (0.4f + 0.4f * Math.random()).toFloat()
            )
            activeParticles.add(p)
        }
    }

    /**
     * Renders entire state, applying screen shakes, 3x3 Chunking walls layers, objects, joysticks & HUD.
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawColor(Color.parseColor("#0A0A0A")) // Dark theme paint background

        val width = canvas.width
        val height = canvas.height

        // Dynamic cyber background styling depending on active performance tiers
        if (PerformanceManager.isBackgroundParallaxEnabled) {
            // Draw flowing matrix digital grids below the game canvas with counter-scrolling parallax
            paint.color = Color.parseColor("#121212")
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE
            
            val gridSpacing = 80f
            val pxOffset = -cameraX * 0.3f
            val pyOffset = -cameraY * 0.3f
            
            val numGridCols = (width / gridSpacing).toInt() + 4
            val numGridRows = (height / gridSpacing).toInt() + 4
            
            val startX = (pxOffset % gridSpacing) - gridSpacing
            val startY = (pyOffset % gridSpacing) - gridSpacing
            
            for (i in -1..numGridCols) {
                val gx = startX + i * gridSpacing
                canvas.drawLine(gx, 0f, gx, height.toFloat(), paint)
            }
            for (j in -1..numGridRows) {
                val gy = startY + j * gridSpacing
                canvas.drawLine(0f, gy, width.toFloat(), gy, paint)
            }
            
            paint.style = Paint.Style.FILL // reset
        } else if (PerformanceManager.selectedTier == MemoryTier.MID) {
            // Light scanning line helpers which are extremely lightweight
            paint.color = Color.parseColor("#0D0D0D")
            paint.strokeWidth = 1f
            paint.style = Paint.Style.STROKE
            val spacing = 40f
            var gy = 0.0f
            while (gy < height) {
                canvas.drawLine(0f, gy, width.toFloat(), gy, paint)
                gy += spacing
            }
            paint.style = Paint.Style.FILL
        }

        canvas.save()

        // Apply visual Screen Shake if active
        if (shakeDuration > 0) {
            val rShakeX = ((Math.random() - 0.5) * shakeIntensity * (shakeDuration / 2.0f)).toFloat()
            val rShakeY = ((Math.random() - 0.5) * shakeIntensity * (shakeDuration / 2.0f)).toFloat()
            canvas.translate(rShakeX, rShakeY)
        }

        // Apply viewport camera shift translations
        canvas.translate(width / 2f - cameraX, height / 2f - cameraY)

        // --- LAZY CHUNK LOADING (ONLY RENDER SURROUNDING 3x3 BLOCKS OF THE ACTIVE PLAYER CHUNK) ---
        val playerTileX = (player.x / tileSize).toInt()
        val playerTileY = (player.y / tileSize).toInt()
        
        val activeChunkCol = playerTileX / chunkSize
        val activeChunkRow = playerTileY / chunkSize

        val padding = 1 // 3x3 surrounding
        for (cr in (activeChunkRow - padding)..(activeChunkRow + padding)) {
            for (cc in (activeChunkCol - padding)..(activeChunkCol + padding)) {

                // Convert chunk segments back to raw tiles ranges
                val startTx = cc * chunkSize
                val endTx = startTx + chunkSize
                val startTy = cr * chunkSize
                val endTy = startTy + chunkSize

                for (y in startTy until endTy) {
                    for (x in startTx until endTx) {
                        if (y in 0 until dungeonHeight && x in 0 until dungeonWidth) {
                            val tileType = dungeonResult.grid[y][x]
                            val tileX = x * tileSize
                            val tileY = y * tileSize

                            val cacheKey = when (tileType) {
                                DungeonGenerator.TILE_WALL -> "tile_wall"
                                DungeonGenerator.TILE_STAIRS -> "tile_stairs"
                                else -> "tile_floor"
                            }

                            val bmp = spriteCache.get(cacheKey)
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, tileX.toFloat(), tileY.toFloat(), paint)
                            } else {
                                // Backup rect failsafe if cache misses
                                paint.color = if (tileType == DungeonGenerator.TILE_WALL) Color.RED else Color.DKGRAY
                                canvas.drawRect(
                                    tileX.toFloat(), tileY.toFloat(),
                                    (tileX + tileSize).toFloat(), (tileY + tileSize).toFloat(), paint
                                )
                            }
                        }
                    }
                }
            }
        }

        // Draw items/loot
        for (loot in activeLoot) {
            loot.draw(canvas, paint)
        }

        // Draw Player
        player.draw(canvas, paint)

        // Draw Hostiles
        for (enemy in activeEnemies) {
            enemy.draw(canvas, paint)
        }

        // Draw Projectiles
        paint.color = Color.parseColor("#00FFFF") // Player neon cyan bullets
        for (bullet in activeBullets) {
            if (!bullet.isPlayerOwned) {
                paint.color = Color.parseColor("#FF3B3B") // Melee/Ranged hostile red plasma balls
            } else if (bullet.isCrit) {
                paint.color = Color.parseColor("#FFD700") // Crit sparkles gold
            } else {
                paint.color = Color.parseColor("#00FFFF")
            }
            canvas.drawCircle(bullet.x, bullet.y, bullet.radius, paint)
        }

        // Draw visual particles
        for (particle in activeParticles) {
            particle.draw(canvas, paint)
        }

        // Draw Floating Damage Numbers
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        for (num in activeDamageNumbers) {
            paint.color = num.color
            paint.textSize = 24f
            paint.alpha = ((num.lifeTime / num.maxLife) * 255f).toInt().coerceIn(0, 255)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(num.text, num.x, num.y, paint)
        }
        paint.alpha = 255
        paint.textAlign = Paint.Align.LEFT

        canvas.restore()

        // --- HUD HUD GAUGE OVERLAYS (STATIC CAMERA RENDERING) ---
        hud.draw(
            canvas, player, gameFloor, sessionKills, sessionEssence,
            width, height, dungeonResult.grid, tileSize, activeEnemies,
            playerXp, playerLevel
        )

        // Draw Virtual Input Controls
        if (isJoystickEnabled) {
            moveJoystick?.draw(canvas)
            shootJoystick?.draw(canvas)
        }

        // Draw Futuristic settings toggle: "JOYSTICK: ON/OFF"
        val toggleX = width - 220f
        val toggleY = 240f
        val toggleW = 160f
        val toggleH = 50f
        
        // Button Background container
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#111111")
        paint.alpha = 200
        canvas.drawRoundRect(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, 12f, 12f, paint)
        
        // Button Border glowing color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = if (isJoystickEnabled) Color.parseColor("#00FFFF") else Color.parseColor("#FF3B3B")
        paint.alpha = 255
        canvas.drawRoundRect(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, 12f, 12f, paint)
        
        // Button Texts
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 19f
        paint.textAlign = Paint.Align.CENTER
        val label = if (isJoystickEnabled) "JOYSTICK: ON" else "JOYSTICK: OFF"
        canvas.drawText(label, toggleX + toggleW / 2f, toggleY + 31f, paint)
        paint.textAlign = Paint.Align.LEFT

        // Draw dodge swipe prompt
        if (player.rollCooldownTimer <= 0) {
            paint.color = Color.parseColor("#00FFFF")
            paint.alpha = 50
            // Subtle graphic overlay
        }

        // Draw Temporary State alerts banner overlay
        if (overlayMessage != null && messageTimer > 0) {
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.BLACK
            paint.alpha = 150
            canvas.drawRect(0f, height * 0.7f, width.toFloat(), height * 0.85f, paint)

            paint.color = Color.parseColor("#00FFFF")
            paint.alpha = 255
            paint.textSize = 38f
            canvas.drawText(overlayMessage!!, width / 2f, height * 0.79f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Dual Touch pointer mapping to virtual analog controls, tap-to-move, and dodge features.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        // Guard against click registers before views dimension binding completes
        val mJ = moveJoystick ?: return true
        val sJ = shootJoystick ?: return true

        val width = if (surfaceWidth > 0) surfaceWidth.toInt() else getWidth()
        val height = if (surfaceHeight > 0) surfaceHeight.toInt() else getHeight()

        // First handle settings toggle tap down check
        if (action == MotionEvent.ACTION_DOWN) {
            val tx = event.x
            val ty = event.y
            val toggleX = width - 220f
            val toggleY = 240f
            if (tx >= toggleX && tx <= width - 60f && ty >= toggleY && ty <= toggleY + 50f) {
                isJoystickEnabled = !isJoystickEnabled
                triggerImpactHaptics()
                showTemporaryMessage("JOYSTICK: " + (if (isJoystickEnabled) "ON" else "OFF"), 1.0f)
                return true
            }
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val tx = event.getX(actionIndex)
                val ty = event.getY(actionIndex)

                var captured = false
                // Check if joysticks are enabled and touch starts inside active joystick radius/region (160px)
                if (isJoystickEnabled) {
                    val distLeft = Math.hypot((tx - mJ.centerX).toDouble(), (ty - mJ.centerY).toDouble()).toFloat()
                    val distRight = Math.hypot((tx - sJ.centerX).toDouble(), (ty - sJ.centerY).toDouble()).toFloat()

                    if (distLeft < 160f) {
                        if (!mJ.isPressed) {
                            mJ.pointerId = pointerId
                            mJ.isPressed = true
                            mJ.update(tx, ty)
                            captured = true
                            hasMoveTarget = false
                        }
                    } else if (distRight < 160f) {
                        if (!sJ.isPressed) {
                            sJ.pointerId = pointerId
                            sJ.isPressed = true
                            sJ.update(tx, ty)
                            captured = true
                        }
                    }
                }

                if (!captured) {
                    // Tap-to-move capture
                    val worldX = tx - (width / 2f) + cameraX
                    val worldY = ty - (height / 2f) + cameraY

                    val now = System.currentTimeMillis()
                    val tapDist = Math.hypot((tx - lastTapX).toDouble(), (ty - lastTapY).toDouble()).toFloat()
                    if (now - lastTapTime < 350 && tapDist < 120f) {
                        // Double tap: dodge roll in tap direction
                        val rx = worldX - player.x
                        val ry = worldY - player.y
                        player.startDodgeRoll(rx, ry)
                        hasMoveTarget = false
                    } else {
                        // Single tap: move to location
                        hasMoveTarget = true
                        targetMoveX = worldX
                        targetMoveY = worldY
                    }

                    lastTapX = tx
                    lastTapY = ty
                    lastTapTime = now

                    // Track this pointer for hold-to-move
                    tapPointerId = pointerId
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until pointerCount) {
                    val pId = event.getPointerId(i)
                    val tx = event.getX(i)
                    val ty = event.getY(i)

                    if (isJoystickEnabled && mJ.isPressed && mJ.pointerId == pId) {
                        mJ.update(tx, ty)
                    } else if (isJoystickEnabled && sJ.isPressed && sJ.pointerId == pId) {
                        sJ.update(tx, ty)
                    } else if (tapPointerId == pId) {
                        // Hold tap: continuous movement toward finger
                        val worldX = tx - (width / 2f) + cameraX
                        val worldY = ty - (height / 2f) + cameraY
                        targetMoveX = worldX
                        targetMoveY = worldY
                        hasMoveTarget = true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)

                if (isJoystickEnabled && mJ.isPressed && mJ.pointerId == pointerId) {
                    // Quick flick on release executes dodge dash
                    if (Math.hypot(mJ.deltaX.toDouble(), mJ.deltaY.toDouble()) > 0.4) {
                        player.startDodgeRoll(mJ.deltaX, mJ.deltaY)
                    }
                    mJ.reset()
                } else if (isJoystickEnabled && sJ.isPressed && sJ.pointerId == pointerId) {
                    sJ.reset()
                }

                if (tapPointerId == pointerId) {
                    tapPointerId = -1
                }
            }
        }
        return true
    }

    /**
     * Screen dimensions bound changes coordinates: configures virtual analog locations dynamically.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Left Joystick at bottom-left corner offset
        moveJoystick = VirtualJoystick(
            220f,
            h - 220f,
            120f,
            50f,
            Color.parseColor("#00FFFF"), // Cyber Cyan knob
            Color.parseColor("#224444")  // Semi-dark container ring
        )

        // Right Joystick at bottom-right corner offset
        shootJoystick = VirtualJoystick(
            w - 220f,
            h - 220f,
            120f,
            50f,
            Color.parseColor("#FF3B3B"), // Cyber red strike knob
            Color.parseColor("#442222")  // Semi-dark attack container
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        gameLoop = GameLoop(holder, this)
        gameLoop?.startLoop()
    }

    private var surfaceWidth = 0f
    private var surfaceHeight = 0f
    private var isResolutionConfigured = false

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!isResolutionConfigured) {
            val scale = PerformanceManager.resolutionScale
            if (scale < 1.0f) {
                isResolutionConfigured = true
                val scaledW = (w * scale).toInt()
                val scaledH = (h * scale).toInt()
                holder.setFixedSize(scaledW, scaledH)
                return
            }
            isResolutionConfigured = true
        }

        surfaceWidth = w.toFloat()
        surfaceHeight = h.toFloat()

        // Re-initialize analog sticks relative to the active resolution canvas size
        moveJoystick = VirtualJoystick(
            220f,
            surfaceHeight - 220f,
            120f,
            50f,
            Color.parseColor("#00FFFF"), // Cyber Cyan knob
            Color.parseColor("#224444")  // Semi-dark container ring
        )

        shootJoystick = VirtualJoystick(
            surfaceWidth - 220f,
            surfaceHeight - 220f,
            120f,
            50f,
            Color.parseColor("#FF3B3B"), // Cyber red strike knob
            Color.parseColor("#442222")  // Semi-dark attack container
        )
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        gameLoop?.stopLoop()
        gameLoop = null
    }

    fun pause() {
        gameLoop?.stopLoop()
        gameLoop = null
    }

    fun resume() {
        if (gameLoop == null && holder.surface.isValid) {
            gameLoop = GameLoop(holder, this)
            gameLoop?.startLoop()
        }
    }
}
