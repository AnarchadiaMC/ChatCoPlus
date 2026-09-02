package org.zeroBzeroT.chatCo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ChatPlayer {
    public final Player player;
    public final UUID playerUUID;
    public boolean chatDisabled;
    public boolean tellsDisabled;
    public String LastMessenger;
    public String LastReceiver;

    private final IgnoreDatabase database;
    private final Set<String> ignores;

    public ChatPlayer(final Player p, final IgnoreDatabase database) {
        player = p;
        playerUUID = p.getUniqueId();
        chatDisabled = false;
        tellsDisabled = false;
        LastMessenger = null;
        LastReceiver = null;

        this.database = database;

        // Load ignore list from database into a lock-free concurrent set for fast reads
        List<String> dbIgnores = database.getIgnoreList(playerUUID);
        this.ignores = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        this.ignores.addAll(dbIgnores);
    }

    /**
     * Toggle ignore for a player. If already ignored, unignore. If not ignored, ignore.
     *
     * @param p The player name to toggle
     */
    public void saveIgnoreList(final String p) {
        if (p.isEmpty()) return;

        if (!this.isIgnored(p)) {
            // Write to DB first — if crash occurs, the ignore persists
            this.database.addIgnore(this.playerUUID, p);
            this.ignores.add(p);
        } else {
            this.database.removeIgnore(this.playerUUID, p);
            this.ignores.remove(p);
        }
    }

    /**
     * Clear the entire ignore list.
     */
    public void unIgnoreAll() {
        this.database.removeAllIgnores(this.playerUUID);
        this.ignores.clear();
    }

    public Player getLastMessenger() {
        if (this.LastMessenger != null) {
            return Bukkit.getPlayerExact(this.LastMessenger);
        }

        return null;
    }

    public void setLastMessenger(final Player sender) {
        this.LastMessenger = sender.getName();
    }

    public Player getLastReceiver() {
        if (this.LastReceiver != null) {
            return Bukkit.getPlayerExact(this.LastReceiver);
        }

        return null;
    }

    public void setLastReceiver(final Player sender) {
        this.LastReceiver = sender.getName();
    }

    /**
     * Check if this player is ignoring someone.
     * Uses in-memory set for O(1) lookups during chat events.
     *
     * @param p The player name to check
     * @return true if ignored
     */
    public boolean isIgnored(final String p) {
        return this.ignores.contains(p);
    }

    /**
     * Get a snapshot of the current ignore list.
     *
     * @return A copy of the ignore list
     */
    public List<String> getIgnoreList() {
        return new ArrayList<>(this.ignores);
    }
}
