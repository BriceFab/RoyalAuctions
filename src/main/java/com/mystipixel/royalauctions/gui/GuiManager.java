package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.CollectionItem;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingStatus;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuManager;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.search.SearchTerms;
import com.mystipixel.royalauctions.service.AuctionService;
import com.mystipixel.royalauctions.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens every menu and runs the text input flows (search / price / bid). */
public final class GuiManager {

    private final JavaPlugin plugin;
    private final AuctionService service;
    private final PluginConfig config;
    private final CategoryManager categories;
    private final com.mystipixel.royalauctions.tier.TierManager tiers;
    private final MessageManager messages;
    private final VaultHook vault;
    private final MenuManager menus;
    private final TextInput textInput;
    private final SearchTerms searchTerms = new SearchTerms();

    private final Map<UUID, CreateSession> createSessions = new ConcurrentHashMap<>();
    // Per-player navigation counter: an async-loaded menu only opens if no newer request came in,
    // so a slow load cannot pop up over the create screen or a confirmation.
    private final Map<UUID, Integer> navigation = new ConcurrentHashMap<>();

    public GuiManager(JavaPlugin plugin, AuctionService service, PluginConfig config, CategoryManager categories,
                      com.mystipixel.royalauctions.tier.TierManager tiers, MessageManager messages,
                      VaultHook vault, MenuManager menus, TextInput textInput) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.categories = categories;
        this.tiers = tiers;
        this.messages = messages;
        this.vault = vault;
        this.menus = menus;
        this.textInput = textInput;
    }

    public void openHub(Player player) {
        int ticket = ticket(player);
        service.loadHubCounts(player.getUniqueId(), counts -> {
            if (!current(player, ticket)) return;
            HubGui gui = new HubGui(this, player, counts.bids(), counts.winning(), counts.selling(), service.activeCache());
            player.openInventory(gui.getInventory());
            playOpen(player, "hub");
        });
    }

    public void openBids(Player player) {
        openBids(player, 0);
    }

    public void openBids(Player player, int page) {
        int ticket = ticket(player);
        service.loadBidListings(player.getUniqueId(), listings -> {
            if (!current(player, ticket)) return;
            BidsGui gui = new BidsGui(this, player, listings);
            gui.populate(page);
            player.openInventory(gui.getInventory());
            playOpen(player, "bids");
        });
    }

    public void openSeller(Player player, UUID sellerId, String sellerName) {
        int ticket = ticket(player);
        service.loadSellerListings(sellerId, listings -> {
            if (!current(player, ticket)) return;
            SellerGui gui = new SellerGui(this, player, sellerName, listings);
            gui.populate(0);
            player.openInventory(gui.getInventory());
            playOpen(player, "seller");
        });
    }

    public void openBrowse(Player player) {
        openBrowse(player, null, null, config.defaultSort(), 0);
    }

    public void openBrowse(Player player, String category, String search, SortOrder sort, int page) {
        // Open first, then fill: the page arrives async and the menu checks it is still open first.
        ticket(player);
        BrowseGui gui = new BrowseGui(this, player, category, search, sort);
        player.openInventory(gui.getInventory());
        playOpen(player, "browse");
        gui.populate(page);
    }

    public void openConfirm(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        ticket(player);
        if (!config.confirmPurchase()) {
            service.purchase(player, listing, () -> openBrowse(player, category, search, sort, page));
            return;
        }
        ConfirmPurchaseGui gui = new ConfirmPurchaseGui(this, listing, category, search, sort, page);
        player.openInventory(gui.getInventory());
        playOpen(player, "confirm-purchase");
    }

    /** Gates a bid (which takes the money at once) behind a confirmation, unless confirmations.bid is off. */
    public void confirmBid(Player player, Listing listing, double amount,
                           String category, String search, SortOrder sort, int page) {
        ticket(player);
        if (!config.confirmBid()) {
            service.placeBid(player, listing, amount, () -> openBrowse(player, category, search, sort, page));
            return;
        }
        ConfirmBidGui gui = new ConfirmBidGui(this, player, listing, amount, category, search, sort, page);
        player.openInventory(gui.getInventory());
        playOpen(player, "confirm-bid");
    }

    public void confirmCancel(Player player, Listing listing, int page) {
        ticket(player);
        if (!config.confirmCancel()) {
            service.cancelListing(player, listing, () -> openListings(player, page));
            return;
        }
        ConfirmCancelGui gui = new ConfirmCancelGui(this, player, listing, page);
        player.openInventory(gui.getInventory());
        playOpen(player, "confirm-cancel");
    }

    public void openBid(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        ticket(player);
        BidGui gui = new BidGui(this, listing, category, search, sort, page);
        player.openInventory(gui.getInventory());
        playOpen(player, "bid");
    }

    public void openCollection(Player player) {
        openCollection(player, 0);
    }

    // Re-reads the listing so the bid screen offers the current minimum bid, not the one captured earlier.
    public void reopenBid(Player player, Listing shown, String category, String search, SortOrder sort, int page) {
        int ticket = ticket(player);
        service.loadListing(shown.id(), fresh -> {
            if (!current(player, ticket)) return;
            if (fresh.isPresent() && fresh.get().status() == ListingStatus.ACTIVE) {
                openBid(player, fresh.get(), category, search, sort, page);
            } else {
                openBrowse(player, category, search, sort, page);
            }
        });
    }

    public void openCollection(Player player, int page) {
        int ticket = ticket(player);
        service.loadCollection(player.getUniqueId(), items -> {
            if (!current(player, ticket)) return;
            if (!config.manualEarnings()) {
                showCollection(player, items, 0, page);
                return;
            }
            service.loadEarnings(player.getUniqueId(), earnings -> {
                if (current(player, ticket)) showCollection(player, items, earnings, page);
            });
        });
    }

    private void showCollection(Player player, List<CollectionItem> items, double earnings, int page) {
        CollectionGui gui = new CollectionGui(this, player, items, earnings);
        gui.populate(page);
        player.openInventory(gui.getInventory());
        playOpen(player, "collection");
    }

    public void openListings(Player player) {
        openListings(player, 0);
    }

    public void openListings(Player player, int page) {
        int ticket = ticket(player);
        service.loadSellerListings(player.getUniqueId(), listings -> {
            if (!current(player, ticket)) return;
            ListingsGui gui = new ListingsGui(this, player, listings);
            gui.populate(page);
            player.openInventory(gui.getInventory());
        });
    }

    public CreateSession session(UUID uuid) {
        return createSessions.get(uuid);
    }

    public void openCreate(Player player) {
        if (!player.hasPermission("royalauctions.sell")) {
            endCreateSession(player, true);
            messages.send(player, "general.no-permission");
            return;
        }
        ticket(player);
        createSessions.computeIfAbsent(player.getUniqueId(),
                k -> new CreateSession(config.defaultDurationHours(), config.defaultType()));
        CreateAuctionGui gui = new CreateAuctionGui(this, player);
        player.openInventory(gui.getInventory());
    }

    public void openDuration(Player player) {
        ticket(player);
        DurationGui gui = new DurationGui(this, player);
        player.openInventory(gui.getInventory());
        playOpen(player, "duration");
    }

    public void setDuration(Player player, int hours) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s != null) {
            s.durationHours(hours);
        }
        openCreate(player);
    }

    public void openConfirmAuction(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || !s.isReady()) {
            openCreate(player);
            return;
        }
        ticket(player);
        ConfirmAuctionGui gui = new ConfirmAuctionGui(this, player);
        player.openInventory(gui.getInventory());
        playOpen(player, "confirm-auction");
    }

    public void confirmCreate(Player player) {
        UUID uuid = player.getUniqueId();
        CreateSession s = createSessions.get(uuid);
        if (s == null || !s.isReady()) {
            openCreate(player);
            return;
        }
        ItemStack item = s.item();
        double price = s.price();
        ListingType type = s.type();
        long durationMillis = config.durationMillisFor(s.durationHours());
        s.busy(true);
        service.createListing(player, s.collectionId(), item, price, type, durationMillis, listed -> {
            s.busy(false);
            if (createSessions.get(uuid) != s) return;
            if (listed) {
                createSessions.remove(uuid);
                if (Players.isCurrent(player)) openListings(player);
            } else if (Players.isCurrent(player)) openCreate(player);
        });
    }

    public void cancelCreate(Player player) {
        endCreateSession(player, true);
        openBrowse(player);
    }

    // Create items are already journalled in collection; shutdown never copies them again.
    public void clearCreateSessions() { createSessions.clear(); }

    public void closeMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof AuctionGui) {
                player.closeInventory();
            }
        }
    }

    public void captureCreateItem(Player player, int slot, ItemStack expected) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || s.busy() || s.hasItem()) return;
        s.busy(true);
        service.capture(player, slot, expected, id -> {
            s.busy(false);
            if (createSessions.get(player.getUniqueId()) != s) return; // Stored in collection on close/quit.
            if (id != null) { s.collectionId(id); s.item(expected); }
            if (Players.isCurrent(player)) openCreate(player);
        });
    }
    public void removeCreateItem(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || s.busy() || s.collectionId() == null) return;
        UUID id = s.collectionId();
        s.collectionId(null); s.item(null);
        service.claimById(player, id, () -> { if (Players.isCurrent(player)) openCreate(player); });
    }
    public void endCreateSession(Player player, boolean refund) {
        CreateSession s = createSessions.remove(player.getUniqueId());
        if (s != null && refund && s.collectionId() != null && !s.busy() && player.isOnline()) {
            service.claimById(player, s.collectionId(), () -> {});
        }
    }

    public void beginSearch(Player player, String category, SortOrder sort) {
        ticket(player);
        textInput.request(player, List.of(t("sign.search-1"), t("sign.search-2")), input -> {
            if (input == null) {
                if (canReopen(player)) openBrowse(player, category, null, sort, 0);
            } else {
                openBrowse(player, category, input.isBlank() ? null : input, sort, 0);
            }
        });
    }

    public void beginPriceInput(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        // Stops the create screen's close from returning the item while the dialog is up. The prompt
        // always answers, null included, which clears this again.
        ticket(player);
        s.awaitingPrice(true);
        textInput.request(player, List.of(t("sign.price-1"), t("sign.price-2")), input -> {
            s.awaitingPrice(false);
            if (input == null) {
                // The item stays in the collection and in the session, so /ah sell picks it up again.
                if (canReopen(player)) openCreate(player);
                return;
            }
            if (!input.isBlank() && !input.equalsIgnoreCase("cancel")) {
                Double price = parsePositive(input);
                if (price == null) {
                    messages.send(player, "general.invalid-number");
                } else {
                    s.price(price);
                    messages.send(player, "create.price-set", "price", vault.format(price));
                }
            }
            openCreate(player);
        });
    }

    public void beginBidInput(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        ticket(player);
        textInput.request(player, List.of(t("sign.bid-1"), t("sign.bid-2")), input -> {
            if (input == null) {
                if (canReopen(player)) openBrowse(player, category, search, sort, page);
                return;
            }
            if (input.isBlank() || input.equalsIgnoreCase("cancel")) {
                openBrowse(player, category, search, sort, page);
                return;
            }
            Double amount = parsePositive(input);
            if (amount == null) {
                messages.send(player, "general.invalid-number");
                openBrowse(player, category, search, sort, page);
            } else {
                // A typed amount is easy to fat-finger, so it gets the same confirmation.
                confirmBid(player, listing, amount, category, search, sort, page);
            }
        });
    }

    // After a null answer: the player may have quit, died, opened another menu or started a newer
    // prompt, and none of those should be covered by our menu.
    private boolean canReopen(Player player) {
        if (!Players.isCurrent(player) || player.isDead() || textInput.isWaiting(player)) {
            return false;
        }
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    public void handleQuit(Player player) {
        // Persisted collection and pending operations survive the session. No offline inventory edits.
        createSessions.remove(player.getUniqueId());
        navigation.remove(player.getUniqueId());
    }

    private int ticket(Player player) {
        return navigation.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private boolean current(Player player, int ticket) {
        return Players.isCurrent(player) && navigation.getOrDefault(player.getUniqueId(), 0) == ticket;
    }

    // Accepts k/m/b/t shorthand ("5k", "50m", "1.5b") and commas.
    private Double parsePositive(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) {
            return null;
        }
        double multiplier = 1;
        char last = s.charAt(s.length() - 1);
        int idx = "kmbt".indexOf(last);
        if (idx >= 0) {
            multiplier = switch (last) {
                case 'k' -> 1_000d;
                case 'm' -> 1_000_000d;
                case 'b' -> 1_000_000_000d;
                default -> 1_000_000_000_000d; // 't'
            };
            s = s.substring(0, s.length() - 1);
        }
        try {
            double value = Double.parseDouble(s) * multiplier;
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public AuctionService service() {
        return service;
    }

    public PluginConfig config() {
        return config;
    }

    public CategoryManager categories() {
        return categories;
    }

    public com.mystipixel.royalauctions.tier.TierManager tiers() {
        return tiers;
    }

    public MessageManager messages() {
        return messages;
    }

    public VaultHook vault() {
        return vault;
    }

    /** Translated item names and enchantment names that searches also match. */
    public SearchTerms searchTerms() {
        return searchTerms;
    }

    /** Menu text from {@code messages.yml} ({@code gui.<key>}), placeholders filled in. */
    public String t(String key, String... kv) {
        return messages.text("gui." + key, kv);
    }

    /** Remaining time, e.g. "1d 3h", "45m 2s", in the gui.time formats. */
    public String timeLeft(long millis) {
        if (millis <= 0) {
            return t("time.expired");
        }
        long seconds = millis / 1000;
        String days = String.valueOf(seconds / 86400);
        String hours = String.valueOf((seconds % 86400) / 3600);
        String minutes = String.valueOf((seconds % 3600) / 60);
        String secs = String.valueOf(seconds % 60);
        if (seconds >= 86400) {
            return t("time.days", "days", days, "hours", hours);
        }
        if (seconds >= 3600) {
            return t("time.hours", "hours", hours, "minutes", minutes);
        }
        if (seconds >= 60) {
            return t("time.minutes", "minutes", minutes, "seconds", secs);
        }
        return t("time.seconds", "seconds", secs);
    }

    private void playOpen(Player player, String menuId) {
        MenuTemplate template = menus.get(menuId);
        if (template != null) {
            template.playSound(player, "open");
        }
    }

    public MenuManager menus() {
        return menus;
    }
}
