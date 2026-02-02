package network.vonix.vonixcore.consumer;

import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Consumer {

    private static Consumer instance;

    private final ConcurrentLinkedQueue<QueueEntry> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public static Consumer getInstance() {
        if (instance == null) {
            instance = new Consumer();
        }
        return instance;
    }

    private Consumer() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "vonixcore-Consumer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            int delayMs = DatabaseConfig.CONFIG.consumerDelayMs.get();
            executor.scheduleAtFixedRate(this::processQueue, delayMs, delayMs, TimeUnit.MILLISECONDS);
            VonixCore.LOGGER.info("[vonixcore] Consumer started with {}ms delay", delayMs);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            while (!queue.isEmpty()) {
                processQueue();
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            VonixCore.LOGGER.info("[vonixcore] Consumer stopped");
        }
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void queueEntry(QueueEntry entry) {
        queue.offer(entry);
    }

    public int getQueueSize() {
        return queue.size();
    }

    private void processQueue() {
        if (paused.get() || queue.isEmpty()) {
            return;
        }

        int batchSize = DatabaseConfig.CONFIG.consumerBatchSize.get();
        int processed = 0;

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            conn.setAutoCommit(false);

            QueueEntry entry;
            while (processed < batchSize && (entry = queue.poll()) != null) {
                try {
                    entry.execute(conn);
                    processed++;
                } catch (SQLException e) {
                    VonixCore.LOGGER.error("[vonixcore] Failed to process queue entry: {}", e.getMessage());
                }
            }

            conn.commit();

            if (processed > 0) {
                VonixCore.LOGGER.debug("[vonixcore] Processed {} queue entries", processed);
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[vonixcore] Database error during queue processing: {}", e.getMessage());
        }
    }

    public interface QueueEntry {
        void execute(Connection conn) throws SQLException;
    }

    public static class BlockLogEntry implements QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String type;
        private final String oldType;
        private final String oldData;
        private final String newType;
        private final String newData;
        private final int action;

        public BlockLogEntry(long time, String user, String world, int x, int y, int z,
                String type, String oldType, String oldData, String newType, String newData, int action) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.oldType = oldType;
            this.oldData = oldData;
            this.newType = newType;
            this.newData = newData;
            this.action = action;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_block (time, user, world, x, y, z, type, old_type, old_data, new_type, new_data, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, type);
                stmt.setString(8, oldType);
                stmt.setString(9, oldData);
                stmt.setString(10, newType);
                stmt.setString(11, newData);
                stmt.setInt(12, action);
                stmt.executeUpdate();
            }
        }
    }

    public static class ContainerLogEntry implements QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String containerType;
        private final String item;
        private final int amount;
        private final int action;

        public ContainerLogEntry(long time, String user, String world, int x, int y, int z,
                String containerType, String item, int amount, int action) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.containerType = containerType;
            this.item = item;
            this.amount = amount;
            this.action = action;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_container (time, user, world, x, y, z, type, item, amount, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, containerType);
                stmt.setString(8, item);
                stmt.setInt(9, amount);
                stmt.setInt(10, action);
                stmt.executeUpdate();
            }
        }
    }
}
