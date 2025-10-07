package org.zeroBzeroT.chatCo;

import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.ibm.icu.text.SpoofChecker;

public class AntiSpam implements Listener {
    
    private static final int MAX_PLAYER_HISTORY = 10;
    private static final int MAX_GLOBAL_HISTORY = 30;
    private static final double MOVEMENT_THRESHOLD = 0.5; // blocks
    
    // Configuration thresholds
    private double selfSimilarityThreshold = 0.80;  // 80% similar to own message
    private double globalSimilarityThreshold = 0.85; // 85% similar to any recent message
    private int minMovementDistance = 2; // blocks moved before first message
    private boolean requireMovement = true;
    
    // Player profile scoring weights
    private int noMovementPenalty = 30;
    private int lowPlaytimePenalty = 25;
    private int noLevelsPenalty = 20;
    private int noActivityPenalty = 15;
    
    // Tracking data
    private final Map<UUID, LinkedList<String>> playerMessageHistory = new ConcurrentHashMap<>();
    private final LinkedList<String> globalMessageHistory = new LinkedList<>();
    private final Map<UUID, Location> playerLastLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerHasMoved = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerSpamScore = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLastMessage = new ConcurrentHashMap<>();
    
    private final Main plugin;
    private final SpoofChecker spoofChecker;
    
    public AntiSpam(Main plugin) {
        this.plugin = plugin;
        this.spoofChecker = new SpoofChecker.Builder().build();
        loadConfig();
    }
    
    private void loadConfig() {
        this.selfSimilarityThreshold = plugin.getConfig().getDouble("AntiSpam.selfSimilarityThreshold", 0.80);
        this.globalSimilarityThreshold = plugin.getConfig().getDouble("AntiSpam.globalSimilarityThreshold", 0.85);
        this.minMovementDistance = plugin.getConfig().getInt("AntiSpam.minMovementDistance", 2);
        this.requireMovement = plugin.getConfig().getBoolean("AntiSpam.requireMovement", true);
        this.noMovementPenalty = plugin.getConfig().getInt("AntiSpam.Penalties.noMovement", 30);
        this.lowPlaytimePenalty = plugin.getConfig().getInt("AntiSpam.Penalties.lowPlaytime", 25);
        this.noLevelsPenalty = plugin.getConfig().getInt("AntiSpam.Penalties.noLevels", 20);
        this.noActivityPenalty = plugin.getConfig().getInt("AntiSpam.Penalties.noActivity", 15);
    }
    
    /**
     * Calculate player risk score based on profile
     * Returns penalty points based on lack of experience, playtime, and activity
     * Higher score = more suspicious (new/inactive player)
     * Lower score = more trusted (experienced/active player)
     */
    private int calculatePlayerRiskScore(Player player) {
        int riskScore = 0;
        
        // 1. Experience Levels Scoring
        int levels = player.getLevel();
        int totalExp = player.getTotalExperience();
        
        if (levels == 0 && totalExp < 10) {
            // Brand new player with no XP at all
            riskScore += noLevelsPenalty; // +20 points
        } else if (levels < 5) {
            // Low level player
            riskScore += (int)(noLevelsPenalty * 0.5); // +10 points
        }
        // Players with 5+ levels get 0 penalty
        
        // 2. Playtime Scoring
        try {
            int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            int playTimeMinutes = playTimeTicks / 1200;
            
            if (playTimeMinutes < 10) {
                // Less than 10 minutes played - very suspicious
                riskScore += lowPlaytimePenalty; // +25 points
            } else if (playTimeMinutes < 30) {
                // 10-30 minutes - still new
                riskScore += (int)(lowPlaytimePenalty * 0.6); // +15 points
            } else if (playTimeMinutes < 60) {
                // 30-60 minutes - somewhat established
                riskScore += (int)(lowPlaytimePenalty * 0.3); // +7 points
            }
            // 60+ minutes gets 0 penalty
        } catch (Exception e) {
            // If statistic not available, assume new player
            riskScore += lowPlaytimePenalty;
        }
        
        // 3. Activity Indicators Scoring
        int activityPenalty = noActivityPenalty; // Start with full penalty
        
        try {
            // Check blocks broken
            int blocksBroken = player.getStatistic(Statistic.MINE_BLOCK);
            if (blocksBroken >= 100) {
                activityPenalty = 0; // Very active
            } else if (blocksBroken >= 50) {
                activityPenalty = (int)(activityPenalty * 0.3); // +4 points
            } else if (blocksBroken >= 10) {
                activityPenalty = (int)(activityPenalty * 0.6); // +9 points
            }
            // Less than 10 blocks keeps full penalty
        } catch (Exception e) {
            // Continue with penalty
        }
        
        // Also check distance moved
        try {
            int distanceMoved = player.getStatistic(Statistic.WALK_ONE_CM);
            if (distanceMoved >= 100000) { // 1000+ blocks
                activityPenalty = Math.min(activityPenalty, 0);
            } else if (distanceMoved >= 50000) { // 500+ blocks
                activityPenalty = Math.min(activityPenalty, (int)(noActivityPenalty * 0.5));
            }
        } catch (Exception e) {
            // Continue
        }
        
        riskScore += activityPenalty;
        
        return riskScore;
    }
    
