package mk.hypertnt.visual;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Standalone protocol experiment; NOT connected to Bukkit, HyperTNT or Geyser. */
public final class VisualFeed implements AutoCloseable {
    public record Pos(UUID world, int x, int y, int z) {
        public Pos { Objects.requireNonNull(world); }
        public Chunk chunk() { return new Chunk(world, x >> 4, z >> 4); }
    }
    public record Chunk(UUID world, int x, int z) {
        public Chunk { Objects.requireNonNull(world); }
    }
    public enum Reason { CHUNK_VALIDATED, CHUNK_UNKNOWN, PLACE, REMOVE, PRIME, REPLACE, PISTON, RECONCILE }
    public enum State { OK, RESYNC_REQUIRED, CLOSED }
    public record Snapshot(UUID epoch, long revision, Map<Pos, String> entries, Set<Chunk> knownChunks) {
        public Snapshot { entries = Map.copyOf(entries); knownChunks = Set.copyOf(knownChunks); }
    }
    public record Batch(UUID epoch, long revision, Reason reason, Map<Pos, String> upserts,
                        Set<Pos> removals, Set<Chunk> known, Set<Chunk> unknown) {
        public Batch { upserts = Map.copyOf(upserts); removals = Set.copyOf(removals);
            known = Set.copyOf(known); unknown = Set.copyOf(unknown); }
    }
    public record Poll(State state, Batch batch) { }
    public record Connection(Snapshot initial, Subscription subscription) { }

    private final UUID epoch = UUID.randomUUID();
    private final int queueLimit;
    private final Map<Pos, String> entries = new LinkedHashMap<>();
    private final Set<Chunk> known = new LinkedHashSet<>();
    private final Set<Subscription> subscribers = new LinkedHashSet<>();
    private long revision;
    private boolean closed;

    public VisualFeed(int queueLimit) {
        if (queueLimit < 1) throw new IllegalArgumentException("queueLimit");
        this.queueLimit = queueLimit;
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("source closed"); }
    private Snapshot snapshot() { return new Snapshot(epoch, revision, entries, known); }

    /** Snapshot and subscription are created in the same critical section. */
    public synchronized Connection connect() {
        requireOpen();
        Subscription subscription = new Subscription();
        subscribers.add(subscription);
        return new Connection(snapshot(), subscription);
    }

    /** A chunk snapshot shares the global revision; the consumer must buffer concurrent batches. */
    public synchronized Snapshot chunkSnapshot(Chunk chunk) {
        requireOpen();
        Map<Pos, String> subset = new LinkedHashMap<>();
        entries.forEach((p, type) -> { if (p.chunk().equals(chunk)) subset.put(p, type); });
        return new Snapshot(epoch, revision, subset, known.contains(chunk) ? Set.of(chunk) : Set.of());
    }

    /** Replace a complete validated chunk, or invalidate it without claiming it contains no TNT. */
    public synchronized void chunk(Chunk chunk, Map<Pos, String> verified, boolean valid) {
        requireOpen(); Objects.requireNonNull(chunk); Objects.requireNonNull(verified);
        if (!valid && !verified.isEmpty()) throw new IllegalArgumentException("unknown chunk cannot supply entries");
        verified.forEach((pos, type) -> {
            if (!pos.chunk().equals(chunk) || type == null || type.isBlank()) throw new IllegalArgumentException("invalid chunk entry");
        });
        Map<Pos, String> previous = new LinkedHashMap<>();
        entries.forEach((pos, type) -> { if (pos.chunk().equals(chunk)) previous.put(pos, type); });
        Set<Pos> removed = new LinkedHashSet<>(previous.keySet());
        removed.removeAll(verified.keySet());
        Map<Pos, String> upserts = new LinkedHashMap<>();
        verified.forEach((pos, type) -> { if (!type.equals(previous.get(pos))) upserts.put(pos, type); });
        boolean wasKnown = known.contains(chunk);
        if (removed.isEmpty() && upserts.isEmpty() && wasKnown == valid) return;
        previous.keySet().forEach(entries::remove);
        entries.putAll(verified);
        if (valid) known.add(chunk); else known.remove(chunk);
        emit(new Batch(epoch, ++revision, valid ? Reason.CHUNK_VALIDATED : Reason.CHUNK_UNKNOWN,
                upserts, removed, valid && !wasKnown ? Set.of(chunk) : Set.of(),
                !valid && wasKnown ? Set.of(chunk) : Set.of()));
    }

    /** One atomic delta, for example both ends of an entire piston movement. */
    public synchronized void change(Reason reason, Map<Pos, String> upserts, Set<Pos> removals) {
        requireOpen(); Objects.requireNonNull(reason); Objects.requireNonNull(upserts); Objects.requireNonNull(removals);
        if (reason == Reason.CHUNK_VALIDATED || reason == Reason.CHUNK_UNKNOWN) throw new IllegalArgumentException("use chunk()");
        for (Map.Entry<Pos, String> item : upserts.entrySet()) {
            if (item.getKey() == null || item.getValue() == null || item.getValue().isBlank()
                    || !known.contains(item.getKey().chunk()) || removals.contains(item.getKey())) throw new IllegalArgumentException("invalid upsert");
        }
        for (Pos pos : removals) {
            if (pos == null || !known.contains(pos.chunk())) throw new IllegalArgumentException("unknown chunk");
        }
        Map<Pos, String> changed = new LinkedHashMap<>();
        Set<Pos> deleted = new LinkedHashSet<>();
        upserts.forEach((pos, type) -> { if (!type.equals(entries.get(pos))) changed.put(pos, type); });
        removals.forEach(pos -> { if (entries.containsKey(pos)) deleted.add(pos); });
        if (changed.isEmpty() && deleted.isEmpty()) return;
        deleted.forEach(entries::remove);
        entries.putAll(changed);
        emit(new Batch(epoch, ++revision, reason, changed, deleted, Set.of(), Set.of()));
    }
    private void emit(Batch batch) {
        for (Subscription sub : subscribers) {
            if (sub.resync) continue;
            if (sub.queue.size() >= queueLimit) {
                sub.queue.clear();
                sub.resync = true;
            } else sub.queue.addLast(batch);
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Subscription sub : subscribers) { sub.closed = true; sub.queue.clear(); }
        subscribers.clear();
        entries.clear(); known.clear();
    }
    public final class Subscription implements AutoCloseable {
        private final ArrayDeque<Batch> queue = new ArrayDeque<>();
        private boolean resync;
        private boolean closed;
        private Subscription() { }
        public Poll poll() {
            synchronized (VisualFeed.this) {
                if (closed) return new Poll(State.CLOSED, null);
                if (resync) return new Poll(State.RESYNC_REQUIRED, null);
                return new Poll(State.OK, queue.pollFirst());
            }
        }
        @Override public void close() {
            synchronized (VisualFeed.this) {
                closed = true; queue.clear(); subscribers.remove(this);
            }
        }
    }
}
