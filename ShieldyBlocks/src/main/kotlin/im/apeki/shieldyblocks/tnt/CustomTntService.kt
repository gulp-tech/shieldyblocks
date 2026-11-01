package im.apeki.shieldyblocks.tnt

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import im.apeki.shieldyblocks.util.MessageFormatter
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.Locale
import java.util.UUID

class CustomTntService(
    private val plugin: ShieldyBlocksPlugin,
) {
    private val file = File(plugin.dataFolder, "tnts.yml")
    private val fingerprintsById = linkedMapOf<String, TntFingerprint>()
    private val lookupSessions = mutableMapOf<UUID, LookupSession>()

    fun load() {
        ensureFile()
        fingerprintsById.clear()

        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("tnts") ?: return
        for (id in section.getKeys(false)) {
            if (normalizeId(id) == VANILLA_ID) {
                plugin.logger.warning("tnts.yml uses reserved TNT id: $VANILLA_ID")
                continue
            }

            val tntSection = section.getConfigurationSection(id) ?: continue
            val fingerprint = loadFingerprint(tntSection) ?: continue
            fingerprintsById[normalizeId(id)] = fingerprint
        }
    }

    fun reload() {
        load()
        lookupSessions.clear()
    }

    fun ids(): Set<String> = linkedSetOf(VANILLA_ID).also { it.addAll(fingerprintsById.keys) }

    fun isValidCustomId(id: String): Boolean {
        val normalizedId = normalizeId(id)
        return normalizedId != VANILLA_ID && TNT_ID_PATTERN.matches(normalizedId)
    }

    fun startLookup(player: Player, id: String) {
        val normalizedId = normalizeId(id)
        val session = LookupSession(normalizedId, System.currentTimeMillis() + LOOKUP_TIMEOUT_MILLIS)
        lookupSessions[player.uniqueId] = session
        player.sendMessage(
            MessageFormatter.format(
                plugin.currentConfig().messages.commandLookupTntWaiting,
                mapOf("tnt" to normalizedId, "seconds" to LOOKUP_TIMEOUT_SECONDS.toString()),
            ),
        )
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            expireLookup(player.uniqueId, session)
        }, LOOKUP_TIMEOUT_TICKS)
    }

    fun clearLookup(playerId: UUID) {
        lookupSessions.remove(playerId)
    }

    fun clearLookups() {
        lookupSessions.clear()
    }

    fun expireLookups() {
        val now = System.currentTimeMillis()
        val iterator = lookupSessions.iterator()
        while (iterator.hasNext()) {
            val (playerId, session) = iterator.next()
            if (session.expiresAtMillis > now) {
                continue
            }

            iterator.remove()
            plugin.server.getPlayer(playerId)?.sendMessage(
                MessageFormatter.format(
                    plugin.currentConfig().messages.commandLookupTntTimeout,
                    mapOf("tnt" to session.id),
                ),
            )
        }
    }

    fun captureLookup(entity: Entity) {
        expireLookups()
        if (lookupSessions.isEmpty()) {
            return
        }

        val location = entity.location
        val iterator = lookupSessions.iterator()
        while (iterator.hasNext()) {
            val (playerId, session) = iterator.next()
            val player = plugin.server.getPlayer(playerId)
            if (player == null || player.world.uid != location.world?.uid || player.location.distanceSquared(location) > LOOKUP_RADIUS_SQUARED) {
                continue
            }

            val fingerprint = readFingerprint(entity)
            if (fingerprint.isEmpty()) {
                iterator.remove()
                player.sendMessage(
                    MessageFormatter.format(
                        plugin.currentConfig().messages.commandLookupTntEmpty,
                        mapOf("tnt" to session.id),
                    ),
                )
                continue
            }

            fingerprintsById[session.id] = fingerprint
            save()
            iterator.remove()
            player.sendMessage(
                MessageFormatter.format(
                    plugin.currentConfig().messages.commandLookupTntSaved,
                    mapOf("tnt" to session.id),
                ),
            )
        }
    }

    private fun expireLookup(playerId: UUID, expectedSession: LookupSession) {
        val session = lookupSessions[playerId] ?: return
        if (session != expectedSession) {
            return
        }

        lookupSessions.remove(playerId)
        plugin.server.getPlayer(playerId)?.sendMessage(
            MessageFormatter.format(
                plugin.currentConfig().messages.commandLookupTntTimeout,
                mapOf("tnt" to session.id),
            ),
        )
    }

    fun resolveTntId(entity: Entity): String {
        val fingerprint = readFingerprint(entity)
        if (fingerprint.isEmpty()) {
            return VANILLA_ID
        }

        for ((id, saved) in fingerprintsById) {
            if (saved.matches(fingerprint)) {
                return id
            }
        }

        return UNKNOWN_ID
    }

    private fun ensureFile() {
        plugin.dataFolder.mkdirs()
        if (!file.exists()) {
            file.writeText("tnts: {}\n")
        }
    }

    private fun save() {
        ensureFile()
        val yaml = YamlConfiguration()
        val root = yaml.createSection("tnts")
        for ((id, fingerprint) in fingerprintsById) {
            val section = root.createSection(id)
            section.set("scoreboard-tags", fingerprint.scoreboardTags.sorted())
            section.set(
                "pdc",
                fingerprint.pdcEntries.map { entry ->
                    mapOf(
                        "key" to entry.key,
                        "type" to entry.type,
                        "value" to entry.value,
                    )
                },
            )
        }
        yaml.save(file)
    }

    private fun loadFingerprint(section: ConfigurationSection): TntFingerprint? {
        val scoreboardTags = section.getStringList("scoreboard-tags")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val pdcEntries = mutableListOf<PdcEntry>()
        for (entry in section.getMapList("pdc")) {
            val key = entry["key"]?.toString()?.trim().orEmpty()
            val type = entry["type"]?.toString()?.trim().orEmpty().uppercase(Locale.ROOT)
            val value = entry["value"] ?: continue
            if (key.isNotEmpty() && type.isNotEmpty()) {
                pdcEntries += PdcEntry(key, type, value)
            }
        }

        return TntFingerprint(pdcEntries, scoreboardTags).takeUnless(TntFingerprint::isEmpty)
    }

    private fun readFingerprint(entity: Entity): TntFingerprint {
        val entries = mutableListOf<PdcEntry>()
        val container = entity.persistentDataContainer
        for (key in container.keys) {
            readPdcEntry(container, key)?.let(entries::add)
        }

        return TntFingerprint(entries, entity.scoreboardTags.toSet())
    }

    private fun readPdcEntry(container: PersistentDataContainer, key: NamespacedKey): PdcEntry? {
        val id = key.toString()
        val type = PDC_TYPES.firstOrNull { container.has(key, it.type) } ?: return null
        val value = type.read(container, key) ?: return null
        return PdcEntry(id, type.name, value)
    }

    private fun normalizeId(id: String): String {
        return id.trim().lowercase(Locale.ROOT)
    }

    private data class LookupSession(
        val id: String,
        val expiresAtMillis: Long,
    )

    private data class TntFingerprint(
        val pdcEntries: List<PdcEntry>,
        val scoreboardTags: Set<String>,
    ) {
        fun isEmpty(): Boolean = pdcEntries.isEmpty() && scoreboardTags.isEmpty()

        fun matches(actual: TntFingerprint): Boolean {
            if (!actual.scoreboardTags.containsAll(scoreboardTags)) {
                return false
            }
            return pdcEntries.all { saved ->
                actual.pdcEntries.any(saved::matches)
            }
        }
    }

    private data class PdcEntry(
        val key: String,
        val type: String,
        val value: Any,
    ) {
        fun matches(other: PdcEntry): Boolean {
            return key == other.key && type == other.type && valuesEqual(value, other.value)
        }

        private fun valuesEqual(left: Any, right: Any): Boolean {
            if (left is List<*> && right is List<*>) {
                return left.map(Any?::toString) == right.map(Any?::toString)
            }
            return left.toString() == right.toString()
        }
    }

    private data class PdcType<T : Any, Z : Any>(
        val name: String,
        val type: PersistentDataType<T, Z>,
        val read: (PersistentDataContainer, NamespacedKey) -> Any?,
    )

    private companion object {
        const val VANILLA_ID = "vanilla"
        const val UNKNOWN_ID = "__unknown__"
        private const val LOOKUP_TIMEOUT_SECONDS = 60
        private const val LOOKUP_TIMEOUT_MILLIS = LOOKUP_TIMEOUT_SECONDS * 1000L
        private const val LOOKUP_TIMEOUT_TICKS = LOOKUP_TIMEOUT_SECONDS * 20L
        private const val LOOKUP_RADIUS_SQUARED = 25.0
        private val TNT_ID_PATTERN = Regex("[a-z0-9_-]{1,64}")

        private val PDC_TYPES = listOf(
            PdcType("BYTE", PersistentDataType.BYTE) { container, key -> container.get(key, PersistentDataType.BYTE) },
            PdcType("SHORT", PersistentDataType.SHORT) { container, key -> container.get(key, PersistentDataType.SHORT) },
            PdcType("INTEGER", PersistentDataType.INTEGER) { container, key -> container.get(key, PersistentDataType.INTEGER) },
            PdcType("LONG", PersistentDataType.LONG) { container, key -> container.get(key, PersistentDataType.LONG) },
            PdcType("FLOAT", PersistentDataType.FLOAT) { container, key -> container.get(key, PersistentDataType.FLOAT) },
            PdcType("DOUBLE", PersistentDataType.DOUBLE) { container, key -> container.get(key, PersistentDataType.DOUBLE) },
            PdcType("STRING", PersistentDataType.STRING) { container, key -> container.get(key, PersistentDataType.STRING) },
            PdcType("BYTE_ARRAY", PersistentDataType.BYTE_ARRAY) { container, key ->
                container.get(key, PersistentDataType.BYTE_ARRAY)?.map(Byte::toInt)
            },
            PdcType("INTEGER_ARRAY", PersistentDataType.INTEGER_ARRAY) { container, key ->
                container.get(key, PersistentDataType.INTEGER_ARRAY)?.toList()
            },
            PdcType("LONG_ARRAY", PersistentDataType.LONG_ARRAY) { container, key ->
                container.get(key, PersistentDataType.LONG_ARRAY)?.toList()
            },
        )
    }
}
