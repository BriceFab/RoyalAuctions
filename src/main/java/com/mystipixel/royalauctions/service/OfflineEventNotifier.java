package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.data.AuctionTransactions;
import com.mystipixel.royalauctions.data.OfflineEvent;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Replays events from while a player was offline as a short summary after they join, and reminds
 * them of anything waiting in their collection unless the summary already pointed there.
 *
 * <p>Read, send, then delete by row id, so a disconnect or crash leaves events queued. One delivery
 * per player at a time, so a join and a live notification cannot show the same events twice.
 */
public final class OfflineEventNotifier implements Listener {

    private static final long DELAY_TICKS = 60L;
    private static final int MAX_LINES = 8;

    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final MessageManager messages;
    private final VaultHook vault;
    private final PluginConfig config;
    private final Workers workers;
    private final Set<UUID> delivering = ConcurrentHashMap.newKeySet();
    private final Set<UUID> again = ConcurrentHashMap.newKeySet();
    private final Set<UUID> remind = ConcurrentHashMap.newKeySet();

    private record Waiting(int items, double earnings) {
        static final Waiting NOTHING = new Waiting(0, 0);

        boolean any() {
            return items > 0 || earnings > 0;
        }
    }

    public OfflineEventNotifier(JavaPlugin plugin, AuctionDatabase db, MessageManager messages, VaultHook vault,
                                PluginConfig config, Workers workers) {
        this.plugin = plugin;
        this.db = db;
        this.messages = messages;
        this.vault = vault;
        this.config = config;
        this.workers = workers;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            remind.add(id);
            start(id);
        }, DELAY_TICKS);
    }

    /** Delivers now to an online recipient; offline players' notices wait for their join. */
    public void notifyOnline(UUID id) {
        workers.sync(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) start(id);
        });
    }

    private void start(UUID id) {
        if (workers.closing()) return;
        if (!delivering.add(id)) {
            again.add(id);                       // picked up when the running delivery finishes
            return;
        }
        workers.async(() -> deliver(id));
    }

    private void deliver(UUID id) {
        boolean joined = remind.remove(id);
        Map<Long, OfflineEvent> events;
        Waiting waiting;
        try {
            events = db.peekEvents(id);
            waiting = joined ? waiting(id) : Waiting.NOTHING;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the join notice (offline events, collection) for " + id, e);
            finish(id);
            return;
        }
        if (events.isEmpty() && !waiting.any()) {
            finish(id);
            return;
        }
        workers.sync(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                finish(id);                      // left before delivery: the events stay queued
                return;
            }
            boolean pointedAtCollect = !events.isEmpty() && send(player, List.copyOf(events.values()));
            if (waiting.any() && !pointedAtCollect) {
                messages.send(player, "collection.waiting", "items", String.valueOf(waiting.items()),
                        "earnings", vault.format(waiting.earnings()));
            }
            if (events.isEmpty()) {
                finish(id);
                return;
            }
            List<Long> shown = new ArrayList<>(events.keySet());
            workers.async(() -> {
                try {
                    db.deleteEvents(shown);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not clear delivered offline auction events", e);
                } finally {
                    finish(id);
                }
            });
        });
    }

    private Waiting waiting(UUID id) throws SQLException {
        int items = db.countCollectionItems(id);
        double earnings = 0;
        if (config.manualEarnings()) {
            for (AuctionTransactions.Operation payout : db.transactions().unclaimedEarnings(id)) {
                earnings += payout.amount();
            }
        }
        return new Waiting(items, earnings);
    }

    private void finish(UUID id) {
        delivering.remove(id);
        if (again.remove(id)) start(id);
    }

    // Returns true if a line pointing at /ah collect (won auction, expired listings) was shown.
    private boolean send(Player player, List<OfflineEvent> events) {
        messages.send(player, "away.header");

        // Expired listings collapse into one count line however many sweeps produced them.
        long expired = 0;
        for (OfflineEvent event : events) {
            if (OfflineEvent.EXPIRED.equals(event.type())) {
                expired += (long) event.amount();
            }
        }

        int lines = 0;
        int hidden = 0;
        boolean pointedAtCollect = expired > 0;
        for (OfflineEvent event : events) {
            if (OfflineEvent.EXPIRED.equals(event.type())) {
                continue;
            }
            if (lines >= MAX_LINES) {
                hidden++;
                continue;
            }
            String item = event.item() == null ? "an item" : event.item();
            switch (event.type()) {
                case OfflineEvent.SOLD -> messages.send(player, "away.sold",
                        "item", item, "amount", vault.format(event.amount()));
                case OfflineEvent.OUTBID -> messages.send(player, "away.outbid",
                        "item", item, "amount", vault.format(event.amount()));
                case OfflineEvent.WON -> {
                    messages.send(player, "away.won", "item", item, "amount", vault.format(event.amount()));
                    pointedAtCollect = true;
                }
                default -> {
                    continue;                    // a type from a future version: skip
                }
            }
            lines++;
        }
        if (expired > 0) {
            messages.send(player, "away.expired", "count", String.valueOf(expired));
        }
        if (hidden > 0) {
            messages.send(player, "away.more", "count", String.valueOf(hidden));
        }
        return pointedAtCollect;
    }
}
