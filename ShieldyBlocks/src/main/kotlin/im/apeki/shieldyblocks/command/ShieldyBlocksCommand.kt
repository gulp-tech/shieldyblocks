package im.apeki.shieldyblocks.command

import im.apeki.shieldyblocks.ShieldyBlocksPlugin
import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.data.ShieldRegionRecord
import im.apeki.shieldyblocks.region.ShieldRegionManager
import im.apeki.shieldyblocks.util.MessageFormatter
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class ShieldyBlocksCommand(
    private val plugin: ShieldyBlocksPlugin,
) : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val shieldyConfig = plugin.currentConfig()
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        return when (args[0].lowercase()) {
            "reload" -> {
                handleReload(sender)
                true
            }

            "give" -> {
                handleGive(sender, args.drop(1))
                true
            }

            "lookuptnt" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleLookupTnt(sender, args.drop(1))
                true
            }

            "add" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleAdd(sender, args.drop(1))
                true
            }

            "remove" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleRemove(sender, args.drop(1))
                true
            }

            "list" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleList(sender)
                true
            }

            "toggle" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleToggle(sender)
                true
            }

            "info" -> {
                if (sender !is Player) {
                    sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandPlayerOnly, emptyMap()))
                    return true
                }
                handleInfo(sender)
                true
            }

            else -> {
                sendUsage(sender)
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        return when (args.size) {
            1 -> {
                buildList {
                    if (sender is Player) {
                        add("add")
                        add("remove")
                        add("list")
                        add("toggle")
                        if (sender.hasPermission(INFO_PERMISSION)) {
                            add("info")
                        }
                    }
                    if (sender.hasPermission(ADMIN_PERMISSION)) {
                        add("reload")
                        add("give")
                        add("lookuptnt")
                    }
                }.filter { it.startsWith(args[0], ignoreCase = true) }
            }

            2 -> {
                if ((args[0].equals("add", ignoreCase = true) || args[0].equals("remove", ignoreCase = true)) && sender is Player) {
                    val regionNumbers = plugin.currentRegionManager().getOwnedRegions(sender.name).indices.map { (it + 1).toString() }
                    val playerNames = Bukkit.getOnlinePlayers().map(Player::getName)
                    (regionNumbers + playerNames).filter { it.startsWith(args[1], ignoreCase = true) }
                } else if (args[0].equals("give", ignoreCase = true) && sender.hasPermission(ADMIN_PERMISSION)) {
                    Bukkit.getOnlinePlayers()
                        .map(Player::getName)
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                } else if (args[0].equals("lookuptnt", ignoreCase = true) && sender.hasPermission(ADMIN_PERMISSION)) {
                    plugin.currentCustomTntService().ids()
                        .filter { it != "vanilla" }
                        .sorted()
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                } else {
                    emptyList()
                }
            }

            3 -> {
                if ((args[0].equals("add", ignoreCase = true) || args[0].equals("remove", ignoreCase = true)) &&
                    sender is Player &&
                    args[1].toIntOrNull() != null
                ) {
                    Bukkit.getOnlinePlayers()
                        .map(Player::getName)
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                } else if (args[0].equals("give", ignoreCase = true) && sender.hasPermission(ADMIN_PERMISSION)) {
                    plugin.currentConfig().protectedBlocksById.keys
                        .sorted()
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun handleLookupTnt(player: Player, args: List<String>) {
        val shieldyConfig = plugin.currentConfig()
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandNoPermission, emptyMap()))
            return
        }

        val tntId = args.singleOrNull()?.trim()?.lowercase()
        if (tntId.isNullOrEmpty() || tntId == "vanilla") {
            sendUsage(player)
            return
        }
        if (!plugin.currentCustomTntService().isValidCustomId(tntId)) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandLookupTntInvalidName, emptyMap()))
            return
        }

        plugin.currentCustomTntService().startLookup(player, tntId)
    }

    private fun handleReload(sender: CommandSender) {
        val shieldyConfig = plugin.currentConfig()
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandNoPermission, emptyMap()))
            return
        }

        when (val result = plugin.reloadRuntimeState()) {
            ShieldyBlocksPlugin.ReloadRuntimeResult.Success -> {
                sender.sendMessage(MessageFormatter.format(plugin.currentConfig().messages.commandReloadSuccess, emptyMap()))
            }

            is ShieldyBlocksPlugin.ReloadRuntimeResult.Failure -> {
                sender.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandReloadFailed,
                        mapOf("reason" to result.reason),
                    ),
                )
            }
        }
    }

    private fun handleGive(sender: CommandSender, args: List<String>) {
        val shieldyConfig = plugin.currentConfig()
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandNoPermission, emptyMap()))
            return
        }

        if (args.size != 2) {
            sendUsage(sender)
            return
        }

        val target = findOnlinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(
                MessageFormatter.format(
                    shieldyConfig.messages.commandGivePlayerOffline,
                    mapOf("target" to args[0]),
                ),
            )
            return
        }

        val definition = resolveBlockDefinition(args[1])
        if (definition == null) {
            sender.sendMessage(
                MessageFormatter.format(
                    shieldyConfig.messages.commandGiveUnknownBlock,
                    mapOf("block_id" to args[1]),
                ),
            )
            return
        }

        val item = plugin.currentShieldBlockItemService().createItem(definition)
        val leftovers = target.inventory.addItem(item).values
        for (leftover in leftovers) {
            target.world.dropItemNaturally(target.location, leftover)
        }

        sender.sendMessage(
            MessageFormatter.format(
                shieldyConfig.messages.commandGiveSuccess,
                mapOf(
                    "target" to target.name,
                    "block_id" to definition.blockId,
                    "block_name" to definition.displayName,
                ),
            ),
        )
    }

    private fun handleAdd(player: Player, args: List<String>) {
        val shieldyConfig = plugin.currentConfig()
        val shieldRegionManager = plugin.currentRegionManager()
        val targetArguments = parseTargetArguments(args) ?: return sendUsage(player)
        val target = findOnlinePlayer(targetArguments.playerName)
        if (target == null) {
            player.sendMessage(
                MessageFormatter.format(
                    shieldyConfig.messages.commandAddPlayerOffline,
                    mapOf("target" to targetArguments.playerName),
                ),
            )
            return
        }

        val region = resolveSelectedRegion(player, targetArguments.regionNumber) ?: return
        when (val result = shieldRegionManager.addParticipant(region, target.name)) {
            ShieldRegionManager.ParticipantChangeResult.AlreadyPresent -> {
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandAddAlreadyMember,
                        mapOf("target" to target.name),
                    ),
                )
            }

            ShieldRegionManager.ParticipantChangeResult.Failed -> {
                player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandUpdateFailed, emptyMap()))
            }

            ShieldRegionManager.ParticipantChangeResult.NotFound -> {
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandAddAlreadyMember,
                        mapOf("target" to target.name),
                    ),
                )
            }

            is ShieldRegionManager.ParticipantChangeResult.Updated -> {
                val regionNumber = shieldRegionManager.resolveRegionNumber(player.name, result.record.regionName) ?: 0
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandAddSuccess,
                        mapOf("target" to target.name, "number" to regionNumber.toString()),
                    ),
                )
            }
        }
    }

    private fun handleRemove(player: Player, args: List<String>) {
        val shieldyConfig = plugin.currentConfig()
        val shieldRegionManager = plugin.currentRegionManager()
        val targetArguments = parseTargetArguments(args) ?: return sendUsage(player)
        val region = resolveSelectedRegion(player, targetArguments.regionNumber) ?: return
        if (player.name.equals(targetArguments.playerName, ignoreCase = true)) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandRemoveSelf, emptyMap()))
            return
        }

        when (val result = shieldRegionManager.removeParticipant(region, targetArguments.playerName)) {
            ShieldRegionManager.ParticipantChangeResult.AlreadyPresent -> {
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandRemoveNotMember,
                        mapOf("target" to targetArguments.playerName),
                    ),
                )
            }

            ShieldRegionManager.ParticipantChangeResult.Failed -> {
                player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandUpdateFailed, emptyMap()))
            }

            ShieldRegionManager.ParticipantChangeResult.NotFound -> {
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandRemoveNotMember,
                        mapOf("target" to targetArguments.playerName),
                    ),
                )
            }

            is ShieldRegionManager.ParticipantChangeResult.Updated -> {
                val regionNumber = shieldRegionManager.resolveRegionNumber(player.name, result.record.regionName) ?: 0
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandRemoveSuccess,
                        mapOf("target" to targetArguments.playerName, "number" to regionNumber.toString()),
                    ),
                )
            }
        }
    }

    private fun handleList(player: Player) {
        val shieldyConfig = plugin.currentConfig()
        val shieldRegionManager = plugin.currentRegionManager()
        val regions = shieldRegionManager.getOwnedRegions(player.name)
        if (regions.isEmpty()) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandListEmpty, emptyMap()))
            return
        }

        player.sendMessage(
            MessageFormatter.format(
                shieldyConfig.messages.commandListHeader,
                mapOf("count" to regions.size.toString()),
            ),
        )
        for ((index, region) in regions.withIndex()) {
            val definition = shieldyConfig.protectedBlocksById[region.blockId]
            player.sendMessage(
                MessageFormatter.format(
                    shieldyConfig.messages.commandListEntry,
                    mapOf(
                        "number" to (index + 1).toString(),
                        "world" to region.worldName,
                        "x" to region.blockX.toString(),
                        "y" to region.blockY.toString(),
                        "z" to region.blockZ.toString(),
                        "distance" to shieldRegionManager.formatListDistance(player.location, region),
                        "block_name" to (definition?.displayName ?: region.blockId),
                        "block_id" to region.blockId,
                        "durability" to region.currentDurability.toString(),
                        "current_durability" to region.currentDurability.toString(),
                        "max_durability" to (definition?.maxDurability?.toString() ?: "-"),
                    ),
                ),
            )
        }
    }

    private fun handleToggle(player: Player) {
        val placementEnabled = plugin.toggleRegionPlacement(player.uniqueId)
        val message = if (placementEnabled) {
            plugin.currentConfig().messages.commandToggleEnabled
        } else {
            plugin.currentConfig().messages.commandToggleDisabled
        }
        player.sendMessage(MessageFormatter.format(message, emptyMap()))
    }

    private fun handleInfo(player: Player) {
        val shieldyConfig = plugin.currentConfig()
        if (!player.hasPermission(INFO_PERMISSION)) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandNoPermission, emptyMap()))
            return
        }

        val shieldRegionManager = plugin.currentRegionManager()
        val region = shieldRegionManager.findRegionAt(player.location)

        if (region == null) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandInfoNotInRegion, emptyMap()))
            return
        }

        if (!player.hasPermission(INFO_OTHERS_PERMISSION) && !region.ownerName.equals(player.name, ignoreCase = true) && !region.participantNames.any { it.equals(player.name, ignoreCase = true) }) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandInfoNoPermissionOthers, emptyMap()))
            return
        }

        val definition = shieldyConfig.protectedBlocksById[region.blockId] ?: return
        player.sendMessage(
            MessageFormatter.format(
                shieldyConfig.messages.commandInfoMessage,
                shieldRegionManager.placeholders(region, definition),
            ),
        )
    }

    private fun parseTargetArguments(args: List<String>): TargetArguments? {
        return when (args.size) {
            1 -> TargetArguments(regionNumber = null, playerName = args[0])
            2 -> {
                val regionNumber = args[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
                TargetArguments(regionNumber = regionNumber, playerName = args[1])
            }

            else -> null
        }
    }

    private fun resolveSelectedRegion(player: Player, regionNumber: Int?): ShieldRegionRecord? {
        val shieldyConfig = plugin.currentConfig()
        val shieldRegionManager = plugin.currentRegionManager()
        if (regionNumber != null) {
            val region = shieldRegionManager.findOwnedRegionByNumber(player.name, regionNumber)
            if (region == null) {
                player.sendMessage(
                    MessageFormatter.format(
                        shieldyConfig.messages.commandRegionNumberInvalid,
                        mapOf("number" to regionNumber.toString()),
                    ),
                )
            }
            return region
        }

        val region = shieldRegionManager.findOwnedRegionAt(player)
        if (region == null) {
            player.sendMessage(MessageFormatter.format(shieldyConfig.messages.commandNotInOwnRegion, emptyMap()))
        }
        return region
    }

    private fun resolveBlockDefinition(blockId: String): ProtectedBlockDefinition? {
        return plugin.currentConfig().protectedBlocksById.entries
            .firstOrNull { (key, _) -> key.equals(blockId, ignoreCase = true) }
            ?.value
    }

    private fun findOnlinePlayer(playerName: String): Player? {
        return Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(playerName, ignoreCase = true) }
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(MessageFormatter.format(plugin.currentConfig().messages.commandUsage, emptyMap()))
    }

    private data class TargetArguments(
        val regionNumber: Int?,
        val playerName: String,
    )

    private companion object {
        const val ADMIN_PERMISSION = "shieldyblocks.admin"
        const val INFO_PERMISSION = "shieldyblocks.info"
        const val INFO_OTHERS_PERMISSION = "shieldyblocks.info.others"
    }
}
