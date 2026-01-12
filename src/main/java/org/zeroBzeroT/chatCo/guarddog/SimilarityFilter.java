package org.zeroBzeroT.chatCo.guarddog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects similar/repeated messages using Levenshtein distance.
 * Prevents spam that slightly alters each message.
 */
public class SimilarityFilter {
    
    private final Map<UUID, LinkedList<String>> playerHistory = new ConcurrentHashMap<>();
    private final LinkedList<String> globalHistory = new LinkedList<>();
    private final Object globalLock = new Object();
    
    private final double threshold;
    private final boolean checkGlobal;
    private final int historySize;
    private final int globalHistorySize;
    
    public SimilarityFilter(double threshold, boolean checkGlobal) {
        this.threshold = threshold;
        this.checkGlobal = checkGlobal;
        this.historySize = 3;
        this.globalHistorySize = 10;
    }
    
    /**
     * Checks if a message is too similar to recent messages.
     * @param playerId The player's UUID
     * @param message The message to check
     * @return true if message should be blocked (too similar)
     */
    public boolean isTooSimilar(UUID playerId, String message) {
        String normalized = normalize(message);
        
        // Check player's own history
        LinkedList<String> history = playerHistory.computeIfAbsent(playerId, k -> new LinkedList<>());
        synchronized (history) {
            for (String prev : history) {
                if (calculateSimilarity(normalized, prev) >= threshold) {
                    return true;
                }
            }
        }
        
        // Check global history if enabled
        if (checkGlobal) {
            synchronized (globalLock) {
                for (String prev : globalHistory) {
                    if (calculateSimilarity(normalized, prev) >= threshold) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Records a message in history after it passes checks.
     * @param playerId The player's UUID
     * @param message The message to record
     */
    public void recordMessage(UUID playerId, String message) {
        String normalized = normalize(message);
        
        // Add to player history
        LinkedList<String> history = playerHistory.computeIfAbsent(playerId, k -> new LinkedList<>());
        synchronized (history) {
            history.addFirst(normalized);
            while (history.size() > historySize) {
                history.removeLast();
            }
        }
        
        // Add to global history
        if (checkGlobal) {
            synchronized (globalLock) {
                globalHistory.addFirst(normalized);
                while (globalHistory.size() > globalHistorySize) {
                    globalHistory.removeLast();
                }
            }
        }
    }
    
    /**
     * Removes a player's history (on disconnect).
     * @param playerId The player's UUID
     */
    public void removePlayer(UUID playerId) {
        playerHistory.remove(playerId);
    }
    
    /**
     * Clears all history.
     */
    public void clear() {
        playerHistory.clear();
        synchronized (globalLock) {
            globalHistory.clear();
        }
    }
    
    /**
     * Normalizes a message for comparison.
     * Lowercases, removes extra spaces, strips color codes.
     */
    private String normalize(String message) {
        // Remove Minecraft color codes (§x)
        String cleaned = message.replaceAll("§[0-9a-fk-or]", "");
        // Lowercase and trim
        cleaned = cleaned.toLowerCase().trim();
        // Collapse multiple spaces
        cleaned = cleaned.replaceAll("\\s+", " ");
        // Remove common leetspeak substitutions for comparison
        cleaned = cleaned.replace("0", "o")
                        .replace("1", "i")
                        .replace("3", "e")
                        .replace("4", "a")
                        .replace("5", "s")
                        .replace("7", "t")
                        .replace("8", "b")
                        .replace("@", "a");
        return cleaned;
    }
    
    /**
     * Calculates similarity between two strings (0.0 to 1.0).
     * Uses Levenshtein distance ratio.
     */
    public double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }
        
        int distance = levenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        return 1.0 - ((double) distance / maxLen);
    }
    
    /**
     * Computes Levenshtein (edit) distance between two strings.
     */
    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        
        // Use two rows instead of full matrix for memory efficiency
        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];
        
        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }
        
        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        
        return prev[len2];
    }
}
