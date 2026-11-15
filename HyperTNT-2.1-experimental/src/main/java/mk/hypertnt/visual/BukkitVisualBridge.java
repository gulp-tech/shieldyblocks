package mk.hypertnt.visual;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import mk.hypertnt.HyperTNTPlugin;
import mk.hypertnt.TntRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.ServicePriority;

/** Optional visual-only observer. Does not modify Bukkit events, blocks or registry. */
public final class BukkitVisualBridge implements Listener, AutoCloseable {
    private final HyperTNTPlugin plugin;
    private final RegistryVisualPublisher source;
    private boolean closed;

    public BukkitVisualBridge(HyperTNTPlugin plugin) {
        this.plugin = plugin;
        this.source = new RegistryVisualPublisher(() -> plugin.getRegistry().snapshot(), new RegistryVisualPublisher.WorldAccess() {
            @Override public void assertMainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bukkit main thread required"); }
            @Override public boolean isLoaded(VisualFeed.Chunk chunk) {
                World world = Bukkit.getWorld(chunk.world());
                return world != null && world.isChunkLoaded(chunk.x(), chunk.z());
            }
            @Override public boolean isTnt(VisualFeed.Pos pos) {
                World world = Bukkit.getWorld(pos.world());
                if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)
                        || pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
                    throw new IllegalStateException("world/chunk unavailable or Y outside world bounds");
                }
                return world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == Material.TNT;
            }
            @Override public boolean isKnownType(String type) { return plugin.getTntManager().hasType(type); }
            @Override public void nextTick(Runnable task) { Bukkit.getScheduler().runTask(plugin, task); }
        }, plugin.getLogger()::warning);
    }
    public void start() {
        source.initialize();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getServicesManager().register(VisualSource.class, source, plugin, ServicePriority.Normal);
        plugin.getLogger().info("Экспериментальный источник Bedrock-визуала включён; отрисовка ядром не подключена.");
    }
    private void safe(Runnable action) {
        if (closed) return;
        try { action.run(); }
        catch (RuntimeException failure) {
            plugin.getLogger().warning("Ошибка экспериментального визуального источника; он отключён: " + failure);
            try { close(); } catch (RuntimeException ignored) { }
        }
    }
    private static VisualFeed.Chunk chunk(Block block) { return new VisualFeed.Chunk(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4); }
    private static VisualFeed.Chunk chunk(Chunk chunk) { return new VisualFeed.Chunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    private boolean relevant(Set<VisualFeed.Chunk> chunks) {
        for (VisualFeed.Chunk chunk : chunks) {
            if (source.isPending(chunk) || !plugin.getRegistry().positionsIn(chunk.world(), chunk.x(), chunk.z()).isEmpty()
                    || !source.chunkSnapshot(chunk).entries().isEmpty()) return true;
        }
        return false;
    }
    private void changed(Set<VisualFeed.Chunk> chunks, VisualFeed.Reason reason, BooleanSupplier cancelled) {
        if (relevant(chunks)) source.mutation(chunks, reason, cancelled);
    }
    public void primed(Location location) {
        safe(() -> changed(Set.of(new VisualFeed.Chunk(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4)), VisualFeed.Reason.PRIME, () -> false));
    }
    public void typesReloaded() { safe(source::invalidateAll); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        safe(() -> {
            Set<VisualFeed.Chunk> chunks = new LinkedHashSet<>(); chunks.add(chunk(event.getBlockPlaced()));
            if (event instanceof BlockMultiPlaceEvent multi) for (BlockState state : multi.getReplacedBlockStates()) chunks.add(chunk(state.getBlock()));
            changed(chunks, VisualFeed.Reason.PLACE, event::isCancelled);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { safe(() -> changed(Set.of(chunk(event.getBlock())), VisualFeed.Reason.REMOVE, event::isCancelled)); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { safe(() -> changed(Set.of(chunk(event.getBlock())), VisualFeed.Reason.REMOVE, event::isCancelled)); }
    private void piston(java.util.List<Block> moved, org.bukkit.block.BlockFace direction, BooleanSupplier cancelled) {
        Set<VisualFeed.Chunk> chunks = new LinkedHashSet<>();
        for (Block block : moved) { chunks.add(chunk(block)); chunks.add(chunk(block.getRelative(direction))); }
        changed(chunks, VisualFeed.Reason.PISTON, cancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { safe(() -> piston(e.getBlocks(), e.getDirection(), e::isCancelled)); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { safe(() -> piston(e.getBlocks(), e.getDirection(), e::isCancelled)); }
    private void blast(java.util.List<Block> blocks, BooleanSupplier cancelled) {
        Set<VisualFeed.Chunk> chunks = new LinkedHashSet<>();
        for (Block block : blocks) chunks.add(chunk(block));
        changed(chunks, VisualFeed.Reason.REMOVE, cancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent e) { safe(() -> blast(e.blockList(), e::isCancelled)); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent e) { safe(() -> blast(e.blockList(), e::isCancelled)); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) { safe(() -> changed(Set.of(chunk(e.getChunk())), VisualFeed.Reason.RECONCILE, () -> false)); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) { safe(() -> source.invalidateChunks(Set.of(chunk(e.getChunk())))); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        source.close();
        Bukkit.getServicesManager().unregister(VisualSource.class, source);
    }
}
