package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Auction changes use durable database reservations; all inventory/Vault effects run on main. */
public final class AuctionService {
    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final AuctionTransactions transactions;
    private final VaultHook vault;
    private final PluginConfig config;
    private final CategoryManager categories;
    private final com.mystipixel.royalauctions.tier.TierManager tiers;
    private final MessageManager messages;
    private final com.mystipixel.royalauctions.hooks.EconGuardHook econGuard;
    private final String worker = UUID.randomUUID().toString();
    private final ExternalEffectRunner effects;
    private final Workers workers;
    private final PendingPayments legacyPayments;
    private final AtomicBoolean recovering = new AtomicBoolean();
    private long lastRecoveryWarning;
    private volatile int activeCache;
    private Consumer<UUID> eventReady = id -> {};

    public void eventNotifier(Consumer<UUID> notifier) { this.eventReady = notifier; }
    /** Main-thread compatibility worker for file receipts created before the database journal. */
    public void retryPayments() { legacyPayments.retryRejected(); }

    public AuctionService(JavaPlugin plugin, AuctionDatabase db, VaultHook vault, PluginConfig config,
                          CategoryManager categories, com.mystipixel.royalauctions.tier.TierManager tiers,
                          MessageManager messages, com.mystipixel.royalauctions.hooks.EconGuardHook econGuard,
                          Workers workers) {
        this.plugin = plugin; this.db = db; this.transactions = db.transactions(); this.vault = vault;
        this.config = config; this.categories = categories; this.tiers = tiers;
        this.messages = messages; this.econGuard = econGuard; this.workers = workers;
        // New exchanges use the database journal; old file receipts retain their original IDs.
        legacyPayments = new PendingPayments(new PaymentJournal(plugin.getDataFolder().toPath().resolve("payments")),
                vault, econGuard, plugin.getLogger());
        effects = new ExternalEffectRunner(transactions, workers::async, workers::sync, worker,
                e -> logError("processing an external effect; check /ah recovery", e), workers::closing);
        plugin.getLogger().info("Auction recovery worker: " + worker);
    }
    /** Start new work. Refused once shutdown begins; work already under way still completes. */
    private void async(Runnable r) { if (!workers.closing()) workers.async(r); }
    private void sync(Runnable r) { workers.sync(r); }
    private void logError(String what, Throwable e) { plugin.getLogger().log(Level.SEVERE, "Error " + what, e); }
    private void tell(Player player, String key, String... values) { if (player.isOnline()) messages.send(player, key, values); }
    private void failure(Player player, Exception e) {
        if (e instanceof AuctionTransactions.Rejected r) tell(player, "exchange.rejected", "reason",
                r.key == null ? r.getMessage() : messages.text("exchange.reasons." + r.key, r.values));
        else { logError("reserving an auction operation", e); tell(player, "exchange.unconfirmed"); }
    }
    private void result(Player player, UUID operation, ExternalEffectRunner.Result result) {
        if (result == ExternalEffectRunner.Result.PENDING)
            tell(player, "exchange.pending", "operation", operation.toString());
        else if (result == ExternalEffectRunner.Result.DECLINED)
            tell(player, "exchange.declined");
    }
    private boolean debit(Player player, double amount) {
        return player.isOnline() && (amount == 0 || vault.withdraw(player, amount));
    }

    public void placeBid(Player bidder, Listing shown, double amount, Runnable onDone) {
        UUID player = bidder.getUniqueId(); String name = bidder.getName();
        async(() -> {
            try {
                var reserved = transactions.reserveBid(shown.id(), player, name, amount, config::bidIncrementFor, config.antiSnipeMillis(), worker);
                effects.execute(reserved.operation().id(), () -> debit(bidder, amount), outcome -> {
                    result(bidder, reserved.operation().id(), outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED) {
                        Listing l = reserved.listing();
                        messages.send(bidder, "bid.placed", "item", l.displayName(), "amount", vault.format(amount));
                        if (reserved.operation().expiry() > l.expiresAt())
                            messages.send(bidder, "bid.extended", "seconds", String.valueOf(Math.max(1, (reserved.operation().expiry() - System.currentTimeMillis()) / 1000)));
                        econGuard.report(player, name, "bid", amount, false, l.sellerId(), l.sellerName(), l.displayName());
                        Player seller = Bukkit.getPlayer(l.sellerId());
                        if (seller != null) messages.send(seller, "bid.new-bid-seller", "item", l.displayName(), "amount", vault.format(amount), "bidder", name);
                    }
                    if (bidder.isOnline()) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(bidder, e); if (bidder.isOnline()) onDone.run(); }); }
        });
    }

