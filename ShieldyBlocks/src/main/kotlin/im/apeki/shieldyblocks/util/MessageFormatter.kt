package im.apeki.shieldyblocks.util

import org.bukkit.ChatColor

object MessageFormatter {
    fun format(template: String, placeholders: Map<String, String> = emptyMap()): String {
        var result = template
        for ((key, value) in placeholders) {
            result = result.replace("{$key}", value)
        }
        result = Regex("rgb\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)").replace(result) {
            val hex = String.format("%02X%02X%02X",
                it.groupValues[1].toInt().coerceIn(0,255),
                it.groupValues[2].toInt().coerceIn(0,255),
                it.groupValues[3].toInt().coerceIn(0,255))
            "§x" + hex.map { c -> "§$c" }.joinToString("")
        }
        result = Regex("&#([0-9A-Fa-f]{6})").replace(result) {
            "§x" + it.groupValues[1].map { c -> "§$c" }.joinToString("")
        }
        return ChatColor.translateAlternateColorCodes('&', result)
    }
}