package mk.hypertnt.visual;

import java.util.Set;
import java.util.UUID;

/** Unstable experimental service; no Geyser/PurpurBedrock dependency. */
public interface VisualSource {
    VisualFeed.Connection connect();
    VisualFeed.Snapshot chunkSnapshot(VisualFeed.Chunk chunk);
    /** Main server thread only. Does not load a chunk; false means unknown. */
    boolean refreshChunk(UUID world, int chunkX, int chunkZ);
    /** Main server thread only; for cooperative integrations reporting uncertain changes. */
    void invalidateChunks(Set<VisualFeed.Chunk> chunks);
}
