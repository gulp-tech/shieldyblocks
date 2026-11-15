package mk.hypertnt.visual;

import mk.hypertnt.TntRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static mk.hypertnt.visual.VisualFeed.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistryVisualPublisherTest {
    @TempDir Path directory;
    UUID worldId;
    TntRegistry registry;
    FakeWorld world;
    RegistryVisualPublisher source;
    Pos a, b, c;
    Chunk ca, cb;
    List<String> warnings;
    TntRegistry.Pos registryPos(Pos p) { return new TntRegistry.Pos(p.world(), p.x(), p.y(), p.z()); }
    static class FakeWorld implements RegistryVisualPublisher.WorldAccess {
        final Set<Chunk> loaded = new HashSet<>();
        final Set<Pos> physicalTnt = new HashSet<>();
        final List<Runnable> tasks = new ArrayList<>();
        boolean fail;
        @Override public void assertMainThread() { }
        @Override public boolean isLoaded(Chunk chunk) { return loaded.contains(chunk); }
        @Override public boolean isTnt(Pos pos) { if (fail) throw new IllegalStateException("verification error"); return physicalTnt.contains(pos); }
        @Override public boolean isKnownType(String type) { return type.equals("tnt_fire") || type.equals("tnt_obs"); }
        @Override public void nextTick(Runnable task) { tasks.add(task); }
        void tick() { List<Runnable> pending = List.copyOf(tasks); tasks.clear(); pending.forEach(Runnable::run); }
    }
    @BeforeEach void setup() {
        worldId = UUID.randomUUID();
        a = new Pos(worldId, 15, 64, 0); b = new Pos(worldId, 16, 64, 0); c = new Pos(worldId, 31, 64, 0);
        ca = a.chunk(); cb = b.chunk();
        registry = new TntRegistry(directory.resolve("placed-tnts.txt"));
        world = new FakeWorld(); warnings = new ArrayList<>();
        source = new RegistryVisualPublisher(registry::snapshot, world, warnings::add);
    }
    @Test void initialSnapshotValidatesOnlyLoadedRegistryChunks() {
        registry.put(registryPos(a), "tnt_fire"); registry.put(registryPos(b), "tnt_obs");
        world.physicalTnt.addAll(Set.of(a, b)); world.loaded.add(ca);
        source.initialize();
        Connection conn = source.connect();
        assertEquals(Map.of(a, "tnt_fire"), conn.initial().entries());
        assertEquals(Set.of(ca), conn.initial().knownChunks());
        assertFalse(source.refreshChunk(worldId, cb.x(), cb.z()));
        assertFalse(world.loaded.contains(cb)); // no implicit loading
        world.loaded.add(cb);
        assertTrue(source.refreshChunk(worldId, cb.x(), cb.z()));
        assertEquals("tnt_obs", conn.subscription().poll().batch().upserts().get(b));
    }
    @Test void pistonInvalidatesAllAffectedBeforeWorldCommitAndResyncsNextTick() {
        registry.put(registryPos(a), "tnt_fire"); registry.put(registryPos(b), "tnt_obs");
        world.physicalTnt.addAll(Set.of(a, b)); world.loaded.addAll(Set.of(ca, cb)); source.initialize();
        Connection conn = source.connect();
        source.mutation(Set.of(ca, cb), Reason.PISTON, () -> false);
        Batch invalidation = conn.subscription().poll().batch();
        assertEquals(Set.of(ca, cb), invalidation.unknown());
        assertEquals(Reason.PISTON, invalidation.reason());
        assertFalse(source.refreshChunk(worldId, ca.x(), ca.z()), "cannot revalidate during an in-flight event");
        registry.moveWithPiston(List.of(registryPos(a), registryPos(b)), List.of(), 1, 0, 0);
        world.physicalTnt.clear(); world.physicalTnt.addAll(Set.of(b, new Pos(worldId, 17, 64, 0)));
        assertEquals(1, world.tasks.size());
        world.tick();
        assertTrue(source.chunkSnapshot(ca).knownChunks().contains(ca));
        assertTrue(source.chunkSnapshot(ca).entries().isEmpty());
        assertEquals(Map.of(b, "tnt_fire", new Pos(worldId, 17, 64, 0), "tnt_obs"), source.chunkSnapshot(cb).entries());
        Batch validated = conn.subscription().poll().batch();
        assertEquals(Reason.CHUNK_VALIDATED, validated.reason());
        assertEquals(Set.of(ca, cb), validated.known());
        assertNull(conn.subscription().poll().batch(), "both chunks published in one revision");
    }
    @Test void lateCancellationQuarantinesChunkAndDoesNotChangeRegistry() {
        registry.put(registryPos(a), "tnt_fire"); world.loaded.add(ca); world.physicalTnt.add(a); source.initialize();
        Connection conn = source.connect(); AtomicBoolean cancelled = new AtomicBoolean();
        source.mutation(Set.of(ca), Reason.REMOVE, cancelled::get);
        conn.subscription().poll(); cancelled.set(true); world.tick();
        assertTrue(source.chunkSnapshot(ca).knownChunks().isEmpty());
        assertFalse(source.refreshChunk(worldId, ca.x(), ca.z()));
        assertEquals("tnt_fire", registry.get(registryPos(a)));
        assertFalse(warnings.isEmpty());
    }
    @Test void validationErrorsAndMismatchedBlocksKeepRegistryAndDoNotPublish() {
        registry.put(registryPos(a), "tnt_fire"); world.loaded.add(ca); world.physicalTnt.add(a); source.initialize();
        Connection conn = source.connect();
        world.fail = true;
        assertFalse(source.refreshChunk(worldId, ca.x(), ca.z()));
        assertTrue(conn.subscription().poll().batch().unknown().contains(ca));
        assertEquals("tnt_fire", registry.get(registryPos(a)));
        world.fail = false; world.physicalTnt.remove(a);
        assertFalse(source.refreshChunk(worldId, ca.x(), ca.z()));
        assertEquals("tnt_fire", registry.get(registryPos(a)));
    }
    @Test void directPrimingDeletionAndReconnectCloseSource() {
        registry.put(registryPos(a), "tnt_fire"); world.loaded.add(ca); world.physicalTnt.add(a); source.initialize();
        Connection first = source.connect();
        source.mutation(Set.of(ca), Reason.PRIME, () -> false);
        assertEquals(Reason.PRIME, first.subscription().poll().batch().reason());
        registry.remove(registryPos(a)); world.physicalTnt.remove(a); world.tick();
        assertEquals(Map.of(), source.chunkSnapshot(ca).entries());
        first.subscription().close();
        Connection again = source.connect();
        assertEquals(Map.of(), again.initial().entries());
        source.close();
        assertEquals(State.CLOSED, again.subscription().poll().state());
        assertThrows(IllegalStateException.class, source::connect);
    }
}
