# Enchantment search

Use the browser search button or `/ah search <query>` to search item names and actual applied/stored enchantments together. Examples: `sharpness`, `sharpness 5`, `sharpness V`, `minecraft:sharpness`, `silk touch`. Name/key matching is case-insensitive and partial; a trailing Arabic number or Roman numeral I–X requests that exact enchantment level. `sharpness 5` does not match level 4 or 50.

Enchantments on equipment and stored enchantments on books are read from item metadata. Lore is not used as enchantment identity. Custom registered enchantments use their namespaced keys too.

For translated names, place Minecraft language JSON files (for example `fr_fr.json` and `en_us.json`) in `plugins/RoyalAuctions/lang/`, then `/ah reload`. Entries such as `enchantment.minecraft.sharpness` are matched without case or accents, so `tranchant V` works. No language files are required for registry-key searches.

The derived `ra_listings.enchantments` column is added automatically. New listings capture it before asynchronous persistence, in the same insert transaction. Existing rows are backfilled by the plugin database worker on startup/reload in batches of 100. Searches during that initial backfill may not yet find all old enchantments; corrupt item data is logged and left untouched for retry. The serialized item remains authoritative. Filtering, counts, sorting and pagination stay in SQL; browsing never deserializes the full auction house.
