package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Auction Duration — layout from gui/duration.yml; options come from listings.durations in config. */
public final class DurationGui extends CreateFlowGui {

    private final MenuTemplate template;
    private final Map<Integer, Integer> slotToHours = new HashMap<>();

    public DurationGui(GuiManager manager, Player player) {
        super(manager, player);
        this.template = manager.menus().duration();
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
        render();
    }

    private void render() {
        inventory.clear();
        slotToHours.clear();
        template.applyStatic(inventory, Map.of());

        CreateSession s = manager.session(player.getUniqueId());
        int selected = s == null ? manager.config().defaultDurationHours() : s.durationHours();

        List<Integer> slots = template.slots("duration-slots");
        List<PluginConfig.DurationOption> options = manager.config().durations();
        for (int i = 0; i < options.size() && i < slots.size(); i++) {
            PluginConfig.DurationOption option = options.get(i);
            int slot = slots.get(i);
            boolean isSelected = option.hours() == selected;
            inventory.setItem(slot, GuiUtil.button(option.icon(),
                    (isSelected ? "&a" : "&e") + option.label(),
                    manager.t(isSelected ? "currently-selected" : "click-select")));
            slotToHours.put(slot, option.hours());
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        int slot = event.getSlot();
        if (slotToHours.containsKey(slot)) {
            manager.setDuration(player, slotToHours.get(slot));
            return;
        }
        if (MenuTemplate.actionOf(event.getCurrentItem()) == com.mystipixel.royalauctions.gui.menu.MenuAction.BACK) {
            manager.openCreate(player);
        }
    }
}
