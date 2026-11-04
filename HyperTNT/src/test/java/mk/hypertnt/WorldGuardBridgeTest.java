package mk.hypertnt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldGuardBridgeTest {
    public static class ShieldPlugin extends JavaPlugin {
        public Repository currentRegionRepository() { return null; }
        public Manager currentRegionManager() { return null; }
    }
    public static class Record { }
    public static class Definition { }
    public static class Repository {
        Record record = new Record();
        public Record findByLocation(String world, int x, int y, int z) {
            return world.equals("raid") && x == 10 && y == 64 && z == 20 ? record : null;
        }
    }
    public static class Damaged { }
    public static class Destroyed {
        final Definition definition = new Definition();
        public Definition getDefinition() { return definition; }
    }
    public static class Manager {
        int hits;
        String lastTnt;
        int drops;
        public Object applyTntImpact(Record record, String id) {
            lastTnt = id;
            return ++hits == 2 ? new Destroyed() : new Damaged();
        }
        public void dropShieldBlockItem(Location location, Definition definition) { drops++; }
    }

    @Test void passesTntIdAndDestroysOnlyAfterManagerReportsDestroyed() {
        HyperTNTPlugin hyper = mock(HyperTNTPlugin.class);
        ShieldPlugin shield = mock(ShieldPlugin.class);
        PluginManager plugins = mock(PluginManager.class);
        World world = mock(World.class);
        Block block = mock(Block.class);
        Repository repository = new Repository();
        Manager manager = new Manager();
        when(world.getName()).thenReturn("raid");
        when(world.getBlockAt(10, 64, 20)).thenReturn(block);
        when(shield.isEnabled()).thenReturn(true);
        when(shield.currentRegionRepository()).thenReturn(repository);
        when(shield.currentRegionManager()).thenReturn(manager);
        when(plugins.getPlugin("ShieldyBlocks")).thenReturn(shield);
        Location core = new Location(world, 10, 64, 20);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            WorldGuardBridge bridge = new WorldGuardBridge(hyper);
            assertTrue(bridge.isShieldCore(core));
            assertTrue(bridge.damageShieldCore(core, "tnt_heavy"));
            assertEquals("tnt_heavy", manager.lastTnt);
            assertEquals(1, manager.hits);
            assertEquals(0, manager.drops);
            verify(block, never()).setType(any(), anyBoolean());
            assertTrue(bridge.damageShieldCore(core, "tnt_poseidon"));
            assertEquals("tnt_poseidon", manager.lastTnt);
            assertEquals(2, manager.hits);
            assertEquals(1, manager.drops);
            verify(block).setType(Material.AIR, false);
            assertFalse(bridge.isShieldCore(new Location(world, 11, 64, 20)));
        }
    }
}
