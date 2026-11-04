package mk.hypertnt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class TntListener implements Listener {

    private final HyperTNTPlugin plugin;

    private final Map<Location, String> placed =
            new HashMap<>();

    private final Map<UUID, List<ArmorStand>> holograms =
            new HashMap<>();

    private final Map<UUID, BukkitTask> tasks =
            new HashMap<>();

    private final Map<String, Long> cooldowns =
            new HashMap<>();

    public TntListener(HyperTNTPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {

        ItemStack item = event.getItemInHand();

        String id =
                plugin.getTntManager().getItemType(item);

        if (id == null) {
            return;
        }

        TntManager.TntType type =
                plugin.getTntManager().getType(id);

        if (type == null) {
            return;
        }

        Location location =
                event.getBlockPlaced().getLocation();

        placed.put(location, id);

        if (type.autoIgnite()) {

            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {

                        if (location.getBlock().getType()
                                == Material.TNT) {

                            prime(
                                    location,
                                    type,
                                    event.getPlayer()
                            );
                        }
                    }
            );
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent event) {

        Location location =
                event.getBlock().getLocation();

        String id = placed.remove(location);

        if (id == null) {
            return;
        }

        TntManager.TntType type =
                plugin.getTntManager().getType(id);

        if (type == null) {
            return;
        }

        event.setDropItems(false);

        event.getBlock().getWorld().dropItemNaturally(
                location,
                plugin.getTntManager().createItem(id, 1)
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent event) {

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();

        ItemStack item = event.getItem();

        String id =
                plugin.getTntManager().getItemType(item);

        if (id != null) {

            TntManager.TntType type =
                    plugin.getTntManager().getType(id);

            if (type != null &&
                    type.throwEnabled() &&
                    event.getAction().isRightClick()) {

                event.setCancelled(true);

                if (!checkCooldown(player, type)) {
                    return;
                }

                if (player.getGameMode()
                        != GameMode.CREATIVE) {

                    item.setAmount(item.getAmount() - 1);
                }

                Location location =
                        player.getEyeLocation();

                Vector direction =
                        location.getDirection();

                double velocityXZ =
                        type.config.getDouble(
                                "ignite.throw.velocity-xz",
                                0.9
                        );

                double velocityY =
                        type.config.getDouble(
                                "ignite.throw.velocity-y",
                                0.2
                        );

                Vector velocity = new Vector(
                        direction.getX() * velocityXZ,
                        direction.getY() * velocityY,
                        direction.getZ() * velocityXZ
                );

                TNTPrimed primed =
                        spawnTnt(
                                location.add(
                                        direction.multiply(0.6)
                                ),
                                type
                        );

                primed.setVelocity(velocity);

                startVisuals(primed, type);

                return;
            }
        }

        if (event.getAction()
                != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        if (block == null ||
                block.getType() != Material.TNT) {
            return;
        }

        if (item == null) {
            return;
        }

        if (item.getType() != Material.FLINT_AND_STEEL &&
                item.getType() != Material.FIRE_CHARGE) {
            return;
        }

        String typeId =
                placed.get(block.getLocation());

        if (typeId == null) {
            return;
        }

        TntManager.TntType type =
                plugin.getTntManager().getType(typeId);

        if (type == null) {
            return;
        }

        event.setCancelled(true);

        if (!checkCooldown(player, type)) {
            return;
        }

        prime(
                block.getLocation(),
                type,
                player
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onRedstone(BlockRedstoneEvent event) {

        if (event.getNewCurrent() <= 0) {
            return;
        }

        Block block = event.getBlock();

        if (block.getType() != Material.TNT) {
            return;
        }

        String id = placed.get(block.getLocation());

        if (id == null) {
            return;
        }

        TntManager.TntType type =
                plugin.getTntManager().getType(id);

        if (type == null) {
            return;
        }

        if (!type.config.getBoolean(
                "ignite.allow.redstone",
                true
        )) {
            event.setNewCurrent(0);
            return;
        }

        prime(block.getLocation(), type, null);

        event.setNewCurrent(0);
    }

    private boolean checkCooldown(
            Player player,
            TntManager.TntType type
    ) {

        int seconds = type.cooldown();

        if (seconds <= 0) {
            return true;
        }

        String key =
                player.getUniqueId() + ":" + type.id;

        long now = System.currentTimeMillis();

        long expires =
                cooldowns.getOrDefault(key, 0L);

        if (expires > now) {

            long remaining =
                    (expires - now + 999) / 1000;

            player.sendMessage(
                    ChatColor.RED +
                            "Подождите " + remaining +
                            " сек. перед использованием."
            );

            return false;
        }

        cooldowns.put(
                key,
                now + seconds * 1000L
        );

        return true;
    }

    private void prime(
            Location location,
            TntManager.TntType type,
            Player player
    ) {

        if (location.getBlock().getType()
                != Material.TNT) {
            return;
        }

        location.getBlock().setType(
                Material.AIR,
                false
        );

        placed.remove(location);

        TNTPrimed primed =
                spawnTnt(
                        location.clone().add(0.5, 0, 0.5),
                        type
                );

        startVisuals(primed, type);
    }

    private TNTPrimed spawnTnt(
            Location location,
            TntManager.TntType type
    ) {

        TNTPrimed primed =
                location.getWorld().spawn(
                        location,
                        TNTPrimed.class
                );

        primed.setFuseTicks(
                Math.max(
                        1,
                        (int) Math.round(
                                type.fuseSeconds() * 20
                        )
                )
        );

        primed.getPersistentDataContainer().set(
                plugin.getTntManager().getEntityKey(),
                PersistentDataType.STRING,
                type.id
        );

        return primed;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onExplosionPrime(
            ExplosionPrimeEvent event
    ) {

        Entity entity = event.getEntity();

        if (!(entity instanceof TNTPrimed primed)) {
            return;
        }

        String id =
                plugin.getTntManager()
                        .getEntityType(primed);

        if (id == null) {
            return;
        }

        TntManager.TntType type =
                plugin.getTntManager().getType(id);

        if (type == null) {
            return;
        }

        event.setCancelled(true);

        removeVisuals(primed.getUniqueId());

        plugin.getExplosionService().explode(
                primed.getLocation(),
                type,
                primed
        );

        primed.remove();
    }

    private void startVisuals(
            TNTPrimed primed,
            TntManager.TntType type
    ) {

        if (!type.config.getBoolean(
                "visual.hologram.enabled",
                false
        )) {
            return;
        }

        List<String> lines =
                type.config.getStringList(
                        "visual.hologram.lines"
                );

        List<ArmorStand> stands =
                new ArrayList<>();

        double height = type.config.getDouble(
                "visual.hologram.height",
                2.0
        );

        double spacing = type.config.getDouble(
                "visual.hologram.line-spacing",
                0.25
        );

        for (int i = 0; i < lines.size(); i++) {

            Location location =
                    primed.getLocation().clone()
                            .add(
                                    0,
                                    height + i * spacing,
                                    0
                            );

            ArmorStand stand =
                    location.getWorld().spawn(
                            location,
                            ArmorStand.class
                    );

            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.setCustomNameVisible(true);

            stands.add(stand);
        }

        holograms.put(
                primed.getUniqueId(),
                stands
        );

        BukkitTask task =
                Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        () -> {

                            if (primed.isDead()) {
                                removeVisuals(
                                        primed.getUniqueId()
                                );
                                return;
                            }

                            double seconds =
                                    primed.getFuseTicks() / 20.0;

                            for (int i = 0;
                                 i < stands.size();
                                 i++) {

                                ArmorStand stand =
                                        stands.get(i);

                                if (stand.isDead()) {
                                    continue;
                                }

                                String text =
                                        lines.get(i)
                                                .replace(
                                                        "{time}",
                                                        String.format(
                                                                Locale.US,
                                                                "%.1f",
                                                                seconds
                                                        )
                                                );

                                stand.setCustomName(
                                        ChatColor.translateAlternateColorCodes(
                                                '&',
                                                text
                                        )
                                );

                                stand.teleport(
                                        primed.getLocation()
                                                .clone()
                                                .add(
                                                        0,
                                                        height + i * spacing,
                                                        0
                                                )
                                );
                            }

                        },
                        0L,
                        Math.max(
                                1,
                                type.config.getInt(
                                        "visual.hologram.update-period-ticks",
                                        4
                                )
                        )
                );

        tasks.put(
                primed.getUniqueId(),
                task
        );
    }

    private void removeVisuals(UUID id) {

        BukkitTask task = tasks.remove(id);

        if (task != null) {
            task.cancel();
        }

        List<ArmorStand> stands =
                holograms.remove(id);

        if (stands != null) {

            for (ArmorStand stand : stands) {
                if (!stand.isDead()) {
                    stand.remove();
                }
            }
        }
    }
}