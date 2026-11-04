package mk.hypertnt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public final class TntManager {

    private final HyperTNTPlugin plugin;

    private final Map<String, TntType> types =
            new LinkedHashMap<>();

    private final Set<NamespacedKey> recipes =
            new HashSet<>();

    private final NamespacedKey itemKey;
    private final NamespacedKey entityKey;

    public TntManager(HyperTNTPlugin plugin) {
        this.plugin = plugin;

        itemKey = new NamespacedKey(plugin, "tnt_type");
        entityKey = new NamespacedKey(plugin, "tnt_type_entity");
    }

    public void loadTypes() {

        unregisterRecipes();
        types.clear();

        File directory = new File(
                plugin.getDataFolder(),
                "tnts"
        );

        for (String source :
                plugin.getConfig().getStringList("tnts.sources")) {

            File file = new File(directory, source);

            if (!file.exists()) {
                plugin.getLogger().warning(
                        "TNT file not found: " + source
                );
                continue;
            }

            YamlConfiguration yaml =
                    YamlConfiguration.loadConfiguration(file);

            for (String id : yaml.getKeys(false)) {

                ConfigurationSection section =
                        yaml.getConfigurationSection(id);

                if (section == null) {
                    continue;
                }

                if (!section.getBoolean("enabled", true)) {
                    continue;
                }

                String normalized =
                        id.toLowerCase(Locale.ROOT);

                types.put(
                        normalized,
                        new TntType(normalized, section)
                );
            }
        }

        registerRecipes();

        plugin.getLogger().info(
                "Loaded " + types.size() + " TNT types."
        );
    }

    public boolean hasType(String id) {
        return types.containsKey(
                id.toLowerCase(Locale.ROOT)
        );
    }

    public TntType getType(String id) {

        if (id == null) {
            return null;
        }

        return types.get(
                id.toLowerCase(Locale.ROOT)
        );
    }

    public List<String> getTypeIds() {
        return new ArrayList<>(types.keySet());
    }

    public NamespacedKey getEntityKey() {
        return entityKey;
    }

    public String getEntityType(org.bukkit.entity.Entity entity) {

        return entity.getPersistentDataContainer().get(
                entityKey,
                PersistentDataType.STRING
        );
    }

    public ItemStack createItem(String id, int amount) {

        TntType type = getType(id);

        if (type == null) {
            return null;
        }

        ConfigurationSection item =
                type.config.getConfigurationSection("item");

        Material material = Material.matchMaterial(
                item != null
                        ? item.getString("material", "TNT")
                        : "TNT"
        );

        if (material == null) {
            material = Material.TNT;
        }

        ItemStack stack = new ItemStack(
                material,
                Math.max(1, amount)
        );

        ItemMeta meta = stack.getItemMeta();

        if (meta == null) {
            return stack;
        }

        meta.setDisplayName(
                color(item != null
                        ? item.getString("name", id)
                        : id)
        );

        if (item != null) {

            List<String> lore = new ArrayList<>();

            for (String line : item.getStringList("lore")) {
                lore.add(color(line));
            }

            meta.setLore(lore);

            int model = item.getInt("custom_model_data", 0);

            if (model > 0) {
                meta.setCustomModelData(model);
            }

            if (item.getBoolean("enchanted", false)) {
                meta.addEnchant(
                        Enchantment.UNBREAKING,
                        1,
                        true
                );

                meta.addItemFlags(
                        ItemFlag.HIDE_ENCHANTS
                );
            }
        }

        meta.getPersistentDataContainer().set(
                itemKey,
                PersistentDataType.STRING,
                id
        );

        stack.setItemMeta(meta);

        return stack;
    }

    public String getItemType(ItemStack item) {

        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        return item.getItemMeta()
                .getPersistentDataContainer()
                .get(
                        itemKey,
                        PersistentDataType.STRING
                );
    }

    private void registerRecipes() {

        for (TntType type : types.values()) {

            ConfigurationSection craft =
                    type.config.getConfigurationSection("craft");

            if (craft == null ||
                    !craft.getBoolean("enabled", false)) {
                continue;
            }

            List<String> shape =
                    craft.getStringList("shape");

            if (shape.size() != 3) {
                continue;
            }

            ItemStack result = createItem(type.id, 1);

            NamespacedKey key = new NamespacedKey(
                    plugin,
                    "recipe_" + type.id
            );

            ShapedRecipe recipe =
                    new ShapedRecipe(key, result);

            recipe.shape(
                    shape.get(0),
                    shape.get(1),
                    shape.get(2)
            );

            ConfigurationSection ingredients =
                    craft.getConfigurationSection("ingredients");

            if (ingredients == null) {
                continue;
            }

            boolean valid = true;

            for (String symbol : ingredients.getKeys(false)) {

                if (symbol.length() != 1) {
                    valid = false;
                    break;
                }

                Material material = Material.matchMaterial(
                        ingredients.getString(symbol, "")
                );

                if (material == null) {
                    valid = false;
                    break;
                }

                recipe.setIngredient(
                        symbol.charAt(0),
                        material
                );
            }

            if (!valid) {
                continue;
            }

            try {
                Bukkit.addRecipe(recipe);
                recipes.add(key);
            } catch (Exception exception) {
                plugin.getLogger().warning(
                        "Failed to register recipe: " + type.id
                );
            }
        }
    }

    private void unregisterRecipes() {

        for (NamespacedKey key : recipes) {
            Bukkit.removeRecipe(key);
        }

        recipes.clear();
    }

    public void shutdown() {
        unregisterRecipes();
        types.clear();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes(
                '&',
                value == null ? "" : value
        );
    }

    public static final class TntType {

        public final String id;
        public final ConfigurationSection config;

        public TntType(
                String id,
                ConfigurationSection config
        ) {
            this.id = id;
            this.config = config;
        }

        public double radius() {
            return config.getDouble(
                    "explosion.radius",
                    6.0
            );
        }

        public double fuseSeconds() {
            return config.getDouble(
                    "ignite.fuse-seconds",
                    4.0
            );
        }

        public boolean throwEnabled() {
            return config.getBoolean(
                    "ignite.throw.enabled",
                    false
            );
        }

        public boolean throwAutoIgnite() {
            return config.getBoolean(
                    "ignite.throw.auto-ignite",
                    false
            );
        }

        public boolean autoIgnite() {
            return config.getBoolean(
                    "ignite.auto-ignite-on-place",
                    false
            );
        }

        public double dropChance(Material material) {

            String path =
                    "explosion.blocks.per-material."
                            + material.name();

            if (config.contains(path + ".drop-chance")) {
                return config.getDouble(
                        path + ".drop-chance"
                );
            }

            return config.getDouble(
                    "explosion.blocks.default.drop-chance",
                    1.0
            );
        }

        public String blockMode(Material material) {

            String path =
                    "explosion.blocks.per-material."
                            + material.name()
                            + ".mode";

            return config.getString(
                    path,
                    config.getString(
                            "explosion.blocks.default.mode",
                            "BREAK"
                    )
            ).toUpperCase(Locale.ROOT);
        }

        public int cooldown() {

            for (String condition :
                    config.getStringList("conditions")) {

                String[] parts =
                        condition.trim().split("\\s+");

                if (parts.length == 2 &&
                        parts[0].equalsIgnoreCase("cooldown")) {

                    try {
                        return Math.max(
                                0,
                                Integer.parseInt(parts[1])
                        );
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            return 0;
        }
    }
}