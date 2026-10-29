package com.inkflow.core.util

/** Lista de floats crescente, sem boxing. Usada no caminho quente da escrita. */
class FloatList(initialCapacity: Int = 192) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(8))
    var size: Int = 0
        private set

    operator fun get(i: Int): Float = data[i]

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun add3(a: Float, b: Float, c: Float) {
        if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
        data[size++] = a
        data[size++] = b
        data[size++] = c
    }

    fun add2(a: Float, b: Float) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 2))
        data[size++] = a
        data[size++] = b
    }

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