    public void purchase(Player buyer, Listing shown, Runnable onDone) {
        UUID player = buyer.getUniqueId(); String name = buyer.getName();
        async(() -> {
            try {
                var reserved = transactions.reserveBuy(shown.id(), player, name, shown.price(), worker);
                effects.execute(reserved.operation().id(), () -> debit(buyer, reserved.operation().amount()), outcome -> {
                    result(buyer, reserved.operation().id(), outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED) {
                        Listing l = reserved.listing();
                        econGuard.report(player, name, "buy", l.price(), false, l.sellerId(), l.sellerName(), l.displayName());
                        tell(buyer, "exchange.purchased");
                        if (config.instantDeliver() && buyer.isOnline())
                            claimById(buyer, settlementId(l.id()), () -> { if (buyer.isOnline()) onDone.run(); });
                        else if (buyer.isOnline()) onDone.run();
                    } else if (buyer.isOnline()) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(buyer, e); if (buyer.isOnline()) onDone.run(); }); }
        });
    }
    private static UUID settlementId(UUID listing) {
        return UUID.nameUUIDFromBytes(("settlement/" + listing).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void cancelListing(Player seller, Listing shown, Runnable onDone) {
        UUID player = seller.getUniqueId();
        async(() -> {
            try { transactions.cancel(shown.id(), player); sync(() -> { messages.send(seller, "cancel.success"); if (seller.isOnline()) onDone.run(); }); }
            catch (Exception e) { sync(() -> { failure(seller, e); if (seller.isOnline()) onDone.run(); }); }
        });
    }

    /** Persist the item and intent before removing anything from the live inventory. */
    public void capture(Player player, int slot, ItemStack expected, Consumer<UUID> completed) {
        UUID owner = player.getUniqueId(); String name = player.getName();
        byte[] bytes = ItemSerialization.serialize(expected);
        async(() -> {
            try {
                var r = transactions.reserveCapture(owner, name, bytes, worker);
                effects.execute(r.operation().id(), () -> {
                    if (!player.isOnline() || !expected.equals(player.getInventory().getItem(slot))) return false;
                    player.getInventory().setItem(slot, null);
                    player.saveData();
                    return true;
                }, outcome -> {
                    result(player, r.operation().id(), outcome);
                    completed.accept(outcome == ExternalEffectRunner.Result.COMPLETED ? r.operation().collection() : null);
                });
            } catch (Exception e) { sync(() -> { failure(player, e); completed.accept(null); }); }
        });
    }

    public void createListing(Player seller, UUID collection, ItemStack item, double price, ListingType type,
                              long duration, Consumer<Boolean> completed) {
        // The menus reach this too, not only /ah sell, so the permission is enforced here.
        if (!seller.hasPermission("royalauctions.sell")) {
            tell(seller, "general.no-permission"); completed.accept(false); return;
        }
        if (collection == null || !Double.isFinite(price) || price < config.minPrice()
                || (config.hasMaxPrice() && price > config.maxPrice()) || duration <= 0) {
            tell(seller, "exchange.invalid-listing"); completed.accept(false); return;
        }
        String category = categories.categorize(item);
        if (!config.canSellCategory(category)) { messages.send(seller, "sell.category-not-allowed", "category", category); completed.accept(false); return; }
        long now = System.currentTimeMillis();
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), ItemSerialization.serialize(item),
                displayNameOf(item), category, tiers.tierOf(item), type, price, now, Math.addExact(now, duration), ListingStatus.DRAFT, 0, null, null, 0);
        double fee = config.feeFor(price);
        int limit = listingLimit(seller);
        async(() -> {
            try {
                var reserved = transactions.reserveCreate(listing, collection, fee, limit, worker);
                effects.execute(reserved.operation().id(), () -> debit(seller, fee), outcome -> {
                    result(seller, reserved.operation().id(), outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED)
                        messages.send(seller, "sell.success", "item", listing.displayName(), "price", vault.format(price), "fee", vault.format(fee));
                    // Even a pending operation retains the item in durable custody. The session is closed.
                    completed.accept(outcome != ExternalEffectRunner.Result.DECLINED);
                });
            } catch (Exception e) { sync(() -> { failure(seller, e); completed.accept(false); }); }
        });
    }

