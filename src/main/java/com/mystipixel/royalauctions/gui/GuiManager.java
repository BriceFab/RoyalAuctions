package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.util.ItemNames;
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
import com.mystipixel.royalauctions.service.AuctionService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens every menu and runs the dialog text input flows (search / price / bid). */
public final class GuiManager {

    private final JavaPlugin plugin;
    private final AuctionService service;
    private final PluginConfig config;
    private final CategoryManager categories;
    private final com.mystipixel.royalauctions.tier.TierManager tiers;
    private final MessageManager messages;
    private final VaultHook vault;
    private final ItemNames itemNames = new ItemNames();
    private final MenuManager menus;
    private final TextInput textInput;

    private final Map<UUID, CreateSession> createSessions = new ConcurrentHashMap<>();
    /**
     * Per-player navigation counter. Every menu request takes a ticket, and a menu that loads its
     * data asynchronously only opens if no newer request came in meanwhile. Otherwise a slow load
     * (say, a double-clicked hub button) could pop up over the create screen or a confirmation.
     */
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

    // ------------------------------------------------------------------ hub / bids / seller view

    /** The {@code /ah} landing menu. Loads the counts the hub icons display, then opens. */
    public void openHub(Player player) {
        UUID uuid = player.getUniqueId();
        int ticket = ticket(player);
        service.loadBidListings(uuid, bids -> service.loadSellerListings(uuid, mine -> {
            if (!current(player, ticket)) return;
            int top = 0;
            for (Listing l : bids) {
                if (l.topBidderId() != null && l.topBidderId().equals(uuid)) {
                    top++;
                }
            }
            HubGui gui = new HubGui(this, player, bids.size(), top, mine.size(), service.activeCache());
            player.openInventory(gui.getInventory());
            playOpen(player, "hub");
        }));
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

    /** {@code /ah <username>} — that seller's active auctions. */
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

    // ------------------------------------------------------------------ browsing / buying / bidding

    public void openBrowse(Player player) {
        openBrowse(player, null, null, config.defaultSort(), 0);
    }

    public void openBrowse(Player player, String category, String search, SortOrder sort, int page) {
        // Open first, then fill: the page is fetched asynchronously, and the menu tracks whether it is
        // still the open inventory before drawing into it.
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

    /**
     * Gate a bid behind a confirmation. A bid takes the money immediately (it's held until you're
     * outbid or the auction ends), so a stray click on "Bid" is a real cost — hence the same
     * treatment Buy-It-Now already had. Falls straight through when confirmations.bid is off.
     */
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

    /** Gate a cancellation behind a confirmation. Falls through when confirmations.cancel is off. */
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

    /**
     * Back from a bid confirmation: re-read the listing, so the bid screen offers the minimum bid
     * as it is now rather than the one captured when the confirmation opened.
     */
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
            CollectionGui gui = new CollectionGui(this, player, items);
            gui.populate(page);
            player.openInventory(gui.getInventory());
            playOpen(player, "collection");
        });
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

    // ------------------------------------------------------------------ create-auction flow

    public CreateSession session(UUID uuid) {
        return createSessions.get(uuid);
    }

    public void openCreate(Player player) {
        // Every way into the create flow (hub, browse, /ah sell) comes through here.
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
                if (player.isOnline()) openListings(player);
            } else if (player.isOnline()) openCreate(player);
        });
    }

    public void cancelCreate(Player player) {
        endCreateSession(player, true);
        openBrowse(player);
    }

    /** All create items are already journalled in collection; shutdown never copies them again. */
    public void clearCreateSessions() { createSessions.clear(); }

    public void captureCreateItem(Player player, int slot, ItemStack expected) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || s.busy() || s.hasItem()) return;
        s.busy(true);
        service.capture(player, slot, expected, id -> {
            s.busy(false);
            if (createSessions.get(player.getUniqueId()) != s) return; // Stored in collection on close/quit.
            if (id != null) { s.collectionId(id); s.item(expected); }
            if (player.isOnline()) openCreate(player);
        });
    }
    public void removeCreateItem(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || s.busy() || s.collectionId() == null) return;
        UUID id = s.collectionId();
        s.collectionId(null); s.item(null);
        service.claimById(player, id, () -> { if (player.isOnline()) openCreate(player); });
    }
    public void endCreateSession(Player player, boolean refund) {
        CreateSession s = createSessions.remove(player.getUniqueId());
        if (s != null && refund && s.collectionId() != null && !s.busy() && player.isOnline()) {
            service.claimById(player, s.collectionId(), () -> {});
        }
    }

    // ------------------------------------------------------------------ text input: search / price / bid

    public void beginSearch(Player player, String category, SortOrder sort) {
        ticket(player);
        textInput.request(player, List.of("^^^^^^^^^^^^^^^", t("sign.search-1"), t("sign.search-2")), input -> {
            if (input == null || input.isBlank()) {
                openBrowse(player, category, null, sort, 0);
            } else {
                openBrowse(player, category, input, sort, 0);
            }
        });
    }

    public void beginPriceInput(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        // Suppress the create screen's return-item-on-close while the dialog is up. Done, Cancel and
        // Escape all answer, which clears this again; the item is already safe in the collection
        // if the dialog is never answered (quit, another plugin's screen).
        ticket(player);
        s.awaitingPrice(true);
        textInput.request(player, List.of("^^^^^^^^^^^^^^^", t("sign.price-1"), t("sign.price-2")), input -> {
            s.awaitingPrice(false);
            if (input != null && !input.isBlank() && !input.equalsIgnoreCase("cancel")) {
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
        textInput.request(player, List.of("^^^^^^^^^^^^^^^", "Enter your bid", "amount"), input -> {
            if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                openBrowse(player, category, search, sort, page);
                return;
            }
            Double amount = parsePositive(input);
            if (amount == null) {
                messages.send(player, "general.invalid-number");
                openBrowse(player, category, search, sort, page);
            } else {
                // A typed amount is the easiest place to fat-finger an extra zero, so it goes
                // through the same confirmation as the one-click bid.
                confirmBid(player, listing, amount, category, search, sort, page);
            }
        });
    }

    // ------------------------------------------------------------------ housekeeping

    public void handleQuit(Player player) {
        // Persisted collection and pending operations survive the session. No offline inventory edits.
        createSessions.remove(player.getUniqueId());
        navigation.remove(player.getUniqueId());
    }

    private int ticket(Player player) {
        return navigation.merge(player.getUniqueId(), 1, Integer::sum);
    }

    /** True if the player is online and has not asked for another menu since taking {@code ticket}. */
    private boolean current(Player player, int ticket) {
        return player.isOnline() && navigation.getOrDefault(player.getUniqueId(), 0) == ticket;
    }

    /** Parse a positive amount, accepting k/m/b/t shorthand (e.g. "5k", "50m", "1.5b") and commas. */
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

    // ------------------------------------------------------------------ accessors

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

    /** Translated vanilla item names, so search works in the language players see. */
    public ItemNames itemNames() {
        return itemNames;
    }

    /** Menu text from {@code messages.yml} ({@code gui.<key>}), placeholders filled in. */
    public String t(String key, String... kv) {
        return messages.text("gui." + key, kv);
    }

    /**
     * Play a menu's configured open sound.
     *
     * <p>MenuTemplate has always been able to do this, but nothing called it — so the sounds blocks in
     * the menu files were decorative and no menu in this plugin ever made a noise.
     */
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
