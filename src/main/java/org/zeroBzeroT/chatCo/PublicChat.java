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
import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;

import java.util.Iterator;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class PublicChat implements Listener {
    public static Main plugin = null;
    private final FileConfiguration permissionConfig;

    public PublicChat(final Main plugin) {
        PublicChat.plugin = plugin;
        File customConfig = Main.PermissionConfig;
        permissionConfig = YamlConfiguration.loadConfiguration(customConfig);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void preProcessChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        // Convert Component to plain text for checking
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Check for unicode characters if the feature is enabled
        if (PublicChat.plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(message)) {
            // Log blocked message if debug is enabled
            if (PublicChat.plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                plugin.getLogger().info("Blocked unicode message from " + player.getName() + ": " + message);
            }
            event.setCancelled(true);
            return;
        }
        
        // Check for blacklisted words
        if (PublicChat.plugin.getBlacklistFilter().containsBlacklistedWord(message)) {
            // Log blocked message if debug is enabled
            if (PublicChat.plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                plugin.getLogger().info("Blocked blacklisted word from " + player.getName() + ": " + message);
            }
            event.setCancelled(true);
            return;
        }

        // Apply prefix colors
        String legacyMessage = replacePrefixColors(message, player);
        
        // Apply inline colors
        legacyMessage = replaceInlineColors(legacyMessage, player);
        
        // Parse any formatting tags like <BOLD>, <UNDERLINE>, etc.
        legacyMessage = parseFormattingTags(legacyMessage);

        if (stripColor(legacyMessage).trim().isEmpty()) {
            event.setCancelled(true);
            return;
        }

        // Convert processed message back to Component
        Component processedComponent = LegacyComponentSerializer.legacySection().deserialize(legacyMessage);
        event.message(processedComponent);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void filterChatRecipients(AsyncChatEvent event) {
        Player player = event.getPlayer();
        boolean chatDisabledGlobal = PublicChat.plugin.getConfig().getBoolean("ChatCo.chatDisabled", false);
        if (chatDisabledGlobal) {
            event.setCancelled(true);
            return;
        }

        boolean isBlackholed = BlackholeModule.isPlayerBlacklisted(player);

        Iterator<net.kyori.adventure.audience.Audience> iterator = event.viewers().iterator();
        while (iterator.hasNext()) {
            net.kyori.adventure.audience.Audience audience = iterator.next();
            if (!(audience instanceof Player)) {
                continue;
            }
            Player recipient = (Player) audience;
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
            iterator = event.viewers().iterator();
            while (iterator.hasNext()) {
                net.kyori.adventure.audience.Audience audience = iterator.next();
                if (audience instanceof Player && !audience.equals(player)) {
                    iterator.remove();
                }
            }
            // Log blocked message if not hidden
            if (!BlackholeModule.isPlayerHidden(player)) {
                String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
                plugin.getLogger().log(Level.INFO, "Blocked message from {0}: {1}", new Object[]{player.getName(), plainMessage});
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void logChatToConsole(AsyncChatEvent event) {
        if (!PublicChat.plugin.getConfig().getBoolean("ChatCo.chatToConsole", true)) {
            return;
        }
        Player player = event.getPlayer();
        String displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String fullMessage = "<" + displayName + "> " + message;
        plugin.getLogger().log(Level.INFO, "[CHAT] {0}", fullMessage);
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
