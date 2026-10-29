package com.inkflow.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formato binário versionado dos pontos de um traço.
 *
 * v1: [versão: 1 byte][quantidade de pontos: int32 LE][x, y, w: float32 LE] * n
 *  - x, y em pontos de página (1/72 pol.)
 *  - w = multiplicador de espessura (1.0 = espessura nominal), vindo de pressão ou velocidade
 *
 * Mudanças futuras (ex.: compressão delta) devem criar a v2 e manter a leitura da v1.
 */
object StrokeCodec {
    const val VERSION_1: Byte = 1
    const val CURRENT: Byte = VERSION_1

    fun encode(points: FloatArray): ByteArray {
        require(points.size % Stroke.STRIDE == 0) { "Pontos inválidos" }
        val buf = ByteBuffer.allocate(1 + 4 + points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CURRENT)
        buf.putInt(points.size / Stroke.STRIDE)
        for (f in points) buf.putFloat(f)
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (val version = buf.get()) {
            VERSION_1 -> {
                val n = buf.getInt()
                require(n >= 0 && bytes.size >= 5 + n * Stroke.STRIDE * 4) { "Traço corrompido" }
                val out = FloatArray(n * Stroke.STRIDE)
                for (i in out.indices) out[i] = buf.getFloat()
                out
            }
            else -> throw IllegalArgumentException("Versão de traço desconhecida: $version")
        }
    }
}
