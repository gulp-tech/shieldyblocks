package im.apeki.shieldyblocks.util

import org.bukkit.entity.Entity

object HyperTntBridge {
    fun resolveTypeId(entity: Entity): String? = try {
        val m = Class.forName("me.hyper_tnt.api.HyperTNTAPI")
            .getMethod("getTntId", Entity::class.java)
        (m.invoke(null, entity) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    } catch (_: Throwable) { null }
}