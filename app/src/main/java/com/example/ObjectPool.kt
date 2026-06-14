package com.example

/**
 * Interface for pooled objects that need to be reset when recyled.
 */
interface Poolable {
    fun reset()
}

/**
 * A highly optimized, thread-safe generic Object Pool for running the game at 60 FPS without GC spikes.
 */
class ObjectPool<T : Poolable>(
    private val capacity: Int,
    private val factory: () -> T
) {
    private val pool = ArrayList<T>(capacity)
    
    init {
        // Pre-allocate items to avoid runtime allocations
        for (i in 0 until capacity) {
            pool.add(factory())
        }
    }

    /**
     * Obtains an instance from the pool. If empty, creates a new one on the fly (failsafe).
     */
    @Synchronized
    fun obtain(): T {
        return if (pool.isNotEmpty()) {
            pool.removeAt(pool.size - 1)
        } else {
            factory()
        }
    }

    /**
     * Recycles an object back into the pool, resetting its state.
     */
    @Synchronized
    fun recycle(item: T) {
        item.reset()
        if (pool.size < capacity && !pool.contains(item)) {
            pool.add(item)
        }
    }

    /**
     * Returns the number of currently cached objects.
     */
    @Synchronized
    fun getFreeCount(): Int = pool.size
}
