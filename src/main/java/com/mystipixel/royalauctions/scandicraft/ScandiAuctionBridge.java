package com.mystipixel.royalauctions.scandicraft;

import com.mystipixel.royalauctions.RoyalAuctionsPlugin;
import com.mystipixel.royalauctions.category.Category;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingPage;
import com.mystipixel.royalauctions.data.ListingQuery;
import com.mystipixel.royalauctions.data.ListingStatus;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.util.Text;
import com.scandicraft.auction.AuctionUiProtocol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Thin Paper/Bukkit transport adapter for ScandiCraft's rich Fabric auction browser.
 *
 * <p>RoyalAuctions remains the source of truth. The bridge only turns the existing browse service into
 * presentation DTOs and turns a client purchase intent back into the existing durable purchase path.
 * It never trusts client-side item, money, ownership or listing state.
 */
public final class ScandiAuctionBridge implements PluginMessageListener, Listener {

    static final int PAGE_SIZE = 8;
    private static final int MAX_PLUGIN_MESSAGE_BYTES = 32_000;
    private static final int MAX_RECENT_REQUESTS = 128;
    private static final long REQUEST_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long OPEN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long BROWSE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long PURCHASE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final RoyalAuctionsPlugin plugin;
    private final GuiManager gui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private boolean started;

    public ScandiAuctionBridge(RoyalAuctionsPlugin plugin, GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    public void start() {
        if (started) {
            return;
        }
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, AuctionUiProtocol.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, AuctionUiProtocol.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        started = true;
        plugin.getLogger().info("ScandiCraft custom auction UI bridge registered on "
                + AuctionUiProtocol.CHANNEL + " (protocol v" + AuctionUiProtocol.VERSION + ").");
    }

    public void close() {
        if (!started) {
            return;
        }
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, AuctionUiProtocol.CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, AuctionUiProtocol.CHANNEL);
        HandlerList.unregisterAll(this);
        sessions.clear();
        lastOpen.clear();
        started = false;
    }

    /** True only when this exact player connection advertised the ScandiCraft auction payload channel. */
    public boolean supports(Player player) {
        return started
                && player != null
                && player.isOnline()
                && player.getListeningPluginChannels().contains(AuctionUiProtocol.CHANNEL);
    }

