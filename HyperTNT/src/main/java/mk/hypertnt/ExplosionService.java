package mk.hypertnt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

public final class ExplosionService {

    private final HyperTNTPlugin plugin;
    private final Random random = new Random();

    public ExplosionService(HyperTNTPlugin plugin) {
        this.plugin = plugin;
    }

    public void explode(
            Location center,
            TntManager.TntType type,
            Entity source
    ) {

        World world = center.getWorld();

        if (world == null || type == null) {
            return;
        }

        ConfigurationSection config = type.config;

        double radius = Math.max(
                0.1,
                Math.min(64.0, type.radius())
        );

        int maxBlocks = config.getInt(
                "explosion.blocks.max-blocks",
                0
        );

        boolean inLiquid = config.getBoolean(
                "explosion.blocks.in-liquid",
                false
        );

        Set<Block> affected = collectBlocks(
                center,
                type,
                radius
        );

        /*
         * Защита от повторной обработки ядра
         * в рамках одного взрыва.
         */
        Set<Location> processedCores = new HashSet<>();

        int changed = 0;

        for (Block block : affected) {

            if (maxBlocks > 0 && changed >= maxBlocks) {
                break;
            }

            Location location = block.getLocation();

            /*
             * Проверка защиты WorldGuard.
             *
             * Разрешены обычные блоки и регионы
             * ShieldyBlocks.
             *
             * Административные регионы защищены.
             */
            if (!plugin.getWorldGuardBridge()
                    .canExplode(center, location)) {
                continue;
            }

            Material material = block.getType();

            if (material.isAir()) {
                continue;
            }

            if (!inLiquid && isLiquid(material)) {
                continue;
            }

            /*
             * Сначала проверяем ядро ShieldyBlocks.
             *
             * Это важно для материалов вроде
             * ANCIENT_DEBRIS и REINFORCED_DEEPSLATE.
             */
            if (plugin.getWorldGuardBridge()
                    .isShieldCore(location)) {

                if (!processedCores.add(location)) {
                    continue;
                }

                plugin.getWorldGuardBridge()
                        .damageShieldCore(
                                location,
                                type.id
                        );

                changed++;
                continue;
            }

            /*
             * Правила разрушения обычных блоков.
             */
            String mode = type.blockMode(material);

            if (mode.equalsIgnoreCase("IGNORE")) {
                continue;
            }

            if (mode.equalsIgnoreCase("PLACE")) {

                Material place = Material.matchMaterial(
                        config.getString(
                                "explosion.blocks.place.material",
                                "SAND"
                        )
                );

                if (place != null && !place.isAir()) {
                    block.setType(place, false);
                    changed++;
                }

                continue;
            }

            if (isUnbreakable(material)) {
                continue;
            }

            breakBlock(
                    block,
                    type.dropChance(material)
            );

            changed++;
        }

        damageEntities(center, type, source);
        applyFire(center, type, affected);
        applyCommands(center, type);
        playEffects(center, type);
    }

