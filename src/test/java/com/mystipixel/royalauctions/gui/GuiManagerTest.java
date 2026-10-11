package com.mystipixel.royalauctions.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

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
}
