package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.EconGuardHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.tier.TierManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// A Player held across the database hop belongs to a session that may have ended: the same account
// can be back online as a new Player object, and isOnline() is still true on the old one.
class RelogTest {
    @TempDir Path folder;
    JavaPlugin plugin; AuctionDatabase db; VaultHook vault; PluginConfig config;
    AuctionService service; MockedStatic<Bukkit> bukkit; MockedStatic<ItemSerialization> serialization;
    final UUID id = UUID.randomUUID(), sellerId = UUID.randomUUID();
    final byte[] bytes = {1, 2, 3};
    Player held, rejoined, seller; ItemStack item;

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class); vault = mock(VaultHook.class); config = mock(PluginConfig.class);
        EconGuardHook guard = mock(EconGuardHook.class); when(guard.allow(any())).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(true);
        when(vault.withdraw(any(), anyDouble())).thenReturn(true); when(config.bidIncrementFor(anyDouble())).thenReturn(10d);
        item = mock(ItemStack.class); when(item.getAmount()).thenReturn(1); when(item.getMaxStackSize()).thenReturn(64);
        when(item.clone()).thenReturn(item); when(item.getType()).thenReturn(org.bukkit.Material.STONE);
        held = player(id); rejoined = player(id); seller = player(sellerId);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(held);
        bukkit.when(() -> Bukkit.getPlayer(sellerId)).thenReturn(seller);
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return mock(BukkitTask.class); });
        // ItemStack byte serialization needs a running server; the Base64 helpers stay real
        serialization = mockStatic(ItemSerialization.class, call -> switch (call.getMethod().getName()) {
            case "serialize" -> bytes;
            case "deserialize" -> item;
            default -> call.callRealMethod();
        });
        db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getLogger("test")); db.init();
        service = new AuctionService(plugin, db, vault, config, mock(CategoryManager.class), mock(TierManager.class),
                mock(MessageManager.class), guard, new Workers(plugin, Runnable::run));
    }
    @AfterEach void close() { db.close(); serialization.close(); bukkit.close(); }

    Player player(UUID uuid) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(uuid); when(p.getName()).thenReturn("name");
        when(p.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getMaxStackSize()).thenReturn(64);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        when(p.getInventory()).thenReturn(inventory);
        return p;
    }
    void relog() { bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(rejoined); }
    void complete(AuctionTransactions.Operation o) throws Exception {
        assertTrue(db.transactions().begin(o.id(), "fixture")); db.transactions().acknowledge(o.id(), true); db.transactions().finish(o.id());
    }
    UUID stored(UUID owner) throws Exception {
        var captured = db.transactions().reserveCapture(owner, "name", bytes, "fixture").operation();
        complete(captured);
        return captured.collection();
    }
    Listing listed(ListingType type) throws Exception {
        UUID collection = stored(sellerId);
        var l = new Listing(UUID.randomUUID(), sellerId, "seller", bytes, "test item", "misc", null, type, 50,
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, ListingStatus.ACTIVE, 0, null, null, 0);
        complete(db.transactions().reserveCreate(l, collection, 0, -1, "fixture").operation());
        return l;
    }

    @Test void captureTakesTheItemFromTheCurrentPlayer() throws Exception {
        when(held.getInventory().getItem(4)).thenReturn(item);
        List<UUID> done = new ArrayList<>();
        service.capture(held, 4, item, done::add);
        assertNotNull(done.get(0));
        verify(held.getInventory()).setItem(4, null);
        assertEquals(1, db.collectionItems(id).size());
    }

    @Test void captureAfterRelogLeavesTheItemAndStoresNothing() throws Exception {
        when(held.getInventory().getItem(4)).thenReturn(item);
        relog();
        List<UUID> done = new ArrayList<>();
        service.capture(held, 4, item, done::add);
        assertEquals(Collections.singletonList(null), done);
        verify(held.getInventory(), never()).setItem(anyInt(), any());
        verify(held, never()).saveData();
        assertTrue(db.collectionItems(id).isEmpty(), "the rejoined player still has the item, so a stored copy would be a duplicate");
    }

    @Test void claimGivesTheItemToTheCurrentPlayer() throws Exception {
        UUID collection = stored(id);
        List<String> done = new ArrayList<>();
        service.claimById(held, collection, () -> done.add("reopen"));
        verify(held.getInventory()).addItem(item);
        assertTrue(db.collectionItems(id).isEmpty());
        assertEquals(List.of("reopen"), done);
    }

    @Test void claimAfterRelogKeepsTheItemInTheCollection() throws Exception {
        UUID collection = stored(id);
        relog();
        List<String> done = new ArrayList<>();
        service.claimById(held, collection, () -> done.add("reopen"));
        verify(held.getInventory(), never()).addItem(any(ItemStack[].class));
        verify(rejoined.getInventory(), never()).addItem(any(ItemStack[].class));
        assertEquals(1, db.collectionItems(id).size());
        assertTrue(done.isEmpty(), "no menu is reopened for the old session");
        assertTrue(db.transactions().pending(50).isEmpty(), "the declined claim is closed cleanly, not held for recovery");
    }

    @Test void instantDeliveryAfterRelogLeavesThePurchaseInTheCollection() throws Exception {
        when(config.instantDeliver()).thenReturn(true);
        Listing listing = listed(ListingType.BIN);
        relog();
        List<String> done = new ArrayList<>();
        service.purchase(held, listing, () -> done.add("reopen"));
        verify(vault).withdraw(held, 50);
        verify(held.getInventory(), never()).addItem(any(ItemStack[].class));
        assertEquals(1, db.collectionItems(id).size());
        assertTrue(done.isEmpty());
    }

    @Test void bidAfterRelogStillChargesTheAccountButReopensNothing() throws Exception {
        Listing listing = listed(ListingType.AUCTION);
        relog();
        List<String> done = new ArrayList<>();
        service.placeBid(held, listing, 60, () -> done.add("reopen"));
        verify(vault).withdraw(held, 60);
        assertEquals(id, db.getListing(listing.id()).orElseThrow().topBidderId());
        assertTrue(done.isEmpty());
    }

    @Test void cancelAfterRelogReopensNothing() throws Exception {
        Player sellerRejoined = player(sellerId);
        bukkit.when(() -> Bukkit.getPlayer(sellerId)).thenReturn(sellerRejoined);
        List<String> done = new ArrayList<>();
        service.cancelListing(seller, listed(ListingType.BIN), () -> done.add("reopen"));
        assertTrue(done.isEmpty());
        assertEquals(1, db.collectionItems(sellerId).size());
    }
}
