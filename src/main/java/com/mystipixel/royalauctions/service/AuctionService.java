package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.api.AuctionCurrency;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.currency.Amounts;
import com.mystipixel.royalauctions.currency.Currencies;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.search.EnchantmentBackfill;
import com.mystipixel.royalauctions.search.EnchantmentIndex;
import com.mystipixel.royalauctions.util.Players;
import com.mystipixel.royalauctions.util.Text;
import com.mystipixel.royalcommon.storage.Lease;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Auction changes use durable database reservations; inventory and currency effects start on main. */
public final class AuctionService {
    public static final String EXPIRY_JOB = "expiry";
    public static final String PRUNE_JOB = "prune";
    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final AuctionTransactions transactions;
    private final Currencies currencies;
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
    private volatile Lease lease;

    public void eventNotifier(Consumer<UUID> notifier) { this.eventReady = notifier; }
    /** Servers sharing a database take turns at the expiry sweep and pruning through this lease. */
    public void lease(Lease lease) { this.lease = lease; }
    /** Takes or renews the job's lease; true when this server should run the job now. Database thread. */
    public boolean holdsLease(String job, Duration ttl) throws SQLException {
        Lease current = lease;
        return current == null || current.tryAcquireOrRenew(job, ttl);
    }
    /** Lets another server take over the jobs at once instead of after the lease runs out. Call from onDisable. */
    public void releaseLeases() {
        Lease current = lease;
        if (current == null) return;
        for (String job : List.of(EXPIRY_JOB, PRUNE_JOB)) {
            try { current.release(job); }
            catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Could not release lease " + job + "; it frees itself when it expires", e); }
        }
    }
    /** Retries file receipts created before the database journal. Main thread. */
    public void retryPayments() { legacyPayments.retryRejected(); }

