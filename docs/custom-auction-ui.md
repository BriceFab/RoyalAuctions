# Custom auction UI bridge

The optional plugin messaging channel is `royalauctions:auction_ui`, protocol v1.
Connections advertising this channel receive the custom browser; all other
connections retain the existing inventory GUI. No client mod is bundled or required.

`AuctionUiProtocol` is a dependency-free Java wire contract. Frames start with a
version and opcode. Integers, UUID halves and doubles are big-endian; strings
are length-prefixed UTF-8. Client opcodes are browse (1), purchase (2), close (3);
server opcodes are snapshot (16), result (17). The codec defines field bounds,
rejects trailing bytes/non-finite prices, and caps frames at 512 KiB. Browse
queries carry page/category/search/sort; purchase intents carry session,
requestId, listingId and expectedPrice. Clients should use the codec and its
fixtures rather than independently duplicating encoding rules.

The bridge selects eight listings per page. It checks the current connection,
session, request deduplication, rate limits, visible listing, permission, fresh
status/expiry, BIN type, owner and unchanged price before delegating to
`AuctionService.purchase`. It never implements an independent economy or item
transfer. Durable journal/claim/payment behavior remains owned by that service.
Closing, disconnecting, opening an inventory or reloading configuration invalidates
sessions; asynchronous callbacks recheck session and browse generation.

## Item presentation

Snapshots contain a registry key, amount and display name, plus listing metadata.
They do not contain serialized item components. A compatible client can render
its registered item icon, or fall back to the base material when that key is
unknown. This is not a faithful preview of per-stack custom models, item-model
components, lore, enchantments/glint, damage, potion/container contents or other
component-dependent appearance/tooltips. The original server ItemStack remains
stored and delivered by the normal purchase path. A richer versioned presentation
contract should only be added after a client demonstrates which components it needs.

## Validation

Run `./mvnw -B package` (or `mvn -B package`). Tests cover malformed/oversized
frames, shared wire fixtures, session/request replay, pending callback closure,
page changes, permission revocation, stale/expired/missing/own/non-BIN listings,
and delegation to the purchase service. SQLite durability/payment tests run
locally; MySQL integration cases require their configured database and otherwise skip.
