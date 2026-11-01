package im.apeki.shieldyblocks.data

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class FileRegionRepository(
        private val plugin: ShieldyBlocksPlugin,
        ) {
    private val file = File(plugin.dataFolder, "regions.yml")
    private val yaml: YamlConfiguration
    private var nextId = 1L

    init {
        plugin.dataFolder.mkdirs()
        yaml = if (file.exists()) {
            YamlConfiguration.loadConfiguration(file)
        } else {
            YamlConfiguration()
        }
        nextId = yaml.getLong("nextId", 1L)
    }

    private fun save() {
        try {
            yaml.set("nextId", nextId)
            yaml.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save regions.yml: ${e.message}")
        }
    }

    fun findAll(): List<ShieldRegionRecord> {
        val section = yaml.getConfigurationSection("regions") ?: return emptyList()
        return section.getKeys(false).mapNotNull { key ->
                readRecord(key)
        }
    }

    fun findByLocation(world: String, x: Int, y: Int, z: Int): ShieldRegionRecord? {
        return findAll().firstOrNull {
            it.worldName == world && it.blockX == x && it.blockY == y && it.blockZ == z
        }
    }

    fun findOwnedBy(owner: String): List<ShieldRegionRecord> {
        return findAll().filter {
            it.ownerName.equals(owner, ignoreCase = true)
        }.sortedBy { it.id }
    }

    fun findWithinBounds(
            world: String,
            minX: Int, maxX: Int,
            minY: Int, maxY: Int,
            minZ: Int, maxZ: Int,
            ): List<ShieldRegionRecord> {
        return findAll().filter {
            it.worldName == world &&
                    it.blockX in minX..maxX &&
                    it.blockY in minY..maxY &&
                    it.blockZ in minZ..maxZ
        }
    }

    fun save(record: ShieldRegionRecord): ShieldRegionRecord {
        val regionName = record.regionName
        val path = "regions.$regionName"
        val id = record.id ?: nextId++

        yaml.set("$path.id", id)
        yaml.set("$path.world", record.worldName)
        yaml.set("$path.x", record.blockX)
        yaml.set("$path.y", record.blockY)
        yaml.set("$path.z", record.blockZ)
        yaml.set("$path.owner", record.ownerName)
        yaml.set("$path.participants", record.participantNames.toList())
        yaml.set("$path.blockId", record.blockId)
        yaml.set("$path.durability", record.currentDurability)

        save()
        return record.copy(id = id)
    }

    fun delete(regionName: String) {
        yaml.set("regions.$regionName", null)
        save()
    }

    fun close() {
        save()
    }

    private fun readRecord(regionName: String): ShieldRegionRecord? {
        val path = "regions.$regionName"
        val section = yaml.getConfigurationSection(path) ?: return null

        return ShieldRegionRecord(
                id = section.getLong("id", 0L).takeIf { it > 0 },
        regionName = regionName,
                worldName = section.getString("world") ?: return null,
        blockX = section.getInt("x"),
                blockY = section.getInt("y"),
                blockZ = section.getInt("z"),
                ownerName = section.getString("owner") ?: return null,
        participantNames = section.getStringList("participants").toSet(),
                blockId = section.getString("blockId") ?: return null,
        currentDurability = section.getInt("durability", 1),
        )
    }
}