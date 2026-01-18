package org.zeroBzeroT.chatCo.guarddog;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Main GuardDog anti-spam module.
 * Coordinates all sub-modules: Captcha, RateLimiter, SimilarityFilter, BotHeuristics.
 * 
 * Design principle: MINIMIZE disruption to legitimate players.
 * - Captcha only triggers on first chat attempt from unverified IP
 * - Rate limiting allows bursts, only blocks sustained spam
 * - Similarity check targets bots, not normal conversation
 */
public class GuardDogModule implements Listener {
    
    private final JavaPlugin plugin;
    private boolean enabled;
    
    // Sub-modules
    private CaptchaManager captchaManager;
    private RateLimiter rateLimiter;
    private SimilarityFilter similarityFilter;
    private BotHeuristics botHeuristics;
    
    // Config values
    private boolean captchaEnabled;
    private boolean rateLimitEnabled;
    private boolean similarityEnabled;
    private boolean heuristicsEnabled;
    
    public GuardDogModule(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        
        if (enabled) {
            plugin.getLogger().info("[GuardDog] Anti-spam module enabled");
        }
    }
    
    /**
     * Loads or reloads configuration and recreates sub-modules.
     */
    private void loadConfig() {
        // Load main toggle
        this.enabled = plugin.getConfig().getBoolean("GuardDog.enabled", true);
        
        // Load captcha config
        this.captchaEnabled = plugin.getConfig().getBoolean("GuardDog.captcha.enabled", true);
        int captchaDuration = plugin.getConfig().getInt("GuardDog.captcha.duration_hours", 24);
        int captchaMaxAttempts = plugin.getConfig().getInt("GuardDog.captcha.max_attempts", 3);
        this.captchaManager = new CaptchaManager(plugin, captchaDuration, captchaMaxAttempts);
        
        // Load rate limit config
        this.rateLimitEnabled = plugin.getConfig().getBoolean("GuardDog.ratelimit.enabled", true);
        int maxBurst = plugin.getConfig().getInt("GuardDog.ratelimit.max_burst", 3);
        int refillSeconds = plugin.getConfig().getInt("GuardDog.ratelimit.refill_seconds", 2);
        this.rateLimiter = new RateLimiter(maxBurst, refillSeconds);
        
        // Load similarity config
        this.similarityEnabled = plugin.getConfig().getBoolean("GuardDog.similarity.enabled", true);
        double threshold = plugin.getConfig().getDouble("GuardDog.similarity.threshold", 0.85);
        boolean checkGlobal = plugin.getConfig().getBoolean("GuardDog.similarity.check_global_chat", true);
        this.similarityFilter = new SimilarityFilter(threshold, checkGlobal);
        
        // Load heuristics config
        this.heuristicsEnabled = plugin.getConfig().getBoolean("GuardDog.heuristics.enabled", true);
        double minMoveDistance = plugin.getConfig().getDouble("GuardDog.heuristics.min_move_distance", 2.0);
        int minAccountAge = plugin.getConfig().getInt("GuardDog.heuristics.min_account_age_seconds", 5);
        this.botHeuristics = new BotHeuristics(plugin, minMoveDistance, minAccountAge);
    }
    
    /**
     * Reloads GuardDog configuration and all sub-modules.
     * Note: This clears rate limit and similarity history, but preserves captcha verifications.
     */
    public void reload() {
        loadConfig();
        plugin.getLogger().info("[GuardDog] Configuration reloaded");
    }
    
    /**
     * Register all event listeners.
     */
    public void registerEvents() {
        if (!enabled) return;
        
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(captchaManager, plugin);
        Bukkit.getPluginManager().registerEvents(botHeuristics, plugin);
    }
    
    /**
     * Main chat filter - runs at LOWEST priority to intercept before other plugins.
     * Focus: Block bots, allow legitimate players through with minimal friction.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        if (!enabled) return;
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        String message = event.getMessage();
        
        // BYPASS: Ops bypass all checks
        if (player.isOp()) {
            recordMessageIfEnabled(player, message);
            return;
        }
        
        // BYPASS: Permission to bypass GuardDog
        if (player.hasPermission("chatco.guarddog.bypass")) {
            recordMessageIfEnabled(player, message);
            return;
        }
        
        // CHECK 1: Captcha verification (IP-based, 24h validity)
        if (captchaEnabled && !captchaManager.isVerified(player)) {
            event.setCancelled(true);
            
            // Show captcha GUI on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!captchaManager.hasPendingCaptcha(player)) {
                    captchaManager.showCaptcha(player);
                }
            });
            return;
        }
        
        // CHECK 2: Heuristics (join time + movement)
        if (heuristicsEnabled && !botHeuristics.passesAllChecks(player)) {
            event.setCancelled(true);
            String reason = botHeuristics.getFailureReason(player);
            player.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
            return;
        }
        
        // CHECK 3: Rate limiting
        if (rateLimitEnabled && !rateLimiter.tryConsume(player.getUniqueId())) {
            event.setCancelled(true);
            long wait = rateLimiter.getSecondsUntilRefill(player.getUniqueId());
            player.sendMessage(Component.text("Slow down! Wait " + Math.max(1, wait) + "s before chatting again.", NamedTextColor.RED));
            return;
        }
        
        // CHECK 4: Similarity filter
        if (similarityEnabled && similarityFilter.isTooSimilar(player.getUniqueId(), message)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Message blocked: too similar to recent messages.", NamedTextColor.RED));
            return;
        }
        
        // ALL CHECKS PASSED - Record message for future similarity checks
        recordMessageIfEnabled(player, message);
    }
    
    private void recordMessageIfEnabled(Player player, String message) {
        if (similarityEnabled) {
            similarityFilter.recordMessage(player.getUniqueId(), message);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        
        Player player = event.getPlayer();
        rateLimiter.removePlayer(player.getUniqueId());
        similarityFilter.removePlayer(player.getUniqueId());
    }
    
    /**
     * Gets the captcha manager for external access.
     */
    public CaptchaManager getCaptchaManager() {
        return captchaManager;
    }
    
    /**
     * Gets the rate limiter for external access.
     */
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
    
    /**
     * Gets the similarity filter for external access.
     */
    public SimilarityFilter getSimilarityFilter() {
        return similarityFilter;
    }
    
    /**
     * Gets the bot heuristics for external access.
     */
    public BotHeuristics getBotHeuristics() {
        return botHeuristics;
    }
    
    /**
     * Checks if GuardDog is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
