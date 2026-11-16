import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs against the original build artifact with isolated class loaders, without server classes. */
public final class JarContractTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }
    public static void main(String[] args) throws Exception {
        URL jar = Path.of(args[0]).toUri().toURL();
        try (URLClassLoader one = new URLClassLoader(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
             URLClassLoader two = new URLClassLoader(new URL[]{jar}, ClassLoader.getPlatformClassLoader())) {
            Class<?> sourceA = one.loadClass("mk.hypertnt.visual.VisualSource");
            Class<?> sourceB = two.loadClass("mk.hypertnt.visual.VisualSource");
            check(sourceA != sourceB, "API classes from different loaders must not be cast as identical");
            check(sourceA.getMethod("connect").getReturnType().isRecord(), "connection is a record");
            Class<?> feedType = one.loadClass("mk.hypertnt.visual.VisualFeed");
            Class<?> chunkType = one.loadClass("mk.hypertnt.visual.VisualFeed$Chunk");
            Class<?> posType = one.loadClass("mk.hypertnt.visual.VisualFeed$Pos");
            UUID world = UUID.randomUUID();
            Object chunk = chunkType.getConstructor(UUID.class, int.class, int.class).newInstance(world, 0, 0);
            Object pos = posType.getConstructor(UUID.class, int.class, int.class, int.class).newInstance(world, 1, 64, 1);
            Object feed = feedType.getConstructor(int.class).newInstance(1);
            feedType.getMethod("chunk", chunkType, Map.class, boolean.class).invoke(feed, chunk, Map.of(pos, "tnt_fire"), true);
            Object connection = feedType.getMethod("connect").invoke(feed);
            Object initial = call(connection, "initial");
            Object subscription = call(connection, "subscription");
            UUID epoch = (UUID) call(initial, "epoch");
            long revision = (Long) call(initial, "revision");
            check(((Map<?, ?>) call(initial, "entries")).get(pos).equals("tnt_fire"), "snapshot entry");
            check(((Set<?>) call(initial, "knownChunks")).contains(chunk), "known chunk");
            feedType.getMethod("chunk", chunkType, Map.class, boolean.class).invoke(feed, chunk, Map.of(pos, "tnt_obs"), true);
            Object poll = call(subscription, "poll");
            check(call(poll, "state").toString().equals("OK"), "poll state");
            Object batch = call(poll, "batch");
            check(call(batch, "epoch").equals(epoch), "batch epoch");
            check(((Long) call(batch, "revision")) == revision + 1, "batch revision");
            check(((Map<?, ?>) call(batch, "upserts")).get(pos).equals("tnt_obs"), "batch upserts");
            feedType.getMethod("chunk", chunkType, Map.class, boolean.class).invoke(feed, chunk, Map.of(pos, "tnt_fire"), true);
            feedType.getMethod("chunk", chunkType, Map.class, boolean.class).invoke(feed, chunk, Map.of(pos, "tnt_obs"), true);
            check(call(call(subscription, "poll"), "state").toString().equals("RESYNC_REQUIRED"), "overflow state");
            call(feed, "close");
            check(call(call(subscription, "poll"), "state").toString().equals("CLOSED"), "closed state");
            System.out.println("Original JAR isolated-loader record and overflow contract: PASS");
        }
    }
}
