package com.mystipixel.royalauctions.hooks;

import com.mystipixel.econguard.api.EconGuard;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconGuardHookTest {
    private final UUID player = UUID.randomUUID();
    private final List<LogRecord> logged = new ArrayList<>();

    @AfterEach void reset() { EconGuard.veto = id -> true; }

    private EconGuardHook hook(boolean econGuardEnabled) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("EconGuard")).thenReturn(econGuardEnabled);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            return new EconGuardHook(logger);
        }
    }

    @Test void followsEconGuardsAnswer() {
        EconGuardHook hook = hook(true);
        assertTrue(hook.hasVeto());
        EconGuard.veto = id -> !id.equals(player);
        assertFalse(hook.allow(player));
        assertTrue(hook.allow(UUID.randomUUID()));
    }

    @Test void permitsWithoutEconGuard() {
        EconGuardHook hook = hook(false);
        EconGuard.veto = id -> false;
        assertFalse(hook.hasVeto());
        assertTrue(hook.allow(player));
    }

    @Test void aFailingVetoPermitsAndWarnsOnce() {
        EconGuardHook hook = hook(true);
        EconGuard.veto = id -> { throw new IllegalStateException("service gone"); };
        assertTrue(hook.allow(player));
        assertTrue(hook.allow(player));
        assertEquals(1, logged.size());
        assertEquals(Level.WARNING, logged.get(0).getLevel());
    }
}
