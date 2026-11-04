package mk.hypertnt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HyperTNTPlugin extends JavaPlugin
        implements CommandExecutor, TabCompleter {

    private TntManager tntManager;
    private ExplosionService explosionService;
    private WorldGuardBridge worldGuardBridge;

    private File messagesFile;
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        loadMessages();
        saveTntResources();

        worldGuardBridge = new WorldGuardBridge(this);
        explosionService = new ExplosionService(this);
        tntManager = new TntManager(this);

        tntManager.loadTypes();

        Bukkit.getPluginManager().registerEvents(
                new TntListener(this),
                this
        );

        PluginCommand command = getCommand("hypertnt");

        if (command == null) {
            getLogger().severe(
                    "Command 'hypertnt' is missing in plugin.yml!"
            );

            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        command.setExecutor(this);
        command.setTabCompleter(this);

        getLogger().info(
                "=================================="
        );
        getLogger().info(
                "HyperTNT 2.0.0 enabled."
        );
        getLogger().info(
                "Loaded TNT types: " +
                        tntManager.getTypeIds().size()
        );

        if (Bukkit.getPluginManager()
                .isPluginEnabled("WorldGuard")) {

            getLogger().info(
                    "WorldGuard detected."
            );
        }

        if (Bukkit.getPluginManager()
                .isPluginEnabled("ShieldyBlocks")) {

            getLogger().info(
                    "ShieldyBlocks detected. Integration enabled."
            );
        }

        getLogger().info(
                "=================================="
        );
    }

    @Override
    public void onDisable() {

        if (tntManager != null) {
            tntManager.shutdown();
        }

        getLogger().info(
                "HyperTNT disabled."
        );
    }

    private void loadMessages() {

        messagesFile = new File(
                getDataFolder(),
                "messages.yml"
        );

        if (!messagesFile.exists()) {

            try {
                saveResource(
                        "messages.yml",
                        false
                );
            } catch (IllegalArgumentException exception) {

                getLogger().warning(
                        "messages.yml was not found in JAR."
                );
            }
        }

        messages = YamlConfiguration.loadConfiguration(
                messagesFile
        );
    }

    private void saveTntResources() {

        File directory = new File(
                getDataFolder(),
                "tnts"
        );

        if (!directory.exists() && !directory.mkdirs()) {

            getLogger().warning(
                    "Could not create TNT directory."
            );
        }

        for (String source :
                getConfig().getStringList("tnts.sources")) {

            File file = new File(
                    directory,
                    source
            );

            if (file.exists()) {
                continue;
            }

            try {

                saveResource(
                        "tnts/" + source,
                        false
                );

                getLogger().info(
                        "Created TNT configuration: " + source
                );

            } catch (IllegalArgumentException exception) {

                getLogger().warning(
                        "TNT resource not found: " + source
                );
            }
        }
    }

    public void reloadPlugin() {

        reloadConfig();

        loadMessages();
        saveTntResources();

        if (tntManager != null) {
            tntManager.loadTypes();
        }

        getLogger().info(
                "HyperTNT configuration reloaded."
        );
    }

    public String message(String path) {

        String text = messages.getString(
                path,
                "&cMessage not found: " + path
        );

        String prefix = messages.getString(
                "prefix",
                "&8[&cHyperTNT&8] &r"
        );

        return color(
                text.replace(
                        "{prefix}",
                        prefix
                )
        );
    }

    public List<String> messageList(String path) {

        List<String> result = new ArrayList<>();

        String prefix = messages.getString(
                "prefix",
                "&8[&cHyperTNT&8] &r"
        );

        for (String line :
                messages.getStringList(path)) {

            result.add(
                    color(
                            line.replace(
                                    "{prefix}",
                                    prefix
                            )
                    )
            );
        }

        return result;
    }

    public String color(String text) {

        if (text == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes(
                '&',
                text
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!sender.hasPermission("hypertnt.admin")) {

            sender.sendMessage(
                    message("errors.no-permission")
            );

            return true;
        }

        if (args.length == 0) {

            sendLines(
                    sender,
                    "commands.help"
            );

            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {

            case "reload" -> {

                reloadPlugin();

                sender.sendMessage(
                        message("commands.reloaded")
                );

                return true;
            }

            case "types" -> {

                sendLines(
                        sender,
                        "commands.types"
                );

                return true;
            }

            case "give" -> {

                if (args.length < 3) {

                    sendLines(
                            sender,
                            "commands.help"
                    );

                    return true;
                }

                Player target = Bukkit.getPlayerExact(
                        args[1]
                );

                if (target == null) {

                    sender.sendMessage(
                            message("errors.player-not-found")
                                    .replace(
                                            "{player}",
                                            args[1]
                                    )
                    );

                    return true;
                }

                String typeId = args[2]
                        .toLowerCase(Locale.ROOT);

                if (!tntManager.hasType(typeId)) {

                    sender.sendMessage(
                            message("errors.type-not-found")
                                    .replace(
                                            "{type}",
                                            typeId
                                    )
                    );

                    return true;
                }

                int amount = 1;

                if (args.length >= 4) {

                    try {

                        amount = Integer.parseInt(
                                args[3]
                        );

                    } catch (NumberFormatException exception) {

                        sender.sendMessage(
                                message("errors.invalid-number")
                        );

                        return true;
                    }
                }

                if (amount < 1 || amount > 2304) {

                    sender.sendMessage(
                            message("errors.invalid-number")
                    );

                    return true;
                }

                int remaining = amount;

                while (remaining > 0) {

                    int stackSize = Math.min(
                            64,
                            remaining
                    );

                    ItemStack item =
                            tntManager.createItem(
                                    typeId,
                                    stackSize
                            );

                    if (item == null) {

                        sender.sendMessage(
                                message("errors.type-not-found")
                                        .replace(
                                                "{type}",
                                                typeId
                                        )
                        );

                        return true;
                    }

                    Map<Integer, ItemStack> leftovers =
                            target.getInventory()
                                    .addItem(item);

                    for (ItemStack leftover :
                            leftovers.values()) {

                        target.getWorld()
                                .dropItemNaturally(
                                        target.getLocation(),
                                        leftover
                                );
                    }

                    remaining -= stackSize;
                }

                sender.sendMessage(
                        message("commands.given")
                                .replace(
                                        "{amount}",
                                        String.valueOf(amount)
                                )
                                .replace(
                                        "{type}",
                                        typeId
                                )
                                .replace(
                                        "{player}",
                                        target.getName()
                                )
                );

                return true;
            }

            default -> {

                sendLines(
                        sender,
                        "commands.help"
                );

                return true;
            }
        }
    }

    private void sendLines(
            CommandSender sender,
            String path
    ) {

        for (String line :
                messageList(path)) {

            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        if (!sender.hasPermission("hypertnt.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {

            return filter(
                    List.of(
                            "give",
                            "reload",
                            "types"
                    ),
                    args[0]
            );
        }

        if (
                args.length == 2 &&
                        args[0].equalsIgnoreCase("give")
        ) {

            return filter(
                    Bukkit.getOnlinePlayers()
                            .stream()
                            .map(Player::getName)
                            .toList(),
                    args[1]
            );
        }

        if (
                args.length == 3 &&
                        args[0].equalsIgnoreCase("give")
        ) {

            return filter(
                    tntManager.getTypeIds(),
                    args[2]
            );
        }

        if (
                args.length == 4 &&
                        args[0].equalsIgnoreCase("give")
        ) {

            return filter(
                    List.of(
                            "1",
                            "16",
                            "32",
                            "64"
                    ),
                    args[3]
            );
        }

        return Collections.emptyList();
    }

    private List<String> filter(
            List<String> values,
            String input
    ) {

        String prefix =
                input.toLowerCase(Locale.ROOT);

        return values.stream()
                .filter(value ->
                        value.toLowerCase(Locale.ROOT)
                                .startsWith(prefix)
                )
                .toList();
    }

    public TntManager getTntManager() {
        return tntManager;
    }

    public ExplosionService getExplosionService() {
        return explosionService;
    }

    public WorldGuardBridge getWorldGuardBridge() {
        return worldGuardBridge;
    }
}