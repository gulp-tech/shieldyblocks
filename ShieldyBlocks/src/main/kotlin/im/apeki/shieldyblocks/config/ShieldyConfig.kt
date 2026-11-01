package im.apeki.shieldyblocks.config

import org.bukkit.Material

data class ShieldyConfig(
    val databaseFile: String,
    val hologram: ShieldyHologramConfig,
    val limits: ShieldyLimitsConfig,
    val blacklistedWorlds: Set<String>,
    val defaultRegionCommands: List<String>,
    val messages: ShieldyMessages,
    val protectedBlocksByMaterial: Map<Material, ProtectedBlockDefinition>,
    val protectedBlocksById: Map<String, ProtectedBlockDefinition>,
)

data class ShieldyHologramConfig(
    val offsetY: Double,
    val lineSpacing: Double,
    val refreshTicks: Long,
    val lines: List<String>,
)

data class ShieldyMessages(
    val regionCreated: String,
    val regionRemovedByPlayer: String,
    val regionRemovedByExplosion: String,
    val regionCreateFailed: String,
    val regionCreateIntersection: String,
    val regionCreateLimitReached: String,
    val regionRemoveFailed: String,
    val commandPlayerOnly: String,
    val commandNoPermission: String,
    val commandUsage: String,
    val commandUpdateFailed: String,
    val commandReloadSuccess: String,
    val commandReloadFailed: String,
    val commandGiveSuccess: String,
    val commandGivePlayerOffline: String,
    val commandGiveUnknownBlock: String,
    val commandLookupTntWaiting: String,
    val commandLookupTntSaved: String,
    val commandLookupTntEmpty: String,
    val commandLookupTntTimeout: String,
    val commandLookupTntInvalidName: String,
    val commandAddSuccess: String,
    val commandAddPlayerOffline: String,
    val commandAddAlreadyMember: String,
    val commandRemoveSuccess: String,
    val commandRemoveNotMember: String,
    val commandRemoveSelf: String,
    val commandNotInOwnRegion: String,
    val commandRegionNumberInvalid: String,
    val commandListEmpty: String,
    val commandListHeader: String,
    val commandListEntry: String,
    val commandListOtherWorldDistance: String,
    val commandToggleEnabled: String,
    val commandToggleDisabled: String,
    val commandInfoMessage: String,
    val commandInfoNotInRegion: String,
    val commandInfoNoPermissionOthers: String,
)

data class ShieldyLimitsConfig(
    val defaultLimit: Int,
    val permissionOverrides: Map<String, Int>,
)

data class ProtectedBlockDefinition(
    val blockId: String,
    val displayName: String,
    val material: Material,
    val unique: Boolean,
    val radius: Int,
    val maxDurability: Int,
    val allowedTnts: Set<String>,

    /*
     * 0   = никогда не блокирует
     * 50  = 50%
     * 100 = всегда блокирует
     */
    val explosionBlockChance: Int,

    val memberHologramViewDistance: Int,
    val otherHologramViewDistance: Int,
)

fun ShieldyConfig.getProtectedBlock(
    material: Material,
): ProtectedBlockDefinition? {
    return protectedBlocksByMaterial[material]
}

fun ShieldyConfig.getProtectedBlock(
    blockId: String,
): ProtectedBlockDefinition? {
    return protectedBlocksById[blockId]
}