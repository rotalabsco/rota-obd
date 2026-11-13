package io.github.obdlivre.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Canal de bytes com o adaptador (BLE, Bluetooth clássico, Wi-Fi ou simulador).
 * Não conhece o protocolo ELM327: só envia e entrega o que chega.
 */
interface ByteLink {
    suspend fun send(bytes: ByteArray)
    fun setReceiver(receiver: (ByteArray) -> Unit)
    suspend fun close()
}

/**
 * Faz uma pergunta ao ELM327 e espera a resposta completa, que termina no prompt '>'.
 * Uma notificação BLE não é uma resposta: os bytes são acumulados até o '>'.
 */
class ElmExchanger(private val link: ByteLink) {
    private val mutex = Mutex()
    private val lock = Any()
    private val buffer = StringBuilder()
    private var pending: CompletableDeferred<String>? = null

    init {
        link.setReceiver(::onBytes)
    }

    private fun onBytes(bytes: ByteArray) {
        synchronized(lock) {
            buffer.append(String(bytes, Charsets.ISO_8859_1))
            val idx = buffer.indexOf(">")
            if (idx >= 0) {
                val text = buffer.substring(0, idx)
                buffer.delete(0, idx + 1)
                pending?.complete(text)
                pending = null
            }
        }
    }

    /** Envia [command] e devolve o texto antes do '>', ou null se estourar o tempo. */
    suspend fun exchange(command: String, timeoutMs: Long): String? = mutex.withLock {
        val deferred = CompletableDeferred<String>()
        synchronized(lock) {
            buffer.clear()
            pending = deferred
        }
        link.send((command + "\r").toByteArray(Charsets.US_ASCII))
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(lock) { pending = null }
        result
    }
}
