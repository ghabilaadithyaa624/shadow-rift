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
    }

    var gameListener: GameListener? = null

    // Run-time Game State
    private var gameLoop: GameLoop? = null
    var gameFloor = 1
    var sessionKills = 0
    var sessionEssence = 0
    
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

    // LRU Sprite/Texture Pre-Render Image Cache (64MB Max Target)
    private val maxCacheBytes = 64 * 1024 * 1024 // 64 MegaBytes Limit
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
        canvasFloor.drawColor(Color.parseColor("#0F0F0F")) // Deep slate
        val tilePaint = Paint().apply {
            color = Color.parseColor("#181818")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvasFloor.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), tilePaint)
        // Add random cyber dot details for a dark-dungeon matrix grid style
        tilePaint.color = Color.parseColor("#00FFFF")
        tilePaint.alpha = 50
        canvasFloor.drawCircle(tileSize / 2f, tileSize / 2f, 2f, tilePaint)
        spriteCache.put("tile_floor", floorTile)

        // Wall tile pre-rendered texture
        val wallTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvasWall = Canvas(wallTile)
        canvasWall.drawColor(Color.parseColor("#030303"))
        val wallPaint = Paint().apply {
            color = Color.parseColor("#FF3B3B") // Neon red glowing borders
            style = Paint.Style.STROKE
            strokeWidth = 4f
            alpha = 150
        }
        canvasWall.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), wallPaint)
        // Draw inner brick segments
        wallPaint.strokeWidth = 2f
        canvasWall.drawLine(0f, tileSize / 2f, tileSize.toFloat(), tileSize / 2f, wallPaint)
        canvasWall.drawLine(tileSize / 2f, 0f, tileSize / 2f, tileSize / 2f, wallPaint)
        canvasWall.drawLine(tileSize * 0.75f, tileSize / 2f, tileSize * 0.75f, tileSize.toFloat(), wallPaint)
        spriteCache.put("tile_wall", wallTile)

        // Stairs tile pre-rendered texture
        val stairsTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvasStairs = Canvas(stairsTile)
        canvasStairs.drawColor(Color.parseColor("#0A0A0A"))
        val stairsPaint = Paint().apply {
            color = Color.parseColor("#00FFFF")
            style = Paint.Style.FILL
        }
        // Draw a glowing pyramid dimensional rift portal
        stairsPaint.alpha = 130
        canvasStairs.drawRect(20f, 20f, tileSize - 20f, tileSize - 20f, stairsPaint)
        stairsPaint.color = Color.WHITE
        stairsPaint.alpha = 255
        canvasStairs.drawRect(30f, 30f, tileSize - 30f, tileSize - 30f, stairsPaint)
        spriteCache.put("tile_stairs", stairsTile)
    }

    /**
     * Applies meta progression stats to Player and sets up the chosen floor map structure.
     */
    fun startNewGame(unlockedSkills: List<SkillNode>) {
        gameFloor = 1
        sessionKills = 0
        sessionEssence = 0
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
        val count = 8 + (floor * 2)

        if (floor % 5 == 0) {
            // Level is multiple of 5: Spawns the Phase-Based Rift Overlord Boss
            val boss = enemyPool.obtain()
            val bx = (dungeonResult.stairsX * tileSize) + (tileSize / 2f)
            val by = (dungeonResult.stairsY * tileSize) + (tileSize / 2f)
            boss.configure(EnemyType.BOSS, bx, by, floor)
            activeEnemies.add(boss)

            showTemporaryMessage("RIFT RULER AWAKENS! DANGER!", 4.0f)
        } else {
            // Spawn normal cyber combat units
            var spawned = 0
            while (spawned < count) {
                val rx = (2 until dungeonWidth - 2).random()
                val ry = (2 until dungeonHeight - 2).random()

                // Ensure selected tile is floor and isn't too close to player start point
                if (dungeonResult.grid[ry][rx] == DungeonGenerator.TILE_FLOOR) {
                    val distToSpawn = Math.hypot((rx - dungeonResult.spawnX).toDouble(), (ry - dungeonResult.spawnY).toDouble())
                    if (distToSpawn > 10) {
                        val enemy = enemyPool.obtain()
                        val type = if (Math.random() < 0.4) EnemyType.RANGED else EnemyType.MELEE
                        enemy.configure(
                            type,
                            (rx * tileSize) + tileSize / 2f,
                            (ry * tileSize) + tileSize / 2f,
                            floor
                        )
                        activeEnemies.add(enemy)
                        spawned++
                    }
                }
            }
        }
    }

    fun triggerScreenShake(duration: Float, intensity: Float) {
        shakeDuration = duration
        shakeIntensity = intensity
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
                                val displayDamage = if (enemy.type == EnemyType.BOSS) bullet.damage.toInt() else (enemy.maxHp / 3f).toInt()
                                activeDamageNumbers.add(
                                    DamageNumber(
                                        enemy.x + (Math.random() * 20 - 10).toFloat(),
                                        enemy.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                        "+$displayDamage",
                                        Color.RED
                                    )
                                )

                                if (died) {
                                    sessionKills++
                                    triggerKillHaptics()
                                    // Spawns items
                                    val loot = lootPool.obtain()
                                    loot.configure(enemy.x, enemy.y, Math.random())
                                    activeLoot.add(loot)
                                    
                                    if (enemy.type == EnemyType.BOSS) {
                                        triggerScreenShake(1.5f, 25f)
                                        showTemporaryMessage("RIFT RULER VANQUISHED!", 3.5f)
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
                            activeDamageNumbers.add(
                                DamageNumber(
                                    player.x + (Math.random() * 20 - 10).toFloat(),
                                    player.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                    "-${bullet.damage.toInt()}",
                                    Color.RED
                                )
                            )

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
                            activeDamageNumbers.add(
                                DamageNumber(
                                    player.x + (Math.random() * 30 - 15).toFloat(),
                                    player.y - 12f + (Math.random() * 20 - 10).toFloat(),
                                    "-1",
                                    Color.RED
                                )
                            )
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

        // Update Loot Drops Collection
        val lootIterator = activeLoot.iterator()
        while (lootIterator.hasNext()) {
            val loot = lootIterator.next()
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
        val sparkVolume = 24
        for (i in 0 until sparkVolume) {
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
            width, height, dungeonResult.grid, tileSize, activeEnemies
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

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
