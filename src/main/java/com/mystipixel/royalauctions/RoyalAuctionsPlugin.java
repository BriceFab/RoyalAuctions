package com.mystipixel.royalauctions;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.command.AuctionCommand;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.gui.AuctionGuiListener;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.gui.SignInput;
import com.mystipixel.royalauctions.gui.menu.MenuManager;
import com.mystipixel.royalauctions.hooks.AuctionPlaceholderExpansion;
import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.service.AuctionService;
import com.mystipixel.royalauctions.service.Workers;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import java.util.Locale;
import java.util.Objects;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class RoyalAuctionsPlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 32735;
    /** How long shutdown waits for exchanges already under way before handing them to /ah recovery. */
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
    private SignInput signInput;
    private Workers workers;

    private BukkitTask expiryTask;
    private BukkitTask recoveryTask;
    private BukkitTask pruneTask;
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

        // Tiers are eco's rarities. EcoItems' rarities/ folder is the suite-wide registry — a rarity's
        // items: list can claim items from any eco plugin — so we read it as the single source of truth.
        this.rarities = new com.mystipixel.royalauctions.hooks.RarityRegistry(
                getDataFolder().getParentFile(), getLogger());
        this.tiers = new com.mystipixel.royalauctions.tier.TierManager(eco, rarities, getLogger());
        this.tiers.load(config.tiersSection());

        this.database = new AuctionDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage — disabling RoyalAuctions.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Vault is a hard dependency, but the economy *provider* (EssentialsX, CMI, an EcoBits currency
        // with vault:true, ...) is a separate plugin and can register after we enable. Disabling here
        // would mean the plugin silently kills itself on a perfectly good server purely because of
        // plugin load order — so wait for the provider instead. The listener also follows providers
        // that register after startup.
        getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalAuctions is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /ah is unavailable until then.");
            // Fallback, in case the provider registered before our listener was active.
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    /** Everything that needs a working economy. Idempotent — runs once, whenever the provider shows up. */
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        com.mystipixel.royalauctions.hooks.EconGuardHook econGuard =
                new com.mystipixel.royalauctions.hooks.EconGuardHook();
        if (econGuard.isPresent()) {
            getLogger().info("EconGuard detected - auction money movements will be reported to the central audit core.");
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
        this.signInput = new SignInput(this);
        getServer().getPluginManager().registerEvents(signInput, this);
        this.guiManager = new GuiManager(this, service, config, categories, tiers, messages, vault, menus, signInput);
        guiManager.enchantmentSearch().reload(new java.io.File(getDataFolder(), "lang"), getLogger());

        getServer().getPluginManager().registerEvents(new AuctionGuiListener(guiManager), this);
        var notifier = new com.mystipixel.royalauctions.service.OfflineEventNotifier(this, database, messages, vault, workers);
        getServer().getPluginManager().registerEvents(notifier, this);
        service.eventNotifier(notifier::notifyOnline);

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

        // The eco plugins register their items in a delayed task (their "Loaded X" lines land after
        // the server reports Done), so the audit, and the category repair sweep (the browse menu only
        // repairs what it draws), wait for that. Earlier, custom items would be filed by base material.
        getServer().getScheduler().runTaskLater(this, () -> {
            categories.auditCustomItems();
            service.repairAllCategories();
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

    /**
     * Completes startup if the economy provider registers after we enabled, and afterwards follows
     * Vault's choice of provider: a higher-priority one (e.g. an EcoBits currency) registering after
     * EssentialsX would otherwise be ignored and auctions would keep paying into the old one.
     */
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
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (pruneTask != null) {
            pruneTask.cancel();
        }
        if (recoveryTask != null) recoveryTask.cancel();
        // Finish (or durably decline) every exchange already under way before storage closes.
        if (workers != null) workers.shutdown(SHUTDOWN_WAIT_MILLIS);
        // Put back any blocks borrowed for sign prompts that are still open.
        if (signInput != null) signInput.restoreAll();
        // Create-session items are already durable. Do not duplicate pending listings on shutdown.
        if (guiManager != null) guiManager.clearCreateSessions();
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

    /** Daily audit-tail prune (first pass 5 minutes after start). Config 0 keeps everything forever. */
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
            return; // /ah is not registered until then, but keep this safe to call regardless
        }
        menus.reload();
        guiManager.enchantmentSearch().reload(new java.io.File(getDataFolder(), "lang"), getLogger());
        scheduleExpiryTask();
        schedulePruneTask();
        categories.auditCustomItems();
        // Categories may have been renamed: refile listings whose category id no longer exists.
        service.repairAllCategories();
    }

    private void validateConfig() {
        new com.mystipixel.royalauctions.config.ConfigValidator(this, config).validate();
    }

    public PluginConfig pluginConfig() {
        return config;
    }
    /**
     * Anonymous usage reporting via bStats.
     *
     * <p>Server owners who want no reporting disable it globally in plugins/bStats/config.yml, which
     * is the mechanism bStats provides; the id itself is fixed because it names this plugin's project.
     */
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
