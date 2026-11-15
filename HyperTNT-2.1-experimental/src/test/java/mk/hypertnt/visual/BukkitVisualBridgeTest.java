package mk.hypertnt.visual;

import mk.hypertnt.HyperTNTPlugin;
import mk.hypertnt.TntManager;
import mk.hypertnt.TntRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Direct calls to event handlers with mocks; not an in-game server test. */
class BukkitVisualBridgeTest {
    @TempDir Path directory;
    @Test void observesPlaceAndBreakWithoutChangingEventsOrBlocks() throws Exception {
        HyperTNTPlugin plugin = mock(HyperTNTPlugin.class);
        TntRegistry registry = new TntRegistry(directory.resolve("placed-tnts.txt"));
        TntManager types = mock(TntManager.class);
        World world = mock(World.class);
        Block block = mock(Block.class);
        UUID id = UUID.randomUUID();
        when(plugin.getRegistry()).thenReturn(registry);
        when(plugin.getTntManager()).thenReturn(types);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(types.hasType("tnt_fire")).thenReturn(true);
        when(world.getUID()).thenReturn(id);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(2, 64, 3)).thenReturn(block);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(2); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(3);
        when(block.getType()).thenReturn(Material.TNT);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        ServicesManager services = mock(ServicesManager.class);
        PluginManager plugins = mock(PluginManager.class);
        List<Runnable> nextTick = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            nextTick.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            BukkitVisualBridge bridge = new BukkitVisualBridge(plugin);
            bridge.start();
            ArgumentCaptor<VisualSource> provider = ArgumentCaptor.forClass(VisualSource.class);
            verify(services).register(eq(VisualSource.class), provider.capture(), eq(plugin), eq(ServicePriority.Normal));
            VisualSource source = provider.getValue();
            assertTrue(source.refreshChunk(id, 0, 0));
            VisualFeed.Connection consumer = source.connect();
            TntRegistry.Pos pos = new TntRegistry.Pos(id, 2, 64, 3);
            registry.put(pos, "tnt_fire"); // the gameplay listener already handled placement
            BlockPlaceEvent place = mock(BlockPlaceEvent.class);
            when(place.getBlockPlaced()).thenReturn(block);
            bridge.onPlace(place);
            assertEquals(VisualFeed.Reason.PLACE, consumer.subscription().poll().batch().reason());
            assertTrue(source.chunkSnapshot(new VisualFeed.Chunk(id, 0, 0)).knownChunks().isEmpty());
            nextTick.remove(0).run();
            assertEquals("tnt_fire", consumer.subscription().poll().batch().upserts().values().iterator().next());
            BlockBreakEvent cancelled = mock(BlockBreakEvent.class);
            when(cancelled.isCancelled()).thenReturn(true);
            EventHandler annotation = BukkitVisualBridge.class.getMethod("onBreak", BlockBreakEvent.class).getAnnotation(EventHandler.class);
            assertEquals(EventPriority.MONITOR, annotation.priority());
            assertTrue(annotation.ignoreCancelled());
            RegisteredListener registered = new RegisteredListener(bridge, (ignored, event) -> bridge.onBreak((BlockBreakEvent) event),
                    annotation.priority(), plugin, annotation.ignoreCancelled());
            registered.callEvent(cancelled); // emulate Bukkit skipping an event cancelled before MONITOR
            assertNull(consumer.subscription().poll().batch());
            assertTrue(nextTick.isEmpty());
            registry.remove(pos); // gameplay listener already handled removal
            BlockBreakEvent broken = mock(BlockBreakEvent.class);
            when(broken.getBlock()).thenReturn(block);
            bridge.onBreak(broken);
            assertEquals(VisualFeed.Reason.REMOVE, consumer.subscription().poll().batch().reason());
            when(block.getType()).thenReturn(Material.AIR);
            nextTick.remove(0).run();
            assertTrue(source.chunkSnapshot(new VisualFeed.Chunk(id, 0, 0)).entries().isEmpty());
            verify(place, never()).setCancelled(anyBoolean());
            verify(broken, never()).setCancelled(anyBoolean());
            verify(block, never()).setType(any(Material.class));
            bridge.close();
            assertEquals(VisualFeed.State.CLOSED, consumer.subscription().poll().state());
            verify(services).unregister(VisualSource.class, source);
        }
    }
}
