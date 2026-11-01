package im.apeki.shieldyblocks.region

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.domains.DefaultDomain
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.managers.RegionManager
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import im.apeki.shieldyblocks.config.ProtectedBlockDefinition
import im.apeki.shieldyblocks.data.ShieldRegionRecord
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin

class WorldGuardRegionService(
    private val plugin: JavaPlugin,
) {

    fun createRegion(
        world: World,
        record: ShieldRegionRecord,
        definition: ProtectedBlockDefinition,
    ): Boolean {
        val regionManager =
            getRegionManager(world) ?: return false

        if (regionManager.getRegion(record.regionName) != null) {
            return false
        }

        return saveRegion(
            regionManager,
            buildRegion(record, world, definition),
        )
    }

    fun ensureRegionExists(
        world: World,
        record: ShieldRegionRecord,
        definition: ProtectedBlockDefinition,
    ): Boolean {
        val regionManager =
            getRegionManager(world) ?: return false

        return saveRegion(
            regionManager,
            buildRegion(record, world, definition),
        )
    }

    fun hasIntersection(
        world: World,
        record: ShieldRegionRecord,
        definition: ProtectedBlockDefinition,
        excludedRegionName: String? = null,
    ): Boolean {
        val regionManager =
            getRegionManager(world) ?: return false

        val candidate =
            buildRegion(record, world, definition)

        return candidate
            .getIntersectingRegions(
                regionManager.regions.values,
            )
            .any { region ->
                region.id != "__global__" &&
                        region.id != excludedRegionName
            }
    }

    fun syncRegionMembers(
        world: World,
        record: ShieldRegionRecord,
    ): Boolean {
        val regionManager =
            getRegionManager(world) ?: return false

        val region =
            regionManager.getRegion(record.regionName)
                ?: return false

        region.owners = DefaultDomain()
        region.members = DefaultDomain()

        region.owners.addPlayer(record.ownerName)

        for (participantName in record.sortedParticipants()) {
            region.members.addPlayer(participantName)
        }

        return try {
            regionManager.saveChanges()
            true
        } catch (exception: Exception) {
            plugin.logger.warning(
                "Failed to update region members for " +
                        "${record.regionName}: ${exception.message}",
            )
            false
        }
    }

    fun deleteRegion(
        world: World,
        regionName: String,
    ): Boolean {
        val regionManager =
            getRegionManager(world) ?: return false

        if (regionManager.getRegion(regionName) == null) {
            return true
        }

        return try {
            regionManager.removeRegion(regionName)
            regionManager.saveChanges()
            true
        } catch (exception: Exception) {
            plugin.logger.warning(
                "Failed to remove region $regionName " +
                        "from WorldGuard: ${exception.message}",
            )
            false
        }
    }

    private fun buildRegion(
        record: ShieldRegionRecord,
        world: World,
        definition: ProtectedBlockDefinition,
    ): ProtectedCuboidRegion {
        val minY =
            (record.blockY - definition.radius)
                .coerceAtLeast(world.minHeight)
                .coerceAtMost(world.maxHeight - 1)

        val maxY =
            (record.blockY + definition.radius)
                .coerceAtLeast(minY)
                .coerceAtMost(world.maxHeight - 1)

        val min = BlockVector3.at(
            record.blockX - definition.radius,
            minY,
            record.blockZ - definition.radius,
        )

        val max = BlockVector3.at(
            record.blockX + definition.radius,
            maxY,
            record.blockZ + definition.radius,
        )

        return ProtectedCuboidRegion(
            record.regionName,
            min,
            max,
        ).also { region ->

            // Владелец.
            region.owners.addPlayer(record.ownerName)

            // Участники добавленные через /ps add.
            for (participantName in record.sortedParticipants()) {
                region.members.addPlayer(participantName)
            }

            // Ломать блоки.
            region.setFlag(
                Flags.BLOCK_BREAK,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.BLOCK_BREAK.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Ставить блоки.
            region.setFlag(
                Flags.BLOCK_PLACE,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.BLOCK_PLACE.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Открывать сундуки, бочки, шалкеры.
            region.setFlag(
                Flags.CHEST_ACCESS,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.CHEST_ACCESS.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Кнопки, рычаги, двери, люки.
            region.setFlag(
                Flags.USE,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.USE.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Взаимодействие с сущностями (фреймы, стойки брони).
            region.setFlag(
                Flags.INTERACT,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.INTERACT.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Урон по животным посторонними.
            region.setFlag(
                Flags.DAMAGE_ANIMALS,
                StateFlag.State.DENY,
            )
            region.setFlag(
                Flags.DAMAGE_ANIMALS.regionGroupFlag,
                RegionGroup.NON_MEMBERS,
            )

            // Поршни не двигают блоки внутри.
            region.setFlag(
                Flags.PISTONS,
                StateFlag.State.DENY,
            )

            // Снежный голем не кладёт снег.
            region.setFlag(
                Flags.SNOWMAN_TRAILS,
                StateFlag.State.DENY,
            )

            // Эндермен не берёт и не кладёт блоки.
            region.setFlag(
                Flags.ENDER_BUILD,
                StateFlag.State.DENY,
            )

            // Взрывы разрешены.
            // ShieldyBlocks сам обрабатывает ядро привата.
            region.setFlag(
                Flags.TNT,
                StateFlag.State.ALLOW,
            )

            region.setFlag(
                Flags.CREEPER_EXPLOSION,
                StateFlag.State.ALLOW,
            )

            region.setFlag(
                Flags.OTHER_EXPLOSION,
                StateFlag.State.ALLOW,
            )

            region.setFlag(
                Flags.GHAST_FIREBALL,
                StateFlag.State.ALLOW,
            )

            region.setFlag(
                Flags.WITHER_DAMAGE,
                StateFlag.State.ALLOW,
            )

            region.setFlag(
                Flags.ENDERDRAGON_BLOCK_DAMAGE,
                StateFlag.State.ALLOW,
            )

            // PvP разрешён.
            region.setFlag(
                Flags.PVP,
                StateFlag.State.ALLOW,
            )

            // Мобы спавнятся.
            region.setFlag(
                Flags.MOB_SPAWNING,
                StateFlag.State.ALLOW,
            )

            // Сообщения при входе и выходе.
            region.setFlag(
                Flags.GREET_TITLE,
                "Вы вошли в регион игрока ${record.ownerName}",
            )

            region.setFlag(
                Flags.FAREWELL_TITLE,
                "Вы покинули регион игрока ${record.ownerName}",
            )
        }
    }

    private fun saveRegion(
        regionManager: RegionManager,
        region: ProtectedCuboidRegion,
    ): Boolean {
        return try {
            regionManager.addRegion(region)
            regionManager.saveChanges()
            true
        } catch (exception: Exception) {
            plugin.logger.warning(
                "Failed to save region ${region.id} " +
                        "to WorldGuard: ${exception.message}",
            )
            false
        }
    }

    private fun getRegionManager(
        world: World,
    ): RegionManager? {
        return WorldGuard.getInstance()
            .platform
            .regionContainer
            .get(BukkitAdapter.adapt(world))
    }
}