    private Set<Block> collectBlocks(
            Location center,
            TntManager.TntType type,
            double radius
    ) {

        Set<Block> blocks = new LinkedHashSet<>();

        World world = center.getWorld();

        if (world == null) {
            return blocks;
        }

        int rays = type.config.getInt(
                "explosion.raycast.rays",
                16
        );

        rays = Math.max(2, Math.min(64, rays));

        double strengthMultiplier =
                type.config.getDouble(
                        "explosion.raycast.strength-multiplier",
                        1.0
                );

        double resistanceMultiplier =
                type.config.getDouble(
                        "explosion.raycast.resistance-multiplier",
                        1.0
                );

        for (int x = 0; x < rays; x++) {
            for (int y = 0; y < rays; y++) {
                for (int z = 0; z < rays; z++) {

                    if (
                            x != 0 && x != rays - 1 &&
                                    y != 0 && y != rays - 1 &&
                                    z != 0 && z != rays - 1
                    ) {
                        continue;
                    }

                    Vector direction = new Vector(
                            x / (double) (rays - 1) * 2 - 1,
                            y / (double) (rays - 1) * 2 - 1,
                            z / (double) (rays - 1) * 2 - 1
                    );

                    if (direction.lengthSquared() == 0) {
                        continue;
                    }

                    direction.normalize().multiply(0.3);

                    Location position = center.clone();

                    double strength =
                            radius *
                                    (0.7 + random.nextDouble() * 0.6) *
                                    strengthMultiplier;

                    double distance = 0;

                    while (
                            strength > 0 &&
                                    distance <= radius
                    ) {

                        Block block = position.getBlock();

                        Material material = block.getType();

                        if (!material.isAir()) {

                            blocks.add(block);

                            double resistance =
                                    material.getBlastResistance();

                            strength -=
                                    (
                                            resistance *
                                                    resistanceMultiplier +
                                                    0.3
                                    ) * 0.3;
                        }

                        position.add(direction);

                        strength -= 0.225;
                        distance += 0.3;
                    }
                }
            }
        }

        return blocks;
    }

    private void breakBlock(
            Block block,
            double dropChance
    ) {

        BlockState state = block.getState();

        Collection<ItemStack> drops =
                block.getDrops();

        /*
         * Сохраняем содержимое контейнеров.
         */
        if (state instanceof InventoryHolder holder) {

            for (ItemStack item :
                    holder.getInventory().getContents()) {

                if (
                        item == null ||
                                item.getType().isAir()
                ) {
                    continue;
                }

                block.getWorld().dropItemNaturally(
                        block.getLocation().add(
                                0.5,
                                0.5,
                                0.5
                        ),
                        item.clone()
                );
            }

            holder.getInventory().clear();
        }

        for (ItemStack drop : drops) {

            if (
                    dropChance < 1.0 &&
                            random.nextDouble() > dropChance
            ) {
                continue;
            }

            block.getWorld().dropItemNaturally(
                    block.getLocation().add(
                            0.5,
                            0.5,
                            0.5
                    ),
                    drop
            );
        }

        block.setType(Material.AIR, false);
    }

    private void damageEntities(
            Location center,
            TntManager.TntType type,
            Entity source
    ) {

        ConfigurationSection config = type.config;

        if (!config.getBoolean(
                "explosion.damage.enabled",
                true
        )) {
            return;
        }

        double radius = type.radius();

        double maximum = config.getDouble(
                "explosion.damage.max-damage",
                20
        );

        double minimum = config.getDouble(
                "explosion.damage.min-damage",
                0
        );

        double knockback = config.getDouble(
                "explosion.knockback.living-multiplier",
                1.0
        );

        for (Entity entity :
                center.getWorld().getNearbyEntities(
                        center,
                        radius,
                        radius,
                        radius
                )) {

            if (
                    entity == source ||
                            !(entity instanceof LivingEntity living)
            ) {
                continue;
            }

            double distance =
                    entity.getLocation().distance(center);

            if (distance > radius) {
                continue;
            }

            double factor =
                    1.0 - distance / radius;

            double damage =
                    minimum +
                            (maximum - minimum) * factor;

            if (damage > 0) {
                living.damage(damage, source);
            }

            if (config.getBoolean(
                    "explosion.knockback.enabled",
                    true
            )) {

                Vector direction =
                        entity.getLocation()
                                .toVector()
                                .subtract(
                                        center.toVector()
                                );

                if (direction.lengthSquared() > 0) {

                    direction.normalize()
                            .multiply(
                                    factor * knockback
                            );

                    entity.setVelocity(
                            entity.getVelocity()
                                    .add(direction)
                    );
                }
            }
        }
    }

