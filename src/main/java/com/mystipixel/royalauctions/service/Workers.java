package com.mystipixel.royalauctions.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;

/**
 * Plugin-owned database threads and main-thread queue. Bukkit drops queued tasks on disable and never
 * waits for async ones; owning both lets {@link #shutdown} finish in-flight exchanges before storage closes.
 */
public final class Workers {
    private final JavaPlugin plugin;
    private final Executor database;
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean closing;

    /** {@code threads}: the database connection pool size; more threads would only wait inside the pool. */
    public Workers(JavaPlugin plugin, int threads) {
        this(plugin, databasePool(threads));
    }

    /** Tests pass an inline executor so exchanges run to completion synchronously. */
    public Workers(JavaPlugin plugin, Executor database) {
        this.plugin = plugin;
        this.database = database;
    }

    private static ExecutorService databasePool(int size) {
        AtomicInteger threads = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(1, size), r -> {
            Thread t = new Thread(r, "RoyalAuctions-db-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** True once shutdown has begun: no new exchanges start, and queued effects are declined. */
    public boolean closing() {
        return closing;
    }

    /** Blocking database work off the main thread. Tracked, so shutdown waits for it. */
    public void async(Runnable task) {
        inFlight.incrementAndGet();
        try {
            database.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Unhandled error in an auction database task", t);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            plugin.getLogger().warning("Dropped an auction database task submitted after shutdown.");
        }
    }

    /** Main-thread work, queued here rather than in Bukkit so shutdown can drain it. */
    public void sync(Runnable task) {
        main.add(task);
        if (!closing && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, this::drainMain);
        }
    }

    private void drainMain() {
        Runnable task;
        while ((task = main.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Unhandled error in an auction main-thread task", t);
            }
        }
    }

    /**
     * Call on the main thread from {@code onDisable}. Stops new exchanges, then drains main-thread steps
     * and database work until both are empty, so every started exchange completes or is durably declined.
     */
    public void shutdown(long timeoutMillis) {
        closing = true;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            drainMain();
            if (inFlight.get() == 0 && main.isEmpty()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                plugin.getLogger().warning("Auction work still running after " + timeoutMillis
                        + "ms at shutdown; anything unfinished is held for /ah recovery.");
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        if (!(database instanceof ExecutorService pool)) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
