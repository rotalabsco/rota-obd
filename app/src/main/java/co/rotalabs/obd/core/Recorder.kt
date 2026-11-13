package co.rotalabs.obd.core

import java.io.BufferedWriter
import java.io.File

/**
 * Grava cada observação como uma linha JSON (NDJSON). Só leitura bem-sucedida ou erro explícito
 * vira linha: falha de leitura nunca vira zero.
 */
interface Recorder {
    fun record(kind: String, fields: Map<String, Any?>)
    fun close() {}
}

object NoopRecorder : Recorder {
    override fun record(kind: String, fields: Map<String, Any?>) {}
}

class NdjsonRecorder(val file: File) : Recorder {
    private val start = System.nanoTime()
    private val writer: BufferedWriter = file.bufferedWriter()

    @Synchronized
    override fun record(kind: String, fields: Map<String, Any?>) {
        val all = LinkedHashMap<String, Any?>()
        all["t_ms"] = (System.nanoTime() - start) / 1_000_000
        all["ts"] = System.currentTimeMillis()
        all["kind"] = kind
        all.putAll(fields)
        writer.write(Json.obj(all))
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() = writer.close()
}

/** JSON mínimo, sem dependências. */
object Json {
    fun obj(map: Map<String, Any?>): String =
        map.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else trim(v)
        is Float -> value(v.toDouble())
        is Number -> v.toString()
        is Map<*, *> -> obj(v.entries.associate { it.key.toString() to it.value })
        is Collection<*> -> v.joinToString(",", "[", "]") { value(it) }
        is IntArray -> v.joinToString(",", "[", "]")
        else -> str(v.toString())
    }

    private fun trim(d: Double): String {
        val s = "%.4f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
