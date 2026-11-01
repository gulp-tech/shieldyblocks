package im.apeki.shieldyblocks

import im.apeki.shieldyblocks.command.ShieldyBlocksCommand
import im.apeki.shieldyblocks.config.ShieldyConfig
import im.apeki.shieldyblocks.config.ShieldyConfigLoader
import im.apeki.shieldyblocks.data.FileRegionRepository
import im.apeki.shieldyblocks.hologram.ShieldRegionHologramService
import im.apeki.shieldyblocks.item.ShieldBlockItemService
import im.apeki.shieldyblocks.listener.ShieldRegionListener
import im.apeki.shieldyblocks.region.ShieldRegionManager
import im.apeki.shieldyblocks.region.WorldGuardRegionService
import im.apeki.shieldyblocks.tnt.CustomTntService
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ShieldyBlocksPlugin : JavaPlugin() {

    private var shieldyConfig: ShieldyConfig? = null
    private var regionRepository: FileRegionRepository? = null
    private var regionManager: ShieldRegionManager? = null
    private var shieldBlockItemService: ShieldBlockItemService? = null
    private var customTntService: CustomTntService? = null
    private var hologramService: ShieldRegionHologramService? = null

    private val regionPlacementDisabled = ConcurrentHashMap<UUID, Boolean>()

    val sessionId: String = UUID.randomUUID().toString()

    override fun onEnable() {
        saveDefaultConfig()

        val loadResult = loadRuntimeState()
        if (loadResult is ReloadRuntimeResult.Failure) {
            logger.severe("Failed to load ShieldyBlocks: ${loadResult.reason}")
            server.pluginManager.disablePlugin(this)
            return
        }

        val command = ShieldyBlocksCommand(this)
        getCommand("shieldyblocks")?.apply {
            setExecutor(command)
            tabCompleter = command
        }

        server.pluginManager.registerEvents(ShieldRegionListener(this), this)

        logger.info("ShieldyBlocks enabled.")
    }

    override fun onDisable() {
        regionManager?.shutdown()
        regionRepository?.close()
        customTntService?.clearLookups()
        logger.info("ShieldyBlocks disabled.")
    }

    fun currentConfig(): ShieldyConfig =
        shieldyConfig ?: throw IllegalStateException("Config not loaded")

    fun currentRegionManager(): ShieldRegionManager =
        regionManager ?: throw IllegalStateException("RegionManager not initialized")

    fun currentRegionRepository(): FileRegionRepository =
        regionRepository ?: throw IllegalStateException("Repository not initialized")

    fun currentShieldBlockItemService(): ShieldBlockItemService =
        shieldBlockItemService ?: throw IllegalStateException("ItemService not initialized")

    fun currentCustomTntService(): CustomTntService =
        customTntService ?: throw IllegalStateException("CustomTntService not initialized")

    fun toggleRegionPlacement(playerId: UUID): Boolean {
        val current = regionPlacementDisabled.getOrDefault(playerId, false)
        val newState = !current
        if (newState) {
            regionPlacementDisabled[playerId] = true
        } else {
            regionPlacementDisabled.remove(playerId)
        }
        return !newState
    }

    fun isRegionPlacementDisabled(playerId: UUID): Boolean =
        regionPlacementDisabled.getOrDefault(playerId, false)

    fun clearRegionPlacementToggle(playerId: UUID) {
        regionPlacementDisabled.remove(playerId)
    }

    fun reloadRuntimeState(): ReloadRuntimeResult {
        reloadConfig()
        regionManager?.shutdown()
        regionRepository?.close()
        customTntService?.clearLookups()
        return loadRuntimeState()
    }

    private fun loadRuntimeState(): ReloadRuntimeResult {
        return try {
            val tntService = CustomTntService(this).also { it.load() }
            customTntService = tntService

            val cfg = ShieldyConfigLoader.load(
                config,
                knownTntIds = tntService.ids(),
                warn = { logger.warning(it) },
            )
            shieldyConfig = cfg

            val repo = FileRegionRepository(this)
            regionRepository = repo

            val itemService = ShieldBlockItemService(this)
            shieldBlockItemService = itemService

            val worldGuardService = WorldGuardRegionService(this)

            val holoService = ShieldRegionHologramService(this, cfg) { _, _ -> emptyMap() }
            hologramService = holoService

            val manager = ShieldRegionManager(
                this, cfg, repo, worldGuardService, holoService,
            )
            regionManager = manager

            holoService.placeholderProvider = { record, definition ->
                manager.placeholders(record, definition)
            }

            manager.synchronizeStoredRegions()
            holoService.start()

            ReloadRuntimeResult.Success
        } catch (exception: Exception) {
            logger.severe("Failed to load config: ${exception.message}")
            ReloadRuntimeResult.Failure(exception.message ?: "Unknown error")
        }
    }

    sealed interface ReloadRuntimeResult {
        data object Success : ReloadRuntimeResult
        data class Failure(val reason: String) : ReloadRuntimeResult
    }
}