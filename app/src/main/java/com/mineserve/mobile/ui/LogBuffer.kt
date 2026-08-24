package com.mineserve.mobile.ui

/** Bounded O(1) append buffer; copying happens only when a UI batch is flushed. */
internal class LogBuffer<T>(private val capacity: Int) {
    private val values = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(value: T) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
    }

    @Synchronized
    fun snapshotAndClear(): List<T> {
        val result = values.toList()
        values.clear()
        return result
    }

    @Synchronized
    fun snapshot(): List<T> = values.toList()

    @Synchronized
    fun last(count: Int): List<T> = values.asSequence()
        .drop((values.size - count).coerceAtLeast(0))
        .toList()

    @Synchronized
    fun replace(newValues: List<T>) {
        values.clear()
        newValues.takeLast(capacity).forEach(values::addLast)
    }

    @Synchronized
    fun clear() {
        values.clear()
    }
}