    private void applyFire(
            Location center,
            TntManager.TntType type,
            Set<Block> affected
    ) {

        if (!type.config.getBoolean(
                "explosion.post.set-fire",
                false
        )) {
            return;
        }

        double chance =
                type.config.getDouble(
                        "explosion.post.fire-chance",
                        0
                );

        for (Block block : affected) {

            if (!block.getType().isAir()) {
                continue;
            }

            if (random.nextDouble() > chance) {
                continue;
            }

            if (!plugin.getWorldGuardBridge()
                    .canExplode(
                            center,
                            block.getLocation()
                    )) {
                continue;
            }

            Block below =
                    block.getRelative(
                            0,
                            -1,
                            0
                    );

            if (below.getType().isSolid()) {
                block.setType(
                        Material.FIRE,
                        false
                );
            }
        }
    }

    private void applyCommands(
            Location center,
            TntManager.TntType type
    ) {

        for (String command :
                type.config.getStringList(
                        "explosion.post.commands"
                )) {

            String formatted =
                    command
                            .replace(
                                    "{world}",
                                    center.getWorld().getName()
                            )
                            .replace(
                                    "{x}",
                                    String.valueOf(
                                            center.getBlockX()
                                    )
                            )
                            .replace(
                                    "{y}",
                                    String.valueOf(
                                            center.getBlockY()
                                    )
                            )
                            .replace(
                                    "{z}",
                                    String.valueOf(
                                            center.getBlockZ()
                                    )
                            );

            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    formatted
            );
        }
    }

    private void playEffects(
            Location center,
            TntManager.TntType type
    ) {

        World world = center.getWorld();

        if (world == null) {
            return;
        }

        String soundName =
                type.config.getString(
                        "visual.sounds.explosion.sound",
                        "ENTITY_GENERIC_EXPLODE"
                );

        try {

            Sound sound =
                    Sound.valueOf(
                            soundName.toUpperCase(
                                    Locale.ROOT
                            )
                    );

            world.playSound(
                    center,
                    sound,
                    (float) type.config.getDouble(
                            "visual.sounds.explosion.volume",
                            4
                    ),
                    (float) type.config.getDouble(
                            "visual.sounds.explosion.pitch",
                            1
                    )
            );

        } catch (Exception ignored) {

            world.playSound(
                    center,
                    Sound.ENTITY_GENERIC_EXPLODE,
                    4,
                    1
            );
        }

        world.spawnParticle(
                Particle.EXPLOSION,
                center,
                1
        );

        if (type.config.getBoolean(
                "visual.particles.enabled",
                false
        )) {

            String particleName =
                    type.config.getString(
                            "visual.particles.type",
                            "FLAME"
                    );

            if (particleName.equalsIgnoreCase(
                    "EXPLOSION_NORMAL"
            )) {
                particleName = "EXPLOSION";
            }

            if (particleName.equalsIgnoreCase(
                    "WATER_SPLASH"
            )) {
                particleName = "SPLASH";
            }

            try {

                Particle particle =
                        Particle.valueOf(
                                particleName.toUpperCase(
                                        Locale.ROOT
                                )
                        );

                world.spawnParticle(
                        particle,
                        center,
                        type.config.getInt(
                                "visual.particles.count",
                                20
                        ),
                        type.config.getDouble(
                                "visual.particles.offset.x",
                                0.3
                        ),
                        type.config.getDouble(
                                "visual.particles.offset.y",
                                0.3
                        ),
                        type.config.getDouble(
                                "visual.particles.offset.z",
                                0.3
                        ),
                        type.config.getDouble(
                                "visual.particles.extra",
                                0
                        )
                );

            } catch (Exception ignored) {
            }
        }
    }

    private boolean isLiquid(
            Material material
    ) {
        return material == Material.WATER ||
                material == Material.LAVA;
    }

    private boolean isUnbreakable(
            Material material
    ) {

        return switch (material) {
            case BEDROCK,
                 BARRIER,
                 COMMAND_BLOCK,
                 CHAIN_COMMAND_BLOCK,
                 REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK,
                 STRUCTURE_VOID,
                 END_PORTAL,
                 END_PORTAL_FRAME,
                 NETHER_PORTAL -> true;

            default -> false;
        };
    }
}