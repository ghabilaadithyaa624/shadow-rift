package com.example

import kotlin.random.Random

class DungeonGenerator(
    private val width: Int,
    private val height: Int,
    private val minRoomSize: Int = 8,
    private val maxRoomSize: Int = 16
) {
    // Cell constants
    companion object {
        const val TILE_WALL = 0
        const val TILE_FLOOR = 1
        const val TILE_STAIRS = 3
    }

    private val grid = Array(height) { IntArray(width) { TILE_WALL } }
    private val leafList = mutableListOf<BSPNode>()
    private val rooms = mutableListOf<Room>()

    inner class BSPNode(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    ) {
        var left: BSPNode? = null
        var right: BSPNode? = null
        var room: Room? = null

        fun split(): Boolean {
            if (left != null || right != null) return false // Already split

            // Determine split direction
            var splitH = Random.nextBoolean()
            if (w > h && w / h >= 1.25) {
                splitH = false
            } else if (h > w && h / w >= 1.25) {
                splitH = true
            }

            val max = (if (splitH) h else w) - minRoomSize
            if (max <= minRoomSize) return false // Too small to split

            val splitPoint = Random.nextInt(minRoomSize, max)

            if (splitH) {
                left = BSPNode(x, y, w, splitPoint)
                right = BSPNode(x, y + splitPoint, w, h - splitPoint)
            } else {
                left = BSPNode(x, y, splitPoint, h)
                right = BSPNode(x + splitPoint, y, w - splitPoint, h)
            }
            return true
        }

        fun getRooms(): List<Room> {
            val list = mutableListOf<Room>()
            room?.let { list.add(it) }
            left?.getRooms()?.let { list.addAll(it) }
            right?.getRooms()?.let { list.addAll(it) }
            return list
        }
    }

    data class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
        val centerX: Int get() = x + w / 2
        val centerY: Int get() = y + h / 2
    }

    /**
     * Main dungeon generator entry point.
     * Returns a 2D grid containing FLOOR, WALLS, and level features.
     */
    fun generate(floor: Int): GeneratorResult {
        // Clear grid and rooms
        for (y in 0 until height) {
            for (x in 0 until width) {
                grid[y][x] = TILE_WALL
            }
        }
        leafList.clear()
        rooms.clear()

        val root = BSPNode(2, 2, width - 4, height - 4)
        val stack = mutableListOf(root)

        // Recursively split rooms
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node.w > maxRoomSize || node.h > maxRoomSize || Random.nextDouble() < 0.8) {
                if (node.split()) {
                    node.left?.let { stack.add(it) }
                    node.right?.let { stack.add(it) }
                } else {
                    leafList.add(node)
                }
            } else {
                leafList.add(node)
            }
        }

        // Generate rooms inside leaf nodes
        for (node in leafList) {
            // Give rooms some padding from node limits
            val rw = Random.nextInt(minRoomSize - 2, node.w - 1).coerceAtLeast(4)
            val rh = Random.nextInt(minRoomSize - 2, node.h - 1).coerceAtLeast(4)
            val rx = node.x + Random.nextInt(0, node.w - rw)
            val ry = node.y + Random.nextInt(0, node.h - rh)

            val room = Room(rx, ry, rw, rh)
            node.room = room
            rooms.add(room)

            // Fill room cells with floor tiles
            for (y in ry until (ry + rh)) {
                for (x in rx until (rx + rw)) {
                    if (y in 0 until height && x in 0 until width) {
                        grid[y][x] = TILE_FLOOR
                    }
                }
            }
        }

        // Interconnect sibling rooms with corridors
        connectNodes(root)

        // Identify spawners and exits
        if (rooms.isEmpty()) {
            // Fallback emergency room if BSP leaves are empty
            val defaultRoom = Room(width / 2 - 5, height / 2 - 5, 10, 10)
            rooms.add(defaultRoom)
            for (y in defaultRoom.y until (defaultRoom.y + defaultRoom.h)) {
                for (x in defaultRoom.x until (defaultRoom.x + defaultRoom.w)) {
                    grid[y][x] = TILE_FLOOR
                }
            }
        }

        // Player spawns in the first room center
        val startRoom = rooms.first()
        val spawnX = startRoom.centerX
        val spawnY = startRoom.centerY

        // Stairs spawn in the furthest room from the starting room to promote exploration
        var stairsRoom = rooms.last()
        var maxDistance = 0f
        for (room in rooms) {
            val dist = Math.hypot((room.centerX - spawnX).toDouble(), (room.centerY - spawnY).toDouble()).toFloat()
            if (dist > maxDistance) {
                maxDistance = dist
                stairsRoom = room
            }
        }

        val stairsX = stairsRoom.centerX
        val stairsY = stairsRoom.centerY
        grid[stairsY][stairsX] = TILE_STAIRS

        return GeneratorResult(grid, spawnX, spawnY, stairsX, stairsY)
    }

    private fun connectNodes(node: BSPNode) {
        val left = node.left
        val right = node.right
        if (left == null || right == null) return

        // Recurse down the tree first
        connectNodes(left)
        connectNodes(right)

        // Get representative rooms from left and right children
        val leftRooms = left.getRooms()
        val rightRooms = right.getRooms()

        if (leftRooms.isNotEmpty() && rightRooms.isNotEmpty()) {
            val r1 = leftRooms.random()
            val r2 = rightRooms.random()
            createCorridor(r1.centerX, r1.centerY, r2.centerX, r2.centerY)
        }
    }

    private fun createCorridor(x1: Int, y1: Int, x2: Int, y2: Int) {
        // Draw L-shaped corridors (Horizontal first then Vertical, or vice versa)
        var x = x1
        var y = y1

        if (Random.nextBoolean()) {
            while (x != x2) {
                grid[y][x] = TILE_FLOOR
                x += if (x2 > x) 1 else -1
            }
            while (y != y2) {
                grid[y][x] = TILE_FLOOR
                y += if (y2 > y) 1 else -1
            }
        } else {
            while (y != y2) {
                grid[y][x] = TILE_FLOOR
                y += if (y2 > y) 1 else -1
            }
            while (x != x2) {
                grid[y][x] = TILE_FLOOR
                x += if (x2 > x) 1 else -1
            }
        }
    }

    data class GeneratorResult(
        val grid: Array<IntArray>,
        val spawnX: Int,
        val spawnY: Int,
        val stairsX: Int,
        val stairsY: Int
    )
}
