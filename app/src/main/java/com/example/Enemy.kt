package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.PriorityQueue

enum class EnemyState {
    IDLE,
    CHASE,
    ATTACK
}

enum class EnemyType {
    MELEE,    // Chases and strikes up close
    RANGED,   // Stays back and shoots plasma balls
    BOSS      // Multiple phases, massive health, spawns on level multiple of 5
}

class Enemy : Poolable {
    var x: Float = 0f
    var y: Float = 0f
    var radius: Float = 20f

    var type: EnemyType = EnemyType.MELEE
    var state: EnemyState = EnemyState.IDLE

    var maxHp: Float = 30f
    var currentHp: Float = 30f
    var speed: Float = 140f
    var damage: Float = 10f

    // Attack mechanics
    var attackCooldown: Float = 0f
    val meleeCooldown: Float = 1.2f
    val rangedCooldown: Float = 2.0f
    val bossCooldown: Float = 0.8f

    // Boss Phase tracking
    var bossPhase: Int = 1
    var screenShakeTriggered: Boolean = false

    // Boss specific behavioral timers
    var bossChargeTimer: Float = 4f
    var bossChargeCooldown: Float = 4.5f
    var bossIsChargingCount: Float = 0f
    var bossChargeVx: Float = 0f
    var bossChargeVy: Float = 0f
    var bossPrepTimer: Float = 0f
    var bossRadialTimer: Float = 1.5f

    // A* Pathfinding optimizations
    var pathUpdateTimer: Float = 0f
    private val pathUpdateInterval: Float = 0.4f // Recalculate pathway every 400ms to save CPU
    private var currentPath = mutableListOf<Pair<Int, Int>>()
    private var currentPathIndex = 0

    var isActive: Boolean = false
    var isDead: Boolean = false
    var hitFlashTimer: Float = 0f

    override fun reset() {
        x = 0f
        y = 0f
        radius = 20f
        type = EnemyType.MELEE
        state = EnemyState.IDLE
        maxHp = 30f
        currentHp = 30f
        speed = 140f
        damage = 10f
        attackCooldown = 0f
        bossPhase = 1
        screenShakeTriggered = false
        bossChargeTimer = 4f
        bossChargeCooldown = 4.5f
        bossIsChargingCount = 0f
        bossChargeVx = 0f
        bossChargeVy = 0f
        bossPrepTimer = 0f
        bossRadialTimer = 1.5f
        pathUpdateTimer = 0f
        currentPath.clear()
        currentPathIndex = 0
        isActive = false
        isDead = false
        hitFlashTimer = 0f
    }

    /**
     * Set up customized properties based on enemy tier and level difficulty scaling.
     */
    fun configure(enemyType: EnemyType, spawnX: Float, spawnY: Float, floor: Int) {
        type = enemyType
        x = spawnX
        y = spawnY
        state = EnemyState.IDLE
        isDead = false
        isActive = true
        bossPhase = 1
        screenShakeTriggered = false

        // Scale difficulty with dungeon progression
        val difficultyMultiplier = 1f + (floor - 1) * 0.15f

        when (enemyType) {
            EnemyType.MELEE -> {
                radius = 20f
                maxHp = 30f * difficultyMultiplier
                speed = 140f + (floor * 5f)
                damage = 10f * difficultyMultiplier
            }
            EnemyType.RANGED -> {
                radius = 18f
                maxHp = 25f * difficultyMultiplier
                speed = 110f + (floor * 3f)
                damage = 12f * difficultyMultiplier
            }
            EnemyType.BOSS -> {
                radius = 60f // 3x standard MELEE enemy size
                maxHp = 350f * difficultyMultiplier
                speed = 100f + (floor * 2f)
                damage = 20f * difficultyMultiplier
                
                // Initialize timers
                bossChargeTimer = 4f
                bossChargeCooldown = 4.5f
                bossIsChargingCount = 0f
                bossChargeVx = 0f
                bossChargeVy = 0f
                bossPrepTimer = 0f
                bossRadialTimer = 1.0f
            }
        }
        currentHp = maxHp
    }

