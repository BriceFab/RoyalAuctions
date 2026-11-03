package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.data.AuctionTransactions;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Separates durable intent, the main-thread effect, and its durable acknowledgement. */
public final class ExternalEffectRunner {
    public enum Result { COMPLETED, DECLINED, PENDING }
    @FunctionalInterface public interface Effect { boolean apply() throws Exception; }
    /** Started on the main thread; the future may complete on any thread. Exceptional: outcome unknown. */
    @FunctionalInterface public interface AsyncEffect { CompletableFuture<Boolean> apply() throws Exception; }
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
        executeAsync(operation, () -> CompletableFuture.completedFuture(effect.apply()), callback);
    }

    /** {@code callback} runs on the main thread. An effect that has already completed is acknowledged at once. */
    public void executeAsync(UUID operation, AsyncEffect effect, Consumer<Result> callback) {
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
                // Shutting down before anything moved: a durable "not applied" lets it finish cleanly.
                if (closing.getAsBoolean()) {
                    acknowledge(operation, false, callback);
                    return;
                }
                CompletableFuture<Boolean> started;
                try {
                    started = effect.apply();
                    if (started == null) throw new IllegalStateException("Effect for " + operation + " returned no future");
                } catch (Exception e) {
                    errors.accept(e);
                    callback.accept(Result.PENDING);
                    return; // Provider exceptions are ambiguous, including "threw after payment".
                }
                started.whenComplete((applied, failure) -> {
                    if (failure != null || applied == null) {
                        errors.accept(failure == null ? new IllegalStateException("Effect for " + operation + " completed without an outcome")
                                : unwrap(failure));
                        main.execute(() -> callback.accept(Result.PENDING));
                        return;
                    }
                    acknowledge(operation, applied, callback);
                });
            });
        });
    }

    private void acknowledge(UUID operation, boolean applied, Consumer<Result> callback) {
        database.execute(() -> {
            try {
                transactions.complete(operation, applied);
            } catch (Exception e) {
                errors.accept(e);
                main.execute(() -> callback.accept(Result.PENDING));
                return;
            }
            main.execute(() -> callback.accept(applied ? Result.COMPLETED : Result.DECLINED));
        });
    }

    private static Exception unwrap(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof Exception e ? e : new ExecutionException(cause);
    }
}
