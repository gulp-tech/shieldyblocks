package mk.hypertnt.visual;

import java.util.*;
import java.util.concurrent.*;
import static mk.hypertnt.visual.VisualFeed.*;

/** In-memory contract tests: no Minecraft server, world, Geyser or Bedrock clients. */
public final class VisualFeedTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        UUID world = UUID.randomUUID();
        Pos a = new Pos(world, -1, 64, 0), b = new Pos(world, -2, 64, 0);
        Chunk chunk = a.chunk();
        check(chunk.x() == -1 && chunk.z() == 0, "negative chunk coords");
        VisualFeed feed = new VisualFeed(2);
        Connection first = feed.connect();
        check(first.initial().revision() == 0 && first.initial().knownChunks().isEmpty(), "start unknown");
        feed.chunk(chunk, Map.of(a, "tnt_fire", b, "tnt_obs"), true);
        Poll load = first.subscription().poll();
        check(load.state() == State.OK && load.batch().known().contains(chunk), "chunk ready event");
        check(load.batch().upserts().size() == 2 && load.batch().revision() == 1, "complete initial chunk");
        Snapshot view = feed.chunkSnapshot(chunk);
        check(view.entries().size() == 2 && view.knownChunks().contains(chunk), "chunk snapshot");
        try { view.entries().clear(); throw new AssertionError("mutable snapshot"); } catch (UnsupportedOperationException expected) { }
        feed.change(Reason.PISTON, Map.of(b, "tnt_fire"), Set.of(a));
        Batch piston = first.subscription().poll().batch();
        check(piston.reason() == Reason.PISTON && piston.upserts().get(b).equals("tnt_fire") && piston.removals().contains(a), "one atomic piston batch");
        Pos other = new Pos(world, 16, 64, 0);
        feed.chunk(other.chunk(), Map.of(), true);
        first.subscription().poll();
        feed.change(Reason.PISTON, Map.of(other, "tnt_fire"), Set.of(b));
        Batch acrossChunks = first.subscription().poll().batch();
        check(acrossChunks.upserts().containsKey(other) && acrossChunks.removals().contains(b), "cross-chunk move is one batch");
        feed.change(Reason.PRIME, Map.of(), Set.of(other));
        check(first.subscription().poll().batch().reason() == Reason.PRIME, "prime removal");
        // A cancellation is modelled by *not* calling the source, not a Bukkit integration test.
        check(first.subscription().poll().batch() == null, "no event without a committed change");
        feed.change(Reason.PLACE, Map.of(a, "tnt"), Set.of());
        first.subscription().poll();
        feed.chunk(chunk, Map.of(), false); // unknown after a failed verification
        Batch unknown = first.subscription().poll().batch();
        check(unknown.unknown().contains(chunk) && unknown.removals().contains(a), "unknown invalidates view");
        check(feed.chunkSnapshot(chunk).knownChunks().isEmpty(), "unknown is not confirmed empty");
        try { feed.change(Reason.PLACE, Map.of(a, "tnt"), Set.of()); throw new AssertionError("allowed unknown chunk"); }
        catch (IllegalArgumentException expected) { }
        feed.chunk(chunk, Map.of(a, "tnt"), true);
        first.subscription().poll();
        // Bounded queue overflow requires complete resubscription, not silent drops.
        feed.change(Reason.REPLACE, Map.of(a, "tnt_fire"), Set.of());
        feed.change(Reason.REPLACE, Map.of(a, "tnt_obs"), Set.of());
        feed.change(Reason.REMOVE, Map.of(), Set.of(a));
        check(first.subscription().poll().state() == State.RESYNC_REQUIRED, "overflow signalled");
        first.subscription().close();
        Connection second = feed.connect();
        check(second.initial().entries().isEmpty() && second.initial().knownChunks().contains(chunk), "resync snapshot");
        feed.change(Reason.PLACE, Map.of(a, "tnt_heavy"), Set.of());
        check(second.subscription().poll().batch().revision() == second.initial().revision() + 1, "no gap on reconnect");
        UUID oldEpoch = second.initial().epoch();
        testMultiChunkInvalidation(world);
        testObserverCleanup(world);
        feed.close();
        check(second.subscription().poll().state() == State.CLOSED, "shutdown notification");
        try { feed.connect(); throw new AssertionError("connect after close"); } catch (IllegalStateException expected) { }
        VisualFeed restarted = new VisualFeed(2);
        check(!restarted.connect().initial().epoch().equals(oldEpoch), "restart has new epoch");
        restarted.close();
        // A connect concurrent with a committed change receives either the update in the
        // snapshot or exactly one subsequent batch. Repeat to exercise both schedules.
        for (int i = 0; i < 300; i++) {
            VisualFeed race = new VisualFeed(4);
            race.chunk(chunk, Map.of(), true);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            Future<Connection> connect = workers.submit(() -> { go.await(); return race.connect(); });
            Future<?> update = workers.submit(() -> { go.await(); race.change(Reason.PLACE, Map.of(a, "tnt_fire"), Set.of()); return null; });
            go.countDown(); Connection c = connect.get(); update.get();
            Poll p = c.subscription().poll();
            boolean inSnapshot = "tnt_fire".equals(c.initial().entries().get(a));
            check(inSnapshot != (p.batch() != null), "connect/change race lost or duplicated update");
            if (p.batch() != null) check(p.batch().revision() == c.initial().revision() + 1, "race revision");
            c.subscription().close(); race.close(); workers.shutdown();
        }
        System.out.println("VisualFeedTest OK: snapshot, atomic multi-chunk invalidation, overflow, reconnect, race");
    }

    /** Simulated core: tracks only already-visible chunks; never calls world/chunk APIs. */
    static final class FakeObserver {
        final Set<Chunk> visible;
        final Map<Pos, String> shown = new HashMap<>();
        UUID epoch;
        long revision;
        boolean connected;
        FakeObserver(Set<Chunk> visible) { this.visible = Set.copyOf(visible); }
        void connect(Snapshot snapshot) {
            shown.clear(); epoch = snapshot.epoch(); revision = snapshot.revision(); connected = true;
            snapshot.entries().forEach((p, type) -> { if (visible.contains(p.chunk()) && snapshot.knownChunks().contains(p.chunk())) shown.put(p, type); });
        }
        void disconnect() { shown.clear(); connected = false; }
        void accept(Poll poll) {
            if (poll.state() != State.OK) { disconnect(); return; }
            Batch batch = poll.batch();
            if (!connected || batch == null) return;
            if (!epoch.equals(batch.epoch()) || batch.revision() != revision + 1) { disconnect(); return; }
            revision = batch.revision();
            shown.keySet().removeIf(p -> batch.unknown().contains(p.chunk()) || batch.removals().contains(p));
            batch.upserts().forEach((p, type) -> { if (visible.contains(p.chunk()) && !batch.unknown().contains(p.chunk())) shown.put(p, type); });
        }
    }

    static void testObserverCleanup(UUID world) {
        Pos a = new Pos(world, 1, 64, 1), b = new Pos(world, 17, 64, 1);
        VisualFeed source = new VisualFeed(1);
        source.chunk(a.chunk(), Map.of(a, "tnt"), true);
        source.chunk(b.chunk(), Map.of(b, "tnt_fire"), true);
        Connection c = source.connect();
        FakeObserver client = new FakeObserver(Set.of(a.chunk())); // b is not visible to this client
        client.connect(c.initial());
        check(client.shown.equals(Map.of(a, "tnt")), "only already visible chunks receive overlays");
        source.invalidateChunks(Set.of(a.chunk(), b.chunk()));
        client.accept(c.subscription().poll());
        check(client.shown.isEmpty() && client.connected, "unknown clears overlays without world load");
        source.chunk(a.chunk(), Map.of(a, "tnt"), true);
        client.accept(c.subscription().poll());
        check(client.shown.containsKey(a), "validated chunk can restore overlays");
        source.change(Reason.REPLACE, Map.of(a, "tnt_fire"), Set.of());
        source.change(Reason.REPLACE, Map.of(a, "tnt_obs"), Set.of()); // overflow
        client.accept(c.subscription().poll());
        check(client.shown.isEmpty() && !client.connected, "overflow clears overlays pending resync");
        c.subscription().close(); c = source.connect(); client.connect(c.initial());
        check("tnt_obs".equals(client.shown.get(a)), "fresh snapshot restores overlay");
        Batch gap = new Batch(c.initial().epoch(), c.initial().revision() + 2, Reason.REMOVE,
                Map.of(), Set.of(a), Set.of(), Set.of());
        client.accept(new Poll(State.OK, gap));
        check(client.shown.isEmpty() && !client.connected, "gap clears overlays and disconnects");
        client.connect(source.connect().initial());
        source.close(); client.accept(c.subscription().poll());
        check(client.shown.isEmpty() && !client.connected, "source close clears overlays");
    }

    static void testMultiChunkInvalidation(UUID world) throws Exception {
        Pos left = new Pos(world, 15, 64, 0), right = new Pos(world, 16, 64, 0);
        Pos extra = new Pos(world, 32, 64, 0), untouched = new Pos(world, 48, 64, 0);
        Set<Chunk> affected = Set.of(left.chunk(), right.chunk(), extra.chunk());
        VisualFeed feed = new VisualFeed(10);
        feed.chunk(left.chunk(), Map.of(left, "tnt_fire"), true);
        // right is unknown; extra and untouched are known and have overrides.
        feed.chunk(extra.chunk(), Map.of(extra, "tnt_obs"), true);
        feed.chunk(untouched.chunk(), Map.of(untouched, "tnt_heavy"), true);
        Connection readyObserver = feed.connect();
        boolean deltaSent = feed.changeOrInvalidate(Reason.REPLACE, Map.of(left, "tnt_obs"), Set.of(), Set.of(left.chunk()));
        check(deltaSent && readyObserver.subscription().poll().batch().reason() == Reason.REPLACE, "known chunks publish normal delta");
        readyObserver.subscription().close();
        Connection observer = feed.connect();
        long oldRevision = observer.initial().revision();
        boolean sent = feed.changeOrInvalidate(Reason.PISTON, Map.of(right, "tnt_fire"), Set.of(left), affected);
        check(!sent, "unknown chunk must force invalidation");
        Batch batch = observer.subscription().poll().batch();
        check(batch.revision() == oldRevision + 1 && batch.reason() == Reason.CHUNK_UNKNOWN, "single revision for all affected chunks");
        check(batch.unknown().equals(affected), "all affected chunks included in one batch");
        check(batch.removals().equals(Set.of(left, extra)), "all affected overrides removed");
        check(observer.subscription().poll().batch() == null, "no partial intermediate batch");
        Connection after = feed.connect();
        check(!after.initial().knownChunks().contains(left.chunk()) && !after.initial().knownChunks().contains(right.chunk())
                && !after.initial().knownChunks().contains(extra.chunk()), "all affected chunks unknown at once");
        check(after.initial().entries().equals(Map.of(untouched, "tnt_heavy")), "unaffected chunks unchanged");
        try {
            feed.changeOrInvalidate(Reason.PISTON, Map.of(right, "tnt_fire"), Set.of(left), Set.of(left.chunk()));
            throw new AssertionError("accepted incomplete affected set");
        } catch (IllegalArgumentException expected) { }
        check(after.initial().revision() == feed.connect().initial().revision(), "invalid input must not mutate");
        feed.close();

        // A concurrent observer's atomic connection can see the state before OR
        // after invalidation, but never just one of the two chunks unknown.
        for (int i = 0; i < 300; i++) {
            VisualFeed concurrent = new VisualFeed(4);
            concurrent.chunk(left.chunk(), Map.of(left, "tnt"), true);
            concurrent.chunk(right.chunk(), Map.of(right, "tnt_fire"), true);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Connection> joining = pool.submit(() -> { start.await(); return concurrent.connect(); });
            Future<?> invalidating = pool.submit(() -> { start.await(); concurrent.invalidateChunks(Set.of(left.chunk(), right.chunk())); return null; });
            start.countDown();
            Connection c = joining.get(); invalidating.get();
            Snapshot snap = c.initial();
            boolean hasLeft = snap.knownChunks().contains(left.chunk());
            boolean hasRight = snap.knownChunks().contains(right.chunk());
            check(hasLeft == hasRight, "observer saw partial invalidation");
            Poll update = c.subscription().poll();
            if (hasLeft) {
                check(update.batch() != null && update.batch().unknown().equals(Set.of(left.chunk(), right.chunk())), "pre-state followed by one complete delta");
                check(update.batch().revision() == snap.revision() + 1, "invalidation revision");
            } else check(update.batch() == null, "post-state has no duplicate delta");
            c.subscription().close(); concurrent.close(); pool.shutdown();
        }
    }
}
