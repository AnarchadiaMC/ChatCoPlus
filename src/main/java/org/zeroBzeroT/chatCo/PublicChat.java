package org.zeroBzeroT.chatCo;

import java.io.File;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import static org.zeroBzeroT.chatCo.Utils.containsUnicode;
import static org.zeroBzeroT.chatCo.Utils.getDirectColorCode;
import static org.zeroBzeroT.chatCo.Utils.parseFormattingTags;
import static org.zeroBzeroT.chatCo.Utils.stripColor;

import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Iterator;

public class PublicChat implements Listener {
    public static Main plugin = null;
    private final FileConfiguration permissionConfig;

    public PublicChat(final Main plugin) {
        PublicChat.plugin = plugin;
        File customConfig = Main.PermissionConfig;
        permissionConfig = YamlConfiguration.loadConfiguration(customConfig);
        // Event registration handled by Main.java - don't register here to avoid duplicates
    }

    public String replacePrefixColors(String message, final Player player) {
        for (String colorName : Utils.getNamedColors().keySet()) {
            String prefix = plugin.getConfig().getString("ChatCo.chatPrefixes." + colorName);
            if (prefix != null && message.startsWith(prefix)) {
                // check for global or player permission
                if (permissionConfig.getBoolean("ChatCo.chatPrefixes." + colorName, false) || player.hasPermission("ChatCo.chatPrefixes." + colorName)) {
                    message = getDirectColorCode(colorName) + message;
                }

                // break here since we found a prefix color code
                break;
            }
        }

        return message;
    }

    public String replaceInlineColors(String message, final Player player) {
        for (String colorName : Utils.getNamedColors().keySet()) {
            String configColorCode = plugin.getConfig().getString("ChatCo.chatColors." + colorName);
            if (configColorCode != null && (permissionConfig.getBoolean("ChatCo.chatColors." + colorName, false) || player.hasPermission("ChatCo.chatColors." + colorName))) {
                message = message.replace(configColorCode, getDirectColorCode(colorName));
            }
        }

        return message;
    }

    // ==================== BUKKIT/SPIGOT API (AsyncPlayerChatEvent) ====================
    
    @EventHandler(priority = EventPriority.LOW)
    public void preProcessChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Check for unicode characters if the feature is enabled
        if (PublicChat.plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(message)) {
            if (PublicChat.plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                plugin.getLogger().info("Blocked unicode message from " + player.getName() + ": " + message);
            }
            event.setMessage("[UNICODE] ***WAS NOT SENT*** - Blocked Message: " + message);
            event.setCancelled(true);
            return;
        }
        
        // Check for blacklisted words
        if (PublicChat.plugin.getBlacklistFilter().containsBlacklistedWord(message)) {
            if (PublicChat.plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                plugin.getLogger().info("Blocked blacklisted word from " + player.getName() + ": " + message);
            }
            event.setMessage("[BLACKLIST] ***WAS NOT SENT*** - Blocked Message: " + message);
            event.setCancelled(true);
            return;
        }

        // Apply prefix colors
        String legacyMessage = replacePrefixColors(message, player);
        
        // Apply inline colors
        legacyMessage = replaceInlineColors(legacyMessage, player);
        
        // Parse any formatting tags
        legacyMessage = parseFormattingTags(legacyMessage);

        if (stripColor(legacyMessage).trim().isEmpty()) {
            event.setCancelled(true);
            return;
        }

        event.setMessage(legacyMessage);
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void filterChatRecipients(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        boolean chatDisabledGlobal = PublicChat.plugin.getConfig().getBoolean("ChatCo.chatDisabled", false);
        if (chatDisabledGlobal) {
            event.setCancelled(true);
            return;
        }

        boolean isBlackholed = BlackholeModule.isPlayerBlacklisted(player);

        Iterator<Player> iterator = event.getRecipients().iterator();
        while (iterator.hasNext()) {
            Player recipient = iterator.next();
            if (recipient.equals(player)) {
                continue; // Sender always sees their own message
            }

            ChatPlayer chatPlayer = PublicChat.plugin.getChatPlayer(recipient);
            if (chatPlayer != null) {
                if (chatPlayer.chatDisabled) {
                    iterator.remove();
                    continue;
                }
                if (chatPlayer.isIgnored(player.getName()) && PublicChat.plugin.getConfig().getBoolean("ChatCo.ignoresEnabled", true)) {
                    iterator.remove();
                    continue;
                }
            }
        }

        if (isBlackholed) {
            // Only sender sees it; remove all other recipients
            event.getRecipients().clear();
            event.getRecipients().add(player);
            
            // Log blocked message if not hidden
            if (!BlackholeModule.isPlayerHidden(player)) {
                plugin.getLogger().log(Level.INFO, "Blocked message from {0}: {1}", new Object[]{player.getName(), event.getMessage()});
            }
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void logChatToConsole(AsyncPlayerChatEvent event) {
        try {
            boolean chatToConsole = PublicChat.plugin.getConfig().getBoolean("ChatCo.chatToConsole", true);
            if (!chatToConsole) {
                return;
            }
            
            Player player = event.getPlayer();
            String displayName = player.getDisplayName();
            String message = event.getMessage();
            String fullMessage = "<" + stripColor(displayName) + "> " + message;
            
            plugin.getLogger().log(Level.INFO, "[CHAT] {0}", fullMessage);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in logChatToConsole event handler", e);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent e) {
        plugin.remove(e.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(final PlayerKickEvent e) {
        plugin.remove(e.getPlayer());
    }
}
