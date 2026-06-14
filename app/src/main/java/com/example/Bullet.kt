package com.example

/**
 * Bullet projectile representing both player and enemy shots. Optimized with Object Pooling.
 */
class Bullet : Poolable {
    var x: Float = 0f
    var y: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var radius: Float = 8f
    var damage: Float = 10f
    var isPlayerOwned: Boolean = true
    var isCrit: Boolean = false
    var isActive: Boolean = false
    
    // Distance or lifetime tracking to prevent infinite bullets
    var rangeLeft: Float = 600f

    override fun reset() {
        x = 0f
        y = 0f
        vx = 0f
        vy = 0f
        radius = 8f
        damage = 10f
        isPlayerOwned = true
        isCrit = false
        isActive = false
        rangeLeft = 600f
    }
    
    fun update(dtSec: Float) {
        val dx = vx * dtSec
        val dy = vy * dtSec
        x += dx
        y += dy
        rangeLeft -= Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (rangeLeft <= 0) {
            isActive = false
        }
    }
}
