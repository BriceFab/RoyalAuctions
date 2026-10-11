package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One line of text from a player, typed into a native dialog (Paper's Dialog API) with Done and
 * Cancel buttons. Nothing is placed in the world.
 */
public final class TextInput implements Listener {

    static final int MAX_LENGTH = 64;
    static final long TIMEOUT_TICKS = 20L * 60 * 5;

    private record Pending(Player player, long token, Consumer<String> callback, Runnable cancelTimeout) {
    }

    // The server side of a prompt, replaced by a fake in tests.
    interface Host {
        boolean enabled();

        void nextTick(Runnable task);

        // Returns what cancels the task.
        Runnable later(Runnable task, long ticks);

        // done gets the raw typed text; cancel runs on Cancel or Escape.
        void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel);

        void close(Player player);
    }

    private final Host host;
    // Main thread only.
    private final Map<UUID, Pending> pending = new HashMap<>();
    private long nextToken;
    // Read from click callbacks, which may arrive off the main thread.
    private volatile boolean closed;

    public TextInput(JavaPlugin plugin, MessageManager messages) {
        this(new PaperHost(plugin, messages));
    }

    TextInput(Host host) {
        this.host = host;
    }

    /**
     * Asks for one line of text. The callback runs exactly once, on the main thread: the cleaned text
     * (possibly empty), or null if no answer is coming (cancel, quit, death, another menu, a newer
     * prompt, or a 5 minute timeout).
     */
    public void request(Player player, List<String> titleLines, Consumer<String> callback) {
        if (closed) {
            return;
        }
        UUID id = player.getUniqueId();
        long token = ++nextToken;
        Runnable cancelTimeout = host.later(() -> {
            if (isCurrent(id, token)) {
                host.close(player);
                finish(id, token, null);
            }
        }, TIMEOUT_TICKS);
        Pending previous = pending.put(id, new Pending(player, token, callback, cancelTimeout));
        if (previous != null) {
            // The new prompt is registered first, so the old caller can see it with isWaiting and
            // leave the screen alone.
            previous.cancelTimeout().run();
            previous.callback().accept(null);
        }
        // The dialog replaces the chest menu on screen.
        player.closeInventory();
        host.show(player, titleLines,
                raw -> answer(id, token, sanitize(raw)),
                () -> answer(id, token, null));
    }

    /** True while {@code player} has a prompt that has not been answered yet. */
    public boolean isWaiting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /** Close every open prompt without calling back. Call first thing in onDisable. */
    public void shutdown() {
        closed = true;
        for (Pending p : pending.values()) {
            p.cancelTimeout().run();
            if (p.player().isOnline()) {
                host.close(p.player());
            }
        }
        pending.clear();
    }

    // Button clicks may arrive after the prompt was replaced or after a reload; the token and the
    // enabled check make those do nothing.
    private void answer(UUID id, long token, String text) {
        if (closed || !host.enabled()) {
            return;
        }
        host.nextTick(() -> finish(id, token, text));
    }

    private boolean isCurrent(UUID id, long token) {
        Pending p = pending.get(id);
        return p != null && p.token() == token;
    }

    private void finish(UUID id, long token, String text) {
        if (!isCurrent(id, token)) {
            return;
        }
        Pending p = pending.remove(id);
        p.cancelTimeout().run();
        p.callback().accept(text);
    }

    // The client sends nothing when a dialog is dismissed some other way, so these events stand in.
    // Next tick, so callers see the player gone, dead, or in the other menu.
    private void dismiss(UUID id) {
        Pending p = pending.get(id);
        if (p != null && !closed) {
            host.nextTick(() -> finish(id, p.token(), null));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dismiss(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        dismiss(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        dismiss(event.getPlayer().getUniqueId());
    }

    // The dialog's max length is only a client hint; a modified client can send anything.
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        raw.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
        String text = out.toString().trim();
        return text.codePointCount(0, text.length()) <= MAX_LENGTH
                ? text : text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH)).trim();
    }

    private static final class PaperHost implements Host {

        private static final String FIELD = "input";

        private final JavaPlugin plugin;
        private final MessageManager messages;

        PaperHost(JavaPlugin plugin, MessageManager messages) {
            this.plugin = plugin;
            this.messages = messages;
        }

        @Override
        public boolean enabled() {
            return plugin.isEnabled();
        }

        @Override
        public void nextTick(Runnable task) {
            Bukkit.getScheduler().runTask(plugin, task);
        }

        @Override
        public Runnable later(Runnable task, long ticks) {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return scheduled::cancel;
        }

        @Override
        public void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel) {
            String title = lines.isEmpty() ? "" : lines.get(0);
            List<DialogBody> body = lines.size() < 2 ? List.of()
                    : List.of(DialogBody.plainMessage(Text.color(String.join("\n", lines.subList(1, lines.size())))));
            // Lives as long as the prompt's timeout; one use per button.
            ClickCallback.Options once = ClickCallback.Options.builder()
                    .uses(1)
                    .lifetime(Duration.ofMillis(TIMEOUT_TICKS * 50))
                    .build();
            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(Text.color(title))
                            .canCloseWithEscape(true)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .inputs(List.of(DialogInput.text(FIELD, Text.color(title))
                                    .labelVisible(false)
                                    .maxLength(MAX_LENGTH)
                                    .build()))
                            .build())
                    // A confirmation dialog's exit action is its second button, so Escape counts as Cancel.
                    .type(DialogType.confirmation(
                            ActionButton.builder(Text.color(messages.text("input.confirm")))
                                    .action(DialogAction.customClick(
                                            (response, audience) -> done.accept(response.getText(FIELD)), once))
                                    .build(),
                            ActionButton.builder(Text.color(messages.text("input.cancel")))
                                    .action(DialogAction.customClick((response, audience) -> cancel.run(), once))
                                    .build())));
            player.showDialog(dialog);
        }

        @Override
        public void close(Player player) {
            player.closeDialog();
        }
    }
}
