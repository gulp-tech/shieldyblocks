package mk.hypertnt;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;

public final class WorldGuardBridge {

    private final HyperTNTPlugin plugin;

    public WorldGuardBridge(HyperTNTPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Разрешает взрыв обычных блоков внутри
     * регионов ShieldyBlocks.
     *
     * Все остальные WorldGuard-регионы
     * считаются административными и защищаются.
     */
    public boolean canExplode(
            Location center,
            Location target
    ) {

        if (center == null ||
                target == null ||
                center.getWorld() == null ||
                target.getWorld() == null) {
            return false;
        }

        if (!center.getWorld().equals(target.getWorld())) {
            return false;
        }

        try {

            RegionContainer container =
                    WorldGuard.getInstance()
                            .getPlatform()
                            .getRegionContainer();

            RegionQuery query =
                    container.createQuery();

            ApplicableRegionSet regions =
                    query.getApplicableRegions(
                            BukkitAdapter.adapt(target)
                    );

            for (ProtectedRegion region : regions) {

                String regionId =
                        region.getId()
                                .toLowerCase(Locale.ROOT);

                if (regionId.equals("__global__")) {
                    continue;
                }

                /*
                 * Разрешаем обход только тех регионов,
                 * которые реально принадлежат ShieldyBlocks.
                 */
                if (isShieldyRegion(
                        target,
                        regionId
                )) {
                    continue;
                }

                /*
                 * Любой другой регион защищён.
                 * В том числе спавн.
                 */
                return false;
            }

            return true;

        } catch (Exception exception) {

            plugin.getLogger().warning(
                    "WorldGuard explosion check failed ("
                            + exception.getClass().getSimpleName()
                            + "): "
                            + exception.getMessage()
            );

            return false;
        }
    }

    /**
     * Проверяет, является ли конкретный регион
     * настоящим регионом ShieldyBlocks.
     */
    private boolean isShieldyRegion(
            Location location,
            String regionId
    ) {

        Plugin shieldy =
                getShieldyPlugin();

        if (shieldy == null) {
            return false;
        }

        try {

            Object repository =
                    shieldy.getClass()
                            .getMethod(
                                    "currentRegionRepository"
                            )
                            .invoke(shieldy);

            /*
             * Проверяем реальные записи SQLite.
             *
             * Это надёжнее, чем определять
             * регион по его названию.
             */
            Method findAll =
                    repository.getClass()
                            .getMethod("findAll");

            Object result =
                    findAll.invoke(repository);

            if (!(result instanceof Iterable<?> records)) {
                return false;
            }

            for (Object record : records) {

                String actualId =
                        String.valueOf(
                                record.getClass()
                                        .getMethod(
                                                "getRegionName"
                                        )
                                        .invoke(record)
                        );

                if (!actualId.equalsIgnoreCase(regionId)) {
                    continue;
                }

                String worldName =
                        String.valueOf(
                                record.getClass()
                                        .getMethod(
                                                "getWorldName"
                                        )
                                        .invoke(record)
                        );

                if (!worldName.equals(
                        location.getWorld().getName()
                )) {
                    continue;
                }

                /*
                 * Здесь запись найдена в настоящей
                 * базе ShieldyBlocks.
                 *
                 * Дополнительно проверяем, что
                 * координаты действительно входят
                 * в соответствующий приват.
                 */
                Object manager =
                        shieldy.getClass()
                                .getMethod(
                                        "currentRegionManager"
                                )
                                .invoke(shieldy);

                Object foundRecord =
                        manager.getClass()
                                .getMethod(
                                        "findRegionAt",
                                        Location.class
                                )
                                .invoke(
                                        manager,
                                        location
                                );

                if (foundRecord == null) {
                    return false;
                }

                String foundId =
                        String.valueOf(
                                foundRecord.getClass()
                                        .getMethod(
                                                "getRegionName"
                                        )
                                        .invoke(foundRecord)
                        );

                return foundId.equalsIgnoreCase(
                        regionId
                );
            }

            return false;

        } catch (Exception exception) {

            plugin.getLogger().warning(
                    "ShieldyBlocks region check failed ("
                            + exception.getClass().getSimpleName()
                            + "): "
                            + exception.getMessage()
            );

            return false;
        }
    }

    private Plugin getShieldyPlugin() {

        Plugin shieldy =
                Bukkit.getPluginManager()
                        .getPlugin("ShieldyBlocks");

        if (shieldy == null ||
                !shieldy.isEnabled()) {
            return null;
        }

        return shieldy;
    }

    /**
     * Проверяет наличие ядра ShieldyBlocks.
     */
    public boolean isShieldCore(
            Location location
    ) {
        return findShieldCore(location) != null;
    }

    /**
     * Находит ядро по точным координатам.
     */
    public Object findShieldCore(
            Location location
    ) {

        Plugin shieldy =
                getShieldyPlugin();

        if (shieldy == null ||
                location == null ||
                location.getWorld() == null) {
            return null;
        }

        try {

            Object repository =
                    shieldy.getClass()
                            .getMethod(
                                    "currentRegionRepository"
                            )
                            .invoke(shieldy);

            Method findMethod =
                    repository.getClass()
                            .getMethod(
                                    "findByLocation",
                                    String.class,
                                    int.class,
                                    int.class,
                                    int.class
                            );

            return findMethod.invoke(
                    repository,
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );

        } catch (Exception exception) {

            plugin.getLogger().warning(
                    "Shield core lookup failed ("
                            + exception.getClass().getSimpleName()
                            + "): "
                            + exception.getMessage()
            );

            return null;
        }
    }

    /**
     * Повреждает ядро ShieldyBlocks.
     *
     * Само изменение прочности выполняет
     * ShieldRegionManager.
     *
     * Используется перегрузка applyTntImpact(record, tntId),
     * чтобы allowed-tnts корректно учитывал
     * реальный тип взрывчатки.
     */
    public boolean damageShieldCore(
            Location location,
            String tntId
    ) {

        Object record =
                findShieldCore(location);

        if (record == null) {
            return false;
        }

        Plugin shieldy =
                getShieldyPlugin();

        if (shieldy == null) {
            return false;
        }

        String resolvedTntId =
                (tntId == null || tntId.isBlank())
                        ? "vanilla"
                        : tntId;

        try {

            Object manager =
                    shieldy.getClass()
                            .getMethod(
                                    "currentRegionManager"
                            )
                            .invoke(shieldy);

            Method twoParamMethod = null;
            Method oneParamMethod = null;

            for (Method method :
                    manager.getClass().getMethods()) {

                if (!method.getName()
                        .equals("applyTntImpact")) {
                    continue;
                }

                Class<?>[] paramTypes =
                        method.getParameterTypes();

                if (
                        paramTypes.length == 2 &&
                                paramTypes[1] == String.class
                ) {
                    twoParamMethod = method;
                    continue;
                }

                if (paramTypes.length == 1) {
                    oneParamMethod = method;
                }
            }

            Object result;

            if (twoParamMethod != null) {

                result = twoParamMethod.invoke(
                        manager,
                        record,
                        resolvedTntId
                );

            } else if (oneParamMethod != null) {

                plugin.getLogger().warning(
                        "ShieldyBlocks does not expose "
                                + "applyTntImpact(record, tntId); "
                                + "falling back to legacy method, "
                                + "allowed-tnts will not work correctly."
                );

                result = oneParamMethod.invoke(
                        manager,
                        record
                );

            } else {

                plugin.getLogger().warning(
                        "ShieldyBlocks applyTntImpact "
                                + "method not found."
                );

                return false;
            }

            if (result == null) {
                return false;
            }

            String resultName =
                    result.getClass()
                            .getSimpleName();

            if (resultName.equals("Destroyed")) {

                Object definition =
                        result.getClass()
                                .getMethod(
                                        "getDefinition"
                                )
                                .invoke(result);

                location.getBlock().setType(
                        Material.AIR,
                        false
                );

                Method dropMethod = null;

                for (Method method :
                        manager.getClass()
                                .getMethods()) {

                    if (
                            method.getName()
                                    .equals(
                                            "dropShieldBlockItem"
                                    ) &&
                                    method.getParameterCount() == 2
                    ) {
                        dropMethod = method;
                        break;
                    }
                }

                if (dropMethod != null) {

                    dropMethod.invoke(
                            manager,
                            location,
                            definition
                    );
                }
            }

            return true;

        } catch (Exception exception) {

            plugin.getLogger().warning(
                    "ShieldyBlocks TNT integration failed ("
                            + exception.getClass().getSimpleName()
                            + "): "
                            + exception.getMessage()
            );

            return false;
        }
    }
}