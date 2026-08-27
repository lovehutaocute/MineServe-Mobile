package com.mineserve.mobile.ui

// 性能修改理由：只在批量刷新时创建不可变快照，供 Compose 安全跳过未变化的日志列表。
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Bounded O(1) append buffer; copying happens only when a UI batch is flushed. */
internal class LogBuffer<T>(private val capacity: Int) {
    private val values = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(value: T) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
    }

    @Synchronized
    fun snapshotAndClear(): ImmutableList<T> {
        val result = values.toImmutableList()
        values.clear()
        return result
    }

    @Synchronized
    fun snapshot(): ImmutableList<T> = values.toImmutableList()

    @Synchronized
    fun last(count: Int): ImmutableList<T> = values.asSequence()
        .drop((values.size - count).coerceAtLeast(0))
        .toImmutableList()

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
