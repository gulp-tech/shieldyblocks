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
        System.out.println("VisualFeedTest OK: snapshot, chunk, atomic batches, unknown, overflow, reconnect, race");
    }
}
