package im.apeki.shieldyblocks.listener

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.config.ShieldyConfig
import im.apeki.shieldyblocks.config.getProtectedBlock
import im.apeki.shieldyblocks.data.ShieldRegionRecord
import im.apeki.shieldyblocks.region.ShieldRegionManager
import im.apeki.shieldyblocks.util.HyperTntBridge
import im.apeki.shieldyblocks.util.MessageFormatter
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.player.PlayerQuitEvent
import kotlin.math.ceil
import kotlin.math.floor

class ShieldRegionListener(
    private val plugin: ShieldyBlocksPlugin,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val shieldyConfig = plugin.currentConfig()
        val manager = plugin.currentRegionManager()

        val definition = plugin.currentShieldBlockItemService()
            .resolvePlacedDefinition(shieldyConfig, event.itemInHand)
            ?: return

        if (!definition.unique && plugin.isRegionPlacementDisabled(event.player.uniqueId)) {
            return
        }

        when (val result = manager.createRegion(event.player, event.blockPlaced, definition)) {
            is ShieldRegionManager.CreateRegionResult.Created -> Unit

            is ShieldRegionManager.CreateRegionResult.Intersects -> {
                event.isCancelled = true
                event.player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.regionCreateIntersection,
                        mapOf(
                            "region" to result.record.regionName,
                            "material" to definition.material.name,
                            "block_name" to definition.displayName,
                            "radius" to definition.radius.toString(),
                            "block_id" to definition.blockId,
                        ),
                    ),
                )
            }

            is ShieldRegionManager.CreateRegionResult.LimitReached -> {
                event.isCancelled = true
                event.player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.regionCreateLimitReached,
                        mapOf(
                            "material" to definition.material.name,
                            "block_name" to definition.displayName,
                            "radius" to definition.radius.toString(),
                            "block_id" to definition.blockId,
                            "limit" to result.limit.toString(),
                            "current" to result.current.toString(),
                        ),
                    ),
                )
            }

            ShieldRegionManager.CreateRegionResult.BlacklistedWorld -> Unit

            ShieldRegionManager.CreateRegionResult.Failed -> {
                event.isCancelled = true
                notifyPlacementFailure(shieldyConfig, event.player, definition)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        plugin.clearRegionPlacementToggle(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val record = plugin.currentRegionRepository().findByLocation(
            event.block.world.name, event.block.x, event.block.y, event.block.z,
        ) ?: return

        val definition = plugin.currentConfig().getProtectedBlock(record.blockId) ?: return

        if (definition.unique) {
            event.isDropItems = false
            event.expToDrop = 0
        }

        val removed = plugin.currentRegionManager().removeRegion(
            record,
            ShieldRegionManager.RemovalCause.Player(event.player.name),
        )

        if (removed && definition.unique) {
            dropUniqueShieldBlock(event.block.location, definition)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onWindChargeExplode(event: EntityExplodeEvent) {
        if (event.entityType != EntityType.WIND_CHARGE) return

        val manager = plugin.currentRegionManager()
        val touchesShieldyRegion = manager.findRegionAt(event.location) != null ||
                event.blockList().any { block -> manager.findRegionAt(block.location) != null }

        if (touchesShieldyRegion) {
            event.blockList().clear()
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityExplodePreventDamage(event: EntityExplodeEvent) {
        preventShieldBlocksExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplodePrepare(event: EntityExplodeEvent) {
        handleExplosion(event)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onExplosionPrime(event: ExplosionPrimeEvent) {
        val location = event.entity.location.clone()
        val tntId = resolveExplosionId(event.entity)

        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                plugin.currentRegionManager().reconcileAfterExplosion(location, tntId)
            },
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplodePreventDamage(event: BlockExplodeEvent) {
        preventShieldBlocksExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplodeMonitor(event: EntityExplodeEvent) {
        preventShieldBlocksExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplodeMonitor(event: BlockExplodeEvent) {
        preventShieldBlocksExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPistonExtend(event: BlockPistonExtendEvent) {
        if (containsShieldBlock(event.blocks)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPistonRetract(event: BlockPistonRetractEvent) {
        if (containsShieldBlock(event.blocks)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entityType != EntityType.WITHER) return

        val record = plugin.currentRegionRepository().findByLocation(
            event.block.world.name, event.block.x, event.block.y, event.block.z,
        ) ?: return

        event.isCancelled = true
        val tntId = resolveExplosionId(event.entity)

        when (val result = plugin.currentRegionManager().applyTntImpact(record, tntId)) {
            is ShieldRegionManager.ExplosionDurabilityResult.Damaged -> Unit
            is ShieldRegionManager.ExplosionDurabilityResult.Destroyed -> {
                event.block.type = Material.AIR
                plugin.currentRegionManager().dropShieldBlockItem(event.block.location, result.definition)
            }
            ShieldRegionManager.ExplosionDurabilityResult.Blocked -> Unit
            ShieldRegionManager.ExplosionDurabilityResult.Failed -> Unit
        }
    }

    private fun handleExplosion(event: EntityExplodeEvent) {
        val impacts = resolveTntImpacts(event)
        if (impacts.isEmpty()) return

        val world = event.location.world ?: return
        val manager = plugin.currentRegionManager()
        val tntId = resolveExplosionId(event.entity)

        for ((record, _) in impacts) {
            val currentRecord = plugin.currentRegionRepository().findByLocation(
                record.worldName, record.blockX, record.blockY, record.blockZ,
            ) ?: continue

            when (val result = manager.applyTntImpact(currentRecord, tntId)) {
                is ShieldRegionManager.ExplosionDurabilityResult.Damaged -> Unit
                is ShieldRegionManager.ExplosionDurabilityResult.Destroyed -> {
                    val block = world.getBlockAt(currentRecord.blockX, currentRecord.blockY, currentRecord.blockZ)
                    block.type = Material.AIR
                    manager.dropShieldBlockItem(block.location, result.definition)
                }
                ShieldRegionManager.ExplosionDurabilityResult.Blocked -> Unit
                ShieldRegionManager.ExplosionDurabilityResult.Failed -> Unit
            }
        }
    }

    private fun preventShieldBlocksExplosion(blocks: MutableList<Block>) {
        val repository = plugin.currentRegionRepository()
        val iterator = blocks.iterator()

        while (iterator.hasNext()) {
            val block = iterator.next()
            val record = repository.findByLocation(block.world.name, block.x, block.y, block.z)
            if (record != null) {
                iterator.remove()
            }
        }
    }

    private fun containsShieldBlock(blocks: List<Block>): Boolean {
        val repository = plugin.currentRegionRepository()
        return blocks.any { block ->
            repository.findByLocation(block.world.name, block.x, block.y, block.z) != null
        }
    }

    private fun resolveTntImpacts(event: EntityExplodeEvent): Map<ShieldRegionRecord, ProtectedBlockDefinition> {
        val config = plugin.currentConfig()
        val repository = plugin.currentRegionRepository()
        val resolved = linkedMapOf<ShieldRegionRecord, ProtectedBlockDefinition>()

        for (block in event.blockList()) {
            val record = repository.findByLocation(block.world.name, block.x, block.y, block.z) ?: continue
            val definition = config.getProtectedBlock(record.blockId) ?: continue
            resolved[record] = definition
        }

        val world = event.location.world ?: return resolved

        val minX = floor(event.location.x - TNT_EFFECT_RADIUS).toInt()
        val maxX = ceil(event.location.x + TNT_EFFECT_RADIUS).toInt()
        val minY = floor(event.location.y - TNT_EFFECT_RADIUS).toInt()
        val maxY = ceil(event.location.y + TNT_EFFECT_RADIUS).toInt()
        val minZ = floor(event.location.z - TNT_EFFECT_RADIUS).toInt()
        val maxZ = ceil(event.location.z + TNT_EFFECT_RADIUS).toInt()

        val records = repository.findWithinBounds(world.name, minX, maxX, minY, maxY, minZ, maxZ)

        for (record in records) {
            if (record in resolved) continue
            val definition = config.getProtectedBlock(record.blockId) ?: continue
            val block = world.getBlockAt(record.blockX, record.blockY, record.blockZ)

            if (isExposedToTnt(event.location, block)) {
                resolved[record] = definition
            }
        }

        return resolved
    }

    private fun resolveExplosionId(entity: Entity): String {
        HyperTntBridge.resolveTypeId(entity)?.let { return it }

        return when (entity.type) {
            EntityType.TNT, EntityType.MINECART_TNT ->
                plugin.currentCustomTntService().resolveTntId(entity)
            EntityType.WITHER, EntityType.WITHER_SKULL -> "wither"
            EntityType.CREEPER -> "creeper"
            EntityType.FIREBALL, EntityType.SMALL_FIREBALL -> "ghast_fireball"
            else -> "other"
        }
    }

    private fun isExposedToTnt(explosionLocation: Location, block: Block): Boolean {
        val world = explosionLocation.world ?: return false
        if (world.uid != block.world.uid) return false

        val target = block.location.clone().add(0.5, 0.5, 0.5)
        val direction = target.toVector().subtract(explosionLocation.toVector())
        val distance = direction.length()

        if (distance <= 0.001) return true
        if (distance > TNT_EFFECT_RADIUS) return false

        val result = world.rayTraceBlocks(
            explosionLocation, direction.normalize(), distance,
            FluidCollisionMode.NEVER, true,
        )

        val hitBlock = result?.hitBlock ?: return true
        return hitBlock.x == block.x && hitBlock.y == block.y && hitBlock.z == block.z
    }

    private fun notifyPlacementFailure(config: ShieldyConfig, player: Player, definition: ProtectedBlockDefinition) {
        player.sendMessage(
            MessageFormatter.format(
                config.messages.regionCreateFailed,
                mapOf(
                    "material" to definition.material.name,
                    "block_name" to definition.displayName,
                    "radius" to definition.radius.toString(),
                    "block_id" to definition.blockId,
                ),
            ),
        )
    }

    private fun dropUniqueShieldBlock(location: Location, definition: ProtectedBlockDefinition) {
        if (!definition.unique) return
        val world = location.world ?: return

        world.dropItemNaturally(
            location.clone().add(0.5, 0.5, 0.5),
            plugin.currentShieldBlockItemService().createItem(definition),
        )
    }

    private companion object {
        const val TNT_EFFECT_RADIUS = 8.0
    }
}