    /**
     * Executes Finite State Machine AI logic, A* path calculations, and attack triggers.
     */
    fun update(
        dtSec: Float,
        player: Player,
        dungeon: Array<IntArray>,
        tileSize: Int,
        bulletPool: ObjectPool<Bullet>,
        activeBullets: MutableList<Bullet>,
        onScreenShakeRequest: () -> Unit
    ) {
        if (!isActive || isDead) return

        if (hitFlashTimer > 0f) {
            hitFlashTimer -= dtSec
        }

        if (attackCooldown > 0) {
            attackCooldown -= dtSec
        }

        // Distance vector to player
        val dxVec = player.x - x
        val dyVec = player.y - y
        val distToPlayer = Math.hypot(dxVec.toDouble(), dyVec.toDouble()).toFloat()

        // Phase check & Boss Mechanics
        if (type == EnemyType.BOSS) {
            val hpPct = currentHp / maxHp
            val newPhase = when {
                hpPct <= 0.33f -> 3
                hpPct <= 0.66f -> 2
                else -> 1
            }
            if (newPhase != bossPhase) {
                bossPhase = newPhase
                onScreenShakeRequest() // Shake screen on phase transition
            }

            // Charge attack processing (Phase 1 & Phase 3)
            if (bossIsChargingCount > 0f) {
                bossIsChargingCount -= dtSec
                val nextX = x + bossChargeVx * dtSec
                val nextY = y + bossChargeVy * dtSec
                val tx = (nextX / tileSize).toInt()
                val ty = (nextY / tileSize).toInt()
                if (ty in dungeon.indices && tx in dungeon[ty].indices && dungeon[ty][tx] != DungeonGenerator.TILE_WALL) {
                    x = nextX
                    y = nextY
                } else {
                    bossIsChargingCount = 0f // Collided with wall, stop charge
                }
                
                // Deal contact damage while charging
                if (distToPlayer < (radius + player.radius) && attackCooldown <= 0f) {
                    player.takeDamage(damage * 1.5f) // High charge damage
                    attackCooldown = meleeCooldown
                }
                return // Bypasses regular pathfinding and FSM movement
            }

            if (bossPrepTimer > 0f) {
                bossPrepTimer -= dtSec
                if (bossPrepTimer <= 0f) {
                    if (distToPlayer > 10f) {
                        bossIsChargingCount = 0.8f // Rush duration
                        bossChargeVx = (dxVec / distToPlayer) * speed * 3.5f
                        bossChargeVy = (dyVec / distToPlayer) * speed * 3.5f
                    }
                }
                return // Frozen in warning phase
            }

            // Recharge charging trigger (Phases 1 and 3)
            if (bossPhase == 1 || bossPhase == 3) {
                bossChargeTimer -= dtSec
                if (bossChargeTimer <= 0f) {
                    bossChargeTimer = bossChargeCooldown
                    bossPrepTimer = 0.6f // Charge prep warning
                }
            }

            // Radial spread attack trigger (Phases 2 and 3)
            if (bossPhase == 2 || bossPhase == 3) {
                bossRadialTimer -= dtSec
                if (bossRadialTimer <= 0f) {
                    bossRadialTimer = if (bossPhase == 2) 1.5f else 2.0f
                    val bCount = if (bossPhase == 2) 16 else 12
                    fireSpreadAttack(tileSizeNormal = tileSize, bulletPool, activeBullets, bulletCount = bCount)
                }
            }
        }

        // 3-state FSM Logic: IDLE -> CHASE -> ATTACK
        val senseRange = if (type == EnemyType.BOSS) 1000f else 600f
        val attackRange = when (type) {
            EnemyType.MELEE -> 50f
            EnemyType.RANGED -> 350f
            EnemyType.BOSS -> if (bossPhase == 2) 400f else 150f
        }

        when (state) {
            EnemyState.IDLE -> {
                if (distToPlayer < senseRange) {
                    state = EnemyState.CHASE
                }
            }
            EnemyState.CHASE -> {
                if (distToPlayer > senseRange * 1.3f) {
                    state = EnemyState.IDLE
                    currentPath.clear()
                } else if (distToPlayer <= attackRange) {
                    state = EnemyState.ATTACK
                } else {
                    // Update and navigate via A*
                    pathUpdateTimer += dtSec
                    if (pathUpdateTimer >= pathUpdateInterval || currentPath.isEmpty()) {
                        pathUpdateTimer = 0f
                        calculateAStarPath(
                            (x / tileSize).toInt(), (y / tileSize).toInt(),
                            (player.x / tileSize).toInt(), (player.y / tileSize).toInt(),
                            dungeon
                        )
                    }
                    followPath(dtSec, tileSize)
                }
            }
            EnemyState.ATTACK -> {
                if (distToPlayer > attackRange * 1.15f) {
                    state = EnemyState.CHASE
                } else {
                    executeAttack(player, distToPlayer, dxVec, dyVec, bulletPool, activeBullets)
                }
            }
        }
    }

