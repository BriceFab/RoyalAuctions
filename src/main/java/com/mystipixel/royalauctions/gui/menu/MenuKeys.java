package com.mystipixel.royalauctions.gui.menu;

import org.bukkit.NamespacedKey;

/** Persistent-data keys stamped onto GUI items so clicks can be resolved. */
public final class MenuKeys {

    /** The {@link MenuAction} a button runs on left-click (and the default for any click). */
    public static final NamespacedKey ACTION = new NamespacedKey("royalauctions", "gui_action");

    /** The {@link MenuAction} a button runs on right-click. Absent means "same as left". */
    public static final NamespacedKey ACTION_RIGHT = new NamespacedKey("royalauctions", "gui_action_right");

    private MenuKeys() {
    }
}
