# Auction reliability and recovery

This update reserves an auction before attempting a charge. Expiry, bids, purchases, and
cancellation share the same database reservation.
An accepted bid becomes the leader only after Vault reports a successful withdrawal and that
result is saved. Expiry then reads the current deadline and funded leader again.

Settlement saves the closed listing, winner's collection item, and seller's payment obligation
in one database transaction. Repeating that database work cannot create a second item or payout.
Outbid refunds are also persistent obligations. A successful sale may therefore appear before its
seller payment arrives; the worker normally processes payments within five seconds. Confirmed
payment failures stay owed and retry after at least one minute.

## What crash recovery can establish

The database and Vault/player inventories do not share a transaction. Vault's standard payment
API does not accept a RoyalAuctions operation ID to deduplicate a retry. If the process stops
between an external effect and its database acknowledgement, its outcome is unknown.
RoyalAuctions holds that exchange and its reservation for review. It does **not** guess that
the payment failed or run it again automatically.

A normal stop or reload does not create held exchanges. Shutdown waits (up to ten seconds) for
exchanges already under way: an effect that already ran is acknowledged, and one that has not
started yet is recorded as not applied, so its reservation is released (a payout goes back to
`READY` and is paid after the restart). Only a crash, a kill, or a provider that throws leaves an
exchange `APPLYING`.

| State | Meaning | Recovery |
|---|---|---|
| `PREPARED` | Saved intent; the external effect has not started | Abandon after two minutes; a late queued worker cannot start it |
| `APPLYING` | External call may have started or finished | Hold; never replay automatically |
| `APPLIED` | External success was acknowledged in the database | Finish database changes automatically |
| `FAILED` | External failure was acknowledged, with no value moved | Finish cleanup; confirmed failed payouts return to `READY` |
| `READY` | A payment remains owed and is safe to attempt | Claim it before calling Vault; back off known failures |
| `DONE` | Database completion committed | No further effects |

These guarantees depend on the database honoring durable commits and the economy provider
reporting outcomes accurately. A provider that reports success before saving balances, reports
failure after moving money, or restores an older balance backup can still require reconciliation.
Inventory capture and claim call `Player.saveData()` before acknowledging success; a failure or
interruption around that save is also held. Server/platform failures, external inventory sync,
and independently restored backups cannot be made atomic by the auction plugin.

## Item custody

Selecting an item in the sell menu first saves its bytes and a capture operation. Only then does
the main thread remove that exact stack from its original inventory slot and save player data.
An incomplete capture locks the saved copy so it cannot be claimed or listed twice.

After confirmed capture, the item lives in collection storage while the menu shows a copy.
A draft listing locks that item until the listing-fee outcome is known. Failed creation keeps the
item in collection. Closing/disconnecting during work may leave the item in `/ah collect`.

Collection claims reserve the actual stored item by ID and owner. The row is removed only after
inventory delivery and player-data save are acknowledged. A full inventory leaves the whole item
in collection; claims no longer drop overflow on the ground.

## Operator procedure

1. Run `/ah recovery` with `royalauctions.admin`. Use `/ah recovery 2`, etc. for additional pages.
   Entries include operation ID, kind, state, player, amount, listing/collection IDs, origin worker,
   and last update time. The log warns once per minute while `APPLYING` entries older than two
   minutes remain. Resource locks deliberately do not expire.
2. **Stop the process that owned the operation.** The origin UUID identifies a process, not a
   permanent server name; each server logs `Auction recovery worker: <UUID>` at startup.
   If the originating process cannot be identified, stop all servers sharing this database.
   Do not merely pause it or rely on heartbeat timeout: a suspended process
   could resume its already queued external effect. A full stop followed by restart creates a new
   worker ID. For a shared database, identify and stop the old process on the relevant server.
3. Keep the affected account/inventory isolated while checking the economy provider's transaction
   records and saved player data. Use the operation's amount, player, kind and timestamps. A current
   balance alone is insufficient evidence. Preserve a backup before any manual corrections.
4. Once the actual outcome is established, use the **server console**:

   ```text
   ah recovery resolve <operation-id> applied confirm
   ah recovery resolve <operation-id> not-applied confirm
   ```

   The operation must be `APPLYING`, older than two minutes, with no recent heartbeat from its
   origin. These checks support the requirement to stop the original process; they do not replace it.
   Choosing an outcome records a permanent row in `ra_reconciliations`. Database-only completion
   then runs. If completion fails, the acknowledged decision remains and recovery can finish it.

