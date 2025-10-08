package org.zeroBzeroT.chatCo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import static org.zeroBzeroT.chatCo.Utils.componentFromLegacyText;
import static org.zeroBzeroT.chatCo.Utils.saveStreamToFile;

public class Main extends JavaPlugin {
    public static File PermissionConfig;
    public static File WhisperLog;
    public static File dataFolder;
    private static File Help;
    private Announcer announcer;
    public Collection<ChatPlayer> playerList;
    private BlacklistFilter blacklistFilter;

    @Override
    public void onDisable() {
        if (announcer != null) {
            announcer.disable();
        }
        playerList.clear();
    }

    // Add this method to handle announcer reloading
    public void reloadAnnouncer() {
        if (announcer != null) {
            announcer.loadConfig();
        } else if (getConfig().getBoolean("ChatCo.announcements.enabled", true)) {
            announcer = new Announcer(this);
        }
    }

    /**
     * Get the blacklist filter
     * @return The blacklist filter instance
     */
    public BlacklistFilter getBlacklistFilter() {
        return blacklistFilter;
    }

    /**
     * Reload the blacklist filter
     */
    public void reloadBlacklistFilter() {
        if (blacklistFilter != null) {
            blacklistFilter.reloadBlacklist();
        }
    }

    @Override
    public void onEnable() {
        playerList = Collections.synchronizedCollection(new ArrayList<>());
        getConfig().options().copyDefaults(true);
        getConfig().options().parseComments(true);

        saveResourceFiles();
        toggleConfigValue(0);
        
        // Initialize blacklist filter
        blacklistFilter = new BlacklistFilter(this);

        final PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PublicChat(this), this);
        pm.registerEvents(new BlackholeModule(this), this);

        if (getConfig().getBoolean("ChatCo.whisperChangesEnabled", true)) {
            pm.registerEvents(new Whispers(this), this);
        }

        if (getConfig().getBoolean("ChatCo.announcements.enabled", true)) {
            announcer = new Announcer(this);
        }

        if (getConfig().getBoolean("ChatCo.bStats", false)) {
            @SuppressWarnings("unused")
            Metrics metrics = new Metrics(this, 16309);
        }

    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void saveResourceFiles() {
        Main.dataFolder = getDataFolder();
        Main.PermissionConfig = new File(Main.dataFolder, "permissionConfig.yml");
        Main.WhisperLog = new File(Main.dataFolder, "whisperlog.txt");
        Main.Help = new File(Main.dataFolder, "help.txt");

        if (!Main.WhisperLog.exists()) {
            Main.WhisperLog.getParentFile().mkdirs();
            saveStreamToFile(getResource("whisperlog.txt"), Main.WhisperLog);
        }

        if (!Main.Help.exists()) {
            Main.Help.getParentFile().mkdirs();
            saveStreamToFile(getResource("help.txt"), Main.Help);
        }

        saveDefaultConfig();

        if (!Main.PermissionConfig.exists()) {
            Main.PermissionConfig.getParentFile().mkdirs();
            saveStreamToFile(getResource("permissionConfig.yml"), Main.PermissionConfig);
        }
    }

    @Override
    public boolean onCommand(final @NotNull CommandSender sender, final @NotNull Command cmd, final @NotNull String commandLabel, final String[] args) {
        if (sender instanceof Player player) {
            if (cmd.getName().equalsIgnoreCase("togglechat")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }

                final boolean chatEnabled = toggleChat(player);
                if (!chatEnabled) {
                    sender.sendMessage(componentFromLegacyText("&cYour chat is now disabled until you type /togglechat or relog."));
                } else {
                    sender.sendMessage(componentFromLegacyText("&cYour chat has been re-enabled, type /togglechat to disable it again."));
                }
                return true;
            } else if (cmd.getName().equalsIgnoreCase("toggletells")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }
                final boolean tellsEnabled = toggleTells(player);
                if (!tellsEnabled) {
                    sender.sendMessage(componentFromLegacyText("&cYou will no longer receive tells, type /toggletells to see them again."));
                } else {
                    sender.sendMessage(componentFromLegacyText("&cYou now receive tells, type /toggletells to disable them again."));
                }
                return true;
            } else if (cmd.getName().equalsIgnoreCase("unignoreall")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }

                try {
                    unIgnoreAll(player);
                } catch (IOException e) {
                    getLogger().warning(String.format("Error while unignoring all players: %s", e.getMessage()));
                }

