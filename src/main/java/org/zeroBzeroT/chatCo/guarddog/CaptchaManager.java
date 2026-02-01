package org.zeroBzeroT.chatCo.guarddog;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Manages visual captcha challenges for bot verification. Players must click
 * the correct item in a GUI to prove they're human.
 *
 * This is a REACTIVE captcha system - it only triggers when a player exhibits
 * suspicious behavior (e.g., rate limiting, similarity violations).
 */
public class CaptchaManager implements Listener {

    private static final String CAPTCHA_TITLE = "§8Verify you are human";
    private static final int INVENTORY_SIZE = 27;

    // Materials that can be the "correct" answer
    private static final Material[] CAPTCHA_ITEMS = {
        Material.SPONGE, Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
        Material.IRON_INGOT, Material.APPLE, Material.COOKIE, Material.CAKE,
        Material.MELON_SLICE, Material.BREAD, Material.CARROT, Material.POTATO
    };

    private final JavaPlugin plugin;
    private final Map<UUID, CaptchaSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
    private final int maxAttempts;

    public CaptchaManager(JavaPlugin plugin, int maxAttempts) {
        this.plugin = plugin;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Checks if a player has passed captcha this session. Session verification
     * resets on disconnect.
     */
    public boolean isVerifiedThisSession(Player player) {
        return verifiedPlayers.contains(player.getUniqueId());
    }

    /**
     * Checks if a player currently has an open captcha GUI.
     */
    public boolean hasPendingCaptcha(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Opens the captcha GUI for a player.
     */
    public void showCaptcha(Player player) {
        if (hasPendingCaptcha(player)) {
            return; // Already showing
        }

        // Select random correct item
        Material correctItem = CAPTCHA_ITEMS[new Random().nextInt(CAPTCHA_ITEMS.length)];
        int correctSlot = new Random().nextInt(INVENTORY_SIZE);

        // Create inventory
        Inventory inv = Bukkit.createInventory(null, INVENTORY_SIZE, Component.text(CAPTCHA_TITLE));

        // Fill with gray glass panes (wrong answers)
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            inv.setItem(i, filler.clone());
        }

        // Place correct item
        ItemStack correctStack = new ItemStack(correctItem);
        ItemMeta correctMeta = correctStack.getItemMeta();
        correctMeta.displayName(Component.text("CLICK ME!", NamedTextColor.GREEN, TextDecoration.BOLD));
        correctStack.setItemMeta(correctMeta);
        inv.setItem(correctSlot, correctStack);

        // Store session
        activeSessions.put(player.getUniqueId(), new CaptchaSession(correctSlot, 0));

        // Open inventory on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.openInventory(inv);
            player.sendMessage(Component.text("Please click the ", NamedTextColor.YELLOW)
                    .append(Component.text(formatMaterialName(correctItem), NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text(" to verify you are human.", NamedTextColor.YELLOW)));
        });
    }

    /**
     * Marks a player as verified for this session.
     */
    public void markVerified(Player player) {
        verifiedPlayers.add(player.getUniqueId());
        activeSessions.remove(player.getUniqueId());
        player.sendMessage(Component.text("✓ Verification successful! You can now chat.", NamedTextColor.GREEN));
    }

    /**
     * Handles failed captcha attempt.
     */
    private void handleFailure(Player player) {
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        session.attempts++;
        if (session.attempts >= maxAttempts) {
            activeSessions.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                player.kick(Component.text("Failed captcha verification. Please reconnect.", NamedTextColor.RED));
            });
        } else {
            player.sendMessage(Component.text("✗ Wrong item! " + (maxAttempts - session.attempts) + " attempts remaining.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Check if clicking in our captcha inventory
        String title = event.getView().title().toString();
        if (!title.contains("Verify you are human")) {
            return;
        }

        event.setCancelled(true);

        int clickedSlot = event.getRawSlot();
        if (clickedSlot < 0 || clickedSlot >= INVENTORY_SIZE) {
            return;
        }

        if (clickedSlot == session.correctSlot) {
            player.closeInventory();
            markVerified(player);
        } else {
            handleFailure(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Player closed captcha without solving - reopen after short delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                player.sendMessage(Component.text("You must complete the captcha to chat!", NamedTextColor.RED));
                showCaptcha(player);
            }
        }, 20L); // 1 second delay
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeSessions.remove(playerId);
        verifiedPlayers.remove(playerId);
    }

    private String formatMaterialName(Material material) {
        String name = material.name().toLowerCase().replace("_", " ");
        // Capitalize first letter of each word
        StringBuilder result = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }
        return result.toString().trim();
    }

    /**
     * Clears a specific player's verification (for testing/admin).
     */
    public void clearVerification(Player player) {
        verifiedPlayers.remove(player.getUniqueId());
    }

    /**
     * Clears all verification data.
     */
    public void clearAllVerifications() {
        verifiedPlayers.clear();
    }

    private static class CaptchaSession {

        final int correctSlot;
        int attempts;

        CaptchaSession(int correctSlot, int attempts) {
            this.correctSlot = correctSlot;
            this.attempts = attempts;
        }
    }
}