| Kind | `applied` means | `not-applied` means |
|---|---|---|
| `BID` / `BUY` | Full withdrawal occurred; commit the bid/purchase | No withdrawal occurred; release the reservation |
| `CREATE` | Listing fee was paid; activate the draft and consume its collection item | Fee was not paid; delete the draft and keep its collection item |
| `PAYOUT` | Full deposit occurred; mark paid without depositing again | No deposit occurred; return it to the retry queue |
| `CAPTURE` | Full item removal is saved in player data; make its saved collection copy available | Player still has the original item; remove the locked backup copy |
| `CLAIM` | Full item delivery is saved in player data; remove the collection row | Player received none of the item; release it for a new claim |

If inventory delivery was partial, or the outcome remains uncertain, **leave it held**. First
reconcile the actual inventory/account to one complete outcome, record the correction in your
operator notes, then select that outcome. Never use `not-applied` as a general “retry” button.
Zero-fee creation has no money movement to investigate; it can be resolved as `applied` to activate
the reserved draft. Do not delete operation or lock rows to unblock an exchange.

## Upgrade and rollback

File receipts from the earlier payment-recovery update remain supported in `payments/pending`
and `payments/receipts`. Keep these folders on their originating server. The compatibility worker
retries explicitly rejected old credits every 30 seconds, up to ten per pass, and leaves unknown
outcomes held. It does not create a second database obligation for those receipts. New exchanges
use only the database journal. `/ah recovery` lists database operations; for older file receipts,
follow [the legacy receipt procedure](payment-recovery.md). Back up both journals during upgrades.

- Stop every RoyalAuctions instance sharing the database before the first upgrade. Back up the
  auction database, economy data, and player data together. Install the new jar on all instances
  before allowing transactions. Older jars bypass these reservations and must not run alongside it.
- Schema changes are additive: `DRAFT` listing status, `ra_operations`, `ra_operation_locks`,
  `ra_workers`, and `ra_reconciliations`. Existing listings, collection items and bids remain readable.
  SQLite uses WAL and `synchronous=FULL`. MySQL auction tables must use InnoDB; startup refuses
  nontransactional tables instead of pretending rollback is safe.
- Existing unresolved damage from an older version cannot be inferred or backfilled without an
  operation history. Reconcile any known incidents before upgrading.
- `closed-retention-days` does not delete listings with unfinished obligations or draft listings.
  The operation journal and reconciliation audit are retained; plan storage accordingly.
- Do not downgrade over a database with new drafts or pending operations. Stop all instances and
  reconcile first, or restore a coordinated pre-upgrade backup of all affected stores. Restoring
  only auction data can replay obligations already paid in the economy provider.
- New `exchange.*` and `recovery.*` messages fall back to bundled defaults without overwriting
  customized `messages.yml`. Console log lines stay in English.

## Validation

`mvn test` runs the SQLite contract tests and listing calculations. To run the same contract
against a **disposable**, loopback MySQL server, set `RA_TEST_MYSQL_PORT` to its port. The test
server must allow the local `root` user with an empty password; the fixture creates and drops
only UUID-named `ra_tx_test_*` databases. Never use a production server for this fixture.
GitHub's build workflow starts a disposable MySQL 8.4 service for these tests.

Coverage includes two independent pools competing for reservations, concurrent finalizers and
expiry workers, stale deadlines/minimum bids, failed withdrawals, injected settlement failure,
reopening the database at external-effect boundaries, payout retry/deduplication, capture/claim
custody, listing limits, pruning, and operator reconciliation. This is database and orchestration
coverage; it does not replace an in-game staging check with the server's economy and inventory
plugins before deployment.

On staging, exercise BIN purchases, two bidders near expiry, insufficient funds, full-inventory
claims, repeated clicks, menu close/disconnect during creation, normal restart, and a forced stop
during an exchange. Confirm balances, leader, collection ownership, and `/ah recovery` together.
EconGuard notifications are best-effort audit integration; the operation journal is the recovery
record and should be consulted after an interrupted exchange.