    /**
     * Check if a message should be blocked as spam
     * @return SpamCheckResult with isSpam flag and reason
     */
    public SpamCheckResult checkMessage(Player player, String message) {
        UUID uuid = player.getUniqueId();
        String normalizedMessage = normalizeMessage(message);
        
        int spamScore = 0;
        List<String> reasons = new ArrayList<>();
        
        // 0. Calculate player profile risk score first
        int playerRiskScore = calculatePlayerRiskScore(player);
        spamScore += playerRiskScore;
        if (playerRiskScore > 0) {
            reasons.add(String.format("new/low-activity player (+%d pts)", playerRiskScore));
        }
        
        // 1. Check movement requirement (scoring-based)
        if (requireMovement && !playerHasMoved.getOrDefault(uuid, false)) {
            Location currentLoc = player.getLocation();
            Location lastLoc = playerLastLocation.get(uuid);
            
            if (lastLoc == null) {
                playerLastLocation.put(uuid, currentLoc.clone());
                // Add movement penalty instead of blocking
                spamScore += noMovementPenalty;
                reasons.add("no movement detected");
            } else {
                double distance = currentLoc.distance(lastLoc);
                if (distance < minMovementDistance) {
                    // Penalty for not moving enough
                    spamScore += noMovementPenalty;
                    reasons.add("insufficient movement");
                } else {
                    playerHasMoved.put(uuid, true);
                }
            }
        }
        
        // 2. Check message velocity (rapid messages)
        Long lastMessageTime = playerLastMessage.get(uuid);
        if (lastMessageTime != null) {
            long timeSince = System.currentTimeMillis() - lastMessageTime;
            if (timeSince < 1000) { // Less than 1 second
                spamScore += 25;
                reasons.add("rapid messaging");
            } else if (timeSince < 3000) { // Less than 3 seconds
                spamScore += 10;
            }
        }
        playerLastMessage.put(uuid, System.currentTimeMillis());
        
        // 3. Check similarity to own recent messages (self-spam)
        LinkedList<String> playerHistory = playerMessageHistory.computeIfAbsent(uuid, k -> new LinkedList<>());
        for (String previousMsg : playerHistory) {
            double similarity = calculateSimilarity(normalizedMessage, previousMsg);
            if (similarity >= selfSimilarityThreshold) {
                spamScore += (int)((similarity - selfSimilarityThreshold) * 200); // 0-40 points
                reasons.add(String.format("repeated message (%.0f%% similar)", similarity * 100));
                break; // Only count once
            }
        }
        
        // 4. Check similarity to global recent messages (multi-bot spam)
        synchronized (globalMessageHistory) {
            for (String globalMsg : globalMessageHistory) {
                double similarity = calculateSimilarity(normalizedMessage, globalMsg);
                if (similarity >= globalSimilarityThreshold) {
                    spamScore += (int)((similarity - globalSimilarityThreshold) * 300); // 0-45 points
                    reasons.add(String.format("similar to recent message (%.0f%% similar)", similarity * 100));
                    break; // Only count once
                }
            }
        }
        
        // 5. Check for excessive caps
        double capsRatio = getCapitalizationRatio(message);
        if (capsRatio > 0.6 && message.length() > 10) {
            spamScore += (int)((capsRatio - 0.6) * 50); // 0-20 points
            reasons.add(String.format("excessive caps (%.0f%%)", capsRatio * 100));
        }
        
        // 6. Check for character repetition (aaaaaaa, !!!!!!!)
        if (hasExcessiveRepetition(message)) {
            spamScore += 15;
            reasons.add("excessive character repetition");
        }
        
        // 7. Check for excessive separators/special characters (bypass technique)
        double separatorRatio = getSeparatorRatio(message);
        if (separatorRatio > 0.3 && message.length() > 10) {
            spamScore += (int)((separatorRatio - 0.3) * 30); // 0-21 points
            reasons.add(String.format("excessive separators (%.0f%%)", separatorRatio * 100));
        }
        
        // 8. Check for invisible/zero-width characters (advanced bypass)
        if (hasInvisibleCharacters(message)) {
            spamScore += 20;
            reasons.add("invisible characters detected");
        }
        
        // 9. Check for leetspeak/heavy character substitution (advanced bypass)
        if (hasExcessiveSubstitutions(message)) {
            spamScore += 15;
            reasons.add("excessive character substitutions");
        }
        
        // 10. Update spam score history with decay (player risk score already factored in)
        int currentScore = playerSpamScore.getOrDefault(uuid, 0);
        currentScore = (int)(currentScore * 0.7); // 30% decay
        currentScore += spamScore;
        playerSpamScore.put(uuid, currentScore);
        
        // Add message to history
        playerHistory.addFirst(normalizedMessage);
        if (playerHistory.size() > MAX_PLAYER_HISTORY) {
            playerHistory.removeLast();
        }
        
        synchronized (globalMessageHistory) {
            globalMessageHistory.addFirst(normalizedMessage);
            if (globalMessageHistory.size() > MAX_GLOBAL_HISTORY) {
                globalMessageHistory.removeLast();
            }
        }
        
        // Determine if message should be blocked
        if (currentScore >= 100) {
            return new SpamCheckResult(true, "Spam detected: " + String.join(", ", reasons) + ". Calm down!");
        } else if (currentScore >= 75) {
            return new SpamCheckResult(true, "Possible spam: " + String.join(", ", reasons) + ". Slow down!");
        } else if (currentScore >= 50) {
            // Warning only, don't block
            player.sendMessage("§e[AntiSpam] Warning: " + String.join(", ", reasons));
        }
        
        return new SpamCheckResult(false, null);
    }
    
