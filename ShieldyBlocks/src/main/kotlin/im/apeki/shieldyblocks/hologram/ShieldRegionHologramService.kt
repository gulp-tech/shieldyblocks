package im.apeki.shieldyblocks.hologram

import eu.decentsoftware.holograms.api.holograms.Hologram
import eu.decentsoftware.holograms.api.holograms.HologramLine
import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.config.ShieldyConfig
import im.apeki.shieldyblocks.config.getProtectedBlock
import im.apeki.shieldyblocks.data.ShieldRegionRecord
import im.apeki.shieldyblocks.util.MessageFormatter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Method

class ShieldRegionHologramService(
    private val plugin: ShieldyBlocksPlugin,
    private val shieldyConfig: ShieldyConfig,
    placeholderProvider:
        (ShieldRegionRecord, ProtectedBlockDefinition) -> Map<String, String> = { _, _ -> emptyMap() },
) {
    var placeholderProvider = placeholderProvider

    private enum class Backend {
        DECENT,
        HOLOGRAPHIC_DISPLAYS,
        NONE,
    }

    private val backend: Backend = detectBackend()
    private val entries = linkedMapOf<String, RegionHologramEntry>()
    private var visibilityTask: BukkitTask? = null

    private fun detectBackend(): Backend {
        val manager = Bukkit.getPluginManager()

        if (manager.getPlugin("DecentHolograms")?.isEnabled == true) {
            plugin.logger.info("Using DecentHolograms for region holograms.")
            return Backend.DECENT
        }

        if (manager.getPlugin("HolographicDisplays")?.isEnabled == true) {
            plugin.logger.info("Using HolographicDisplays for region holograms.")
            return Backend.HOLOGRAPHIC_DISPLAYS
        }

        plugin.logger.warning("No supported hologram plugin found. Region holograms are disabled.")
        return Backend.NONE
    }

    fun upsert(record: ShieldRegionRecord) {
        remove(record.regionName)

        if (backend == Backend.NONE) return

        val world = Bukkit.getWorld(record.worldName) ?: return
        val definition = shieldyConfig.getProtectedBlock(record.blockId) ?: return

        val location = Location(
            world,
            record.blockX + 0.5,
            record.blockY + shieldyConfig.hologram.offsetY,
            record.blockZ + 0.5,
        )

        val placeholders = placeholderProvider(record, definition)
        val lines = shieldyConfig.hologram.lines.map { line ->
            MessageFormatter.format(line, placeholders)
        }

        val hologram: Any = try {
            when (backend) {
                Backend.DECENT -> createDecentHologram(record, definition, location, lines)
                Backend.HOLOGRAPHIC_DISPLAYS -> createHolographicDisplaysHologram(location, lines)
                Backend.NONE -> return
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to create hologram for ${record.regionName}: ${exception.message}")
            return
        }

        entries[record.regionName] = RegionHologramEntry(record, definition, hologram, location)

        for (player in Bukkit.getOnlinePlayers()) {
            updateVisibilityForPlayer(player)
        }
    }

    fun remove(regionName: String) {
        val entry = entries.remove(regionName) ?: return
        deleteHologram(entry.hologram)
    }

    fun start() {
        if (visibilityTask != null || backend == Backend.NONE) return

        val period = shieldyConfig.hologram.refreshTicks.coerceAtLeast(1L)
        visibilityTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                for (player in Bukkit.getOnlinePlayers()) {
                    updateVisibilityForPlayer(player)
                }
            },
            period,
            period,
        )
    }

    fun shutdown() {
        visibilityTask?.cancel()
        visibilityTask = null

        for (entry in entries.values) {
            deleteHologram(entry.hologram)
        }
        entries.clear()
    }

    private fun createDecentHologram(
        record: ShieldRegionRecord,
        definition: ProtectedBlockDefinition,
        location: Location,
        lines: List<String>,
    ): Hologram {
        val hologram = Hologram(record.regionName, location, false)
        hologram.isDefaultVisibleState = false
        hologram.displayRange = maxOf(
            definition.memberHologramViewDistance,
            definition.otherHologramViewDistance,
        )
        hologram.updateRange = hologram.displayRange
        hologram.updateInterval = shieldyConfig.hologram.refreshTicks.toInt().coerceAtLeast(1)

        val page = hologram.getPage(0)
            ?: error("DecentHolograms created hologram without a default page")

        for (line in lines) {
            val hologramLine = HologramLine(page, page.nextLineLocation, line)
            hologramLine.height = shieldyConfig.hologram.lineSpacing
            page.addLine(hologramLine)
        }

        return hologram
    }

    private fun createHolographicDisplaysHologram(
        location: Location,
        lines: List<String>,
    ): Any {
        val apiClass = Class.forName("com.gmail.filoghost.holographicdisplays.api.HologramsAPI")
        val createMethod = apiClass.getMethod(
            "createHologram",
            org.bukkit.plugin.Plugin::class.java,
            Location::class.java,
        )
        val hologram = createMethod.invoke(null, plugin, location)
            ?: error("HolographicDisplays returned null hologram")

        val visibilityManager = invokeNoArgs(hologram, "getVisibilityManager")
        if (visibilityManager != null) {
            invokeBoolean(visibilityManager, "setVisibleByDefault", false)
        }

        for (line in lines) {
            invokeString(hologram, "appendTextLine", line)
        }

        return hologram
    }

    private fun deleteHologram(hologram: Any) {
        try {
            when (hologram) {
                is Hologram -> hologram.delete()
                else -> invokeNoArgs(hologram, "delete")
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to delete hologram: ${exception.message}")
        }
    }

    private fun updateVisibilityForPlayer(player: Player) {
        for (entry in entries.values) {
            val shouldShow = shouldShow(player, entry)

            when (backend) {
                Backend.DECENT -> updateDecentVisibility(entry.hologram as Hologram, player, shouldShow)
                Backend.HOLOGRAPHIC_DISPLAYS -> updateHolographicDisplaysVisibility(entry.hologram, player, shouldShow)
                Backend.NONE -> Unit
            }
        }
    }

    private fun updateDecentVisibility(hologram: Hologram, player: Player, shouldShow: Boolean) {
        val isShown = hologram.isVisible(player)

        if (shouldShow && !isShown) {
            hologram.removeHidePlayer(player)
            hologram.setShowPlayer(player)
            hologram.show(player, 0)
        } else if (!shouldShow && isShown) {
            hologram.removeShowPlayer(player)
            hologram.setHidePlayer(player)
            hologram.hide(player)
        } else if (!shouldShow) {
            hologram.removeShowPlayer(player)
            hologram.setHidePlayer(player)
        } else {
            hologram.removeHidePlayer(player)
            hologram.setShowPlayer(player)
        }
    }

    private fun updateHolographicDisplaysVisibility(hologram: Any, player: Player, shouldShow: Boolean) {
        try {
            val manager = invokeNoArgs(hologram, "getVisibilityManager") ?: return
            val methodName = if (shouldShow) "showTo" else "hideTo"
            invokePlayer(manager, methodName, player)
        } catch (exception: Exception) {
            plugin.logger.warning("Failed to update HolographicDisplays visibility: ${exception.message}")
        }
    }

    private fun shouldShow(player: Player, entry: RegionHologramEntry): Boolean {
        if (player.world.name != entry.record.worldName) return false

        val viewDistance = if (isRegionMember(player.name, entry.record)) {
            entry.definition.memberHologramViewDistance
        } else {
            entry.definition.otherHologramViewDistance
        }

        if (viewDistance <= 0) return false

        return player.location.distanceSquared(entry.location) <=
                viewDistance.toDouble() * viewDistance.toDouble()
    }

    private fun isRegionMember(playerName: String, record: ShieldRegionRecord): Boolean {
        return record.ownerName.equals(playerName, ignoreCase = true) ||
                record.participantNames.any { it.equals(playerName, ignoreCase = true) }
    }

    private fun invokeNoArgs(target: Any, name: String): Any? {
        return target.javaClass.getMethod(name).invoke(target)
    }

    private fun invokeBoolean(target: Any, name: String, value: Boolean) {
        target.javaClass.getMethod(name, Boolean::class.javaPrimitiveType).invoke(target, value)
    }

    private fun invokeString(target: Any, name: String, value: String) {
        target.javaClass.getMethod(name, String::class.java).invoke(target, value)
    }

    private fun invokePlayer(target: Any, name: String, player: Player) {
        val method: Method = target.javaClass.getMethod(name, Player::class.java)
        method.invoke(target, player)
    }

    private data class RegionHologramEntry(
        val record: ShieldRegionRecord,
        val definition: ProtectedBlockDefinition,
        val hologram: Any,
        val location: Location,
    )
}