                return true;
            } else if (cmd.getName().equalsIgnoreCase("ignore")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }

                if (args.length < 1) {
                    sender.sendMessage(componentFromLegacyText("&cYou forgot to type the name of the player."));
                    return true;
                }

                try {
                    ignorePlayer(player, args[0]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(componentFromLegacyText("&cYou entered an invalid player name."));
                } catch (IOException e) {
                    getLogger().warning(String.format("Error while ignoring player: %s", e.getMessage()));
                }

                final Player ignore = Bukkit.getPlayer(args[0]);
                if (ignore == null) {
                    sender.sendMessage(componentFromLegacyText("&cYou have entered a player who does not exist or is offline."));
                }

                return true;
            } else if (cmd.getName().equalsIgnoreCase("ignored")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }

                final ChatPlayer chatter = getChatPlayer(player);
                final List<String> ignoreList = chatter.getIgnoreList();
                int i = ignoreList.size();

                sender.sendMessage(componentFromLegacyText("&eIgnored players:"));

                if (i > 0) {
                    String ignores = String.join(", ", ignoreList);
                    sender.sendMessage(componentFromLegacyText("&e&o" + ignores));
                }

                sender.sendMessage(componentFromLegacyText("&e" + i + " players ignored."));

                return true;
            } else if (cmd.getName().equalsIgnoreCase("killme")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }


                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isDead()) {
                            return;
                        }

                        // Simply set health to 0 - setLastDamageCause is deprecated and for internal use only
                        player.setHealth(0.0);
                    }
                }.runTask(this);

                return true;
            } else if (cmd.getName().equalsIgnoreCase("spawnpoint")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(componentFromLegacyText("&cThis command can only be used by players."));
                    return true;
                }


                Location location = player.getRespawnLocation();
                if (location != null) {
                    final double x = location.getX();
                    final double y = location.getY();
                    final double z = location.getZ();

                    player.sendMessage(componentFromLegacyText("&6[&eAnarchadia&6] &aYour bed is set at: &bX: &f" + String.format("%.2f", x) + ", &bY: &f" + String.format("%.2f", y) + ", &bZ: &f" + String.format("%.2f", z)));
                } else {
                    player.sendMessage(componentFromLegacyText("&6[&eAnarchadia&6] &cYou don't have a bed set."));
                }

                return true;
            }
        }

        if ((cmd.getName().equalsIgnoreCase("mute") || cmd.getName().equalsIgnoreCase("unmute")) && (sender.isOp() || sender instanceof ConsoleCommandSender)) {
            if (cmd.getName().equalsIgnoreCase("mute")) {
                if (args.length == 0) {
                    sender.sendMessage("Usage: /mute <player> or /mute reload");
                    return true;
                }

                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    BlackholeModule.reloadConfiguration();
                    sender.sendMessage("Mute configuration reloaded.");
                    return true;
                }

                if (args.length == 1) {
                    Player target = Bukkit.getPlayer(args[0]);
                    if (target == null) {
                        sender.sendMessage("Player not found.");
                        return true;
                    }

                    if (BlackholeModule.isPlayerBlacklisted(target)) {
                        sender.sendMessage("Player is already muted.");
                    } else {
                        BlackholeModule.addPlayerToBlacklist(target, false);
                        sender.sendMessage("Muted player.");
                    }
                    return true;
                }
            } else if (cmd.getName().equalsIgnoreCase("unmute")) {
                if (args.length != 1) {
                    sender.sendMessage("Usage: /unmute <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("Player not found.");
                    return true;
                }

                if (BlackholeModule.isPlayerBlacklisted(target)) {
                    BlackholeModule.removePlayerFromBlacklist(target);
                    sender.sendMessage("Unmuted player.");
                } else {
                    sender.sendMessage("Player is not muted.");
                }
                return true;
            }
        } else if (cmd.getName().equalsIgnoreCase("consolemute") && (sender.isOp() || sender instanceof ConsoleCommandSender)) {
            if (args.length != 1) {
                sender.sendMessage("Usage: /consolemute <player>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return true;
            }

            if (!BlackholeModule.isPlayerBlacklisted(target)) {
                sender.sendMessage("Player must be muted first.");
                return true;
            }

            BlackholeModule.setPlayerHidden(target, !BlackholeModule.isPlayerHidden(target));
            sender.sendMessage("Player messages will " + (BlackholeModule.isPlayerHidden(target) ? "not" : "now") + " show in console.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("chatco")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                saveConfig();
                reloadAnnouncer();
                reloadBlacklistFilter();
                BlackholeModule.reloadConfiguration();
                sender.sendMessage("§aConfig reloaded successfully!");
                sender.sendMessage("§7- Announcer settings");
                sender.sendMessage("§7- Blacklist filter");
                sender.sendMessage("§7- Blackhole/mute settings");
                return true;
            }
            
            if (args.length >= 2 && args[0].equalsIgnoreCase("blacklist")) {
                // Check if the sender has the blacklist management permission
                if (!sender.hasPermission("ChatCo.admin.blacklist")) {
                    sender.sendMessage("You don't have permission to manage the blacklist");
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("test") && args.length >= 3) {
                    // Join remaining args as the test message
                    String testMessage = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    boolean blocked = blacklistFilter.containsBlacklistedWord(testMessage);
                    sender.sendMessage("Test message: \"" + testMessage + "\" would be " + 
                        (blocked ? "BLOCKED" : "ALLOWED"));
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("add") && args.length >= 3) {
                    // Add word to blacklist
                    String word = args[2].toLowerCase();
                    List<String> blacklist = getConfig().getStringList("ChatCo.wordBlacklist");
                    if (!blacklist.contains(word)) {
                        blacklist.add(word);
                        getConfig().set("ChatCo.wordBlacklist", blacklist);
                        saveConfig();
                        reloadBlacklistFilter();
                        sender.sendMessage("Added \"" + word + "\" to blacklist");
                    } else {
                        sender.sendMessage("Word \"" + word + "\" is already blacklisted");
                    }
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("remove") && args.length >= 3) {
                    // Remove word from blacklist
                    String word = args[2].toLowerCase();
                    List<String> blacklist = getConfig().getStringList("ChatCo.wordBlacklist");
                    if (blacklist.contains(word)) {
                        blacklist.remove(word);
                        getConfig().set("ChatCo.wordBlacklist", blacklist);
                        saveConfig();
                        reloadBlacklistFilter();
                        sender.sendMessage("Removed \"" + word + "\" from blacklist");
                    } else {
                        sender.sendMessage("Word \"" + word + "\" is not in the blacklist");
                    }
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("list")) {
                    // List blacklisted words
                    List<String> blacklist = getConfig().getStringList("ChatCo.wordBlacklist");
                    if (blacklist.isEmpty()) {
                        sender.sendMessage("The blacklist is empty");
                    } else {
                        sender.sendMessage("Blacklisted words: " + String.join(", ", blacklist));
                    }
                    return true;
                }
            }

            if (args.length >= 2) {
                if (args[0].equalsIgnoreCase("whispers")) {
                    if (args[1].equalsIgnoreCase("e")) {
                        toggleConfigValue(5);
                        sender.sendMessage("Whisper changes enabled");
                    } else if (args[1].equalsIgnoreCase("d")) {
                        toggleConfigValue(6);
                        sender.sendMessage("Whisper changes disabled");
                    }
                }

                if (args[0].equalsIgnoreCase("newcommands")) {
                    if (args[1].equalsIgnoreCase("e")) {
                        toggleConfigValue(7);
                        sender.sendMessage("New Whisper commands enabled");
                    } else if (args[1].equalsIgnoreCase("d")) {
                        toggleConfigValue(8);
                        sender.sendMessage("New whisper commands disabled");
                    }
                }

                if (args[0].equalsIgnoreCase("whisperlog")) {
                    if (args[1].equalsIgnoreCase("e")) {
                        toggleConfigValue(9);
                        sender.sendMessage("Whisper logging enabled");
                    } else if (args[1].equalsIgnoreCase("d")) {
                        toggleConfigValue(10);
                        sender.sendMessage("Whisper logging disabled");
                    }
                }

                return true;
            }
        }

        return false;
    }

    public ChatPlayer getChatPlayer(final Player p) {
        for (final ChatPlayer chatPlayer : playerList) {
            if (chatPlayer.playerUUID.equals(p.getUniqueId())) {
                return chatPlayer;
            }
        }

        ChatPlayer newChatPlayer = null;

        try {
            newChatPlayer = new ChatPlayer(p);
            playerList.add(newChatPlayer);
        } catch (IOException e) {
            getLogger().warning(String.format("Error creating ChatPlayer: %s", e.getMessage()));
        }

        return newChatPlayer;
    }

    private boolean toggleChat(final Player p) {
        if (getChatPlayer(p).chatDisabled) {
            return getChatPlayer(p).chatDisabled = false;
        }

        return getChatPlayer(p).chatDisabled = true;
    }

    private boolean toggleTells(final Player p) {
        if (getChatPlayer(p).tellsDisabled) {
            return getChatPlayer(p).tellsDisabled = false;
        }

        return getChatPlayer(p).tellsDisabled = true;
    }

    private void ignorePlayer(final Player p, final String target) throws IOException {
        final ChatPlayer chatter = getChatPlayer(p);
        chatter.saveIgnoreList(target);
        boolean isNowIgnored = chatter.isIgnored(target);

        String message = "&eChat messages from " + target + " will be ";
        message += isNowIgnored ? "hidden." : "visible.";

        p.sendMessage(componentFromLegacyText(message));
    }

    private void unIgnoreAll(final Player p) throws IOException {
        final ChatPlayer chatter = getChatPlayer(p);
        chatter.unIgnoreAll();

        String message = "&eIgnore list deleted.";
        p.sendMessage(componentFromLegacyText(message));
    }

    public void remove(Player player) {
        playerList.removeIf(p -> p.player.equals(player));
    }

    private void toggleConfigValue(final int change) {
        switch (change) {
            case 5 -> getConfig().set("ChatCo.whisperChangesEnabled", true);
            case 6 -> getConfig().set("ChatCo.whisperChangesEnabled", false);
            case 7 -> getConfig().set("ChatCo.announcementsEnabled", true);
            case 8 -> getConfig().set("ChatCo.announcementsEnabled", false);
            case 9 -> getConfig().set("ChatCo.whisperLog", true);
            case 10 -> getConfig().set("ChatCo.whisperLog", false);
            default -> {
                // Default case - no changes
            }
        }
        saveConfig();
    }
}
