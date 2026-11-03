package com.mystipixel.royalauctions.currency;

import com.mystipixel.royalauctions.api.AuctionCurrency;
import com.mystipixel.royalauctions.hooks.VaultHook;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The built-in {@code money} currency. Vault answers synchronously on the main thread, so the futures
 * are already complete; a provider exception propagates to the caller as an unknown outcome.
 */
final class VaultCurrency implements AuctionCurrency {

    private final VaultHook vault;

    VaultCurrency(VaultHook vault) {
        this.vault = vault;
    }

    @Override
    public String id() {
        return MONEY;
    }

    @Override
    public String format(double amount) {
        return vault.format(amount);
    }

    @Override
    public int scale() {
        // Vault's fractionalDigits() is -1 when the economy does not round: amounts keep every digit.
        int digits = vault.fractionalDigits();
        return digits < 0 ? Amounts.UNROUNDED : digits;
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, double amount, UUID operation) {
        return CompletableFuture.completedFuture(vault.withdraw(Bukkit.getOfflinePlayer(player), amount));
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, double amount, UUID operation) {
        return CompletableFuture.completedFuture(vault.deposit(Bukkit.getOfflinePlayer(player), amount));
    }
}
