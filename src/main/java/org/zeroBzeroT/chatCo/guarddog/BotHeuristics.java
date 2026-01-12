package org.zeroBzeroT.chatCo.guarddog;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player behavior heuristics to detect bot-like patterns.
 * - Join time tracking
 * - Movement verification
 */
public class BotHeuristics implements Listener {
    
    private final JavaPlugin plugin;
    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    
    private final double minMoveDistance;
    private final long minAccountAgeMs;
    
    public BotHeuristics(JavaPlugin plugin, double minMoveDistance, int minAccountAgeSeconds) {
        this.plugin = plugin;
        this.minMoveDistance = minMoveDistance;
        this.minAccountAgeMs = minAccountAgeSeconds * 1000L;
    }
    
    /**
     * Checks if a player has been on the server long enough to chat.
     */
    public boolean hasWaitedLongEnough(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        if (data == null) return false;
        
        return System.currentTimeMillis() - data.joinTime >= minAccountAgeMs;
    }
    
    /**
     * Gets seconds remaining until player can chat.
     */
    public long getSecondsUntilCanChat(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        if (data == null) return minAccountAgeMs / 1000;
        
        long elapsed = System.currentTimeMillis() - data.joinTime;
        long remaining = minAccountAgeMs - elapsed;
        return Math.max(0, remaining / 1000);
    }
    
    /**
     * Checks if a player has moved enough to chat.
     * This helps verify they're not frozen in login limbo.
     */
    public boolean hasMovedEnough(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        if (data == null) return false;
        
        return data.totalDistance >= minMoveDistance;
    }
    
    /**
     * Gets distance remaining until player can chat.
     */
    public double getDistanceRemaining(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        if (data == null) return minMoveDistance;
        
        return Math.max(0, minMoveDistance - data.totalDistance);
    }
    
    /**
     * Checks if player passes all heuristic checks.
     */
    public boolean passesAllChecks(Player player) {
        return hasWaitedLongEnough(player) && hasMovedEnough(player);
    }
    
    /**
     * Gets a descriptive reason why player failed checks.
     */
    public String getFailureReason(Player player) {
        if (!hasWaitedLongEnough(player)) {
            long seconds = getSecondsUntilCanChat(player);
            return "Please wait " + seconds + " more second(s) before chatting.";
        }
        if (!hasMovedEnough(player)) {
            double distance = getDistanceRemaining(player);
            return String.format("Please move around (%.1f blocks remaining).", distance);
        }
        return null;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        playerData.put(player.getUniqueId(), new PlayerData(
            System.currentTimeMillis(),
            loc.getX(), loc.getY(), loc.getZ(),
            0.0
        ));
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerData.get(player.getUniqueId());
        if (data == null) return;
        
        // Only count if already moved enough (optimization) or need to track
        if (data.totalDistance >= minMoveDistance) return;
        
        Location to = event.getTo();
        if (to == null) return;
        
        // Calculate horizontal distance only (ignore Y for authentication plugins)
        double dx = to.getX() - data.lastX;
        double dz = to.getZ() - data.lastZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        // Only count significant movement (not just head rotation)
        if (distance > 0.01) {
            data.totalDistance += distance;
            data.lastX = to.getX();
            data.lastY = to.getY();
            data.lastZ = to.getZ();
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerData.remove(event.getPlayer().getUniqueId());
    }
    
    /**
     * Clears all heuristic data.
     */
    public void clear() {
        playerData.clear();
    }
    
    private static class PlayerData {
        final long joinTime;
        double lastX, lastY, lastZ;
        double totalDistance;
        
        PlayerData(long joinTime, double x, double y, double z, double totalDistance) {
            this.joinTime = joinTime;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.totalDistance = totalDistance;
        }
    }
}
