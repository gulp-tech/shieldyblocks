package im.apeki.shieldyblocks.config

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale

/** Loads the plugin settings from the server configuration. */
object ShieldyConfigLoader {
    fun load(config: FileConfiguration, knownTntIds: Set<String>, warn: (String) -> Unit): ShieldyConfig {
        fun message(key: String): String = config.getString("messages.$key", "") ?: ""
        val messages = ShieldyMessages(
            regionCreated = message("region-created"),
            regionRemovedByPlayer = message("region-removed-player"),
            regionRemovedByExplosion = message("region-removed-explosion"),
            regionCreateFailed = message("region-create-failed"),
            regionCreateIntersection = message("region-create-intersection"),
            regionCreateLimitReached = message("region-create-limit-reached"),
            regionRemoveFailed = message("region-remove-failed"),
            commandPlayerOnly = message("command-player-only"),
            commandNoPermission = message("command-no-permission"),
            commandUsage = message("command-usage"),
            commandUpdateFailed = message("command-update-failed"),
            commandReloadSuccess = message("command-reload-success"),
            commandReloadFailed = message("command-reload-failed"),
            commandGiveSuccess = message("command-give-success"),
            commandGivePlayerOffline = message("command-give-error-player-offline"),
            commandGiveUnknownBlock = message("command-give-error-unknown-block"),
            commandLookupTntWaiting = message("command-lookuptnt-waiting"),
            commandLookupTntSaved = message("command-lookuptnt-saved"),
            commandLookupTntEmpty = message("command-lookuptnt-empty"),
            commandLookupTntTimeout = message("command-lookuptnt-timeout"),
            commandLookupTntInvalidName = message("command-lookuptnt-invalid-name"),
            commandAddSuccess = message("command-add-success"),
            commandAddPlayerOffline = message("command-add-error-player-offline"),
            commandAddAlreadyMember = message("command-add-error-already-member"),
            commandRemoveSuccess = message("command-remove-success"),
            commandRemoveNotMember = message("command-remove-error-not-member"),
            commandRemoveSelf = message("command-remove-error-self"),
            commandNotInOwnRegion = message("command-error-not-in-own-region"),
            commandRegionNumberInvalid = message("command-region-number-invalid"),
            commandListEmpty = message("command-list-empty"),
            commandListHeader = message("command-list-header"),
            commandListEntry = message("command-list-entry"),
            commandListOtherWorldDistance = message("command-list-distance-other-world"),
            commandToggleEnabled = message("command-toggle-enabled"),
            commandToggleDisabled = message("command-toggle-disabled"),
            commandInfoMessage = message("command-info-message"),
            commandInfoNotInRegion = message("command-info-not-in-region"),
            commandInfoNoPermissionOthers = message("command-info-no-permission-others"),
        )
        val limits = config.getConfigurationSection("limits.overrides")
        val overrides = limits?.getKeys(false)?.associateWith { limits.getInt(it).coerceAtLeast(0) } ?: emptyMap()
        val blocks = linkedMapOf<String, ProtectedBlockDefinition>()
        val blockSection = config.getConfigurationSection("blocks")
        for (id in blockSection?.getKeys(false).orEmpty()) {
            val section = blockSection?.getConfigurationSection(id) ?: continue
            val materialName = section.getString("material", "") ?: ""
            val material = Material.matchMaterial(materialName)
            if (material == null || !material.isBlock) {
                warn("Unknown block material '$materialName' for $id; skipping")
                continue
            }
            val allowedTnts = section.getStringList("allowed-tnts").map { it.lowercase(Locale.ROOT) }.toSet()
            for (tnt in allowedTnts - knownTntIds) warn("Unknown TNT id '$tnt' for block $id")
            blocks[id] = ProtectedBlockDefinition(
                blockId = id,
                displayName = section.getString("display-name", id) ?: id,
                material = material,
                unique = section.getBoolean("unique", false),
                radius = section.getInt("radius", 10).coerceAtLeast(0),
                maxDurability = section.getInt("durability", 1).coerceAtLeast(1),
                allowedTnts = allowedTnts,
                explosionBlockChance = section.getInt("explosion-block-chance", 0).coerceIn(0, 100),
                memberHologramViewDistance = section.getInt("hologram.member-view-distance", 48).coerceAtLeast(0),
                otherHologramViewDistance = section.getInt("hologram.other-view-distance", 24).coerceAtLeast(0),
            )
        }
        val byMaterial = linkedMapOf<Material, ProtectedBlockDefinition>()
        for (block in blocks.values.filterNot { it.unique }) {
            if (byMaterial.putIfAbsent(block.material, block) != null) {
                warn("Multiple non-unique blocks use ${block.material}; using the first")
            }
        }
        return ShieldyConfig(
            databaseFile = config.getString("database.file", "regions.db") ?: "regions.db",
            hologram = ShieldyHologramConfig(
                offsetY = config.getDouble("hologram.offset-y", 2.1),
                lineSpacing = config.getDouble("hologram.line-spacing", 0.25),
                refreshTicks = config.getLong("hologram.refresh-ticks", 10).coerceAtLeast(1),
                lines = config.getStringList("hologram.lines"),
            ),
            limits = ShieldyLimitsConfig(config.getInt("limits.default", 4).coerceAtLeast(0), overrides),
            blacklistedWorlds = config.getStringList("blacklisted-worlds").toSet(),
            defaultRegionCommands = config.getStringList("default-region-commands"),
            messages = messages,
            protectedBlocksByMaterial = byMaterial,
            protectedBlocksById = blocks,
        )
    }
}
