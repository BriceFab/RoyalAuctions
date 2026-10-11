package com.mystipixel.royalauctions.data;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

// Paper's byte serialization keeps all components and PDC data, which eco custom items rely on.
public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static byte[] serialize(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack deserialize(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    public static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] fromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
