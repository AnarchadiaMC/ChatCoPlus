package org.zeroBzeroT.chatCo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * SQLite-backed storage for player ignore lists with a write-behind queue.
 *
 * Writes (add/remove/clear) are enqueued and flushed by a single background thread
 * in batches. This serializes all DB writes and prevents spam abuse from hammering SQLite.
 * Reads (getIgnoreList, isIgnored) go directly to the DB — they are only called on player join.
 */
public class IgnoreDatabase {

    private final Logger logger;
    private Connection connection;

    // Prepared statements (cached for performance)
    private PreparedStatement addIgnore;
    private PreparedStatement removeIgnore;
    private PreparedStatement removeAllIgnores;
    private PreparedStatement isIgnored;
    private PreparedStatement getIgnoreList;

    // Concurrency controls
    private final Object dbLock = new Object();
    private volatile boolean closed = false;

    // Write queue
    private final ConcurrentLinkedQueue<WriteOp> writeQueue = new ConcurrentLinkedQueue<>();
    private final Thread flushThread;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private static final int MAX_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MS = 50;

    /**
     * Represents a pending write operation.
     */
    private record WriteOp(Type type, UUID uuid, String name) {
        enum Type { ADD, REMOVE, CLEAR }
    }

    public IgnoreDatabase(Logger logger, File dataFolder) {
        this.logger = logger;
        open(dataFolder);
        createSchema();
        prepareStatements();

        // Single daemon thread that drains the write queue
        flushThread = new Thread(this::flushLoop, "IgnoreDB-Writer");
        flushThread.setDaemon(true);
        flushThread.start();
    }

    /**
     * Open the SQLite connection with WAL mode for concurrent read performance.
     */
    private void open(File dataFolder) {
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "ignores.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            connection = DriverManager.getConnection(url);

            // WAL mode: concurrent reads while writing
            // synchronous=NORMAL: fsyncs WAL at commit — crash-safe without FULL overhead
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA busy_timeout=5000");
            }

