# RoyalAuctions

A Hypixel-style Auction House for Paper, built to work properly with the **eco suite**
(EcoItems, EcoArmor, Talismans, Reforges, EcoPets, EcoScrolls, StatTrackers) — and to work
just as well on a server that has none of them.

Part of a suite with [RoyalBank](https://github.com/joogiebear/RoyalBank),
[RoyalBazaar](https://github.com/joogiebear/RoyalBazaar) and
[EconGuard](https://github.com/joogiebear/EconGuard).

---

## Contents

- [Requirements](#requirements)
- [Install](#install)
- [Commands](#commands)
- [Permissions](#permissions)
- [Menus](#menus)
- [Categories](#categories)
- [Item tiers](#item-tiers)
- [Confirmations](#confirmations)
- [Storage](#storage)
- [Reliability and recovery](#reliability-and-recovery)
- [PlaceholderAPI](#placeholderapi)
- [NPCs (Citizens)](#npcs-citizens)
- [Building](#building)
- [Gotchas](#gotchas)

---

## Requirements

| | |
|---|---|
| **Server** | Paper 26.2 or newer (Java 25) |
| **Required** | Vault **and an economy provider** (EssentialsX, CMI, an EcoBits currency with `vault: true`, …) |
| **Optional** | eco suite, PlaceholderAPI, EconGuard, Citizens |

The eco suite is entirely optional. Without it you lose custom-item categorisation and the tier
filter; everything else works, and the plugin never touches an eco class.

## Install

1. Drop `RoyalAuctions.jar` into `plugins/`.
2. Start the server. Configs generate on first run.
3. `/ah` — done.

JDBC drivers and HikariCP are downloaded at runtime by Paper's library loader; nothing is shaded.

---

## Commands

Base command `/auctionhouse`, aliases **`/ah`**, `/auctions`, `/auction`.

| Command | What it does |
|---|---|
| `/ah` | Open the **hub** — Auction Browser, View Bids, Manage Auctions |
| `/ah browse` | Straight to the browser |
| `/ah bids` | Auctions you have bid on ("top bidder on X of Y") |
| `/ah sell` *(or `create`)* | Create-auction flow |
| `/ah search <query>` | Search listings by name |
| `/ah listings` | Manage your own listings |
| `/ah collect` | Claim purchases and returned items |
| `/ah <username>` | View that player's active auctions |
| `/ah category` | **Admin.** Inspect the held item: category, *which rule matched*, and tier |
| `/ah reload` | **Admin.** Reload config, categories, menus, rarities |
| `/ah recovery [page]` | **Admin.** Inspect pending exchanges and held operations |

## Permissions

| Node | Default | |
|---|---|---|
| `royalauctions.use` | `true` | Open and browse |
| `royalauctions.sell` | `true` | List items (checked on every way into the create menu, not only `/ah sell`) |
| `royalauctions.limit.<n>` | — | Allow up to `<n>` active listings, overriding `max-per-player`; the highest granted wins |
| `royalauctions.limit.unlimited` | — | No active-listing cap |
| `royalauctions.admin` | `op` | `/ah reload`, `/ah category`, `/ah recovery`; no listing cap |

> NPCs run commands **as the clicking player**, so they inherit that player's permissions. If you
> restrict `royalauctions.use`, a player without it clicking the NPC gets nothing — that's expected.

---

## Menus

Every screen is a file in `gui/`, authored in the **EcoMenus dialect** — the same shape you already
know from EcoMenus/EcoShop.

```yaml
title: "&8Auction Browser"
rows: 6

mask:
  items:
    - red_stained_glass_pane      # 1
    - black_stained_glass_pane    # 2
  pattern:
    - "A10000000"                 # digits = mask.items filler
    - "A20000000"                 # 0 / letters = named dynamic regions
    - "A67777777"

regions:
  category-slots: "A"             # column 1: the category buttons
  content-slots: "0"              # the listing grid

slots:
  - id: sort
    item: 'hopper name:"&eSort: &f%sort%"'
    lore:
      - "&7Click to change sorting."
    location:                     # 1-based row/column, not raw slot indexes
      row: 6
      column: 6
    left-click:
      - id: ah_sort
```

Files: `hub`, `browse`, `bids`, `seller`, `manage`, `collection`, `create`, `duration`,
`bid`, `confirm-purchase`, `confirm-auction`, `confirm-bid`, `confirm-cancel`.

**Two extensions over stock EcoMenus:**

- **Named mask regions** — one menu can have several dynamic areas (`content-slots` *and*
  `category-slots`), not just one.
- **Per-click actions** — `left-click:` and `right-click:` can do *different* things. A button with
  no `right-click:` behaves the same on both, so nothing changes for buttons that don't care.

**List placeholders.** A lore line that is *exactly* `%tier_list%` (or `%type_list%`) expands into
**one line per option**, with the current one marked — that's how the filter buttons show their
whole list rather than just the current value:

```yaml
  - id: tier
    item: 'nether_star hide_attributes name:"&eItem Tier"'
    lore:
      - "&7Filter listings by item tier."
      - ""
      - "%tier_list%"             # -> "> EPIC" for current, dimmed for the rest
      - ""
      - "&eLeft-click &7to go up"
      - "&eRight-click &7to go down"
    left-click:
      - id: ah_tier_prev
    right-click:
      - id: ah_tier_next          # swap these two to reverse the direction
```

**Effect ids:** `ah_hub` `ah_browse` `ah_bids` `ah_manage` `ah_collection` `ah_create` `ah_search`
`ah_sort` `ah_tier_prev` `ah_tier_next` `ah_type_prev` `ah_type_next` `ah_clear_filters`
`ah_prev_page` `ah_next_page` `ah_set_price` `ah_set_duration` `ah_toggle_type` `ah_remove_item`
`ah_continue` `ah_confirm` `ah_confirm_purchase` `ah_confirm_bid` `ah_confirm_cancel` `ah_bid_min`
`ah_bid_custom` `ah_back` `ah_cancel` `close_inventory`

> ⚠️ **YAML gotcha:** an inline item spec containing a **colon followed by a space** must be quoted,
> or SnakeYAML reads it as a mapping and the file fails to load:
> ```yaml
> item: 'hopper name:"&eSort: &f%sort%"'    # quoted — correct
> item: hopper name:"&eSort: &f%sort%"      # BREAKS the file
> ```

---

### Searching in other languages

The server only knows English item names, so a search for what a French player sees (`casque`)
finds nothing. To fix that, copy Minecraft language files into `plugins/RoyalAuctions/lang/` and run
`/ah reload`. For `fr_fr.json`: in a client's `.minecraft/assets/indexes/<version>.json`, look up
`minecraft/lang/fr_fr.json` and copy the file under `assets/objects/` that its hash names. A search
then also finds vanilla items whose name in any loaded language contains the text, ignoring case and
accents. Items with a custom name are matched on that name, as before.

## Categories

One file per category in `categories/`. **The file name is the category id**, so
`categories/weapons.yml` defines `weapons`. Add a category by dropping in a file; remove it by
deleting the file. Column 1 of the browser is ordered by each file's `priority` (lowest first).

```yaml
# categories/weapons.yml
display-name: "&cWeapons"
icon: DIAMOND_SWORD
priority: 10

match:
  items:                                  # exact pins - see below
    - "ecoitems:brazen_sword"
    - "minecraft:trident"
  eco-namespaces: ["ecoarmor"]            # a whole eco plugin -> one category
  material-suffixes: ["_SWORD", "_AXE"]
  materials: ["BOW", "CROSSBOW"]
  material-prefixes: []
  tags: ["WEAPON"]                        # EDIBLE BLOCK ARMOR TOOL WEAPON
  any: false                              # the catch-all
```

**Exactly one category must be the catch-all** (`any: true`, highest `priority` number) or items
matching nothing would have nowhere to go. That's `tools_misc.yml` by default.

### How an item's category is decided

Most rules **infer** a category from the item's **base material** — which is only ever a *guess* for
a custom item. A custom dagger built on a `STICK` would land in Misc. So:

1. **`match.items:`** — an exact pin. Accepts eco ids *and* vanilla (`minecraft:trident`).
   **Beats every other rule, in any category, at any priority.** This is the escape hatch.
2. `match.eco-namespaces:` — a whole eco plugin maps to one category.
3. `materials` / suffixes / prefixes / `tags` — inference from the base material.
4. `any: true` — the catch-all.

### Two tools so none of this is a black box

- **`/ah category`** (holding an item) prints the item key, where it lands, **which rule decided
  it**, and its tier.
- **A startup audit** logs every eco item that only hit the catch-all:

  ```
  [RoyalAuctions] Category audit: 24 of 141 eco item(s) fell through to 'tools_misc'.
                  Pin them under a category's match.items: if they belong elsewhere:
                    - ecoitems:enchanted_diamond
                    - stattrackers:arrows_shot
                    ...
  ```

  This tells you **exactly** which items are worth pinning, instead of leaving you to enumerate
  every material in the game. When everything resolves it prints
  `Category audit: all 124 eco items resolved to a category.`

### Zero-inference mode

```yaml
category-options:
  strict-items: true    # ONLY items pinned via match.items: get a category.
                        # Everything else goes to the catch-all. No guessing at all.
```

### Renaming a category

Category is stamped on a listing when it's created, so renaming one would strand every existing
listing under an id no button matches. RoyalAuctions **re-derives and persists** those on load
instead:

```
[RoyalAuctions] Re-categorised 3 listing(s) whose category no longer exists.
```

Migrating from an older version with an inline `categories:` block in `config.yml`? It's split into
`categories/*.yml` automatically on first start, and the plugin tells you the old block can be
deleted.

---

## Item tiers

**Tiers are eco's rarities.** They're read straight from `plugins/EcoItems/rarities/`.

The important part — and it isn't obvious — is that **EcoItems' `rarities/` folder is the rarity
registry for the *whole* eco suite, not just EcoItems.** Each rarity file has an `items:` list that
can claim items from **any** eco namespace:

```yaml
# plugins/EcoItems/rarities/rare.yml
lore:
  - "&9&lRARE"
weight: 3
items:
  - ecoitems:enchanted_diamond
  - talismans:starter_talisman
  - reforges:stone_sharp
  - ecoarmor:set_miner_helmet
  - ecopets:frog_spawn_egg
  - ecoscrolls:scroll_hot_potato_book
```

So there is **no per-plugin mapping config**, and RoyalAuctions never compiles against a paid
plugin. Whatever rarity an item shows in-game is exactly what the tier filter sorts by — one source
of truth, no drift. Add a rarity file, and it becomes filterable.

An item's rarity comes from either a rarity's `items:` list **or** an EcoItems item's own `rarity:`
field. If several rarities claim an item, the **highest `weight` wins** (that's why `none` ships with
weight 100).

Tiers are discovered automatically. `config.yml` only *curates* the filter:

```yaml
tiers:
  enabled: true
  order:                  # cycle order; discovered rarities not listed are appended
    - common
    - uncommon
    - rare
    - epic
    - legendary
    - mythic
  hide: []                # rarities to leave out of the filter entirely
  icons:
    epic: PURPLE_DYE
```

The tier is resolved **once, when the listing is created**, and stored on the row — browsing never
recomputes it. Listings created before this version have no tier and appear only under *No Filter*.

Without EcoItems installed, the tier filter simply reports `0 tiers` and sits inert.

---

## Confirmations

Buy-It-Now and Create always had a confirm screen. Bids and cancellations did **not** — and both
move real value on a single click (a bid takes the money immediately and holds it until you're
outbid or the auction ends). Both are now gated, including the *typed* bid amount, which is exactly
where you'd fat-finger an extra zero.

```yaml
confirmations:
  bid: true
  cancel: true
  purchase: true
```

Set one to `false` and the code falls **straight through** to the action — no screen is rendered, so
you pay nothing for the feature.

---

## Collecting sale earnings manually

With `listings.manual-earnings-collection: true`, money from a sale is not deposited when the item
sells. It waits, journaled like any other payout, until the seller clicks **Earnings** in the
Collection menu, so it only reaches their balance on purpose (useful with bounty plugins). Bid
refunds are still paid at once, and turning the option off pays out whatever is waiting.

Existing installs keep their `gui/collection.yml`; copy the `earnings` slot from the bundled file to
get the button (effect `ah_claim_earnings`, placeholder `%earnings%`).

## Storage

```yaml
storage:
  type: SQLITE            # or MYSQL for a network / shared setup
  sqlite-file: auctions.db
  mysql:
    host: localhost
    port: 3306
    database: royalauctions
    username: root
    password: ""
    properties: "useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true"
    pool-size: 10
```

HikariCP over JDBC. Listings, collection items, bids and notifications are stored alongside a
persistent operation journal, resource reservations, and operator reconciliation history.
MySQL requires InnoDB tables; SQLite uses WAL with full synchronous commits.

Items are stored with Paper's `serializeAsBytes()`, which preserves the **full** NBT/component data
— so eco custom items survive a listing intact with no per-plugin code, and even survive the eco
plugin being uninstalled.

> **`ra_bids` is new.** The listing row only ever stored the *current* top bidder, so once you were
> outbid there was no record you had ever bid — which made "auctions I've bid on" unknowable. Every
> bid is now recorded. It is **not backfillable**: only bids placed from this version on appear in
> View Bids.

## Reliability and recovery

Bids and purchases reserve the listing before charging. Expiry checks the current deadline and
funded leader under the same reservation. Closing an auction saves its outcome, collection item,
and seller payment obligation in one database transaction. Refunds and seller payments remain
queued until successfully processed; confirmed failures are retried.

Items selected in the sell menu enter persistent custody. Collection claims retain the saved
item until inventory delivery is acknowledged. If a crash leaves a Vault payment or inventory
change uncertain, the exchange is held for staff review rather than automatically repeated.

See [the recovery and upgrade guide](docs/RECOVERY.md) for `/ah recovery`, console reconciliation,
shared-server upgrades, guarantees and limitations. Stop and upgrade **all** servers sharing the
database together. Test the update on staging with your economy and inventory plugins first.

## PlaceholderAPI

Registered under the `royalauctions` identifier (auto-registers when PlaceholderAPI is present).

## NPCs (Citizens)

`/ah` takes no arguments, so an NPC just runs it:

```
/npc create Auctioneer
/npc command add ah
/npc command cooldown 2
```

The command **must run as the player** (Citizens' default — `player: true`). `/ah` is player-only, so
an NPC set to run it as console will just print "player only".

---

## Building

```bash
mvn package                # tests + target/RoyalAuctions.jar
```

Java 21, Maven. `eco` is a `provided` dependency resolved from the Auxilor repo. Versioning is
`year.week.revision` (e.g. `2026.28.1`), matching the eco suite's numbering.

---

## Gotchas

- **Paper 26.2 requires Java 25** to *run*, even though the plugin targets Java 21 bytecode.
- **A running JVM keeps old plugin classes** even after you replace the jar — fully stop the server
  before testing a new build.
- **EcoItems' lookup namespace is `ecoitems:` (plural)**, not `ecoitem:`.
- **Vanilla item ids** (`minecraft:diamond`) are resolved via Bukkit, not eco — eco's lookup doesn't
  reliably handle the `minecraft:` namespace.
- The plugin **waits** for a Vault economy provider rather than disabling if one isn't registered
  when it enables. Vault being a hard dependency says nothing about the *economy plugin*, which is
  separate and can register later; disabling would mean the plugin silently kills itself purely
  because of plugin load order.

## Legacy payment receipts

Existing file-based payouts and refunds remain recoverable after upgrading. New exchanges use
the database journal above. See [legacy payment recovery notes](docs/payment-recovery.md).
