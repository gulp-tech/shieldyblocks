package im.apeki.shieldyblocks.util

import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import java.util.Locale

/** HyperTNT marks primed TNT with this persistent key; no class-loader coupling is needed. */
object HyperTntBridge {
    private val typeKey = NamespacedKey("hypertnt", "tnt_type_entity")

    fun resolveTypeId(entity: Entity): String? = entity.persistentDataContainer
        .get(typeKey, PersistentDataType.STRING)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)
}
