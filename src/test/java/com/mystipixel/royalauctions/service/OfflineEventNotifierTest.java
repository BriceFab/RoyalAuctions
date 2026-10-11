package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.data.OfflineEvent;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The join notice against real SQLite: offline events plus the reminder of what waits in /ah collect.
class OfflineEventNotifierTest {
    @TempDir Path folder;
    JavaPlugin plugin; AuctionDatabase db; MessageManager messages; VaultHook vault; PluginConfig config;
    OfflineEventNotifier notifier; Player player; MockedStatic<Bukkit> bukkit;
    final UUID id = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class); messages = mock(MessageManager.class); vault = mock(VaultHook.class);
        config = mock(PluginConfig.class);
        when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(vault.format(anyDouble())).thenAnswer(i -> "$" + i.getArgument(0));
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return mock(BukkitTask.class); });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return mock(BukkitTask.class); });
        db = new AuctionDatabase(folder.toFile(), new YamlConfiguration(), Logger.getLogger("test")); db.init();
        notifier = new OfflineEventNotifier(plugin, db, messages, vault, config, new Workers(plugin, Runnable::run));
    }

    @AfterEach void close() { db.close(); bukkit.close(); }

    void join() {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        notifier.onJoin(event);
    }

    // what a quit during listing leaves behind: the item in the collection and no event
    void itemInCollection() throws Exception {
        var o = db.transactions().reserveCapture(id, "player", new byte[]{1, 2, 3}, "fixture").operation();
        db.transactions().begin(o.id(), "fixture"); db.transactions().acknowledge(o.id(), true); db.transactions().finish(o.id());
    }

    void heldEarnings(double amount) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("auctions.db"));
             var ps = c.prepareStatement("INSERT INTO ra_operations (id,kind,state,player_id,amount,expiry,created_at,updated_at,note) "
                     + "VALUES (?,'PAYOUT','READY',?,?,0,0,0,'SOLD')")) {
            ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, id.toString()); ps.setDouble(3, amount);
            ps.executeUpdate();
        }
    }

    void verifyReminder(String items, String earnings) {
        verify(messages).send(same(player), eq("collection.waiting"), eq("items"), eq(items), eq("earnings"), eq(earnings));
    }

    void verifyNoReminder() {
        verify(messages, never()).send(any(Player.class), eq("collection.waiting"), any(String[].class));
    }

    @Test void itemLeftInCollectionIsMentionedOnJoin() throws Exception {
        itemInCollection();
        join();
        verifyReminder("1", "$0.0");
    }

    @Test void nothingWaitingMeansNoMessage() {
        join();
        verifyNoInteractions(messages);
    }

    @Test void heldEarningsCountOnlyWhenCollectedByHand() throws Exception {
        heldEarnings(250);
        join();
        verifyNoInteractions(messages);

        when(config.manualEarnings()).thenReturn(true);
        join();
        verifyReminder("0", "$250.0");
    }

    @Test void oneReminderAfterASummaryThatDoesNotMentionCollect() throws Exception {
        itemInCollection();
        db.addEvent(id, OfflineEvent.SOLD, "Diamond sword", 100, System.currentTimeMillis());
        join();
        verify(messages).send(same(player), eq("away.header"));
        verify(messages).send(same(player), eq("away.sold"), any(String[].class));
        verifyReminder("1", "$0.0");
    }

    @Test void noReminderWhenTheSummaryAlreadyPointsAtCollect() throws Exception {
        itemInCollection();
        db.addEvent(id, OfflineEvent.WON, "Diamond sword", 100, System.currentTimeMillis());
        join();
        verify(messages).send(same(player), eq("away.won"), any(String[].class));
        verifyNoReminder();

        db.addEvent(id, OfflineEvent.EXPIRED, null, 2, System.currentTimeMillis());
        join();
        verify(messages).send(same(player), eq("away.expired"), any(String[].class));
        verifyNoReminder();
    }

    @Test void liveNotificationsDoNotRemind() throws Exception {
        itemInCollection();
        db.addEvent(id, OfflineEvent.SOLD, "Diamond sword", 100, System.currentTimeMillis());
        notifier.notifyOnline(id);
        verify(messages).send(same(player), eq("away.sold"), any(String[].class));
        verifyNoReminder();
    }

    @Test void playerWhoLeftBeforeTheNoticeGetsNothing() throws Exception {
        itemInCollection();
        when(player.isOnline()).thenReturn(false);
        join();
        verifyNoInteractions(messages);
    }
}
