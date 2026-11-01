package im.apeki.shieldyblocks.region

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.config.ShieldyConfig
import im.apeki.shieldyblocks.config.getProtectedBlock
import im.apeki.shieldyblocks.data.FileRegionRepository
import im.apeki.shieldyblocks.data.ShieldRegionRecord
import im.apeki.shieldyblocks.hologram.ShieldRegionHologramService
import im.apeki.shieldyblocks.util.MessageFormatter
import im.apeki.shieldyblocks.util.RegionIdFactory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import kotlin.random.Random

class ShieldRegionManager(
    private val plugin: ShieldyBlocksPlugin,
    private val shieldyConfig: ShieldyConfig,
    private val regionRepository: FileRegionRepository,
    private val worldGuardRegionService: WorldGuardRegionService,
    private val hologramService: ShieldRegionHologramService,
) {
    private val maxProtectedBlockRadius: Int by lazy {
        shieldyConfig.protectedBlocksById.values.maxOfOrNull { it.radius } ?: 0
    }

    fun synchronizeStoredRegions() {
        for (record in regionRepository.findAll()) {
            val world = plugin.server.getWorld(record.worldName)
            if (world == null) {
                plugin.logger.warning("Skipping region ${record.regionName}: world ${record.worldName} is not loaded")
                continue
            }

            val definition = shieldyConfig.getProtectedBlock(record.blockId)
            if (definition == null) {
                worldGuardRegionService.deleteRegion(world, record.regionName)
                regionRepository.delete(record.regionName)
                hologramService.remove(record.regionName)
                plugin.logger.warning("Removed region ${record.regionName}: block id ${record.blockId} is no longer configured")
                continue
            }

            if (!record.matchesWorldBlock(world, definition)) {
                worldGuardRegionService.deleteRegion(world, record.regionName)
                regionRepository.delete(record.regionName)
                hologramService.remove(record.regionName)
                plugin.logger.warning("Removed stale region ${record.regionName}: the shield block is missing or changed")
                continue
            }

            if (!worldGuardRegionService.ensureRegionExists(world, record, definition)) {
                plugin.logger.warning("Failed to ensure WorldGuard region ${record.regionName}")
                continue
            }

            if (!worldGuardRegionService.syncRegionMembers(world, record)) {
                plugin.logger.warning("Failed to synchronize members for region ${record.regionName}")
            }

            hologramService.upsert(record)
        }
    }

    fun createRegion(
        owner: Player,
        block: Block,
        definition: ProtectedBlockDefinition,
    ): CreateRegionResult {
        if (block.world.name in shieldyConfig.blacklistedWorlds) {
            return CreateRegionResult.BlacklistedWorld
        }

        regionRepository.findByLocation(
            block.world.name, block.x, block.y, block.z,
        )?.let { staleRecord ->
            removeRegion(staleRecord, RemovalCause.Silent)
        }

        val limit = resolveRegionLimit(owner)
        val current = getOwnedRegions(owner.name).size

        if (current >= limit) {
            return CreateRegionResult.LimitReached(current, limit)
        }

        val record = ShieldRegionRecord(
            regionName = RegionIdFactory.create(block.world.name, block.x, block.y, block.z),
            worldName = block.world.name,
            blockX = block.x,
            blockY = block.y,
            blockZ = block.z,
            ownerName = owner.name,
            participantNames = emptySet(),
            blockId = definition.blockId,
            currentDurability = definition.maxDurability,
        )

        if (worldGuardRegionService.hasIntersection(block.world, record, definition)) {
            return CreateRegionResult.Intersects(record)
        }

        if (!worldGuardRegionService.createRegion(block.world, record, definition)) {
            return CreateRegionResult.Failed
        }

        return try {
            val savedRecord = regionRepository.save(record)
            hologramService.upsert(savedRecord)
            notifyOwnerRegionCreated(owner, savedRecord, definition)
            CreateRegionResult.Created(savedRecord)
        } catch (exception: Exception) {
            worldGuardRegionService.deleteRegion(block.world, record.regionName)
            plugin.logger.warning("Failed to save region ${record.regionName}: ${exception.message}")
            CreateRegionResult.Failed
        }
    }

    fun removeRegion(record: ShieldRegionRecord, cause: RemovalCause): Boolean {
        val current = regionRepository.findByLocation(
            record.worldName, record.blockX, record.blockY, record.blockZ,
        )

        if (current == null || current.regionName != record.regionName) return false

        hologramService.remove(record.regionName)

        var removed = true
        val world = Bukkit.getWorld(record.worldName)

        if (world != null && !worldGuardRegionService.deleteRegion(world, record.regionName)) {
            removed = false
        }

        try {
            regionRepository.delete(record.regionName)
        } catch (exception: Exception) {
            removed = false
            plugin.logger.warning("Failed to delete region ${record.regionName} from storage: ${exception.message}")
        }

        if (cause != RemovalCause.Silent) {
            if (removed) {
                notifyOwnerRegionRemoved(record, cause)
            } else {
                notifyOwnerRegionRemovalFailed(record)
            }
        }

        return removed
    }

    fun getOwnedRegions(ownerName: String): List<ShieldRegionRecord> {
        return regionRepository.findOwnedBy(ownerName)
    }

    fun findOwnedRegionByNumber(ownerName: String, regionNumber: Int): ShieldRegionRecord? {
        return getOwnedRegions(ownerName).getOrNull(regionNumber - 1)
    }

    fun findOwnedRegionAt(player: Player): ShieldRegionRecord? {
        return getOwnedRegions(player.name).firstOrNull { record ->
            val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: return@firstOrNull false
            record.contains(player.world, player.location.blockX, player.location.blockY, player.location.blockZ, definition)
        }
    }

    fun findRegionAt(location: Location): ShieldRegionRecord? {
        val world = location.world ?: return null
        return regionRepository.findAll().firstOrNull { record ->
            val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: return@firstOrNull false
            record.contains(world, location.blockX, location.blockY, location.blockZ, definition)
        }
    }

    fun addParticipant(record: ShieldRegionRecord, participantName: String): ParticipantChangeResult {
        if (record.ownerName.equals(participantName, ignoreCase = true) ||
            record.participantNames.any { it.equals(participantName, ignoreCase = true) }
        ) {
            return ParticipantChangeResult.AlreadyPresent
        }

        return saveParticipantUpdate(
            record.copy(participantNames = record.participantNames + participantName),
        )
    }

    fun removeParticipant(record: ShieldRegionRecord, participantName: String): ParticipantChangeResult {
        val participants = record.participantNames.filterNot {
            it.equals(participantName, ignoreCase = true)
        }.toSet()

        if (participants.size == record.participantNames.size) {
            return ParticipantChangeResult.NotFound
        }

        return saveParticipantUpdate(record.copy(participantNames = participants))
    }

    fun resolveRegionNumber(ownerName: String, regionName: String): Int? {
        return getOwnedRegions(ownerName)
            .indexOfFirst { it.regionName == regionName }
            .takeIf { it >= 0 }
            ?.plus(1)
    }

    fun resolveRegionLimit(player: Player): Int {
        var resolvedLimit = shieldyConfig.limits.defaultLimit

        for ((permissionSuffix, limit) in shieldyConfig.limits.permissionOverrides) {
            if (player.hasPermission("shieldyblocks.limit.$permissionSuffix")) {
                resolvedLimit = maxOf(resolvedLimit, limit)
            }
        }

        return resolvedLimit
    }

    fun applyTntImpact(record: ShieldRegionRecord): ExplosionDurabilityResult {
        return applyTntImpact(record, "vanilla")
    }

    fun applyTntImpact(record: ShieldRegionRecord, tntId: String): ExplosionDurabilityResult {
        val current = regionRepository.findByLocation(
            record.worldName, record.blockX, record.blockY, record.blockZ,
        ) ?: return ExplosionDurabilityResult.Failed

        if (current.regionName != record.regionName) return ExplosionDurabilityResult.Failed

        val definition = shieldyConfig.getProtectedBlock(current.blockId)
            ?: return ExplosionDurabilityResult.Failed

        val normalizedTntId = tntId.trim().lowercase(Locale.ROOT)

        if (definition.allowedTnts.isNotEmpty() && normalizedTntId !in definition.allowedTnts) {
            return ExplosionDurabilityResult.Blocked
        }

        val chance = definition.explosionBlockChance.coerceIn(0, 100)

        if (chance == 100 || (chance > 0 && Random.nextInt(100) < chance)) {
            return ExplosionDurabilityResult.Blocked
        }

        val remaining = current.currentDurability - 1

        if (remaining > 0) {
            val updated = current.copy(currentDurability = remaining)
            return try {
                val saved = regionRepository.save(updated)
                hologramService.upsert(saved)
                ExplosionDurabilityResult.Damaged(saved)
            } catch (exception: Exception) {
                plugin.logger.warning("Failed to update durability for region ${current.regionName}: ${exception.message}")
                ExplosionDurabilityResult.Failed
            }
        }

        return if (removeRegion(current, RemovalCause.Explosion)) {
            ExplosionDurabilityResult.Destroyed(definition)
        } else {
            ExplosionDurabilityResult.Failed
        }
    }

    private fun saveParticipantUpdate(record: ShieldRegionRecord): ParticipantChangeResult {
        return try {
            val saved = regionRepository.save(record)
            val world = plugin.server.getWorld(saved.worldName) ?: return ParticipantChangeResult.Failed

            if (!worldGuardRegionService.syncRegionMembers(world, saved)) {
                return ParticipantChangeResult.Failed
            }

            hologramService.upsert(saved)
            ParticipantChangeResult.Updated(saved)
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to update participants for region ${record.regionName}: ${exception.message}")
            ParticipantChangeResult.Failed
        }
    }

    fun formatListDistance(playerLocation: Location, record: ShieldRegionRecord): String {
        if (playerLocation.world?.name != record.worldName) {
            return shieldyConfig.messages.commandListOtherWorldDistance
        }

        val distance = playerLocation.distance(
            Location(playerLocation.world, record.blockX.toDouble(), record.blockY.toDouble(), record.blockZ.toDouble()),
        )

        return distance.toInt().toString()
    }

    private fun notifyOwnerRegionCreated(owner: Player, record: ShieldRegionRecord, definition: ProtectedBlockDefinition) {
        owner.sendMessage(
            MessageFormatter.format(shieldyConfig.messages.regionCreated, placeholders(record, definition)),
        )
    }

    private fun notifyOwnerRegionRemoved(record: ShieldRegionRecord, cause: RemovalCause) {
        val owner = Bukkit.getPlayerExact(record.ownerName)?.takeIf(Player::isOnline) ?: return
        val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: return

        val template = when (cause) {
            is RemovalCause.Player -> shieldyConfig.messages.regionRemovedByPlayer
            RemovalCause.Explosion -> shieldyConfig.messages.regionRemovedByExplosion
            RemovalCause.Silent -> return
        }

        val causePlaceholders = when (cause) {
            is RemovalCause.Player -> mapOf("player" to cause.playerName)
            RemovalCause.Explosion, RemovalCause.Silent -> emptyMap()
        }

        owner.sendMessage(
            MessageFormatter.format(template, placeholders(record, definition) + causePlaceholders),
        )
    }

    private fun notifyOwnerRegionRemovalFailed(record: ShieldRegionRecord) {
        val owner = Bukkit.getPlayerExact(record.ownerName)?.takeIf(Player::isOnline) ?: return
        val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: return

        owner.sendMessage(
            MessageFormatter.format(shieldyConfig.messages.regionRemoveFailed, placeholders(record, definition)),
        )
    }

    fun placeholders(record: ShieldRegionRecord, definition: ProtectedBlockDefinition): Map<String, String> {
        val regionNumber = resolveRegionNumber(record.ownerName, record.regionName)
        val world = Bukkit.getWorld(record.worldName)

        val minY = if (world != null) {
            (record.blockY - definition.radius).coerceAtLeast(world.minHeight).coerceAtMost(world.maxHeight - 1)
        } else {
            record.blockY - definition.radius
        }

        val maxY = if (world != null) {
            (record.blockY + definition.radius).coerceAtLeast(minY).coerceAtMost(world.maxHeight - 1)
        } else {
            record.blockY + definition.radius
        }

        return mapOf(
            "id" to (record.id?.toString() ?: ""),
            "number" to (regionNumber?.toString() ?: ""),
            "region" to record.regionName,
            "world" to record.worldName,
            "x" to record.blockX.toString(),
            "y" to record.blockY.toString(),
            "z" to record.blockZ.toString(),
            "min_x" to (record.blockX - definition.radius).toString(),
            "min_y" to minY.toString(),
            "min_z" to (record.blockZ - definition.radius).toString(),
            "max_x" to (record.blockX + definition.radius).toString(),
            "max_y" to maxY.toString(),
            "max_z" to (record.blockZ + definition.radius).toString(),
            "radius" to definition.radius.toString(),
            "durability" to record.currentDurability.toString(),
            "current_durability" to record.currentDurability.toString(),
            "max_durability" to definition.maxDurability.toString(),
            "material" to definition.material.name,
            "block_name" to definition.displayName,
            "owner" to record.ownerName,
            "block_id" to definition.blockId,
            "participants" to if (record.participantNames.isEmpty()) "нет" else record.sortedParticipants().joinToString(", "),
            "participants_count" to record.participantNames.size.toString(),
            "explosion_block_chance" to definition.explosionBlockChance.toString(),
        )
    }

    fun shutdown() {
        hologramService.shutdown()
    }

    fun removeMissingShieldBlocks() {
        for (record in regionRepository.findAll()) {
            val world = Bukkit.getWorld(record.worldName) ?: continue

            if (!world.isChunkLoaded(record.blockX shr 4, record.blockZ shr 4)) continue

            val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: continue

            if (record.matchesWorldBlock(world, definition)) continue

            plugin.logger.info(
                "Shield block for region ${record.regionName} is missing at ${record.worldName} " +
                        "${record.blockX} ${record.blockY} ${record.blockZ}; removing region.",
            )

            removeRegion(record, RemovalCause.Explosion)
        }
    }

    fun reconcileAfterExplosion(location: Location, tntId: String) {
        val world = location.world ?: return
        val searchRadius = maxProtectedBlockRadius + EXPLOSION_RECONCILE_PADDING

        val records = regionRepository.findWithinBounds(
            world.name,
            location.blockX - searchRadius, location.blockX + searchRadius,
            location.blockY - searchRadius, location.blockY + searchRadius,
            location.blockZ - searchRadius, location.blockZ + searchRadius,
        )

        for (record in records) {
            reconcileRecordAfterExplosion(record, tntId)
        }
    }

    private fun reconcileRecordAfterExplosion(record: ShieldRegionRecord, tntId: String) {
        val current = regionRepository.findByLocation(
            record.worldName, record.blockX, record.blockY, record.blockZ,
        ) ?: return

        val world = Bukkit.getWorld(current.worldName) ?: return

        if (!world.isChunkLoaded(current.blockX shr 4, current.blockZ shr 4)) return

        val definition = shieldyConfig.getProtectedBlock(current.blockId) ?: return

        if (current.matchesWorldBlock(world, definition)) return

        val block = world.getBlockAt(current.blockX, current.blockY, current.blockZ)

        when (val result = applyTntImpact(current, tntId)) {
            is ExplosionDurabilityResult.Damaged -> block.setType(definition.material, false)
            is ExplosionDurabilityResult.Destroyed -> dropShieldBlockItem(block.location, result.definition)
            ExplosionDurabilityResult.Blocked -> block.setType(definition.material, false)
            ExplosionDurabilityResult.Failed -> Unit
        }
    }

    fun dropShieldBlockItem(location: Location, definition: ProtectedBlockDefinition) {
        val world = location.world ?: return

        val item = if (definition.unique) {
            plugin.currentShieldBlockItemService().createItem(definition)
        } else {
            ItemStack(definition.material, 1)
        }

        world.dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), item)
    }

    sealed interface CreateRegionResult {
        data class Created(val record: ShieldRegionRecord) : CreateRegionResult
        data class Intersects(val record: ShieldRegionRecord) : CreateRegionResult
        data class LimitReached(val current: Int, val limit: Int) : CreateRegionResult
        data object BlacklistedWorld : CreateRegionResult
        data object Failed : CreateRegionResult
    }

    sealed interface ParticipantChangeResult {
        data class Updated(val record: ShieldRegionRecord) : ParticipantChangeResult
        data object AlreadyPresent : ParticipantChangeResult
        data object NotFound : ParticipantChangeResult
        data object Failed : ParticipantChangeResult
    }

    sealed interface ExplosionDurabilityResult {
        data class Damaged(val record: ShieldRegionRecord) : ExplosionDurabilityResult
        data class Destroyed(val definition: ProtectedBlockDefinition) : ExplosionDurabilityResult
        data object Blocked : ExplosionDurabilityResult
        data object Failed : ExplosionDurabilityResult
    }

    sealed interface RemovalCause {
        data class Player(val playerName: String) : RemovalCause
        data object Explosion : RemovalCause
        data object Silent : RemovalCause
    }

    private companion object {
        const val EXPLOSION_RECONCILE_PADDING = 32
    }
}