package org.zeroBzeroT.chatCo.hooks;

import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.zeroBzeroT.chatCo.BlackholeModule;
import org.zeroBzeroT.chatCo.Main;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;

/**
 * Hook into DiscordSRV API to prevent blackholed/shadow-muted players and
 * blacklisted words from being broadcast to Discord or relayed from Discord.
 */
public class DiscordSRVHook {

    private final Main plugin;

    public DiscordSRVHook(Main plugin) {
        this.plugin = plugin;
        DiscordSRV.api.subscribe(this);
        plugin.getLogger().info("[DiscordSRVHook] Successfully subscribed to DiscordSRV API events!");
    }

    /**
     * Intercepts messages originating in Minecraft before DiscordSRV sends them to Discord.
     */
    @Subscribe
    public void onGameChatMessagePreProcess(GameChatMessagePreProcessEvent event) {
        Player player = event.getPlayer();

        // 1. Blackhole / Shadow-ban Check
        if (player != null && BlackholeModule.isPlayerBlacklisted(player)) {
            event.setCancelled(true);
            if (!BlackholeModule.isPlayerHidden(player)) {
                plugin.getLogger().log(Level.INFO, "[DiscordSRVHook] Blocked blackholed message from {0} to Discord", player.getName());
            }
            return;
        }

        // 2. Word Blacklist Filter Check
        String rawMessage = event.getMessage();

        if (rawMessage != null && plugin.getBlacklistFilter() != null && plugin.getBlacklistFilter().containsBlacklistedWord(rawMessage)) {
            event.setCancelled(true);
            if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false) && player != null) {
                plugin.getLogger().log(Level.INFO, "[DiscordSRVHook] Blocked blacklisted word from {0} to Discord: {1}", new Object[]{player.getName(), rawMessage});
            }
        }
    }

    /**
     * Intercepts messages originating in Discord before DiscordSRV broadcasts them to Minecraft.
     */
    @Subscribe
    public void onDiscordGuildMessagePreProcess(DiscordGuildMessagePreProcessEvent event) {
        if (plugin.getBlacklistFilter() == null) {
            return;
        }

        String content = event.getMessage() != null ? event.getMessage().getContentDisplay() : null;
        if (content != null && plugin.getBlacklistFilter().containsBlacklistedWord(content)) {
            event.setCancelled(true);
            if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                String author = event.getAuthor() != null ? event.getAuthor().getName() : "Unknown";
                plugin.getLogger().log(Level.INFO, "[DiscordSRVHook] Blocked blacklisted Discord message from {0} to Minecraft: {1}", new Object[]{author, content});
            }
        }
    }

    /**
     * Unsubscribes from DiscordSRV API on plugin disable/reload.
     */
    public void unhook() {
        try {
            DiscordSRV.api.unsubscribe(this);
            plugin.getLogger().info("[DiscordSRVHook] Unsubscribed from DiscordSRV API events.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[DiscordSRVHook] Error while unsubscribing from DiscordSRV", e);
        }
    }
}
