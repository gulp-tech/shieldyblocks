package im.apeki.shieldyblocks.data

import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import org.bukkit.World

data class ShieldRegionRecord(
    val id: Long? = null,
    val regionName: String,
    val worldName: String,
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int,
    val ownerName: String,
    val participantNames: Set<String>,
    val blockId: String,
    val currentDurability: Int,
) {
    fun matchesWorldBlock(world: World, def: ProtectedBlockDefinition): Boolean =
        world.name == worldName && world.getBlockAt(blockX, blockY, blockZ).type == def.material

    fun contains(world: World, x: Int, y: Int, z: Int, def: ProtectedBlockDefinition): Boolean {
        if (world.name != worldName) return false
        val minY = (blockY - def.radius).coerceAtLeast(world.minHeight)
        val maxY = (blockY + def.radius).coerceAtMost(world.maxHeight - 1)
        return x in (blockX - def.radius)..(blockX + def.radius) &&
                y in minY..maxY &&
                z in (blockZ - def.radius)..(blockZ + def.radius)
    }

    fun sortedParticipants(): List<String> = participantNames.sortedBy { it.lowercase() }
}