    /**
     * Open the rich browser when the ScandiCraft client supports it.
     *
     * @return true when the command has been handled by the custom UI; false means callers must use
     *         the existing inventory GUI unchanged.
     */
    public boolean open(Player player, String search) {
        if (!supports(player) || !player.hasPermission("royalauctions.use")) {
            return false;
        }

        long now = System.nanoTime();
        Long previous = lastOpen.put(player.getUniqueId(), now);
        if (previous != null && now - previous < OPEN_INTERVAL_NANOS && sessions.containsKey(player.getUniqueId())) {
            return true;
        }

        AuctionUiProtocol.Query query = new AuctionUiProtocol.Query(
                0,
                "",
                presentation(search, AuctionUiProtocol.MAX_SEARCH),
                protocolSort(gui.config().defaultSort()));
        Session session = new Session(UUID.randomUUID(), query);
        sessions.put(player.getUniqueId(), session);

        // /ah can be triggered from the inventory shortcut. Close the server inventory first so a
        // vanilla container cannot stay logically open underneath the client-rendered screen.
        player.closeInventory();
        loadAndSend(player, session, query);
        return true;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] data) {
        if (!AuctionUiProtocol.CHANNEL.equals(channel)
                || data.length < 2
                || data.length > MAX_PLUGIN_MESSAGE_BYTES
                || !player.hasPermission("royalauctions.use")) {
            return;
        }

        final AuctionUiProtocol.ClientMessage message;
        try {
            message = AuctionUiProtocol.decodeClient(data);
        } catch (IllegalArgumentException ignored) {
            // Untrusted client input: malformed packets are intentionally dropped without log spam.
            return;
        }

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.id.equals(message.session())) {
            return;
        }

        if (message instanceof AuctionUiProtocol.CloseRequest) {
            sessions.remove(player.getUniqueId(), session);
            return;
        }
        if (message instanceof AuctionUiProtocol.BrowseRequest browse) {
            handleBrowse(player, session, browse);
            return;
        }
        if (message instanceof AuctionUiProtocol.PurchaseRequest purchase) {
            handlePurchase(player, session, purchase);
        }
    }

    private void handleBrowse(Player player, Session session, AuctionUiProtocol.BrowseRequest request) {
        if (!session.remember(request.requestId())) {
            sendResult(player, session, request.requestId(), "rejected");
            return;
        }
        if (!session.allowBrowse()) {
            sendResult(player, session, request.requestId(), "rate_limited");
            return;
        }

        AuctionUiProtocol.Query query = request.query();
        if (!query.category().isEmpty() && gui.categories().byId(query.category()) == null) {
            sendResult(player, session, request.requestId(), "rejected");
            return;
        }
        session.query = query;
        loadAndSend(player, session, query);
    }

    private void handlePurchase(Player player, Session session, AuctionUiProtocol.PurchaseRequest request) {
        if (!session.remember(request.requestId())) {
            sendResult(player, session, request.requestId(), "rejected");
            return;
        }
        if (!session.allowPurchase()) {
            sendResult(player, session, request.requestId(), "rate_limited");
            return;
        }
        if (!session.visibleListings.contains(request.listingId())) {
            sendResult(player, session, request.requestId(), "rejected");
            return;
        }

        gui.service().loadListing(request.listingId(), optional -> {
            if (!current(player, session)) {
                return;
            }
            if (optional.isEmpty()) {
                sendResult(player, session, request.requestId(), "not_found");
                refresh(player, session);
                return;
            }

            Listing fresh = optional.get();
            if (fresh.status() != ListingStatus.ACTIVE || fresh.expiresAt() <= System.currentTimeMillis()) {
                sendResult(player, session, request.requestId(), "stale");
                refresh(player, session);
                return;
            }
            if (fresh.type() != ListingType.BIN || fresh.sellerId().equals(player.getUniqueId())) {
                sendResult(player, session, request.requestId(), "rejected");
                refresh(player, session);
                return;
            }
            if (Double.compare(fresh.price(), request.expectedPrice()) != 0) {
                sendResult(player, session, request.requestId(), "stale");
                refresh(player, session);
                return;
            }

            // All irreversible effects remain inside RoyalAuctions' existing journalled purchase path.
            // Its callback runs after completion/decline and already emits the authoritative chat result.
            gui.service().purchase(player, fresh, () -> {
                if (current(player, session)) {
                    refresh(player, session);
                }
            });
        });
    }

    private void refresh(Player player, Session session) {
        loadAndSend(player, session, session.query);
    }

    private void loadAndSend(Player player, Session session, AuctionUiProtocol.Query query) {
        // Once a new snapshot is in flight, cards from the previous view are no longer actionable.
        session.visibleListings = Set.of();
        long generation = ++session.generation;
        ListingQuery dbQuery = toListingQuery(query, gui);
        gui.service().loadBrowsePage(dbQuery, query.page(), PAGE_SIZE, page -> {
            if (!current(player, session) || session.generation != generation) {
                return;
            }

            int pages = page.pageCount(PAGE_SIZE);
            if (query.page() >= pages && query.page() > 0) {
                AuctionUiProtocol.Query corrected = new AuctionUiProtocol.Query(
                        pages - 1, query.category(), query.search(), query.sort());
                session.query = corrected;
                loadAndSend(player, session, corrected);
                return;
            }

            session.query = query;
            sendSnapshot(player, session, query, page);
        });
    }

    private void sendSnapshot(Player player, Session session, AuctionUiProtocol.Query query, ListingPage page) {
        try {
            double balance = gui.vault().balance(player);
            if (!Double.isFinite(balance)) {
                return;
            }

            List<AuctionUiProtocol.Category> categories = new ArrayList<>();
            for (Category category : gui.categories().categories()) {
                if (categories.size() >= AuctionUiProtocol.MAX_CATEGORIES) {
                    break;
                }
                try {
                    categories.add(category(category));
                } catch (IllegalArgumentException error) {
                    plugin.getLogger().fine("Skipping invalid ScandiCraft auction category '"
                            + category.id() + "': " + error.getMessage());
                }
            }

            List<AuctionUiProtocol.Listing> listings = new ArrayList<>();
            for (Listing row : page.rows()) {
                if (listings.size() >= PAGE_SIZE) {
                    break;
                }
                try {
                    listings.add(listing(row));
                } catch (IllegalArgumentException error) {
                    plugin.getLogger().fine("Skipping invalid ScandiCraft auction listing "
                            + row.id() + ": " + error.getMessage());
                }
            }

            String balanceText = presentation(Text.plain(Text.color(gui.vault().format(balance))), 64);
            if (balanceText.isEmpty()) {
                balanceText = String.format(java.util.Locale.ROOT, "%,.2f", balance);
            }

            session.visibleListings = listings.stream()
                    .map(AuctionUiProtocol.Listing::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

            send(player, new AuctionUiProtocol.Snapshot(
                    session.id,
                    true,
                    query,
                    PAGE_SIZE,
                    Math.max(0, page.total()),
                    balance,
                    balanceText,
                    categories,
                    listings));
        } catch (RuntimeException error) {
            plugin.getLogger().warning("Could not build ScandiCraft auction snapshot: " + error.getMessage());
        }
    }

    private AuctionUiProtocol.Category category(Category category) {
        String id = presentation(category.id(), 64);
        String display = presentation(Text.plain(Text.color(category.displayName())), 96);
        if (display.isEmpty()) {
            display = id;
        }
        return new AuctionUiProtocol.Category(id, display, category.icon().getKey().toString());
    }

    private AuctionUiProtocol.Listing listing(Listing listing) {
        ItemStack item = listing.item();
        if (item.getType().isAir()) {
            throw new IllegalArgumentException("listing item is air");
        }

        double displayPrice = listing.displayPrice();
        String priceText = presentation(Text.plain(Text.color(gui.vault().format(displayPrice))), 64);
        if (priceText.isEmpty()) {
            priceText = String.format(java.util.Locale.ROOT, "%,.2f", displayPrice);
        }

        String displayName = presentation(listing.displayName(), 256);
        if (displayName.isEmpty()) {
            displayName = item.getType().getKey().toString();
        }
        String seller = presentation(listing.sellerName(), 64);
        if (seller.isEmpty()) {
            seller = "Unknown";
        }
        String category = presentation(listing.category(), 64);
        if (category.isEmpty()) {
            category = "misc";
        }

        return new AuctionUiProtocol.Listing(
                listing.id(),
                item.getType().getKey().toString(),
                Math.max(1, Math.min(127, item.getAmount())),
                displayName,
                seller,
                category,
                presentation(listing.tier(), 64),
                listing.type() == ListingType.AUCTION
                        ? AuctionUiProtocol.ListingKind.AUCTION
                        : AuctionUiProtocol.ListingKind.BIN,
                listing.price(),
                displayPrice,
                priceText,
                Math.max(0, listing.expiresAt()),
                Math.max(0, listing.bidCount()));
    }

    private void sendResult(Player player, Session session, UUID requestId, String status) {
        send(player, new AuctionUiProtocol.Result(session.id, requestId, status, ""));
    }

    private void send(Player player, AuctionUiProtocol.ServerMessage message) {
        if (!supports(player)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = AuctionUiProtocol.encodeServer(message);
        } catch (IllegalArgumentException error) {
            return;
        }
        if (bytes.length > MAX_PLUGIN_MESSAGE_BYTES) {
            plugin.getLogger().warning("ScandiCraft auction payload exceeded plugin-message safety limit: "
                    + bytes.length + " bytes.");
            return;
        }
        player.sendPluginMessage(plugin, AuctionUiProtocol.CHANNEL, bytes);
    }

    private boolean current(Player player, Session session) {
        return player.isOnline()
                && supports(player)
                && sessions.get(player.getUniqueId()) == session;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        sessions.remove(player);
        lastOpen.remove(player);
    }

    /**
     * Plugin messages require an enabled source plugin. Paper fires PluginDisableEvent while the
     * plugin is still able to send, whereas JavaPlugin#onDisable runs after isEnabled became false.
     */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin || !started) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player != null && supports(player)) {
                send(player, new AuctionUiProtocol.Snapshot(
                        session.id,
                        false,
                        session.query,
                        1,
                        0,
                        0,
                        "0",
                        List.of(),
                        List.of()));
            }
        }
    }

    static ListingQuery toListingQuery(AuctionUiProtocol.Query query, GuiManager gui) {
        String category = query.category().isEmpty() ? null : query.category();
        String search = query.search().isEmpty() ? null : query.search();
        return new ListingQuery(
                category,
                null,
                null,
                search,
                serverSort(query.sort()),
                gui.itemNames().listingNamesMatching(search));
    }

    static SortOrder serverSort(AuctionUiProtocol.Sort sort) {
        return switch (sort) {
            case NEWEST -> SortOrder.NEWEST;
            case OLDEST -> SortOrder.OLDEST;
            case PRICE_LOW -> SortOrder.PRICE_LOW;
            case PRICE_HIGH -> SortOrder.PRICE_HIGH;
            case ENDING_SOON -> SortOrder.ENDING_SOON;
        };
    }

    static AuctionUiProtocol.Sort protocolSort(SortOrder sort) {
        return switch (sort) {
            case NEWEST -> AuctionUiProtocol.Sort.NEWEST;
            case OLDEST -> AuctionUiProtocol.Sort.OLDEST;
            case PRICE_LOW -> AuctionUiProtocol.Sort.PRICE_LOW;
            case PRICE_HIGH -> AuctionUiProtocol.Sort.PRICE_HIGH;
            case ENDING_SOON -> AuctionUiProtocol.Sort.ENDING_SOON;
        };
    }

    /** Strip server formatting/control codes before presentation data crosses the client boundary. */
    static String presentation(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxLength));
        boolean skipLegacyCode = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (skipLegacyCode) {
                skipLegacyCode = false;
                continue;
            }
            if (codePoint == '§') {
                skipLegacyCode = true;
                continue;
            }
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT
                    || Character.getType(codePoint) == Character.SURROGATE) {
                continue;
            }
            int width = Character.charCount(codePoint);
            if (out.length() + width > maxLength) {
                break;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString().strip();
    }

    static final class Session {
        final UUID id;
        AuctionUiProtocol.Query query;
        long generation;
        Set<UUID> visibleListings = Set.of();
        private long lastBrowseNanos;
        private long lastPurchaseNanos;
        private final LinkedHashMap<UUID, Long> recentRequests = new LinkedHashMap<>();

        Session(UUID id, AuctionUiProtocol.Query query) {
            this.id = id;
            this.query = query;
        }

        boolean allowBrowse() {
            long now = System.nanoTime();
            if (lastBrowseNanos != 0 && now - lastBrowseNanos < BROWSE_INTERVAL_NANOS) {
                return false;
            }
            lastBrowseNanos = now;
            return true;
        }

        boolean allowPurchase() {
            long now = System.nanoTime();
            if (lastPurchaseNanos != 0 && now - lastPurchaseNanos < PURCHASE_INTERVAL_NANOS) {
                return false;
            }
            lastPurchaseNanos = now;
            return true;
        }

        boolean remember(UUID requestId) {
            long now = System.nanoTime();
            Iterator<Map.Entry<UUID, Long>> it = recentRequests.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                if (now - entry.getValue() > REQUEST_TTL_NANOS) {
                    it.remove();
                }
            }
            if (recentRequests.containsKey(requestId)) {
                return false;
            }
            recentRequests.put(requestId, now);
            while (recentRequests.size() > MAX_RECENT_REQUESTS) {
                Iterator<UUID> keys = recentRequests.keySet().iterator();
                keys.next();
                keys.remove();
            }
            return true;
        }
    }
}
