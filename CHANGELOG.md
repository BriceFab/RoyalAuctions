## 2026.41.0 - 2026-10-10

### Features
- remind players on join of items and earnings waiting in /ah collect (`2be8ae4`)
- search listings by translated item name and by enchantment (`39fdc2a`)
- optional manual collection of sale earnings (`b6c4de0`)

### Fixes
- stop the Back button hiding Search in the browse menu (`dfb9b3e`)
- honour EconGuard's trade veto on bids and purchases (`0c6fd1c`)
- make the custom bid prompt translatable (`2846939`)
- read menu text and exchange reasons from messages.yml (`ca9d0b2`)

### Refactors
- read all player-facing text from config (`6c9f5b6`)
- align the text dialog with the shared TextInput (`a0a32dc`)
- replace the sign input with a native text dialog (`6c742cd`)

### Documentation
- add AGENTS.md with the suite's development standards (`a057019`)

### Other
- Close open menus on disable (`9ebaa15`)

## 2026.40.0 — 2026-10-03

### 🐛 Fixes
- use the ISO week-year for release versions (`7cc2336`)

## 2026.39.1 — 2026-09-24

### 🐛 Fixes
- finish in-flight exchanges on shutdown and honour listing-limit permissions (`f112121`)
- literal message placeholders, atomic category reloads, stricter config checks (`ca46e09`)
- guard sign prompts, stale menus and repeat clicks (`b204bb3`)
- scope MySQL migrations, index browse filters, harden duplicate checks (`c2b35bc`)
- keep the sign prompt off block entities and blocks already in use (`6e85386`)

### 📝 Documentation
- add MIT license (`7c642fa`)

## 2026.39.0 — 2026-09-23

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`c606b57`)

## 2026.37.0 — 2026-09-13

### 🐛 Fixes
- identify recovery workers in server startup logs (`10af515`)
- journal auction exchanges and recover interrupted settlement (`0130b7b`)
- retain unresolved payment settlements and prevent unsafe retries (`91a95a2`)
- release at 10:00 Central or later, not exactly 10:00 (`639cfec`)

## 2026.36.0 — 2026-09-06

### ✨ Features
- bounded retention for the audit tail (`b8b3ffa`)
- while-you-were-away summary on join (`81d238b`)
- anti-snipe bid extension (`fe3fd49`)
- ENDING_SOON browse sort (`fc77ff9`)
- optional sellable-categories restriction on listings (`89d8903`)

### 🐛 Fixes
- close the last-second-bid race in the expiry sweep (`b773288`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`271097f`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- report anonymous usage stats via bStats (`e5d15cd`)

### 🐛 Fixes
- actually play menu sounds (`fe3d8d4`)
- return escrowed create-flow items on shutdown instead of destroying them (`05b7532`)

### ⚡ Performance
- page auction browsing in SQL instead of in memory (`859bc62`)

## 2026.29.1 — 2026-07-17

### 🐛 Fixes
- don't swallow a failed migration as 'column already exists' (`e2996cd`)
- pass the Modrinth payload as a file, not inline (`f4b007a`)

## 2026.29.0 — 2026-07-17

### ✨ Features
- player-head icons + eco-style direct row/column (`56fc719`)
- add ConfigValidator for load-time sanity checks (`fbb2d21`)
- report auction money movements to EconGuard (`797e9b8`)

