package com.mystipixel.royalauctions.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import com.mystipixel.royalauctions.api.AuctionCurrency;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.currency.Currencies;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.service.AuctionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.OptionalInt;
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
        GuiManager manager = new GuiManager(null, null, null, null, null, null, null, null, null, null);
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
        GuiManager manager = new GuiManager(null, service, null, null, null, null, null, null, null, null);
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

    private record QuickSell(GuiManager manager, AuctionService service, MessageManager messages, Player player, ItemStack item) { }

    private QuickSell quickSellSetup() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getHeldItemSlot()).thenReturn(3);
        when(inventory.getItem(3)).thenReturn(item);
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission("royalauctions.sell")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PluginConfig config = mock(PluginConfig.class);
        when(config.defaultDurationHours()).thenReturn(6);
        when(config.durationMillisFor(6)).thenReturn(21_600_000L);
        Currencies currencies = mock(Currencies.class);
        when(currencies.offers(AuctionCurrency.MONEY)).thenReturn(true);
        when(currencies.scale(AuctionCurrency.MONEY)).thenReturn(OptionalInt.of(2));
        AuctionService service = mock(AuctionService.class);
        MessageManager messages = mock(MessageManager.class);
        GuiManager manager = new GuiManager(null, service, config, null, null, messages, null, currencies, null, null);
        return new QuickSell(manager, service, messages, player, item);
    }

    @SuppressWarnings("unchecked")
    @Test void quickSellListsTheHeldStackAtAFixedMoneyPrice() {
        QuickSell q = quickSellSetup();
        UUID collection = UUID.randomUUID();
        q.manager().quickSell(q.player(), "1k");
        ArgumentCaptor<Consumer<UUID>> captured = ArgumentCaptor.forClass(Consumer.class);
        verify(q.service()).capture(eq(q.player()), eq(3), eq(q.item()), captured.capture());
        captured.getValue().accept(collection);
        verify(q.service()).createListing(eq(q.player()), eq(collection), eq(q.item()), eq(1000.0), eq(ListingType.BIN),
                eq(21_600_000L), eq(AuctionCurrency.MONEY), any());
    }

    @SuppressWarnings("unchecked")
    @Test void aRefusedQuickSellGivesTheItemBack() {
        QuickSell q = quickSellSetup();
        UUID collection = UUID.randomUUID();
        q.manager().quickSell(q.player(), "50");
        ArgumentCaptor<Consumer<UUID>> captured = ArgumentCaptor.forClass(Consumer.class);
        verify(q.service()).capture(any(), anyInt(), any(), captured.capture());
        captured.getValue().accept(collection);
        ArgumentCaptor<Consumer<Boolean>> listed = ArgumentCaptor.forClass(Consumer.class);
        verify(q.service()).createListing(any(), any(), any(), anyDouble(), any(), anyLong(), any(), listed.capture());
        listed.getValue().accept(false);
        verify(q.service()).claimById(eq(q.player()), eq(collection), any());
    }

    @SuppressWarnings("unchecked")
    @Test void aSecondQuickSellWaitsForTheFirst() {
        QuickSell q = quickSellSetup();
        q.manager().quickSell(q.player(), "50");
        q.manager().quickSell(q.player(), "50");
        ArgumentCaptor<Consumer<UUID>> captured = ArgumentCaptor.forClass(Consumer.class);
        verify(q.service(), times(1)).capture(any(), anyInt(), any(), captured.capture());
        captured.getValue().accept(null);
        q.manager().quickSell(q.player(), "50");
        verify(q.service(), times(2)).capture(any(), anyInt(), any(), any());
    }

    @Test void quickSellRefusesAnUnreadablePriceBeforeTakingTheItem() {
        QuickSell q = quickSellSetup();
        q.manager().quickSell(q.player(), "free");
        verify(q.messages()).send(q.player(), "sell.usage");
        verify(q.service(), never()).capture(any(), anyInt(), any(), any());
    }
}