    /**
     * Normalize message for comparison using advanced Unicode handling
     * Uses same techniques as BlacklistFilter to catch bypass attempts:
     * - Unicode normalization (NFKC) to handle special characters
     * - SpoofChecker skeleton to detect homoglyphs and confusables
     * - Removes colors, formatting, and separators
     */
    private String normalizeMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        
        // Remove color codes and formatting
        String normalized = message.replaceAll("§[0-9a-fk-or]", "");
        normalized = normalized.replaceAll("&[0-9a-fk-or]", "");
        normalized = normalized.replaceAll("<[^>]+>", ""); // Remove tags
        
        // Convert to lowercase
        normalized = normalized.toLowerCase();
        
        // Apply Unicode normalization (NFKC form - compatibility decomposition + canonical composition)
        // This converts fancy Unicode characters to their standard equivalents
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        
        // Use SpoofChecker to get the "skeleton" - removes confusable characters
        // e.g., "Gооgle" (with Cyrillic о) becomes "Google"
        try {
            normalized = spoofChecker.getSkeleton(normalized);
        } catch (Exception e) {
            // If skeleton generation fails, continue with normalized text
        }
        
        // Remove common separators and special characters that don't affect meaning
        normalized = normalized.replaceAll("[_\\-.,;:!?()\\[\\]{}]", "");
        
        // Remove multiple spaces
        normalized = normalized.replaceAll("\\s+", " ");
        
        // Final trim
        normalized = normalized.trim();
        