    public AuctionService(JavaPlugin plugin, AuctionDatabase db, VaultHook vault, Currencies currencies, PluginConfig config,
                          CategoryManager categories, com.mystipixel.royalauctions.tier.TierManager tiers,
                          MessageManager messages, com.mystipixel.royalauctions.hooks.EconGuardHook econGuard,
                          Workers workers) {
        this.plugin = plugin; this.db = db; this.transactions = db.transactions(); this.currencies = currencies;
        this.config = config; this.categories = categories; this.tiers = tiers;
        this.messages = messages; this.econGuard = econGuard; this.workers = workers;
        // New exchanges use the database journal; old file receipts retain their original IDs.
        legacyPayments = new PendingPayments(new PaymentJournal(plugin.getDataFolder().toPath().resolve("payments")),
                vault, econGuard, plugin.getLogger());
        effects = new ExternalEffectRunner(transactions, workers::async, workers::sync, worker,
                e -> logError("processing an external effect; check /ah recovery", e), workers::closing);
        plugin.getLogger().info("Auction recovery worker: " + worker);
    }
    // Refused once shutdown begins; work already under way still completes.
    private void async(Runnable r) { if (!workers.closing()) workers.async(r); }
    private void sync(Runnable r) { workers.sync(r); }
    private void logError(String what, Throwable e) { plugin.getLogger().log(Level.SEVERE, "Error " + what, e); }
    private void tell(Player player, String key, String... values) { if (player.isOnline()) messages.send(player, key, values); }
    private void failure(Player player, Exception e) { failure(player, e, null); }
    // currency: formats the amounts a refusal quotes (the minimum bid)
    private void failure(Player player, Exception e, String currency) {
        if (e instanceof AuctionTransactions.Rejected r) tell(player, "exchange.rejected", "reason", reason(r, currency));
        else { logError("reserving an auction operation", e); tell(player, "exchange.unconfirmed"); }
    }
    private String reason(AuctionTransactions.Rejected r) { return reason(r, null); }
    private String reason(AuctionTransactions.Rejected r, String currency) {
        String[] values = r.values.clone();
        for (int i = 0; currency != null && i + 1 < values.length; i += 2)
            if (values[i].equals("minimum")) values[i + 1] = currencies.format(currency, Double.parseDouble(values[i + 1]));
        return messages.text("exchange.reasons." + r.key, values);
    }
    private void result(Player player, UUID operation, ExternalEffectRunner.Result result) {
        if (result == ExternalEffectRunner.Result.PENDING)
            tell(player, "exchange.pending", "operation", operation.toString());
        else if (result == ExternalEffectRunner.Result.DECLINED)
            tell(player, "exchange.declined");
    }
    // Looked up again when the effect starts: a currency that left meanwhile declines instead of moving anything.
    private CompletableFuture<Boolean> debit(Player player, String currency, double amount, UUID operation) {
        AuctionCurrency provider = currencies.get(currency);
        if (provider == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
        if (amount == 0) return CompletableFuture.completedFuture(true);
        return workers.track(provider.withdraw(player.getUniqueId(), amount, operation));
    }
    private static boolean money(String currency) { return AuctionCurrency.MONEY.equals(currency); }
    /** Formats an amount in the currency. Main thread. */
    public String format(String currency, double amount) { return currencies.format(currency, amount); }
    /** Minimum raise over {@code currentBid}: the larger of the flat and the percentage increment, rounded up to the currency's scale. */
    public double bidIncrement(String currency, double currentBid) {
        return Amounts.share(currentBid, config.bidIncrementPercent(), config.bidMinIncrement(), currencies.scale(currency));
    }
    /** The lowest bid the listing accepts now, rounded up to the currency's scale (older listings may have finer amounts). */
    public double minimumBid(Listing listing) {
        double minimum = listing.nextMinBid(bidIncrement(listing.currency(), listing.currentBid()));
        OptionalInt scale = currencies.scale(listing.currency());
        return scale.isPresent() ? Amounts.roundUp(minimum, scale.getAsInt()) : minimum;
    }
    private boolean unavailable(Player player, String currency) {
        if (currencies.get(currency) != null) return false;
        tell(player, "exchange.currency-unavailable");
        return true;
    }
    // A typed bid must also be payable in the currency's smallest unit.
    private boolean unpayable(Player player, String currency, double amount) {
        if (unavailable(player, currency)) return true;
        int scale = currencies.scale(currency).orElseThrow();
        if (Amounts.fits(amount, scale)) return false;
        tooPrecise(player, scale);
        return true;
    }
    /** Tells the player the amount has more decimals than the currency allows. */
    public void tooPrecise(Player player, int scale) {
        if (scale == 0) tell(player, "general.whole-number");
        else tell(player, "general.too-many-decimals", "digits", String.valueOf(scale));
    }

    // EconGuard watches Vault money only.
    private boolean vetoed(Player player, Listing listing) {
        if (!money(listing.currency())) return false;
        if (!econGuard.allow(player.getUniqueId())) tell(player, "exchange.restricted-self");
        else if (!econGuard.allow(listing.sellerId())) tell(player, "exchange.restricted-other", "player", listing.sellerName());
        else return false;
        return true;
    }

    public void placeBid(Player bidder, Listing shown, double amount, Runnable onDone) {
        String currency = shown.currency();
        if (vetoed(bidder, shown) || unpayable(bidder, currency, amount)) { onDone.run(); return; }
        UUID player = bidder.getUniqueId(); String name = bidder.getName();
        async(() -> {
            try {
                var reserved = transactions.reserveBid(shown.id(), player, name, amount, current -> bidIncrement(currency, current), config.antiSnipeMillis(), worker);
                UUID id = reserved.operation().id();
                effects.executeAsync(id, () -> debit(bidder, currency, amount, id), outcome -> {
                    result(bidder, id, outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED) {
                        Listing l = reserved.listing();
                        messages.send(bidder, "bid.placed", "item", l.displayName(), "amount", format(currency, amount));
                        if (reserved.operation().expiry() > l.expiresAt())
                            messages.send(bidder, "bid.extended", "seconds", String.valueOf(Math.max(1, (reserved.operation().expiry() - System.currentTimeMillis()) / 1000)));
                        if (money(currency)) econGuard.report(player, name, "bid", amount, false, l.sellerId(), l.sellerName(), l.displayName());
                        Player seller = Bukkit.getPlayer(l.sellerId());
                        if (seller != null) messages.send(seller, "bid.new-bid-seller", "item", l.displayName(), "amount", format(currency, amount), "bidder", name);
                    }
                    if (Players.isCurrent(bidder)) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(bidder, e, currency); if (Players.isCurrent(bidder)) onDone.run(); }); }
        });
    }

    public void purchase(Player buyer, Listing shown, Runnable onDone) {
        String currency = shown.currency();
        if (vetoed(buyer, shown) || unavailable(buyer, currency)) { onDone.run(); return; }
        UUID player = buyer.getUniqueId(); String name = buyer.getName();
        async(() -> {
            try {
                var reserved = transactions.reserveBuy(shown.id(), player, name, shown.price(), worker);
                UUID id = reserved.operation().id();
                effects.executeAsync(id, () -> debit(buyer, currency, reserved.operation().amount(), id), outcome -> {
                    result(buyer, id, outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED) {
                        Listing l = reserved.listing();
                        if (money(currency)) econGuard.report(player, name, "buy", l.price(), false, l.sellerId(), l.sellerName(), l.displayName());
                        tell(buyer, "exchange.purchased");
                        // after a relog the purchase simply stays in the collection
                        if (config.instantDeliver() && Players.isCurrent(buyer))
                            claimById(buyer, settlementId(l.id()), () -> { if (Players.isCurrent(buyer)) onDone.run(); });
                        else if (Players.isCurrent(buyer)) onDone.run();
                    } else if (Players.isCurrent(buyer)) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(buyer, e); if (Players.isCurrent(buyer)) onDone.run(); }); }
        });
    }
    private static UUID settlementId(UUID listing) {
        return UUID.nameUUIDFromBytes(("settlement/" + listing).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void cancelListing(Player seller, Listing shown, Runnable onDone) {
        UUID player = seller.getUniqueId();
        async(() -> {
            try { transactions.cancel(shown.id(), player); sync(() -> { messages.send(seller, "cancel.success"); if (Players.isCurrent(seller)) onDone.run(); }); }
            catch (Exception e) { sync(() -> { failure(seller, e); if (Players.isCurrent(seller)) onDone.run(); }); }
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
                    if (!Players.isCurrent(player) || !expected.equals(player.getInventory().getItem(slot))) return false;
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
                              long duration, String currency, Consumer<Boolean> completed) {
        // Menus reach this too, not only /ah sell, so the permission is enforced here.
        if (!seller.hasPermission("royalauctions.sell")) {
            tell(seller, "general.no-permission"); completed.accept(false); return;
        }
        if (!currencies.offers(currency)) {
            tell(seller, "exchange.currency-unavailable"); completed.accept(false); return;
        }
        if (collection == null || !Double.isFinite(price) || price < config.minPrice()
                || (config.hasMaxPrice() && price > config.maxPrice()) || duration <= 0) {
            tell(seller, "exchange.invalid-listing"); completed.accept(false); return;
        }
        int scale = currencies.scale(currency).orElseThrow();
        if (!Amounts.fits(price, scale)) { tooPrecise(seller, scale); completed.accept(false); return; }
        String category = categories.categorize(item);
        if (!config.canSellCategory(category)) { messages.send(seller, "sell.category-not-allowed", "category", category); completed.accept(false); return; }
        long now = System.currentTimeMillis();
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), ItemSerialization.serialize(item),
                displayNameOf(item), category, tiers.tierOf(item), type, price, now, Math.addExact(now, duration), ListingStatus.DRAFT, 0, null, null, 0,
                EnchantmentIndex.of(item), currency);
        double fee = listingFee(currency, price);
        int limit = listingLimit(seller);
        async(() -> {
            try {
                var reserved = transactions.reserveCreate(listing, collection, fee, limit, worker);
                UUID id = reserved.operation().id();
                effects.executeAsync(id, () -> debit(seller, currency, fee, id), outcome -> {
                    result(seller, id, outcome);
                    if (outcome == ExternalEffectRunner.Result.COMPLETED)
                        messages.send(seller, "sell.success", "item", listing.displayName(), "price", format(currency, price), "fee", format(currency, fee));
                    // Even a pending operation retains the item in durable custody. The session is closed.
                    completed.accept(outcome != ExternalEffectRunner.Result.DECLINED);
                });
            } catch (Exception e) { sync(() -> { failure(seller, e); completed.accept(false); }); }
        });
    }

    /** The listing fee in the listing's currency, rounded up to its scale. */
    public double listingFee(String currency, double price) {
        return Amounts.share(price, config.feePercent(), config.feeMinimum(), currencies.scale(currency));
    }

    /**
     * Active-listing cap: unlimited for admins, else the highest {@code royalauctions.limit.<n>}
     * ({@code royalauctions.limit.unlimited} lifts it), else {@code max-per-player}. Negative = unlimited.
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
                    if (!Players.isCurrent(player)) return false;
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
                    if (Players.isCurrent(player)) onDone.run();
                });
            } catch (Exception e) { sync(() -> { failure(player, e); if (Players.isCurrent(player)) onDone.run(); }); }
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

    /**
     * One page of browse results, fetched off the main thread; stale categories on it are repaired.
     * {@code knownTotal}: the match count from an earlier fetch of the same filters, or -1 to count.
     */
    public void loadBrowsePage(ListingQuery query, int page, int perPage, int knownTotal, Consumer<ListingPage> callback) {
        async(() -> {
            try {
                ListingPage result = db.browse(query, page, perPage, knownTotal);
                sync(() -> {
                    repairStaleCategories(result.rows());
                    callback.accept(result);
                });
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(ListingPage.empty()));
            }
        });
    }

    /** Repairs categories renamed in config on every active listing. Run after eco has registered its items. */
    public void repairAllCategories() {
        async(() -> {
            try {
                List<Listing> list = db.activeListings();
                sync(() -> repairStaleCategories(list));
            } catch (Exception e) {
                logError("repairing listing categories", e);
            }
        });
    }

    /** Indexes enchantments of listings from before enchantment search. Once per start, not on reload. */
    public void backfillEnchantmentIndex() {
        async(() -> {
            try {
                new EnchantmentBackfill(db, data -> EnchantmentIndex.of(ItemSerialization.deserialize(data)),
                        workers::closing, plugin.getLogger()).run();
            } catch (SQLException e) {
                logError("indexing listing enchantments for search", e);
            }
        });
    }

    public void loadActiveListings(Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListings();
                sync(() -> {
                    repairStaleCategories(list);
                    callback.accept(list);
                });
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    // Categories are stamped at creation, so a renamed category strands listings; re-derive and persist.
    // Main thread: categorising reads the item through eco.
    private void repairStaleCategories(List<Listing> listings) {
        Map<UUID, String> repaired = new LinkedHashMap<>();
        for (Listing l : listings) {
            if (categories.isKnown(l.category())) {
                continue;
            }
            try {
                String fresh = categories.categorize(l.item());
                l.category(fresh);
                repaired.put(l.id(), fresh);
            } catch (Exception e) {
                logError("categorising listing " + l.id(), e);
            }
        }
        if (repaired.isEmpty()) {
            return;
        }
        async(() -> {
            try {
                db.updateCategories(repaired);
                plugin.getLogger().info("Re-categorised " + repaired.size()
                        + " listing(s) whose category no longer exists.");
            } catch (Exception e) {
                logError("repairing listing categories", e);
            }
        });
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

    /** Active auctions this player has bid on, winning or outbid. */
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

    public void loadHubCounts(UUID player, Consumer<AuctionDatabase.HubCounts> callback) {
        async(() -> {
            try {
                AuctionDatabase.HubCounts counts = db.hubCounts(player);
                sync(() -> callback.accept(counts));
            } catch (Exception e) {
                logError("loading hub counts", e);
                sync(() -> callback.accept(new AuctionDatabase.HubCounts(0, 0, 0)));
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
            // held across two sweeps, so another server only takes over when this one stops sweeping
            if (holdsLease(EXPIRY_JOB, Duration.ofMillis(config.expirySweepTicks() * 50L * 2)))
                settleExpired();
            activeCache = db.countActive();
        } catch (Exception e) { logError("settling expired auctions", e); }
    }
    private void settleExpired() throws SQLException {
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
                        case READY -> {
                            // Retried once the currency is back; meanwhile it moves aside for the rest of the queue.
                            if (currencies.get(o.currency()) == null) transactions.defer(o.id());
                            else pay(o, transactions.paymentContext(o.listing()), outcome -> { });
                        }
                        default -> { }
                    }
                } catch (Exception e) {
                    logError("recovering operation " + o.id(), e);
                    // a row that keeps failing must not hold back the rest of the queue
                    try { transactions.defer(o.id()); } catch (SQLException deferral) { logError("deferring operation " + o.id(), deferral); }
                }
            }
        } catch (Exception e) { logError("reading recovery journal", e); }
        finally { recovering.set(false); }
    }
    // Goes through the journal so a READY payout moves money at most once.
    private void pay(AuctionTransactions.Operation o, AuctionTransactions.PaymentContext context,
                     Consumer<ExternalEffectRunner.Result> then) {
        effects.executeAsync(o.id(), () -> {
            // Never another currency: a payout whose currency is gone is declined and stays owed.
            AuctionCurrency provider = currencies.get(o.currency());
            if (provider == null) return CompletableFuture.completedFuture(false);
            return workers.track(provider.deposit(o.player(), o.amount(), o.id()));
        }, outcome -> {
            if (outcome == ExternalEffectRunner.Result.COMPLETED) eventReady.accept(o.player());
            if (outcome == ExternalEffectRunner.Result.COMPLETED && money(o.currency())) {
                boolean refund = "OUTBID".equals(o.note());
                UUID counterparty = refund ? null : context.buyer();
                String counterpartyName = counterparty == null ? null : Bukkit.getOfflinePlayer(counterparty).getName();
                econGuard.report(o.player(), o.playerName(), refund ? "bid-refund" : context.auction() ? "auction-sale" : "sale",
                        o.amount(), true, counterparty, counterpartyName, context.itemName());
            }
            then.accept(outcome);
        });
    }

    /** Sale earnings waiting for {@code seller} to collect (manual-earnings-collection), totalled per currency. */
    public void loadEarnings(UUID seller, Consumer<Map<String, Double>> callback) {
        async(() -> {
            try {
                Map<String, Double> totals = earningsByCurrency(transactions.unclaimedEarnings(seller));
                sync(() -> callback.accept(totals));
            } catch (Exception e) {
                logError("loading earnings", e);
                sync(() -> callback.accept(Map.of()));
            }
        });
    }
    /** Amounts summed per currency, never across currencies, in order of first appearance. */
    public static Map<String, Double> earningsByCurrency(List<AuctionTransactions.Operation> payouts) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (var o : payouts) totals.merge(o.currency(), o.amount(), Double::sum);
        return totals;
    }
    /** Per-currency totals as one line, e.g. "$5.00, 120 tokens". Main thread. */
    public String formatTotals(Map<String, Double> totals) {
        return currencies.formatTotals(totals, messages.text("gui.amount-separator"));
    }

    /** Pay out every sale waiting for this player; {@code onDone} runs once all have finished. */
    public void claimEarnings(Player player, Runnable onDone) {
        UUID seller = player.getUniqueId();
        async(() -> {
            try {
                var earnings = transactions.unclaimedEarnings(seller);
                if (earnings.isEmpty()) {
                    sync(() -> { tell(player, "collection.no-earnings"); if (Players.isCurrent(player)) onDone.run(); });
                    return;
                }
                var remaining = new java.util.concurrent.atomic.AtomicInteger(earnings.size());
                Set<String> unavailable = new HashSet<>();
                for (var o : earnings) {
                    var context = transactions.paymentContext(o.listing());
                    if (currencies.get(o.currency()) == null) {
                        // stays owed; one notice per missing currency
                        boolean first = unavailable.add(o.currency());
                        sync(() -> {
                            if (first) tell(player, "collection.earnings-unavailable", "currency", currencies.name(o.currency()));
                            if (remaining.decrementAndGet() == 0 && Players.isCurrent(player)) onDone.run();
                        });
                        continue;
                    }
                    sync(() -> pay(o, context, outcome -> {
                        if (outcome == ExternalEffectRunner.Result.COMPLETED)
                            tell(player, "collection.earnings-claimed", "amount", format(o.currency(), o.amount()), "item", context.itemName());
                        else result(player, o.id(), outcome);
                        if (remaining.decrementAndGet() == 0 && Players.isCurrent(player)) onDone.run();
                    }));
                }
            } catch (Exception e) { sync(() -> { failure(player, e); if (Players.isCurrent(player)) onDone.run(); }); }
        });
    }

    public void recoveryCommand(org.bukkit.command.CommandSender sender, String[] args) {
        // Resolve only from console, with an explicit decision and confirmation. The store also
        // refuses operations whose originating process is still heartbeating.
        if (args.length > 1 && args[1].equalsIgnoreCase("resolve")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) || args.length != 5
                    || !Set.of("applied", "not-applied").contains(args[3]) || !args[4].equals("confirm")) {
                messages.send(sender, "recovery.resolve-usage"); return;
            }
            UUID id;
            try { id = UUID.fromString(args[2]); } catch (IllegalArgumentException e) { messages.send(sender, "recovery.invalid-id"); return; }
            async(() -> {
                try { transactions.resolve(id, args[3].equals("applied"), sender.getName()); sync(() -> messages.send(sender, "recovery.reconciled", "operation", id.toString())); }
                catch (Exception e) { sync(() -> messages.send(sender, "recovery.refused", "reason",
                        e instanceof AuctionTransactions.Rejected r ? reason(r) : String.valueOf(e.getMessage()))); }
            });
            return;
        }
        int page;
        try { page = args.length == 1 ? 1 : Integer.parseInt(args[1]); if (page < 1 || page > 1_000_000) throw new NumberFormatException(); }
        catch (NumberFormatException e) { messages.send(sender, "recovery.usage"); return; }
        async(() -> {
            try {
                List<String[]> lines = new ArrayList<>();
                for (var o : transactions.pending(50, (page - 1) * 50)) lines.add(new String[] {
                        "id", String.valueOf(o.id()), "kind", String.valueOf(o.kind()), "state", String.valueOf(o.state()),
                        "player", String.valueOf(o.player()), "amount", String.valueOf(o.amount()), "currency", o.currency(),
                        "listing", String.valueOf(o.listing()), "collection", String.valueOf(o.collection()),
                        "origin", String.valueOf(o.worker()), "updated", String.valueOf(java.time.Instant.ofEpochMilli(o.updated()))});
                sync(() -> {
                    messages.send(sender, "recovery.header", "page", String.valueOf(page), "count", String.valueOf(lines.size()));
                    lines.forEach(values -> messages.send(sender, "recovery.entry", values));
                });
            } catch (Exception e) { logError("listing recovery operations", e); }
        });
    }
    public void refreshActiveCount() { async(() -> { try { activeCache = db.countActive(); } catch (Exception e) { logError("counting auctions", e); } }); }
    public int activeCache() { return activeCache; }
    // VARCHAR(256) in the listings and events tables; MySQL rejects longer.
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
