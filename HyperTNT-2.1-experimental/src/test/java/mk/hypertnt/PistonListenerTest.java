package mk.hypertnt;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PistonListenerTest {
    @TempDir Path directory;
    private HyperTNTPlugin plugin;
    private TntRegistry registry;
    private TntListener listener;
    private World world;
    private UUID worldId;
    private Block piston;

    @BeforeEach void setup() {
        plugin = mock(HyperTNTPlugin.class);
        registry = new TntRegistry(directory.resolve("placed-tnts.txt"));
        when(plugin.getRegistry()).thenReturn(registry);
        listener = new TntListener(plugin);
        world = mock(World.class);
        worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        piston = mock(Block.class);
    }

    private Block block(int x, PistonMoveReaction reaction) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(0);
        when(block.getPistonMoveReaction()).thenReturn(reaction);
        return block;
    }
    private TntRegistry.Pos pos(int x) { return new TntRegistry.Pos(worldId, x, 64, 0); }

    @Test void eventRegistrationRunsAfterNormalCancellationAndIgnoresCancelled() throws Exception {
        EventHandler extend = TntListener.class.getMethod("onPistonExtend", BlockPistonExtendEvent.class).getAnnotation(EventHandler.class);
        EventHandler retract = TntListener.class.getMethod("onPistonRetract", BlockPistonRetractEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, extend.priority());
        assertTrue(extend.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, retract.priority());
        assertTrue(retract.ignoreCancelled());
        registry.put(pos(1), "tnt_fire");
        Block moved = block(1, PistonMoveReaction.MOVE);
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.EAST);
        // Model a plugin cancelling at HIGHEST before HyperTNT's MONITOR listener.
        event.setCancelled(true);
        RegisteredListener registered = new RegisteredListener(listener,
                (ignored, e) -> listener.onPistonExtend((BlockPistonExtendEvent)e),
                extend.priority(), plugin, extend.ignoreCancelled());
        registered.callEvent(event);
        listener.onPistonExtend(event); // defensive check, even if called directly
        assertEquals("tnt_fire", registry.get(pos(1)));
        assertNull(registry.get(pos(2)));
        BlockPistonRetractEvent retractEvent = new BlockPistonRetractEvent(piston, List.of(moved), BlockFace.WEST);
        retractEvent.setCancelled(true);
        listener.onPistonRetract(retractEvent);
        assertEquals("tnt_fire", registry.get(pos(1)));
        assertNull(registry.get(pos(0)));
    }

    @Test void extendAndRetractKeepAdjacentTypesAndOrdinaryTntSeparate() {
        registry.put(pos(1), "tnt_fire");
        registry.put(pos(2), "tnt_obs");
        Block a = block(1, PistonMoveReaction.MOVE);
        Block b = block(2, PistonMoveReaction.MOVE);
        listener.onPistonExtend(new BlockPistonExtendEvent(piston, List.of(a, b), BlockFace.EAST));
        assertNull(registry.get(pos(1)));
        assertEquals("tnt_fire", registry.get(pos(2)));
        assertEquals("tnt_obs", registry.get(pos(3)));
        // Retraction event direction is the supplied direction of block motion.
        Block c = block(3, PistonMoveReaction.MOVE);
        listener.onPistonRetract(new BlockPistonRetractEvent(piston, List.of(c, b), BlockFace.WEST));
        assertEquals("tnt_fire", registry.get(pos(1)));
        assertEquals("tnt_obs", registry.get(pos(2)));
        assertNull(registry.get(pos(3)));
    }

    @Test void destroyedBlocksDoNotClearAnAdditionalDestination() {
        registry.put(pos(1), "tnt_fire");
        registry.put(pos(2), "stale");
        registry.put(pos(3), "unrelated");
        listener.onPistonExtend(new BlockPistonExtendEvent(piston,
                List.of(block(1, PistonMoveReaction.MOVE), block(2, PistonMoveReaction.BREAK)), BlockFace.EAST));
        assertNull(registry.get(pos(1)));
        assertEquals("tnt_fire", registry.get(pos(2)));
        assertEquals("unrelated", registry.get(pos(3)));
    }
}
