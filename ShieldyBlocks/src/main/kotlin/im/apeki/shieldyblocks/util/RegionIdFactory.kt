package im.apeki.shieldyblocks.util

import java.util.Locale

object RegionIdFactory {
    fun create(world: String, x: Int, y: Int, z: Int): String =
        "sb_${world.lowercase(Locale.ROOT)}_${x}_${y}_${z}"
}