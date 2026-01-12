package org.zeroBzeroT.chatCo.guarddog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Bucket rate limiter for chat messages.
 * Allows short bursts but prevents sustained spam.
 */
public class RateLimiter {
    
    private final Map<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int maxTokens;
    private final long refillIntervalMs;
    
    public RateLimiter(int maxTokens, int refillSeconds) {
        this.maxTokens = maxTokens;
        this.refillIntervalMs = refillSeconds * 1000L;
    }
    
    /**
     * Attempts to consume a token for the given player.
     * @param playerId The player's UUID
     * @return true if message is allowed, false if rate limited
     */
    public boolean tryConsume(UUID playerId) {
        TokenBucket bucket = buckets.computeIfAbsent(playerId, 
            k -> new TokenBucket(maxTokens, refillIntervalMs));
        return bucket.tryConsume();
    }
    
    /**
     * Gets the remaining tokens for a player.
     * @param playerId The player's UUID
     * @return Number of remaining tokens
     */
    public int getRemainingTokens(UUID playerId) {
        TokenBucket bucket = buckets.get(playerId);
        if (bucket == null) {
            return maxTokens;
        }
        bucket.refill();
        return bucket.tokens;
    }
    
    /**
     * Gets seconds until next token refill.
     * @param playerId The player's UUID
     * @return Seconds until refill, 0 if tokens available
     */
    public long getSecondsUntilRefill(UUID playerId) {
        TokenBucket bucket = buckets.get(playerId);
        if (bucket == null || bucket.tokens > 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - bucket.lastRefillTime;
        long remaining = refillIntervalMs - elapsed;
        return Math.max(0, remaining / 1000);
    }
    
    /**
     * Removes a player's bucket (on disconnect).
     * @param playerId The player's UUID
     */
    public void removePlayer(UUID playerId) {
        buckets.remove(playerId);
    }
    
    /**
     * Clears all rate limit data.
     */
    public void clear() {
        buckets.clear();
    }
    
    /**
     * Internal token bucket implementation.
     */
    private static class TokenBucket {
        private int tokens;
        private final int maxTokens;
        private final long refillIntervalMs;
        private long lastRefillTime;
        
        TokenBucket(int maxTokens, long refillIntervalMs) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
            this.refillIntervalMs = refillIntervalMs;
            this.lastRefillTime = System.currentTimeMillis();
        }
        
        synchronized boolean tryConsume() {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
        
        synchronized void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            int tokensToAdd = (int) (elapsed / refillIntervalMs);
            if (tokensToAdd > 0) {
                tokens = Math.min(maxTokens, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
}
