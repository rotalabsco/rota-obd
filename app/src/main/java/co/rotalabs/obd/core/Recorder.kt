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

/** Repassa cada linha para [inner] e avisa [onRecord] (ex.: o painel flutuante pega os últimos valores). */
class ObservingRecorder(private val inner: Recorder, private val onRecord: (String, Map<String, Any?>) -> Unit) : Recorder {
    override fun record(kind: String, fields: Map<String, Any?>) {
        inner.record(kind, fields)
        runCatching { onRecord(kind, fields) }
    }

    override fun close() = inner.close()
}

object NoopRecorder : Recorder {
    override fun record(kind: String, fields: Map<String, Any?>) {}
}

/**
 * A escrita no arquivo roda numa thread própria, fora da tela: antes cada leitura escrevia e
 * descarregava o arquivo na thread de interface. A ordem das linhas é mantida (fila única) e o
 * arquivo é descarregado sempre que a fila esvazia, então no máximo as últimas linhas ficam no buffer.
 */
class NdjsonRecorder(val file: File) : Recorder {
    private val start = System.nanoTime()
    private val writer: BufferedWriter = file.bufferedWriter()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "gravacao").apply { isDaemon = true } }
    private val pending = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var closed = false

    @Synchronized
    override fun record(kind: String, fields: Map<String, Any?>) {
        if (closed) return
        val all = LinkedHashMap<String, Any?>()
        all["t_ms"] = (System.nanoTime() - start) / 1_000_000
        all["ts"] = System.currentTimeMillis()
        all["kind"] = kind
        all.putAll(fields)
        val line = Json.obj(all)
        pending.incrementAndGet()
        io.execute {
            runCatching {
                writer.write(line)
                writer.newLine()
                if (pending.decrementAndGet() == 0) writer.flush()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        io.execute { runCatching { writer.close() } }
        io.shutdown()
        io.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
    }
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