    /**
     * Follows the A* node path, moving smoothly between grid waypoints.
     */
    private fun followPath(dtSec: Float, tileSize: Int) {
        if (currentPath.isEmpty()) return

        if (currentPathIndex >= currentPath.size) {
            currentPathIndex = currentPath.size - 1
        }

        val targetTile = currentPath[currentPathIndex]
        val targetX = (targetTile.first * tileSize) + (tileSize / 2f)
        val targetY = (targetTile.second * tileSize) + (tileSize / 2f)

        val dx = targetX - x
        val dy = targetY - y
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (dist < 15f) {
            // Arrived at current waypoint, progress path target index
            if (currentPathIndex < currentPath.size - 1) {
                currentPathIndex++
            }
        } else {
            // Move toward waypoint
            val moveStep = speed * dtSec
            if (moveStep >= dist) {
                x = targetX
                y = targetY
            } else {
                x += (dx / dist) * moveStep
                y += (dy / dist) * moveStep
            }
        }
    }

    private fun executeAttack(
        player: Player,
        dist: Float,
        dx: Float,
        dy: Float,
        bulletPool: ObjectPool<Bullet>,
        activeBullets: MutableList<Bullet>
    ) {
        if (attackCooldown > 0) return

        when (type) {
            EnemyType.MELEE -> {
                // Instantly hit player within close physical bounds
                player.takeDamage(damage)
                attackCooldown = meleeCooldown
            }
            EnemyType.RANGED -> {
                // Fire plasma ball projectile towards player
                val bullet = bulletPool.obtain()
                val ang = Math.atan2(dy.toDouble(), dx.toDouble())
                bullet.x = x
                bullet.y = y
                bullet.vx = Math.cos(ang).toFloat() * 450f
                bullet.vy = Math.sin(ang).toFloat() * 450f
                bullet.damage = damage
                bullet.isPlayerOwned = false
                bullet.isActive = true
                bullet.rangeLeft = 500f
                activeBullets.add(bullet)
                attackCooldown = rangedCooldown
            }
            EnemyType.BOSS -> {
                player.takeDamage(damage * 0.5f)
                attackCooldown = bossCooldown
            }
        }
    }

    private fun fireSpreadAttack(
        tileSizeNormal: Int,
        bulletPool: ObjectPool<Bullet>,
        activeBullets: MutableList<Bullet>,
        bulletCount: Int
    ) {
        val angleIncrement = (2 * Math.PI) / bulletCount
        for (i in 0 until bulletCount) {
            val bullet = bulletPool.obtain()
            val ang = i * angleIncrement
            bullet.x = x
            bullet.y = y
            bullet.vx = Math.cos(ang).toFloat() * 380f
            bullet.vy = Math.sin(ang).toFloat() * 380f
            bullet.damage = damage * 0.8f
            bullet.isPlayerOwned = false
            bullet.isActive = true
            bullet.rangeLeft = 600f
            activeBullets.add(bullet)
        }
    }

