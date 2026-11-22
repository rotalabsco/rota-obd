package co.rotalabs.obd.core

/**
 * Atualização pelo próprio app, a partir da "Versão de teste" publicada pelo GitHub Actions.
 *
 * O workflow recria a release `latest` a cada push na main com o título "Versão de teste #N", em que
 * N é o número do build, o mesmo usado como versionCode. Basta comparar os dois.
 */
object UpdateCheck {
    const val RELEASE_API = "https://api.github.com/repos/rotalabsco/rota-obd/releases/tags/latest"
    const val ASSET_NAME = "rota-obd-debug.apk"

    data class Release(val build: Int, val apkUrl: String, val sizeBytes: Long?, val publishedAt: String?, val notes: String? = null)

    /** Linhas "- ..." das notas da release (o que mudou), sem o resto do texto. */
    fun changes(notes: String?): List<String> =
        notes.orEmpty().lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }

    private val buildNumber = Regex("#(\\d+)")

    /** Lê a resposta da API de releases; null se não houver número de build ou APK. */
    fun parse(json: String): Release? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val title = root["name"] as? String ?: return null
        val build = buildNumber.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val asset = (root["assets"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == ASSET_NAME } ?: return null
        val url = asset["browser_download_url"] as? String ?: return null
        if (!url.startsWith("https://")) return null
        val size = (asset["size"] as? Double)?.toLong()
        return Release(build, url, size, root["published_at"] as? String, root["body"] as? String)
    }

    fun isNewer(release: Release, installedBuild: Long): Boolean = release.build > installedBuild
}
