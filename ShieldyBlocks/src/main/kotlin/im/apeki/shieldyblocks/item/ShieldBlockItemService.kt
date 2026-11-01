package im.apeki.shieldyblocks.item

import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.config.ShieldyConfig
import im.apeki.shieldyblocks.config.getProtectedBlock
import im.apeki.shieldyblocks.util.MessageFormatter
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

class ShieldBlockItemService(
    plugin: JavaPlugin,
) {
    private val blockIdKey = NamespacedKey(plugin, "shield_block_id")

    fun createItem(definition: ProtectedBlockDefinition): ItemStack {
        return ItemStack(definition.material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(
                    MessageFormatter.format(definition.displayName),
                )

                if (definition.unique) {
                    meta.persistentDataContainer.set(
                        blockIdKey,
                        PersistentDataType.STRING,
                        definition.blockId,
                    )
                }
            }
        }
    }

    fun resolvePlacedDefinition(
        config: ShieldyConfig,
        item: ItemStack?,
    ): ProtectedBlockDefinition? {
        if (item == null || item.type.isAir) {
            return null
        }

        val uniqueBlockId = item.itemMeta
            ?.persistentDataContainer
            ?.get(blockIdKey, PersistentDataType.STRING)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        if (uniqueBlockId != null) {
            val definition =
                config.getProtectedBlock(uniqueBlockId)
                    ?: return null

            return definition.takeIf {
                it.unique && it.material == item.type
            }
        }

        return config.getProtectedBlock(item.type)
    }
}