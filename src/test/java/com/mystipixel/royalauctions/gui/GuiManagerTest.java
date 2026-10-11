package com.mystipixel.royalauctions.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.service.AuctionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuiManagerTest {

    private Player viewing(InventoryHolder holder) {
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        Player player = mock(Player.class);
        when(player.getOpenInventory()).thenReturn(view);
        return player;
    }

    @Test void closeMenusClosesOnlyAuctionMenus() {
        Player inMenu = viewing(mock(AuctionGui.class));
        Player inChest = viewing(mock(InventoryHolder.class));
        Player inOwnInventory = viewing(null);
        GuiManager manager = new GuiManager(null, null, null, null, null, null, null, null, null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(inMenu, inChest, inOwnInventory));
            manager.closeMenus();
        }
        verify(inMenu).closeInventory();
        verify(inChest, never()).closeInventory();
        verify(inOwnInventory, never()).closeInventory();
    }

    // Navigation tickets restart after a quit, so the first menu after a relog can carry the same
    // ticket as a load still running for the old session.
    @SuppressWarnings("unchecked")
    @Test void aLoadFromBeforeARelogDoesNotOpenAMenu() {
        UUID id = UUID.randomUUID();
        Player held = mock(Player.class), rejoined = mock(Player.class);
        when(held.getUniqueId()).thenReturn(id); when(held.isOnline()).thenReturn(true);
        when(rejoined.getUniqueId()).thenReturn(id); when(rejoined.isOnline()).thenReturn(true);
        AuctionService service = mock(AuctionService.class);
        GuiManager manager = new GuiManager(null, service, null, null, null, null, null, null, null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(held);
            manager.openBids(held);
            manager.handleQuit(held);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(rejoined);
            manager.openBids(rejoined);
            ArgumentCaptor<Consumer<List<Listing>>> loads = ArgumentCaptor.forClass(Consumer.class);
            verify(service, times(2)).loadBidListings(eq(id), loads.capture());
            loads.getAllValues().get(0).accept(List.of());
        }
        verify(held, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
}