    /**
     * Active-listing cap for a seller: unlimited for admins, else the highest
     * {@code royalauctions.limit.<n>} they hold ({@code royalauctions.limit.unlimited} lifts it),
     * else {@code max-per-player}. Any negative result means unlimited.
     */
    public int listingLimit(Player seller) {
        if (seller.hasPermission("royalauctions.admin")) return -1;
        String prefix = "royalauctions.limit.";
        int best = Integer.MIN_VALUE;
        for (PermissionAttachmentInfo info : seller.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (!info.getValue() || !permission.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            String value = permission.substring(prefix.length());
            if (value.equalsIgnoreCase("unlimited")) return -1;
            try { best = Math.max(best, Integer.parseInt(value)); } catch (NumberFormatException ignored) { }
        }
        int limit = best == Integer.MIN_VALUE ? config.maxPerPlayer() : best;
        return limit < 0 ? -1 : limit;
    }

    public void claim(Player player, CollectionItem item, Runnable onDone) { claimById(player, item.id(), onDone); }
    public void claimById(Player player, UUID id, Runnable onDone) {
        UUID owner = player.getUniqueId(); String name = player.getName();
        async(() -> {
            try {
                var r = transactions.reserveClaim(id, owner, name, worker);
                effects.execute(r.operation().id(), () -> {
                    if (!player.isOnline()) return false;
                    ItemStack item = r.item().item();
                    if (!hasRoom(player, item)) { messages.send(player, "collection.full-inventory"); return false; }
                    // On the main thread, capacity cannot change between the check and the add.
                    if (!player.getInventory().addItem(item).isEmpty())
                        throw new IllegalStateException("Partial inventory delivery; reconcile " + r.operation().id());
                    player.saveData();
                    return true;
                }, outcome -> {
                    result(player, r.operation().id(), outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED) messages.send(player, "collection.claimed", "item", displayNameOf(r.item().item()));
                    if (player.isOnline()) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(player, e); if (player.isOnline()) onDone.run(); }); }
        });
    }
    static boolean hasRoom(Player player, ItemStack item) {
        int remaining = item.getAmount();
        int max = Math.min(item.getMaxStackSize(), player.getInventory().getMaxStackSize());
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) remaining -= max;
            else if (slot.isSimilar(item)) remaining -= Math.max(0, max - slot.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ loaders (async → main callback)

    /**
     * One page of browse results, fetched off the main thread. The stale-category repair runs over the
     * rows on this page; a full sweep also happens at startup, so listings the player never scrolls to
     * still get fixed.
     */
    public void loadBrowsePage(ListingQuery query, int page, int perPage, Consumer<ListingPage> callback) {
        async(() -> {
            try {
                ListingPage result = db.browse(query, page, perPage);
                repairStaleCategories(result.rows());
                sync(() -> callback.accept(result));
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(ListingPage.empty()));
            }
        });
    }

    /**
     * Pass over every active listing, repairing categories renamed in config. Runs after startup
     * (once eco has registered its items, so custom items are not filed by base material) and
     * after each reload.
     */
    public void repairAllCategories() {
        async(() -> {
            try {
                repairStaleCategories(db.activeListings());
            } catch (Exception e) {
                logError("repairing listing categories", e);
            }
        });
    }

    public void loadActiveListings(Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListings();
                repairStaleCategories(list);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    /**
     * A listing's category is stamped when it's created, so renaming a category in config would
     * otherwise strand every existing listing under an id no button matches — visible in "All", but
     * in no category. Re-derive those from the item and persist the fix, once.
     */
    private void repairStaleCategories(List<Listing> listings) {
        int repaired = 0;
        for (Listing l : listings) {
            if (categories.isKnown(l.category())) {
                continue;
            }
            String fresh = categories.categorize(l.item());
            l.category(fresh);
            try {
                db.updateCategory(l.id(), fresh);
                repaired++;
            } catch (Exception e) {
                logError("repairing category for listing " + l.id(), e);
            }
        }
        if (repaired > 0) {
            plugin.getLogger().info("Re-categorised " + repaired
                    + " listing(s) whose category no longer exists.");
        }
    }

    /** A fresh copy of one listing, or empty if it no longer exists. */
    public void loadListing(UUID id, Consumer<Optional<Listing>> callback) {
        async(() -> {
            try {
                Optional<Listing> listing = db.getListing(id);
                sync(() -> callback.accept(listing));
            } catch (Exception e) {
                logError("loading listing " + id, e);
                sync(() -> callback.accept(Optional.empty()));
            }
        });
    }

    public void loadSellerListings(UUID sellerId, Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListingsBySeller(sellerId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading seller listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    /** Active auctions this player has bid on (winning or outbid) — backs the View Bids menu. */
    public void loadBidListings(UUID bidderId, Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListingsBidOnBy(bidderId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading bid listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    public void loadCollection(UUID ownerId, Consumer<List<CollectionItem>> callback) {
        async(() -> {
            try {
                List<CollectionItem> list = db.collectionItems(ownerId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading collection", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }


    public void sweepExpired() {
        if (workers.closing()) return;
        try {
            for (Listing l : db.dueExpirations(System.currentTimeMillis())) {
                try {
                    if (transactions.expire(l.id())) {
                        Listing settled = db.getListing(l.id()).orElse(l);
                        eventReady.accept(settled.status() == ListingStatus.SOLD && settled.topBidderId() != null
                                ? settled.topBidderId() : settled.sellerId());
                    }
                }
                catch (Exception e) { logError("settling auction " + l.id(), e); }
            }
            activeCache = db.countActive();
        } catch (Exception e) { logError("settling expired auctions", e); }
    }
    /** Complete safe database-only work; retry only payouts confirmed not to have moved money. */
    public void recover() {
        if (workers.closing() || !recovering.compareAndSet(false, true)) return;
        try {
            transactions.heartbeat(worker);
            if (System.currentTimeMillis() - lastRecoveryWarning > 60_000) {
                lastRecoveryWarning = System.currentTimeMillis();
                int held = transactions.heldCount();
                if (held > 0) plugin.getLogger().warning(held + " auction exchange(s) need review. Use /ah recovery; uncertain external effects are held, never automatically repeated.");
            }
            for (var o : transactions.recoverable(50, config.manualEarnings())) {
                try {
                    switch (o.state()) {
                        case APPLIED, FAILED -> {
                            transactions.finish(o.id());
                            if (o.kind() == AuctionTransactions.Kind.PAYOUT) eventReady.accept(o.player());
                        }
                        case PREPARED -> transactions.abandon(o.id());
                        case READY -> pay(o, transactions.paymentContext(o.listing()), outcome -> { });
                        default -> { }
                    }
                } catch (Exception e) { logError("recovering operation " + o.id(), e); }
            }
        } catch (Exception e) { logError("reading recovery journal", e); }
        finally { recovering.set(false); }
    }
    /** Deposit a READY payout through the journal, so it moves money at most once. */
    private void pay(AuctionTransactions.Operation o, AuctionTransactions.PaymentContext context,
                     Consumer<ExternalEffectRunner.Result> then) {
        effects.execute(o.id(), () -> vault.deposit(Bukkit.getOfflinePlayer(o.player()), o.amount()), outcome -> {
            if (outcome == ExternalEffectRunner.Result.COMPLETED) {
                eventReady.accept(o.player());
                boolean refund = "OUTBID".equals(o.note());
                UUID counterparty = refund ? null : context.buyer();
                String counterpartyName = counterparty == null ? null : Bukkit.getOfflinePlayer(counterparty).getName();
                econGuard.report(o.player(), o.playerName(), refund ? "bid-refund" : context.auction() ? "auction-sale" : "sale",
                        o.amount(), true, counterparty, counterpartyName, context.itemName());
            }
            then.accept(outcome);
        });
    }

    /** Total sale money waiting for {@code seller} to collect (manual-earnings-collection). */
    public void loadEarnings(UUID seller, Consumer<Double> callback) {
        async(() -> {
            try {
                double total = transactions.unclaimedEarnings(seller).stream()
                        .mapToDouble(AuctionTransactions.Operation::amount).sum();
                sync(() -> callback.accept(total));
            } catch (Exception e) {
                logError("loading earnings", e);
                sync(() -> callback.accept(0.0));
            }
        });
    }

    /** Pay out every sale waiting for this player; {@code onDone} runs once all have finished. */
    public void claimEarnings(Player player, Runnable onDone) {
        UUID seller = player.getUniqueId();
        async(() -> {
            try {
                var earnings = transactions.unclaimedEarnings(seller);
                if (earnings.isEmpty()) {
                    sync(() -> { tell(player, "collection.no-earnings"); if (player.isOnline()) onDone.run(); });
                    return;
                }
                var remaining = new java.util.concurrent.atomic.AtomicInteger(earnings.size());
                for (var o : earnings) {
                    var context = transactions.paymentContext(o.listing());
                    sync(() -> pay(o, context, outcome -> {
                        if (outcome == ExternalEffectRunner.Result.COMPLETED)
                            tell(player, "collection.earnings-claimed", "amount", vault.format(o.amount()), "item", context.itemName());
                        else result(player, o.id(), outcome);
                        if (remaining.decrementAndGet() == 0 && player.isOnline()) onDone.run();
                    }));
                }
            } catch (Exception e) { sync(() -> { failure(player, e); if (player.isOnline()) onDone.run(); }); }
        });
    }

    public void recoveryCommand(org.bukkit.command.CommandSender sender, String[] args) {
        // Resolve only from console, with an explicit decision and confirmation. The store also
        // refuses operations whose originating process is still heartbeating.
        if (args.length > 1 && args[1].equalsIgnoreCase("resolve")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) || args.length != 5
                    || !Set.of("applied", "not-applied").contains(args[3]) || !args[4].equals("confirm")) {
                sender.sendMessage("Console: /ah recovery resolve <operation-id> <applied|not-applied> confirm"); return;
            }
            UUID id;
            try { id = UUID.fromString(args[2]); } catch (IllegalArgumentException e) { sender.sendMessage("Invalid operation ID."); return; }
            async(() -> {
                try { transactions.resolve(id, args[3].equals("applied"), sender.getName()); sync(() -> sender.sendMessage("Reconciled " + id)); }
                catch (Exception e) { sync(() -> sender.sendMessage("Recovery refused: " + e.getMessage())); }
            });
            return;
        }
        int page;
        try { page = args.length == 1 ? 1 : Integer.parseInt(args[1]); if (page < 1 || page > 1_000_000) throw new NumberFormatException(); }
        catch (NumberFormatException e) { sender.sendMessage("Usage: /ah recovery [page] or /ah recovery resolve <id> <applied|not-applied> confirm"); return; }
        async(() -> {
            try {
                List<String> lines = new ArrayList<>();
                for (var o : transactions.pending(50, (page - 1) * 50)) lines.add(o.id() + " " + o.kind() + " " + o.state()
                        + " player=" + o.player() + " amount=" + o.amount() + " listing=" + o.listing()
                        + " collection=" + o.collection() + " origin=" + o.worker() + " updated=" + java.time.Instant.ofEpochMilli(o.updated()));
                sync(() -> { sender.sendMessage("Pending auction operations, page " + page + " (up to 50): " + lines.size()); lines.forEach(sender::sendMessage); });
            } catch (Exception e) { logError("listing recovery operations", e); }
        });
    }
    public void refreshActiveCount() { async(() -> { try { activeCache = db.countActive(); } catch (Exception e) { logError("counting auctions", e); } }); }
    public int activeCache() { return activeCache; }
    /** Longest name the listings and events tables hold (VARCHAR(256); MySQL rejects longer). */
    static final int MAX_NAME_LENGTH = 256;

    private String displayNameOf(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return truncate(Text.plain(item.getItemMeta().displayName()));
        String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    static String truncate(String name) {
        if (name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH) return name;
        return name.substring(0, name.offsetByCodePoints(0, MAX_NAME_LENGTH - 1)) + "…";
    }
}