    /**
     * Grid-based standard A* Pathfinding.
     */
    private fun calculateAStarPath(startX: Int, startY: Int, endX: Int, endY: Int, dungeon: Array<IntArray>) {
        val rows = dungeon.size
        val cols = dungeon[0].size

        // Failsafe bounds check
        if (startX !in 0 until cols || startY !in 0 until rows || endX !in 0 until cols || endY !in 0 until rows) {
            return
        }

        class Node(val x: Int, val y: Int, var g: Int, var h: Int, val parent: Node?) : Comparable<Node> {
            val f: Int get() = g + h
            override fun compareTo(other: Node): Int = this.f.compareTo(other.f)
        }

        val openSet = PriorityQueue<Node>()
        val closedSet = HashSet<Pair<Int, Int>>()

        val startNode = Node(startX, startY, 0, Math.abs(endX - startX) + Math.abs(endY - startY), null)
        openSet.add(startNode)

        val directions = arrayOf(
            Pair(0, -1), Pair(0, 1), Pair(-1, 0), Pair(1, 0)
        )

        var foundNode: Node? = null

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break

            if (current.x == endX && current.y == endY) {
                foundNode = current
                break
            }

            closedSet.add(Pair(current.x, current.y))

            for (dir in directions) {
                val nx = current.x + dir.first
                val ny = current.y + dir.second

                if (nx in 0 until cols && ny in 0 until rows) {
                    if (dungeon[ny][nx] == DungeonGenerator.TILE_WALL) continue
                    if (closedSet.contains(Pair(nx, ny))) continue

                    val moveCost = 10
                    val newG = current.g + moveCost
                    val h = (Math.abs(endX - nx) + Math.abs(endY - ny)) * 10

                    // Check if open set already contains this tile at a lower or equal G cost
                    val matchInOpen = openSet.find { it.x == nx && it.y == ny }
                    if (matchInOpen == null || matchInOpen.g > newG) {
                        if (matchInOpen != null) {
                            openSet.remove(matchInOpen)
                        }
                        openSet.add(Node(nx, ny, newG, h, current))
                    }
                }
            }
        }

