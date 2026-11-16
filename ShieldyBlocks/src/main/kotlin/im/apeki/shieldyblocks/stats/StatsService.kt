package im.apeki.shieldyblocks.stats

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import org.bukkit.scheduler.BukkitTask
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class StatsService(
    private val plugin: ShieldyBlocksPlugin,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5L))
        .build()

    private val serverId = loadServerId()
    private var task: BukkitTask? = null

    fun start() {
        stop()
        submitSnapshot()
        task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { submitSnapshot() },
            24000L,
            24000L,
        )
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun submitSnapshot() {
        try {
            val payload = StatsPayload(
                productId = PRODUCT_ID,
                serverId = serverId,
                sessionId = plugin.sessionId,
                serverVersion = resolveServerVersion(),
                productVersion = plugin.description.version,
                jvmVersion = resolveJvmVersion(),
                serverSoftware = resolveServerSoftware(),
                onlineMode = plugin.server.onlineMode,
                currentOnline = plugin.server.onlinePlayers.size,
                operatingSystem = System.getProperty("os.name").orEmpty(),
                cpuArchitecture = normalizeArchitecture(System.getProperty("os.arch").orEmpty()),
                cpuCores = Runtime.getRuntime().availableProcessors(),
                ramMb = Runtime.getRuntime().maxMemory().toMegabytes(),
            )

            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable { send(payload) },
            )
        } catch (_: Throwable) {
            // Статистика не должна ломать плагин.
        }
    }

    private fun send(payload: StatsPayload) {
        try {
            val request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toJson()))
                .build()

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally { null }
        } catch (_: Throwable) {
            // Статистика не должна ломать плагин.
        }
    }

    private fun resolveServerVersion(): String {
        val candidates = listOf(
            plugin.server.bukkitVersion,
            plugin.server.version,
        )
        candidates.forEach { candidate ->
            extractVersionNumber(candidate)?.let { return it }
        }
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun resolveJvmVersion(): String {
        return extractJvmMajorVersion(System.getProperty("java.version").orEmpty())
    }

    private fun resolveServerSoftware(): String {
        return when {
            hasClass("catserver.server.CatServer") -> "catserver"
            hasClass("io.izzel.arclight.server.Launcher") -> "arclight"
            hasClass("com.mohistmc.MohistMCStart") -> "mohist"
            hasClass("org.magmafoundation.magma.remapper.proxy.ProxyClass") -> "magma"
            hasClass("org.leavesmc.leaves.LeavesConfig") -> "leaves"
            hasClass("org.dreeam.leaf.LeafBootstrap") -> "leaf"
            hasClass("org.galemc.gale.version.AbstractPaperVersionFetcher") -> "gale"
            hasClass("io.papermc.paper.threadedregions.RegionizedServer") -> "folia"
            hasClass("gg.pufferfish.pufferfish.PufferfishConfig") -> "pufferfish"
            hasClass("org.purpurmc.purpur.PurpurConfig") -> "purpur"
            hasClass("io.papermc.paper.configuration.Configuration") -> "paper"
            hasClass("com.destroystokyo.paper.PaperConfig") -> "paper"
            hasClass("org.spigotmc.SpigotConfig") -> "spigot"
            else -> {
                val rawName = "${plugin.server.name} ${plugin.server.version}".trim().lowercase(Locale.ROOT)
                when {
                    "shieldspigot" in rawName -> "shieldspigot"
                    "craftbukkit" in rawName -> "craftbukkit"
                    else -> plugin.server.name.trim().lowercase(Locale.ROOT)
                }
            }
        }
    }

    private fun normalizeArchitecture(rawArchitecture: String): String {
        return when (rawArchitecture.lowercase(Locale.ROOT)) {
            "amd64", "x86_64", "x64" -> "x64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            "aarch64", "arm64" -> "arm64"
            "arm", "arm32" -> "arm"
            else -> rawArchitecture
        }
    }

    private fun extractVersionNumber(rawValue: String?): String? {
        val source = rawValue?.trim().orEmpty()
        if (source.isEmpty()) return null
        return Regex("\\b\\d+\\.\\d+(?:\\.\\d+)?\\b").find(source)?.value
    }

    private fun extractJvmMajorVersion(rawVersion: String): String {
        val value = rawVersion.trim()
        if (value.isEmpty()) return value
        Regex("^1\\.(\\d+)").find(value)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("^(\\d+)").find(value)?.groupValues?.getOrNull(1)?.let { return it }
        return value
    }

    private fun hasClass(name: String): Boolean = try {
        Class.forName(name, false, plugin::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    private fun loadServerId(): String {
        val path = plugin.dataFolder.toPath().resolve(".storage")
        Files.createDirectories(path.parent)

        if (Files.isRegularFile(path)) {
            Files.newBufferedReader(path, UTF_8).use { reader ->
                val value = reader.readLine()?.trim().orEmpty()
                if (runCatching { UUID.fromString(value) }.isSuccess) {
                    return value
                }
            }
        }

        val value = UUID.randomUUID().toString()
        Files.newBufferedWriter(path, UTF_8, CREATE, TRUNCATE_EXISTING, WRITE).use { writer ->
            writer.write(value)
            writer.newLine()
        }

        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            runCatching { Files.setAttribute(path, "dos:hidden", true) }
        }

        return value
    }

    private fun Long.toMegabytes(): Long {
        return (this.toDouble() / 1048576).roundToLong()
    }

    private data class StatsPayload(
        val productId: String,
        val serverId: String,
        val sessionId: String,
        val serverVersion: String,
        val productVersion: String,
        val jvmVersion: String,
        val serverSoftware: String,
        val onlineMode: Boolean,
        val currentOnline: Int,
        val operatingSystem: String,
        val cpuArchitecture: String,
        val cpuCores: Int,
        val ramMb: Long,
    ) {
        fun toJson(): String {
            return buildString(256) {
                append('{')
                appendJsonField("productId", productId); append(',')
                appendJsonField("serverId", serverId); append(',')
                appendJsonField("sessionId", sessionId); append(',')
                appendJsonField("serverVersion", serverVersion); append(',')
                appendJsonField("productVersion", productVersion); append(',')
                appendJsonField("jvmVersion", jvmVersion); append(',')
                appendJsonField("serverSoftware", serverSoftware); append(',')
                append("\"onlineMode\":").append(onlineMode); append(',')
                append("\"currentOnline\":").append(currentOnline); append(',')
                appendJsonField("operatingSystem", operatingSystem); append(',')
                appendJsonField("cpuArchitecture", cpuArchitecture); append(',')
                append("\"cpuCores\":").append(cpuCores); append(',')
                append("\"ramMb\":").append(ramMb)
                append('}')
            }
        }

        private fun StringBuilder.appendJsonField(name: String, value: String) {
            append('"'); append(name); append("\":\"")
            append(value.escapeJson()); append('"')
        }

        private fun String.escapeJson(): String {
            val source = this
            return buildString(source.length + 16) {
                source.forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (character.code < 0x20) {
                                append("\\u")
                                append(character.code.toString(16).padStart(4, '0'))
                            } else {
                                append(character)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val URL = "https://api.leetiumlabs.com/stats"
        private const val PRODUCT_ID = "6a7adffc51be7191a173dc4c"
    }
}