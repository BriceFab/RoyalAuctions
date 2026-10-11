package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.data.AuctionTransactions;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Separates durable intent, the main-thread effect, and its durable acknowledgement. */
public final class ExternalEffectRunner {
    public enum Result { COMPLETED, DECLINED, PENDING }
    @FunctionalInterface public interface Effect { boolean apply() throws Exception; }
    private final AuctionTransactions transactions;
    private final Executor database;
    private final Executor main;
    private final String worker;
    private final Consumer<Exception> errors;
    private final BooleanSupplier closing;

    public ExternalEffectRunner(AuctionTransactions transactions, Executor database, Executor main,
                                String worker, Consumer<Exception> errors) {
        this(transactions, database, main, worker, errors, () -> false);
    }

    /** {@code closing}: once true, effects that have not started are declined instead of applied. */
    public ExternalEffectRunner(AuctionTransactions transactions, Executor database, Executor main,
                                String worker, Consumer<Exception> errors, BooleanSupplier closing) {
        this.transactions = transactions; this.database = database; this.main = main;
        this.worker = worker; this.errors = errors; this.closing = closing;
    }

    public void execute(UUID operation, Effect effect, Consumer<Result> callback) {
        database.execute(() -> {
            try {
                if (!transactions.begin(operation, worker)) {
                    main.execute(() -> callback.accept(Result.PENDING));
                    return;
                }
            } catch (Exception e) {
                errors.accept(e);
                main.execute(() -> callback.accept(Result.PENDING));
                return; // No confirmed durable intent: never attempt the external effect.
            }
            main.execute(() -> {
                final boolean applied;
                // Shutting down before anything moved: a durable "not applied" lets it finish cleanly.
                try { applied = !closing.getAsBoolean() && effect.apply(); }
                catch (Exception e) {
                    errors.accept(e);
                    callback.accept(Result.PENDING);
                    return; // Provider exceptions are ambiguous, including "threw after payment".
                }
                database.execute(() -> {
                    try {
                        transactions.acknowledge(operation, applied);
                        transactions.finish(operation);
                    } catch (Exception e) {
                        errors.accept(e);
                        main.execute(() -> callback.accept(Result.PENDING));
                        return;
                    }
                    main.execute(() -> callback.accept(applied ? Result.COMPLETED : Result.DECLINED));
                });
            });
        });
    }
}
