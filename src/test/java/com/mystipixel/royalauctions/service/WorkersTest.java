package com.mystipixel.royalauctions.service;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Shutdown finishes work already under way instead of dropping it the way Bukkit's scheduler does.
class WorkersTest {

    private JavaPlugin disabledPlugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(false); // as during onDisable: Bukkit would refuse new tasks
        return plugin;
    }

    @Test void shutdownRunsDatabaseWorkAndTheMainThreadStepsItQueues() {
        Workers workers = new Workers(disabledPlugin(), 2);
        List<String> steps = new CopyOnWriteArrayList<>();
        workers.async(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            steps.add("database");
            workers.sync(() -> {
                steps.add("main");
                workers.async(() -> steps.add("acknowledge"));
            });
        });
        workers.shutdown(5_000);
        assertEquals(List.of("database", "main", "acknowledge"), steps);
        assertTrue(workers.closing());
    }

    @Test void workSubmittedAfterShutdownIsDroppedNotRun() {
        Workers workers = new Workers(disabledPlugin(), 2);
        workers.shutdown(1_000);
        List<String> steps = new CopyOnWriteArrayList<>();
        workers.async(() -> steps.add("late"));
        assertTrue(steps.isEmpty());
    }
}
