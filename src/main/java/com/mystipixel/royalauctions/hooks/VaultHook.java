package com.mystipixel.royalauctions.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Vault economy wrapper. Call from the main thread. */
public final class VaultHook {

    // Swapped on the main thread when a higher-priority provider registers after startup.
    private volatile Economy economy;

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.economy = rsp.getProvider();
        return economy != null;
    }

    /** The current provider's name, or null before one is found. */
    public String providerName() {
        Economy current = economy;
        return current == null ? null : current.getName();
    }

    public boolean isReady() {
        return economy != null;
    }

    public double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.withdrawPlayer(player, amount);
        return r.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.depositPlayer(player, amount);
        return r.transactionSuccess();
    }

    public String format(double amount) {
        return economy == null ? String.format("%,.2f", amount) : economy.format(amount);
    }
}
