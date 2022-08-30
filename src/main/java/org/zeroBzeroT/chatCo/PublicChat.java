package org.zeroBzeroT.chatCo;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;

import static org.zeroBzeroT.chatCo.Utils.componentFromLegacyText;

public class PublicChat implements Listener {
    public final Main plugin;
    private final FileConfiguration permissionConfig;

    public PublicChat(final Main plugin) {
        this.plugin = plugin;
        File customConfig = Main.PermissionConfig;
        this.permissionConfig = YamlConfiguration.loadConfiguration(customConfig);
    }

    public String replacePrefixColors(String message, final Player player) {
        for (ChatColor color : ChatColor.values()) {
            if (this.plugin.getConfig().getString("ChatCo.chatPrefixes." + color.name()) != null && message.startsWith(this.plugin.getConfig().getString("ChatCo.chatPrefixes." + color.name()))) {

                // check for global or player permission
                if (this.permissionConfig.getBoolean("ChatCo.chatPrefixes." + color.name(), false) || player.hasPermission("ChatCo.chatPrefixes." + color.name())) {
                    message = color + message;
                }

                // break here since we found a prefix color code
                break;
            }
        }

        return message;
    }

    public String replaceInlineColors(String message, final Player player) {
        for (ChatColor color : ChatColor.values()) {
            if ((this.permissionConfig.getBoolean("ChatCo.chatColors." + color.name(), false) || player.hasPermission("ChatCo.chatColors." + color.name()))
                    && this.plugin.getConfig().getString("ChatCo.chatColors." + color.name()) != null) {
                message = message.replace(this.plugin.getConfig().getString("ChatCo.chatColors." + color.name()), color.toString());
            }
        }

        return message;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        // Set format to the plain message, since the player is not needed
        event.setFormat("%2$s");

        // Cancel the event, because the chat is rewritten to system messages
        event.setCancelled(true);

        // Plain message
        final Player player = event.getPlayer();
        String legacyMessage = this.replacePrefixColors(event.getMessage(), player);
        legacyMessage = this.replaceInlineColors(legacyMessage, player);

        // Do not send empty messages
        if (ChatColor.stripColor(legacyMessage).trim().length() == 0) {
            return;
        }

        // Message text
        TextComponent messageText = componentFromLegacyText(legacyMessage);

        // Sender name
        TextComponent messageSender = componentFromLegacyText(player.getDisplayName());
        if (plugin.getConfig().getBoolean("ChatCo.whisperOnClick", true)) {
            messageSender.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/w " + player.getName() + " "));
            messageSender.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Whisper to " + player.getName()).create()));
        }

        // Message
        TextComponent message = new TextComponent();
        message.addExtra(componentFromLegacyText("<"));
        message.addExtra(messageSender);
        message.addExtra(componentFromLegacyText("> "));
        message.addExtra(messageText);

        // Send to the players
        for (Player recipient : event.getRecipients()) {
            try {
                ChatPlayer chatPlayer = this.plugin.getChatPlayer(recipient);

                if ((!chatPlayer.chatDisabled || !this.plugin.checkForChatDisable) &&
                        (!chatPlayer.isIgnored(player.getName()) || !this.plugin.checkForIgnores)) {
                    recipient.spigot().sendMessage(message);
                }

            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent e) {
        this.plugin.remove(e.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(final PlayerKickEvent e) {
        this.plugin.remove(e.getPlayer());
    }
}