            logger.info("Ignore database opened: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to open ignore database", e);
        }
    }

    private void createSchema() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ignores (
                    player_uuid TEXT NOT NULL,
                    ignored_name TEXT NOT NULL COLLATE NOCASE,
                    PRIMARY KEY (player_uuid, ignored_name COLLATE NOCASE)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ignores_uuid ON ignores(player_uuid)");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create ignore database schema", e);
        }
    }

    private void prepareStatements() {
        try {
            addIgnore = connection.prepareStatement(
                "INSERT OR IGNORE INTO ignores (player_uuid, ignored_name) VALUES (?, ?)");
            removeIgnore = connection.prepareStatement(
                "DELETE FROM ignores WHERE player_uuid = ? AND ignored_name = ?");
            removeAllIgnores = connection.prepareStatement(
                "DELETE FROM ignores WHERE player_uuid = ?");
            isIgnored = connection.prepareStatement(
                "SELECT 1 FROM ignores WHERE player_uuid = ? AND ignored_name = ?");
            getIgnoreList = connection.prepareStatement(
                "SELECT ignored_name FROM ignores WHERE player_uuid = ?");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to prepare ignore database statements", e);
        }
    }

    // ── Queue operations (called from any thread) ──────────────────────────

    /**
     * Enqueue an add-ignore write. Returns immediately.
     */
    public void addIgnore(UUID playerUUID, String ignoredName) {
        if (closed) return;
        writeQueue.offer(new WriteOp(WriteOp.Type.ADD, playerUUID, ignoredName));
    }

    /**
     * Enqueue a remove-ignore write. Returns immediately.
     */
    public void removeIgnore(UUID playerUUID, String ignoredName) {
        if (closed) return;
        writeQueue.offer(new WriteOp(WriteOp.Type.REMOVE, playerUUID, ignoredName));
    }

    /**
     * Enqueue a clear-all write. Returns immediately.
     */
    public void removeAllIgnores(UUID playerUUID) {
        if (closed) return;
        writeQueue.offer(new WriteOp(WriteOp.Type.CLEAR, playerUUID, null));
    }

    // ── Read operations (direct DB, called only on player join) ────────────

    /**
     * Check if a player is ignoring someone. Hits the DB directly.
     */
    public boolean isIgnored(UUID playerUUID, String ignoredName) {
        synchronized (dbLock) {
            if (connection == null || isIgnored == null) return false;
            try {
                isIgnored.setString(1, playerUUID.toString());
                isIgnored.setString(2, ignoredName);
                try (ResultSet rs = isIgnored.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to check ignore for " + playerUUID, e);
                return false;
            }
        }
    }

    /**
     * Get the full ignore list for a player. Hits the DB directly.
     */
    public List<String> getIgnoreList(UUID playerUUID) {
        List<String> list = new ArrayList<>();
        synchronized (dbLock) {
            if (connection == null || getIgnoreList == null) return list;
            try {
                getIgnoreList.setString(1, playerUUID.toString());
                try (ResultSet rs = getIgnoreList.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString("ignored_name"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to get ignore list for " + playerUUID, e);
            }
        }
        return list;
    }

    // ── Background flush thread ────────────────────────────────────────────

    /**
     * Main loop for the background writer thread.
     * Drains the queue in batches, writes within a single transaction per batch.
     */
    private void flushLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Block until something appears in the queue
                WriteOp first = writeQueue.poll();
                if (first == null) {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                    continue;
                }

                // Drain up to MAX_BATCH_SIZE more ops
                List<WriteOp> batch = new ArrayList<>();
                batch.add(first);
                WriteOp next;
                while (batch.size() < MAX_BATCH_SIZE && (next = writeQueue.poll()) != null) {
                    batch.add(next);
                }

                // Execute the batch in a single transaction
                flushBatch(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "IgnoreDB writer thread crashed", e);
        } finally {
            shutdownLatch.countDown();
        }
    }

    /**
     * Execute a batch of write ops inside a single transaction.
     */
    private void flushBatch(List<WriteOp> batch) {
        if (batch.isEmpty()) return;

        synchronized (dbLock) {
            if (connection == null) return;

            try {
                connection.setAutoCommit(false);

                for (WriteOp op : batch) {
                    switch (op.type()) {
                        case ADD -> {
                            addIgnore.setString(1, op.uuid().toString());
                            addIgnore.setString(2, op.name());
                            addIgnore.executeUpdate();
                        }
                        case REMOVE -> {
                            removeIgnore.setString(1, op.uuid().toString());
                            removeIgnore.setString(2, op.name());
                            removeIgnore.executeUpdate();
                        }
                        case CLEAR -> {
                            removeAllIgnores.setString(1, op.uuid().toString());
                            removeAllIgnores.executeUpdate();
                        }
                    }
                }

                connection.commit();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to flush ignore write batch (" + batch.size() + " ops)", e);
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    logger.log(Level.WARNING, "Rollback also failed", ex);
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Failed to restore autocommit", e);
                }
            }
        }
    }

    // ── Migration ──────────────────────────────────────────────────────────

    /**
     * Migrate all old per-player text files into the SQLite database.
     */
    public void migrateFromTextFiles(File dataFolder) {
        File ignoreDir = new File(dataFolder, "ignorelists");
        if (!ignoreDir.exists() || !ignoreDir.isDirectory()) {
            return;
        }

        File[] files = ignoreDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return;
        }

        logger.info("Migrating " + files.length + " ignore list file(s) to SQLite...");

        int migrated = 0;
        int entries = 0;

        synchronized (dbLock) {
            if (connection == null) return;
            
            for (File file : files) {
                String fileName = file.getName().replace(".txt", "");

                UUID uuid = null;
                try {
                    uuid = UUID.fromString(fileName);
                } catch (IllegalArgumentException e) {
                    // Try to resolve legacy name-based ignore files
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(fileName);
                    if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                        uuid = offlinePlayer.getUniqueId();
                        logger.info("Resolved legacy ignore file '" + fileName + "' to UUID: " + uuid);
                    } else {
                        logger.warning("Skipping non-UUID and unresolvable ignore file: " + file.getName());
                        continue;
                    }
                }

                List<String> ignoredNames = readIgnoreFile(file);
                if (ignoredNames.isEmpty()) {
                    file.delete();
                    migrated++;
                    continue;
                }

                // Write directly to DB (not through queue) during migration
                boolean success = false;
                try {
                    connection.setAutoCommit(false);
                    for (String name : ignoredNames) {
                        if (name != null && !name.trim().isEmpty()) {
                            addIgnore.setString(1, uuid.toString());
                            addIgnore.setString(2, name.trim());
                            addIgnore.executeUpdate();
                            entries++;
                        }
                    }
                    connection.commit();
                    success = true;
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Failed to migrate ignore file: " + file.getName(), e);
                    try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
                } finally {
                    try { connection.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
                }

                if (success) {
                    file.delete();
                    migrated++;
                }
            }
        }

        File[] remaining = ignoreDir.listFiles();
        if (remaining != null && remaining.length == 0) {
            ignoreDir.delete();
        }

        logger.info("Migration complete: " + migrated + " file(s), " + entries + " ignore entries imported.");
    }

    private List<String> readIgnoreFile(File file) {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    names.add(line);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read ignore file: " + file.getName(), e);
        }
        return names;
    }

    // ── Shutdown ───────────────────────────────────────────────────────────

    /**
     * Flush remaining writes and close the database connection.
     */
    public void close() {
        if (closed) return;
        closed = true;

        // Signal the flush thread to stop
        flushThread.interrupt();

        // Wait for the flush thread to finish (up to 5 seconds)
        try {
            shutdownLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (dbLock) {
            // Flush any remaining ops that arrived between interrupt and shutdown
            List<WriteOp> remaining = new ArrayList<>();
            WriteOp op;
            while ((op = writeQueue.poll()) != null) {
                remaining.add(op);
            }
            if (!remaining.isEmpty()) {
                logger.info("Flushing " + remaining.size() + " remaining ignore writes before shutdown...");
                flushBatch(remaining);
            }

            try {
                if (addIgnore != null) addIgnore.close();
                if (removeIgnore != null) removeIgnore.close();
                if (removeAllIgnores != null) removeAllIgnores.close();
                if (isIgnored != null) isIgnored.close();
                if (getIgnoreList != null) getIgnoreList.close();
                if (connection != null) connection.close();
                logger.info("Ignore database closed.");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error closing ignore database", e);
            }
        }
    }
}
