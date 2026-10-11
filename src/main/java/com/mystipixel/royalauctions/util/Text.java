package com.mystipixel.royalauctions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Turns legacy '&' colour strings from config into Adventure {@link Component}s. */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Text() {
    }

    /** Disables the default italic Adventure applies to item names. */
    public static Component color(String input) {
        return AMP.deserialize(input == null ? "" : input).decoration(TextDecoration.ITALIC, false);
    }

    /** Leaves italics as authored, for chat messages. */
    public static Component chat(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }

    /** Colour-stripped text of a component. */
    public static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }
}
