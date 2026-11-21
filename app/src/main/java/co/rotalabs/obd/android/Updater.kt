package co.rotalabs.obd.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import co.rotalabs.obd.core.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca, baixa e instala a "Versão de teste" publicada no GitHub.
 *
 * A instalação passa pelo instalador do sistema: o Android sempre pede para o usuário tocar em
 * "Instalar". Só funciona se a versão nova tiver a mesma assinatura da instalada.
 */
class Updater(private val context: Context) {

    /** Build da versão instalada (versionCode, que o CI preenche com o número do build). */
    val installedBuild: Long
        get() {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }

    val installedName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    suspend fun fetchLatest(): UpdateCheck.Release? = withContext(Dispatchers.IO) {
        val conn = open(UpdateCheck.RELEASE_API)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub respondeu ${conn.responseCode}")
            UpdateCheck.parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Baixa o APK para o cache do app; [progress] recebe de 0 a 1 quando o tamanho é conhecido. */
    suspend fun download(release: UpdateCheck.Release, progress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "atualizacao").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "rota-obd-${release.build}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Download respondeu ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(total?.let { (done.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            if (release.sizeBytes != null && file.length() != release.sizeBytes) {
                throw IllegalStateException("Download incompleto (${file.length()} de ${release.sizeBytes} bytes)")
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    /** O Android exige que o usuário libere "instalar apps desconhecidos" para este app uma vez. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Entrega o APK ao instalador do sistema; o resultado chega em [InstallResultReceiver]. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Rota.OBD")
        }
}

/** Recebe o andamento da instalação e abre a tela de confirmação do sistema. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            lastError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "falha $status"
            listener?.invoke(lastError!!)
        }
    }

    companion object {
        /** Mensagem da última falha, para a tela mostrar. */
        @Volatile var lastError: String? = null
        @Volatile var listener: ((String) -> Unit)? = null
    }
}
