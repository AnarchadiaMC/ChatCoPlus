package org.zeroBzeroT.chatCo.guarddog;

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
import org.bukkit.configuration.file.YamlConfiguration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages visual captcha challenges for bot verification.
 * Players must click the correct item in a GUI to prove they're human.
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
    private final Map<String, Long> verifiedIps = new ConcurrentHashMap<>();
    private final File dataFile;
    private final long verificationDurationMs;
    private final int maxAttempts;
    
    public CaptchaManager(JavaPlugin plugin, long durationHours, int maxAttempts) {
        this.plugin = plugin;
        this.verificationDurationMs = durationHours * 60 * 60 * 1000;
        this.maxAttempts = maxAttempts;
        this.dataFile = new File(plugin.getDataFolder(), "guarddog_verified.yml");
        loadVerifiedIps();
    }
    
    /**
     * Checks if a player's IP is verified (passed captcha recently).
     */
    public boolean isVerified(Player player) {
        String ip = getPlayerIp(player);
        if (ip == null) return true; // Can't check, allow
        
        Long verifiedTime = verifiedIps.get(ip);
        if (verifiedTime == null) return false;
        
        return System.currentTimeMillis() - verifiedTime < verificationDurationMs;
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
        activeSessions.put(player.getUniqueId(), new CaptchaSession(correctSlot, correctItem, 0));
        
        // Open inventory on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.openInventory(inv);
            player.sendMessage(Component.text("Please click the ", NamedTextColor.YELLOW)
                .append(Component.text(formatMaterialName(correctItem), NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" to verify you are human.", NamedTextColor.YELLOW)));
        });
    }
    
    /**
     * Marks a player's IP as verified.
     */
    public void verify(Player player) {
        String ip = getPlayerIp(player);
        if (ip != null) {
            verifiedIps.put(ip, System.currentTimeMillis());
            saveVerifiedIps();
        }
        activeSessions.remove(player.getUniqueId());
        player.sendMessage(Component.text("✓ Verification successful! You can now chat.", NamedTextColor.GREEN));
    }
    
    /**
     * Handles failed captcha attempt.
     */
    private void handleFailure(Player player) {
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;
        
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
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;
        
        // Check if clicking in our captcha inventory
        String title = event.getView().title().toString();
        if (!title.contains("Verify you are human")) return;
        
        event.setCancelled(true);
        
        int clickedSlot = event.getRawSlot();
        if (clickedSlot < 0 || clickedSlot >= INVENTORY_SIZE) return;
        
        if (clickedSlot == session.correctSlot) {
            player.closeInventory();
            verify(player);
        } else {
            handleFailure(player);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        
        CaptchaSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;
        
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
        activeSessions.remove(event.getPlayer().getUniqueId());
    }
    
    private String getPlayerIp(Player player) {
        if (player.getAddress() == null) return null;
        return player.getAddress().getAddress().getHostAddress();
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
    
    private void loadVerifiedIps() {
        if (!dataFile.exists()) return;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        long now = System.currentTimeMillis();
        
        for (String ip : config.getKeys(false)) {
            long timestamp = config.getLong(ip);
            // Only load non-expired entries
            if (now - timestamp < verificationDurationMs) {
                verifiedIps.put(ip, timestamp);
            }
        }
    }
    
    private void saveVerifiedIps() {
        YamlConfiguration config = new YamlConfiguration();
        long now = System.currentTimeMillis();
        
        // Only save non-expired entries
        for (Map.Entry<String, Long> entry : verifiedIps.entrySet()) {
            if (now - entry.getValue() < verificationDurationMs) {
                config.set(entry.getKey(), entry.getValue());
            }
        }
        
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save GuardDog verified IPs: " + e.getMessage());
        }
    }
    
    /**
     * Clears all verification data.
     */
    public void clearAllVerifications() {
        verifiedIps.clear();
        saveVerifiedIps();
    }
    
    private static class CaptchaSession {
        final int correctSlot;
        final Material correctItem;
        int attempts;
        
        CaptchaSession(int correctSlot, Material correctItem, int attempts) {
            this.correctSlot = correctSlot;
            this.correctItem = correctItem;
            this.attempts = attempts;
        }
    }
}
