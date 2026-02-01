package org.zeroBzeroT.chatCo.guarddog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * Main GuardDog anti-spam module. Coordinates all sub-modules: Captcha,
 * RateLimiter, SimilarityFilter, BotHeuristics.
 *
 * Design principle: MINIMIZE disruption to legitimate players. - Captcha only
 * triggers after multiple suspicious behaviors (suspicion threshold) - Rate
 * limiting allows bursts, only blocks sustained spam - Similarity check targets
 * bots, not normal conversation
 */
public class GuardDogModule implements Listener {

    private final JavaPlugin plugin;
    private boolean enabled;

    // Sub-modules
    private CaptchaManager captchaManager;
    private RateLimiter rateLimiter;
    private SimilarityFilter similarityFilter;
    private BotHeuristics botHeuristics;

    // Suspicion tracking - triggers captcha after threshold
    private final Map<UUID, Integer> suspicionScores = new ConcurrentHashMap<>();

    // Config values
    private boolean captchaEnabled;
    private boolean rateLimitEnabled;
    private boolean similarityEnabled;
    private boolean heuristicsEnabled;
    private int suspicionThreshold;

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
        int captchaMaxAttempts = plugin.getConfig().getInt("GuardDog.captcha.max_attempts", 3);
        this.suspicionThreshold = plugin.getConfig().getInt("GuardDog.captcha.suspicion_threshold", 2);
        this.captchaManager = new CaptchaManager(plugin, captchaMaxAttempts);

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
     * Reloads GuardDog configuration and all sub-modules. Note: This clears
     * rate limit, similarity history, and suspicion scores.
     */
    public void reload() {
        suspicionScores.clear();
        loadConfig();
        plugin.getLogger().info("[GuardDog] Configuration reloaded");
    }

    /**
     * Register all event listeners.
     */
    public void registerEvents() {
        if (!enabled) {
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(captchaManager, plugin);
        Bukkit.getPluginManager().registerEvents(botHeuristics, plugin);
    }

    /**
     * Increments suspicion score for a player.
     *
     * @return true if player should now be captcha-challenged
     */
    private boolean incrementSuspicion(Player player) {
        UUID playerId = player.getUniqueId();
        int newScore = suspicionScores.merge(playerId, 1, Integer::sum);
        return newScore >= suspicionThreshold;
    }

    /**
     * Resets suspicion score for a player.
     */
    private void resetSuspicion(Player player) {
        suspicionScores.remove(player.getUniqueId());
    }

    /**
     * Gets current suspicion score for a player.
     */
    public int getSuspicionScore(Player player) {
        return suspicionScores.getOrDefault(player.getUniqueId(), 0);
    }

    /**
     * Main chat filter - runs at LOWEST priority to intercept before other
     * plugins. Focus: Block bots, allow legitimate players through with minimal
     * friction.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        if (!enabled) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }

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

        // CHECK 0: If player has pending captcha, they must solve it first
        if (captchaEnabled && captchaManager.hasPendingCaptcha(player)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Complete the captcha first!", NamedTextColor.RED));
            return;
        }

        // CHECK 1: Heuristics (join time + movement) - no suspicion tracking, just block
        if (heuristicsEnabled && !botHeuristics.passesAllChecks(player)) {
            event.setCancelled(true);
            String reason = botHeuristics.getFailureReason(player);
            player.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
            return;
        }

        // CHECK 2: Rate limiting - track suspicion on failure
        if (rateLimitEnabled && !rateLimiter.tryConsume(player.getUniqueId())) {
            event.setCancelled(true);

            if (captchaEnabled && !captchaManager.isVerifiedThisSession(player)) {
                if (incrementSuspicion(player)) {
                    triggerCaptcha(player, "rate limit violations");
                    return;
                }
            }

            long wait = rateLimiter.getSecondsUntilRefill(player.getUniqueId());
            player.sendMessage(Component.text("Slow down! Wait " + Math.max(1, wait) + "s before chatting again.", NamedTextColor.RED));
            return;
        }

        // CHECK 3: Similarity filter - track suspicion on failure
        if (similarityEnabled && similarityFilter.isTooSimilar(player.getUniqueId(), message)) {
            event.setCancelled(true);

            if (captchaEnabled && !captchaManager.isVerifiedThisSession(player)) {
                if (incrementSuspicion(player)) {
                    triggerCaptcha(player, "repeated message pattern");
                    return;
                }
            }

            player.sendMessage(Component.text("Message blocked: too similar to recent messages.", NamedTextColor.RED));
            return;
        }

        // ALL CHECKS PASSED - Record message for future similarity checks
        recordMessageIfEnabled(player, message);
    }

    /**
     * Triggers captcha for a player due to suspicious behavior.
     */
    private void triggerCaptcha(Player player, String reason) {
        resetSuspicion(player); // Reset score, they'll prove themselves with captcha
        player.sendMessage(Component.text("⚠ Suspicious activity detected (" + reason + "). Please verify you are human.", NamedTextColor.GOLD));

        Bukkit.getScheduler().runTask(plugin, () -> {
            captchaManager.showCaptcha(player);
        });
    }

    private void recordMessageIfEnabled(Player player, String message) {
        if (similarityEnabled) {
            similarityFilter.recordMessage(player.getUniqueId(), message);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enabled) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        rateLimiter.removePlayer(playerId);
        similarityFilter.removePlayer(playerId);
        suspicionScores.remove(playerId);
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
