package mk.hypertnt.visual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mk.hypertnt.TntRegistry;

/** Experimental source-side adapter; all methods except connection/snapshot need the main thread. */
public final class RegistryVisualPublisher implements VisualSource, AutoCloseable {
    public interface WorldAccess {
        void assertMainThread();
        boolean isLoaded(VisualFeed.Chunk chunk);
        boolean isTnt(VisualFeed.Pos pos);
        boolean isKnownType(String type);
        void nextTick(Runnable task);
    }
    private final Supplier<Map<TntRegistry.Pos, String>> registry;
    private final WorldAccess world;
    private final Consumer<String> warn;
    private final VisualFeed feed = new VisualFeed(256);
    private final Map<VisualFeed.Chunk, List<BooleanSupplier>> pending = new LinkedHashMap<>();
    private final Set<VisualFeed.Chunk> quarantined = new LinkedHashSet<>();
    private boolean scheduled;
    private boolean closed;

    public RegistryVisualPublisher(Supplier<Map<TntRegistry.Pos, String>> registry, WorldAccess world, Consumer<String> warn) {
        this.registry = registry;
        this.world = world;
        this.warn = warn;
    }
    @Override public VisualFeed.Connection connect() { return feed.connect(); }
    @Override public VisualFeed.Snapshot chunkSnapshot(VisualFeed.Chunk chunk) { return feed.chunkSnapshot(chunk); }
    private static VisualFeed.Pos toVisual(TntRegistry.Pos p) { return new VisualFeed.Pos(p.world(), p.x(), p.y(), p.z()); }

    /** Start with verified loaded chunks containing registry entries; everything else is unknown. */
    public void initialize() {
        world.assertMainThread();
        Set<VisualFeed.Chunk> chunks = new LinkedHashSet<>();
        registry.get().keySet().forEach(p -> chunks.add(toVisual(p).chunk()));
        for (VisualFeed.Chunk chunk : chunks) refreshChunk(chunk.world(), chunk.x(), chunk.z());
    }
    @Override public boolean refreshChunk(UUID id, int x, int z) {
        world.assertMainThread();
        VisualFeed.Chunk chunk = new VisualFeed.Chunk(id, x, z);
        if (closed) return false;
        if (pending.containsKey(chunk) || quarantined.contains(chunk)) return false;
        try {
            Map<VisualFeed.Chunk, Map<VisualFeed.Pos, String>> verified = collectVerified(Set.of(chunk));
            feed.validatedChunks(verified);
            return true;
        } catch (RuntimeException ex) {
            feed.invalidateChunks(Set.of(chunk));
            warn.accept("Визуал HyperTNT: проверка чанка " + chunk + " не удалась: " + ex);
            return false;
        }
    }
    private Map<VisualFeed.Chunk, Map<VisualFeed.Pos, String>> collectVerified(Set<VisualFeed.Chunk> chunks) {
        Map<VisualFeed.Chunk, Map<VisualFeed.Pos, String>> verified = new LinkedHashMap<>();
        for (VisualFeed.Chunk chunk : chunks) {
            if (!world.isLoaded(chunk)) throw new IllegalStateException("чанк не загружен: " + chunk);
            verified.put(chunk, new LinkedHashMap<>());
        }
        // One registry snapshot for the whole batch; Bukkit block reads stay on the main thread.
        for (Map.Entry<TntRegistry.Pos, String> e : registry.get().entrySet()) {
            VisualFeed.Pos pos = toVisual(e.getKey());
            Map<VisualFeed.Pos, String> target = verified.get(pos.chunk());
            if (target == null) continue;
            if (!world.isKnownType(e.getValue()) || !world.isTnt(pos)) {
                throw new IllegalStateException("непроверенная запись " + pos);
            }
            target.put(pos, e.getValue());
        }
        return verified;
    }
    @Override public void invalidateChunks(Set<VisualFeed.Chunk> chunks) {
        world.assertMainThread();
        if (!closed) feed.invalidateChunks(chunks);
    }
    public boolean isPending(VisualFeed.Chunk chunk) {
        world.assertMainThread();
        return pending.containsKey(chunk);
    }
    /** Invalidate synchronously; reconcile after the event has finished and the world has changed. */
    public void mutation(Set<VisualFeed.Chunk> affected, VisualFeed.Reason reason, BooleanSupplier cancelled) {
        world.assertMainThread();
        if (closed || affected.isEmpty()) return;
        Set<VisualFeed.Chunk> chunks = Set.copyOf(affected);
        feed.invalidateChunks(chunks, reason);
        for (VisualFeed.Chunk chunk : chunks) pending.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(cancelled);
        if (!scheduled) {
            scheduled = true;
            world.nextTick(this::flush);
        }
    }
    private void flush() {
        world.assertMainThread();
        if (closed) return;
        Map<VisualFeed.Chunk, List<BooleanSupplier>> batch = new LinkedHashMap<>(pending);
        pending.clear(); scheduled = false;
        boolean uncertain = false;
        try {
            for (List<BooleanSupplier> events : batch.values())
                for (BooleanSupplier cancelled : events) if (cancelled.getAsBoolean()) uncertain = true;
        } catch (RuntimeException ex) { uncertain = true; }
        if (uncertain) {
            quarantined.addAll(batch.keySet());
            warn.accept("Визуал HyperTNT: поздняя отмена или ошибка события; чанки " + batch.keySet() + " оставлены неизвестными до ручной сверки.");
            return;
        }
        if (batch.keySet().stream().anyMatch(quarantined::contains)) return;
        try {
            feed.validatedChunks(collectVerified(batch.keySet()));
        } catch (RuntimeException ex) {
            // All affected chunks remain unknown; do not publish a partial piston movement.
            warn.accept("Визуал HyperTNT: пакетная проверка чанков " + batch.keySet() + " не удалась: " + ex);
        }
    }
    /** Configuration reload changes available type IDs; clear all view data first. */
    public void invalidateAll() {
        world.assertMainThread();
        if (closed) return;
        VisualFeed.Connection temporary = feed.connect();
        try { feed.invalidateChunks(temporary.initial().knownChunks()); }
        finally { temporary.subscription().close(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; pending.clear(); quarantined.clear(); feed.close();
    }
}
