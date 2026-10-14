package com.mystipixel.royalauctions;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.command.AuctionCommand;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.gui.AuctionGuiListener;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.gui.TextInput;
import com.mystipixel.royalauctions.gui.menu.MenuManager;
import com.mystipixel.royalauctions.hooks.AuctionPlaceholderExpansion;
import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.search.SearchTerms;
import com.mystipixel.royalauctions.service.AuctionService;
import com.mystipixel.royalauctions.service.OfflineEventNotifier;
import com.mystipixel.royalauctions.service.Workers;
import com.mystipixel.royalcommon.storage.ServerId;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.enchantments.Enchantment;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class RoyalAuctionsPlugin extends JavaPlugin {

    // bStats project id: names this plugin, not the server, so it is not configurable.
    private static final int BSTATS_PLUGIN_ID = 32735;
    // How long shutdown waits for in-flight exchanges before leaving them to /ah recovery.
    private static final long SHUTDOWN_WAIT_MILLIS = 10_000L;

    private PluginConfig config;
    private MessageManager messages;
    private VaultHook vault;
    private CategoryManager categories;
    private com.mystipixel.royalauctions.hooks.RarityRegistry rarities;
    private com.mystipixel.royalauctions.tier.TierManager tiers;
    private AuctionDatabase database;
    private AuctionService service;
    private MenuManager menus;
    private GuiManager guiManager;
    private TextInput textInput;
    private Workers workers;

    private BukkitTask expiryTask;
    private BukkitTask recoveryTask;
    private BukkitTask pruneTask;
    // longer than the daily period, so the server that pruned last keeps the job; another takes it over a day
    // after that server stops
    private static final Duration PRUNE_LEASE = Duration.ofHours(25);
    private BukkitTask eventPollTask;
    private String serverId;
    private AuctionPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new PluginConfig(this);
        validateConfig();
        this.messages = new MessageManager(this);
        this.vault = new VaultHook();

        EcoHook eco = new EcoHook();
        if (eco.isPresent()) {
            getLogger().info("eco detected - custom item categories enabled.");
        }
        // Menu templates resolve their item lookups through eco, so this must be set before they load.
        com.mystipixel.royalauctions.gui.menu.MenuTemplate.EcoHookHolder.set(eco);
        this.categories = new CategoryManager(eco, getLogger());
        this.categories.load(config.categoriesSection(), config.categoryOptionsSection());

        // Tiers are eco's rarities. EcoItems' rarities/ folder is the suite-wide registry (a rarity's
        // items: list can claim items from any eco plugin), so it is the single source of truth.
        this.rarities = new com.mystipixel.royalauctions.hooks.RarityRegistry(
                getDataFolder().getParentFile(), getLogger());
        this.tiers = new com.mystipixel.royalauctions.tier.TierManager(eco, rarities, getLogger());
        this.tiers.load(config.tiersSection());

        this.database = new AuctionDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            serverId = ServerId.resolve(config.storageSection().getString("server-id", ""), getDataFolder().toPath());
        } catch (IOException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Could not work out this server's id (storage.server-id), disabling RoyalAuctions.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage, disabling RoyalAuctions.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // The economy provider is a separate plugin and may register after we enable, so wait for it
        // rather than disabling over load order. The listener also follows later provider changes.
        getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalAuctions is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /ah is unavailable until then.");
            // In case the provider registered before the listener was active.
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    // Everything that needs a working economy. Idempotent: runs once, whenever the provider shows up.
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        com.mystipixel.royalauctions.hooks.EconGuardHook econGuard =
                new com.mystipixel.royalauctions.hooks.EconGuardHook(getLogger());
        if (econGuard.isPresent()) {
            getLogger().info("EconGuard detected - auction money movements will be reported to the central audit core"
                    + (econGuard.hasVeto()
                    ? "; flagged players can't bid or buy when EconGuard's enforcement.block-flagged-trades is on."
                    : "; this EconGuard predates the pre-trade veto, so nothing is blocked."));
        }
        this.workers = new Workers(this);
        try {
            this.service = new AuctionService(this, database, vault, config, categories, tiers, messages, econGuard, workers);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Cannot load payment records safely; disabling RoyalAuctions", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Bukkit cancels this main-thread task on disable. No async Vault calls.
        getServer().getScheduler().runTaskTimer(this, service::retryPayments, 600L, 600L);
        this.menus = new MenuManager(this);
        this.textInput = new TextInput(this, messages);
        getServer().getPluginManager().registerEvents(textInput, this);
        this.guiManager = new GuiManager(this, service, config, categories, tiers, messages, vault, menus, textInput);
        loadSearchTerms();

        getServer().getPluginManager().registerEvents(new AuctionGuiListener(guiManager), this);
        var notifier = new OfflineEventNotifier(this, database, messages, vault, config, workers);
        getServer().getPluginManager().registerEvents(notifier, this);
        service.eventNotifier(notifier::notifyOnline);
        service.lease(database.lease(serverId));
        if (database.type() == AuctionDatabase.Type.MYSQL) {
            // a sale or outbid on another server leaves its notice in the database for whoever is online here
            long period = 20L * Math.max(1, config.storageSection().getInt("refresh-seconds", 5));
            eventPollTask = getServer().getScheduler().runTaskTimer(this, () -> notifier.pollOnline(
                    getServer().getOnlinePlayers().stream().map(org.bukkit.entity.Player::getUniqueId).toList()), period, period);
            getLogger().info("Sharing the auction database as server " + serverId + ".");
        }

        AuctionCommand command = new AuctionCommand(this, guiManager, messages);
        if (getCommand("auctionhouse") != null) {
            getCommand("auctionhouse").setExecutor(command);
            getCommand("auctionhouse").setTabCompleter(command);
        }

        scheduleExpiryTask();
        schedulePruneTask();
        // Timers only enqueue: the work runs on the plugin's own database threads, which shutdown
        // waits for, instead of Bukkit async tasks that could still be running as storage closes.
        recoveryTask = getServer().getScheduler().runTaskTimer(this, () -> workers.async(service::recover), 1L, 100L);
        service.refreshActiveCount();

        // eco plugins register their items in a delayed task, so the audit and category repair wait;
        // run earlier, custom items would be filed by base material.
        getServer().getScheduler().runTaskLater(this, () -> {
            categories.auditCustomItems();
            service.repairAllCategories();
            service.backfillEnchantmentIndex();
        }, 100L);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new AuctionPlaceholderExpansion(
                    service, config, vault, getPluginMeta().getVersion());
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }

        setupMetrics();
        getLogger().info("RoyalAuctions enabled.");
    }

    private void tryLateEnable() {
        if (fullyEnabled) {
            return;
        }
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().severe("Still no Vault economy provider after waiting. Install an economy plugin"
                    + " (e.g. EssentialsX) and restart. RoyalAuctions is loaded but inactive.");
        }
    }

    // Completes a late startup, then follows Vault's choice of provider so a higher-priority one
    // registering later (e.g. an EcoBits currency) is not ignored.
    private final class EconomyWaiter implements Listener {
        @EventHandler
        public void onServiceRegister(ServiceRegisterEvent event) {
            if (event.getProvider().getService() != Economy.class) {
                return;
            }
            if (!fullyEnabled) {
                if (vault.setup()) {
                    getLogger().info("Vault economy provider detected. Finishing RoyalAuctions startup.");
                    finishEnable();
                }
                return;
            }
            refreshEconomy();
        }

        @EventHandler
        public void onServiceUnregister(ServiceUnregisterEvent event) {
            if (fullyEnabled && event.getProvider().getService() == Economy.class) {
                refreshEconomy();
            }
        }

        private void refreshEconomy() {
            String before = vault.providerName();
            if (vault.setup() && !Objects.equals(before, vault.providerName())) {
                getLogger().info("Vault economy provider changed from " + before + " to "
                        + vault.providerName() + "; auctions now use it.");
            }
        }
    }

    @Override
    public void onDisable() {
        // First, so nothing below can be answered by a prompt callback reopening a menu.
        if (textInput != null) textInput.shutdown();
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (pruneTask != null) {
            pruneTask.cancel();
        }
        if (recoveryTask != null) recoveryTask.cancel();
        if (eventPollTask != null) eventPollTask.cancel();
        // Finish (or durably decline) every in-flight exchange before storage closes.
        if (workers != null) workers.shutdown(SHUTDOWN_WAIT_MILLIS);
        if (service != null) service.releaseLeases();
        // Create-session items are already durable. Do not duplicate pending listings on shutdown.
        if (guiManager != null) guiManager.clearCreateSessions();
        // Last: drained callbacks can reopen menus, and with sessions cleared the close refunds nothing.
        if (guiManager != null) guiManager.closeMenus();
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (database != null) {
            database.close();
        }
    }

    private void scheduleExpiryTask() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        long interval = config.expirySweepTicks();
        this.expiryTask = getServer().getScheduler()
                .runTaskTimer(this, () -> workers.async(service::sweepExpired), interval, interval);
    }

    // Daily prune, first pass 5 minutes after start. 0 in config keeps everything.
    private void schedulePruneTask() {
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
        if (config.closedRetentionDays() <= 0) {
            return;
        }
        this.pruneTask = getServer().getScheduler().runTaskTimer(this, () -> workers.async(() -> {
            long cutoff = System.currentTimeMillis()
                    - config.closedRetentionDays() * 24L * 60L * 60L * 1000L;
            try {
                if (!service.holdsLease(AuctionService.PRUNE_JOB, PRUNE_LEASE)) {
                    return;
                }
                int removed = database.pruneClosed(cutoff);
                if (removed > 0) {
                    getLogger().info("Pruned " + removed + " closed listing(s) older than "
                            + config.closedRetentionDays() + " days (and their bid history).");
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Retention prune failed", e);
            }
        }), 20L * 300L, 20L * 60L * 60L * 24L);
    }

    /** Reload config, messages and categories. Storage-backend changes still need a restart. */
    public void reloadEverything() {
        config.reload();
        validateConfig();
        messages.reload();
        categories.load(config.categoriesSection(), config.categoryOptionsSection());
        // Re-read eco's rarities too, so adding a rarity file is picked up by /ah reload.
        this.rarities = new com.mystipixel.royalauctions.hooks.RarityRegistry(
                getDataFolder().getParentFile(), getLogger());
        this.tiers.rebind(rarities);
        this.tiers.load(config.tiersSection());
        if (!fullyEnabled) {
            return; // /ah is not registered until then, but keep this safe to call
        }
        menus.reload();
        loadSearchTerms();
        scheduleExpiryTask();
        schedulePruneTask();
        categories.auditCustomItems();
        // Categories may have been renamed: refile listings whose category id no longer exists.
        service.repairAllCategories();
    }

    // Registry read here on the main thread; the language files are read on a worker.
    private void loadSearchTerms() {
        List<String> enchantments = new ArrayList<>();
        for (Enchantment enchantment : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            enchantments.add(enchantment.getKey().toString());
        }
        File dir = new File(getDataFolder(), "lang");
        workers.async(() -> guiManager.searchTerms().load(SearchTerms.readLanguageFiles(dir, getLogger()), enchantments));
    }

    private void validateConfig() {
        new com.mystipixel.royalauctions.config.ConfigValidator(this, config).validate();
    }

    public PluginConfig pluginConfig() {
        return config;
    }
    // Opting out is done globally in plugins/bStats/config.yml.
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_backend",
                () -> getConfig().getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("confirm_purchase",
                () -> String.valueOf(getConfig().getBoolean("settings.confirm-purchase", true))));
        metrics.addCustomChart(new SimplePie("eco_items_hooked",
                () -> String.valueOf(getServer().getPluginManager().isPluginEnabled("EcoItems"))));
    }

}
