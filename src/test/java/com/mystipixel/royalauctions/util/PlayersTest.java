package com.mystipixel.royalauctions.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayersTest {

    @Test void onlyTheObjectTheServerHoldsIsCurrent() {
        UUID id = UUID.randomUUID();
        Player held = mock(Player.class), rejoined = mock(Player.class);
        when(held.getUniqueId()).thenReturn(id); when(held.isOnline()).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(held);
            assertTrue(Players.isCurrent(held));
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(rejoined);
            assertFalse(Players.isCurrent(held), "relogged: isOnline() is still true on the old object");
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
            assertFalse(Players.isCurrent(held));
            assertFalse(Players.isCurrent(null));
        }
    }
}
