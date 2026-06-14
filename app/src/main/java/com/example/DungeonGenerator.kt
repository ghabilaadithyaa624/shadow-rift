package com.example

import kotlin.random.Random

class DungeonGenerator(
    private val width: Int,
    private val height: Int,
    private val minRoomSize: Int = 6,
    private val maxRoomSize: Int = 11
) {
    // Cell constants
    companion object {
        const val TILE_WALL = 0
        const val TILE_FLOOR = 1
        const val TILE_STAIRS = 3
    }

    data class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
        val centerX: Int get() = x + w / 2
        val centerY: Int get() = y + h / 2
        
        fun intersects(other: Room): Boolean {
            return x < other.x + other.w && x + w > other.x &&
                   y < other.y + other.h && y + h > other.y
        }
    }

    data class GeneratorResult(
        val grid: Array<IntArray>,
        val spawnX: Int,
        val spawnY: Int,
        val stairsX: Int,
        val stairsY: Int,
        val rooms: List<Room>
    )

    /**
     * Generates a procedural dungeon with 5 to 8 connected rooms.
     */
    fun generate(floor: Int): GeneratorResult {
        val grid = Array(height) { IntArray(width) { TILE_WALL } }
        val rooms = mutableListOf<Room>()

        // Generate 5-8 rooms connected by corridors
        val roomCount = Random.nextInt(5, 9) // 5 to 8 rooms inclusive
        var attempts = 0
        
        while (rooms.size < roomCount && attempts < 1000) {
            attempts++
            val rw = Random.nextInt(minRoomSize, maxRoomSize + 1)
            val rh = Random.nextInt(minRoomSize, maxRoomSize + 1)
            val rx = Random.nextInt(2, width - rw - 2)
            val ry = Random.nextInt(2, height - rh - 2)

            val newRoom = Room(rx, ry, rw, rh)

            // Check if it overlaps with any already placed rooms (including 1 tile spacing padding)
            var overlaps = false
            for (room in rooms) {
                val paddedOther = Room(room.x - 1, room.y - 1, room.w + 2, room.h + 2)
                if (newRoom.intersects(paddedOther)) {
                    overlaps = true
                    break
                }
            }

            if (!overlaps) {
                rooms.add(newRoom)
            }
        }

        // If we failed to get at least 5 rooms due to strict padding, retry with direct overlap check
        if (rooms.size < 5) {
            attempts = 0
            while (rooms.size < 5 && attempts < 500) {
                attempts++
                val rw = Random.nextInt(minRoomSize, maxRoomSize + 1)
                val rh = Random.nextInt(minRoomSize, maxRoomSize + 1)
                val rx = Random.nextInt(2, width - rw - 2)
                val ry = Random.nextInt(2, height - rh - 2)
                val newRoom = Room(rx, ry, rw, rh)
                
                var overlaps = false
                for (room in rooms) {
                    if (newRoom.intersects(room)) {
                        overlaps = true
                        break
                    }
                }
                if (!overlaps) {
                    rooms.add(newRoom)
                }
            }
        }

        // Fill all rooms floor cells with TILE_FLOOR
        for (room in rooms) {
            for (y in room.y until (room.y + room.h)) {
                for (x in room.x until (room.x + room.w)) {
                    if (y in 0 until height && x in 0 until width) {
                        grid[y][x] = TILE_FLOOR
                    }
                }
            }
        }

        // Connect room centers sequentially to guarantee connectivity and avoid isolating any rooms
        for (i in 0 until rooms.size - 1) {
            val r1 = rooms[i]
            val r2 = rooms[i + 1]
            createCorridor(grid, r1.centerX, r1.centerY, r2.centerX, r2.centerY)
        }

        // Player spawns in the center of the first generated room
        val firstRoom = rooms.first()
        val spawnX = firstRoom.centerX
        val spawnY = firstRoom.centerY

        // Glowing cyan EXIT portal spawns in the center of the last generated room
        val lastRoom = rooms.last()
        val stairsX = lastRoom.centerX
        val stairsY = lastRoom.centerY
        grid[stairsY][stairsX] = TILE_STAIRS

        return GeneratorResult(grid, spawnX, spawnY, stairsX, stairsY, rooms)
    }

    private fun createCorridor(grid: Array<IntArray>, x1: Int, y1: Int, x2: Int, y2: Int) {
        var x = x1
        var y = y1

        // Horizontal then Vertical, or Vertical then Horizontal
        if (Random.nextBoolean()) {
            while (x != x2) {
                if (y in grid.indices && x in grid[y].indices) {
                    grid[y][x] = TILE_FLOOR
                }
                x += if (x2 > x) 1 else -1
            }
            while (y != y2) {
                if (y in grid.indices && x in grid[y].indices) {
                    grid[y][x] = TILE_FLOOR
                }
                y += if (y2 > y) 1 else -1
            }
        } else {
            while (y != y2) {
                if (y in grid.indices && x in grid[y].indices) {
                    grid[y][x] = TILE_FLOOR
                }
                y += if (y2 > y) 1 else -1
            }
            while (x != x2) {
                if (y in grid.indices && x in grid[y].indices) {
                    grid[y][x] = TILE_FLOOR
                }
                x += if (x2 > x) 1 else -1
            }
        }
    }
}