        // Backtrack path nodes
        currentPath.clear()
        var trace = foundNode
        while (trace != null) {
            currentPath.add(0, Pair(trace.x, trace.y))
            trace = trace.parent
        }
        currentPathIndex = if (currentPath.isNotEmpty()) 0 else -1
    }

    /**
     * Inflict damage on enemies and returns true if they died.
     */
    fun takeDamage(amount: Float): Boolean {
        if (!isActive || isDead) return false
        currentHp = (currentHp - amount).coerceAtLeast(0f)
        hitFlashTimer = 0.20f // Red flash for 200ms
        if (currentHp <= 0) {
            isDead = true
            isActive = false
            return true
        }
        return false
    }

    /**
     * Renders Enemy visually.
     */
    fun draw(canvas: Canvas, paint: Paint) {
        if (!isActive || isDead) return

        // Drop shadow
        paint.color = Color.BLACK
        paint.alpha = 110
        if (type == EnemyType.BOSS) {
            drawOctagon(canvas, x, y + radius * 0.2f, radius, paint)
        } else {
            canvas.drawCircle(x, y + radius * 0.3f, radius * 0.9f, paint)
        }
        paint.alpha = 255

        // Core fill based on hostile classification
        if (hitFlashTimer > 0f) {
            paint.color = Color.RED
            if (type == EnemyType.BOSS) {
                drawOctagon(canvas, x, y, radius, paint)
            } else {
                canvas.drawCircle(x, y, radius, paint)
            }
        } else {
            if (type == EnemyType.BOSS) {
                // Draw Boss regular octagon layers
                paint.color = if (bossPhase == 2) Color.parseColor("#FF003C") else if (bossPhase == 3) Color.parseColor("#9B0000") else Color.parseColor("#E61C1C")
                drawOctagon(canvas, x, y, radius, paint)

                // Tactical inner octagon
                paint.color = Color.parseColor("#0A0A0A")
                drawOctagon(canvas, x, y, radius * 0.6f, paint)

                // Glowing core octagon
                paint.color = if (bossPhase == 3) Color.parseColor("#00FFFF") else if (bossPhase == 2) Color.parseColor("#FFAA00") else Color.parseColor("#FF2222")
                drawOctagon(canvas, x, y, radius * 0.35f, paint)

                // Dynamic counter-pulsing rifting shield rings around the boss on HIGH-END devices
                if (PerformanceManager.selectedTier == MemoryTier.HIGH) {
                    val originalStyle = paint.style
                    val originalWidth = paint.strokeWidth
                    paint.style = Paint.Style.STROKE
                    
                    val pulseTimer = (System.currentTimeMillis() % 4000) / 4000f
                    val rPulseOffset = 0.15f * Math.sin(pulseTimer * 2 * Math.PI).toFloat()
                    
                    // Pulsing core outer glow
                    paint.strokeWidth = 3f
                    paint.color = if (bossPhase == 3) Color.parseColor("#00FFFF") else if (bossPhase == 2) Color.parseColor("#FFAA00") else Color.parseColor("#FF003C")
                    paint.alpha = 140
                    drawOctagon(canvas, x, y, radius * (1.1f + rPulseOffset), paint)
                    
                    // Concentric counter outer boundary
                    paint.strokeWidth = 1.5f
                    paint.color = Color.parseColor("#00FFFF")
                    paint.alpha = 70
                    drawOctagon(canvas, x, y, radius * (1.3f - rPulseOffset), paint)
                    
                    paint.style = originalStyle
                    paint.strokeWidth = originalWidth
                    paint.alpha = 255
                }
                
                // If preparing to charge, draw warning outline glow
                if (bossPrepTimer > 0f) {
                    val originalStyle = paint.style
                    val originalWidth = paint.strokeWidth
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 6f
                    paint.color = Color.YELLOW
                    drawOctagon(canvas, x, y, radius + 10f * (bossPrepTimer / 0.6f), paint)
                    paint.style = originalStyle
                    paint.strokeWidth = originalWidth
                }
            } else {
                when (type) {
                    EnemyType.MELEE -> {
                        paint.color = Color.parseColor("#FF2222")
                    }
                    EnemyType.RANGED -> {
                        paint.color = Color.parseColor("#BF55EC")
                    }
                    EnemyType.BOSS -> {
                        paint.color = Color.RED
                    }
                }
                canvas.drawCircle(x, y, radius, paint)

                // Draw tactical inner ring core
                paint.color = Color.parseColor("#0A0A0A")
                canvas.drawCircle(x, y, radius * 0.6f, paint)

                // Draw core glowing energy cell
                when (type) {
                    EnemyType.MELEE -> paint.color = Color.parseColor("#FF3B3B")
                    EnemyType.RANGED -> paint.color = Color.parseColor("#D2527F")
                    else -> paint.color = Color.RED
                }
                canvas.drawCircle(x, y, radius * 0.35f, paint)
            }
        }

        // Simple health slider bar above their heads for damaged enemies or bosses
        if (currentHp < maxHp && type != EnemyType.BOSS) {
            val barW = radius * 1.6f
            val barH = 6f
            val bx = x - barW / 2
            val by = y - radius * 1.3f

            // Red background
            paint.color = Color.DKGRAY
            canvas.drawRect(bx, by, bx + barW, by + barH, paint)

            // Dynamic green/health layer
            val healthPercent = (currentHp / maxHp).coerceIn(0f, 1f)
            paint.color = Color.GREEN
            canvas.drawRect(bx, by, bx + (barW * healthPercent), by + barH, paint)
        }
    }

    private fun drawOctagon(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val path = android.graphics.Path()
        val angleStep = Math.PI / 4.0
        for (i in 0 until 8) {
            val angle = i * angleStep
            val px = cx + (r * Math.cos(angle)).toFloat()
            val py = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
