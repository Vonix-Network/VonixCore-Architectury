package network.vonix.vonixcore.consumer;

import network.vonix.vonixcore.VonixCore;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batches protection data writes to the database.
 */
public class Consumer {

    private static Consumer instance;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(10000);

    public static Consumer getInstance() {
        if (instance == null) {
            instance = new Consumer();
        }
        return instance;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return; // Already running
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-Consumer");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
        VonixCore.LOGGER.info("[VonixCore] Protection consumer started");
    }

    public void stop() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flush(); // Final flush
        VonixCore.LOGGER.info("[VonixCore] Protection consumer stopped");
    }

    public void queue(LogEntry entry) {
        if (!queue.offer(entry)) {
            VonixCore.LOGGER.warn("[Consumer] Queue full, dropping log entry");
        }
    }

    private void flush() {
        if (queue.isEmpty())
            return;

        try {
            int processed = 0;
            LogEntry entry;
            while ((entry = queue.poll()) != null && processed < 500) {
                // Write to database
                writeToDatabase(entry);
                processed++;
            }
            if (processed > 0) {
                VonixCore.LOGGER.debug("[Consumer] Flushed {} entries", processed);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Consumer] Error flushing: {}", e.getMessage());
        }
    }

    private void writeToDatabase(LogEntry entry) {
        // TODO: Implement actual database write
    }

    public record LogEntry(String type, String user, String world, int x, int y, int z, String data) {
    }
}