        return normalized;
    }
    
    /**
     * Calculate similarity between two strings using Levenshtein distance
     * Returns value between 0.0 (completely different) and 1.0 (identical)
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        
        int distance = levenshteinDistance(s1, s2);
        int maxLength = Math.max(s1.length(), s2.length());
        
        return 1.0 - ((double) distance / maxLength);
    }
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
    
    /**
     * Get ratio of capital letters to total letters
     */
    private double getCapitalizationRatio(String message) {
        if (message.isEmpty()) return 0.0;
        
        long letters = message.chars().filter(Character::isLetter).count();
        if (letters == 0) return 0.0;
        
        long caps = message.chars().filter(Character::isUpperCase).count();
        return (double) caps / letters;
    }
    
    /**
     * Check for excessive character repetition
     */
    private boolean hasExcessiveRepetition(String message) {
        int maxRepeat = 0;
        int currentRepeat = 1;
        char lastChar = 0;
        
        for (char c : message.toCharArray()) {
            if (c == lastChar) {
                currentRepeat++;
                maxRepeat = Math.max(maxRepeat, currentRepeat);
            } else {
                currentRepeat = 1;
                lastChar = c;
            }
        }
        
        return maxRepeat >= 5;
    }
    
    /**
     * Get ratio of separator/special characters to total characters
     * Used to detect messages like "h_e_l_l_o" or "h-e-l-l-o"
     */
    private double getSeparatorRatio(String message) {
        if (message.isEmpty()) return 0.0;
        
        long totalChars = message.length();
        long separators = message.chars()
            .filter(c -> c == '_' || c == '-' || c == '.' || c == ',' || 
                        c == ';' || c == ':' || c == '|' || c == '/' || 
                        c == '\\' || c == '*' || c == '+' || c == '=')
            .count();
        
        return (double) separators / totalChars;
    }
    
    /**
     * Check for invisible/zero-width characters (common spam bypass)
     * Detects: zero-width space, zero-width joiner, zero-width non-joiner, etc.
     */
    private boolean hasInvisibleCharacters(String message) {
        for (char c : message.toCharArray()) {
            // Check for zero-width characters
            if (c == '\u200B' ||  // Zero-width space
                c == '\u200C' ||  // Zero-width non-joiner
                c == '\u200D' ||  // Zero-width joiner
                c == '\uFEFF' ||  // Zero-width no-break space
                c == '\u180E' ||  // Mongolian vowel separator
                c == '\u2060' ||  // Word joiner
                c == '\u2061' ||  // Function application
                c == '\u2062' ||  // Invisible times
                c == '\u2063' ||  // Invisible separator
                c == '\u2064') {  // Invisible plus
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check for excessive character substitutions (leetspeak detection)
     * Looks for patterns like: 3 for E, 0 for O, 1 for I/L, @ for A, $ for S
     * This catches bots trying to bypass filters with "g00gle" or "d1sc0rd"
     */
    private boolean hasExcessiveSubstitutions(String message) {
        if (message.length() < 5) return false;
        
        int substitutionCount = 0;
        String lower = message.toLowerCase();
        
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            
            // Check for common leetspeak substitutions
            if (c == '0' || c == '3' || c == '1' || c == '4' || 
                c == '5' || c == '7' || c == '@' || c == '$') {
                
                // Check context - should be surrounded by letters for it to count
                boolean hasLetterBefore = i > 0 && Character.isLetter(lower.charAt(i - 1));
                boolean hasLetterAfter = i < lower.length() - 1 && Character.isLetter(lower.charAt(i + 1));
                
                if (hasLetterBefore || hasLetterAfter) {
                    substitutionCount++;
                }
            }
        }
        
        // If more than 30% of the message is substitutions, flag it
        double substitutionRatio = (double) substitutionCount / message.length();
        return substitutionRatio > 0.3;
    }
    
    /**
     * Reset player's spam score (e.g., after timeout or forgiveness)
     */
    public void resetSpamScore(UUID uuid) {
        playerSpamScore.put(uuid, 0);
    }
    
    /**
     * Get player's current spam score
     */
    public int getSpamScore(UUID uuid) {
        return playerSpamScore.getOrDefault(uuid, 0);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().distanceSquared(event.getTo()) < MOVEMENT_THRESHOLD * MOVEMENT_THRESHOLD) {
            return; // Ignore head movement
        }
        
        UUID uuid = event.getPlayer().getUniqueId();
        Location lastLoc = playerLastLocation.get(uuid);
        
        if (lastLoc == null) {
            playerLastLocation.put(uuid, event.getTo().clone());
        } else {
            double distance = event.getTo().distance(lastLoc);
            if (distance >= minMovementDistance) {
                playerHasMoved.put(uuid, true);
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerMessageHistory.remove(uuid);
        playerLastLocation.remove(uuid);
        playerHasMoved.remove(uuid);
        playerSpamScore.remove(uuid);
        playerLastMessage.remove(uuid);
    }
    
    /**
     * Result class for spam checks
     */
    public static class SpamCheckResult {
        private final boolean isSpam;
        private final String reason;
        
        public SpamCheckResult(boolean isSpam, String reason) {
            this.isSpam = isSpam;
            this.reason = reason;
        }
        
        public boolean isSpam() {
            return isSpam;
        }
        
        public String getReason() {
            return reason;
        }
    }
}
