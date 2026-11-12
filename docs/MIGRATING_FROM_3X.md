# Migrating from RabbitMQ 3.x to 4.x with Caerbannog

Step-by-step how-tos for a move from RabbitMQ 3.x to 4.x. The short version is in the [README](../README.md); the design decisions are in [`docs/spec.md`](spec.md) ("Migrating from RabbitMQ 3.x (M8)").

| How-to | |
| --- | --- |
| [1. Analyse a 3.x broker from a definitions file](#1-analyse-a-3x-broker-from-a-definitions-file) | no connection needed |
| [2. Analyse a live 3.x broker](#2-analyse-a-live-3x-broker) | read-only requests |
| [3. Review the report and generate migration files](#3-review-the-report-and-generate-migration-files) | |
| [4. Apply the migration to a 4.x broker](#4-apply-the-migration-to-a-4x-broker) | |
| [5. Migrate users, virtual hosts and permissions](#5-migrate-users-virtual-hosts-and-permissions) | the access file and its secrets |
| [6. Move the messages of a queue with a Shovel](#6-move-the-messages-of-a-queue-with-a-shovel) | |
| [7. Check that a Shovel finished, or remove a failed one](#7-check-that-a-shovel-finished-or-remove-a-failed-one) | |
| [8. A suggested order for a blue-green move](#8-a-suggested-order-for-a-blue-green-move) | new 4.x broker, never in place |

## What it does, and what it does not

**It does:** read the topology of a 3.x broker (live or from `definitions.json`) and tell you what 4.x will not accept; propose a 4.x fix per item and write your decisions as ordinary migration files; carry virtual hosts, users (with their password hashes), permissions and topic permissions in a separate access file; move the messages of one queue with a broker-side Shovel after a typed confirmation.

**It does not:** change anything on the 3.x broker (every request the app sends to it is a GET; a 3.x connection is a migration source only, so streams, publishing and topology editing are off for it); upgrade a broker in place; create, show or generate passwords (hashes are passed through and never displayed); do anything by itself (nothing is sent to a broker until you apply a migration or confirm a forward).

Tested against RabbitMQ 3.13 as the source and 4.x as the destination. Other 3.x versions are not verified. To try it on your machine without a real 3.x broker, see "The 3.x broker (M8 hand trial)" in [`dev/README.md`](../dev/README.md).

---

## 1. Analyse a 3.x broker from a definitions file

**You need:** a `definitions.json` exported from the 3.x broker. In its management UI: Overview, "Export definitions" (or `rabbitmqctl export_definitions definitions.json`). Export the whole broker if you also want users and permissions: a file exported for one virtual host only holds no users, virtual hosts or permissions.

1. Open **Migrate from 3.x** and choose **Definitions file**.
2. Click **Choose definitions file** and pick the file. Nothing is read before this; the file is read on this machine only, and files over 64 MB are refused.
3. If the file is a one-virtual-host export, the app asks "Which virtual host is this file from?". Type the name (there is no default) and click Analyse. The virtual host must already exist on the 4.x broker; no access migration can be generated for such a file.

**You should see:** the file name, the counts (virtual hosts, queues, exchanges, bindings) and the compatibility report (how-to 3).

**If it goes wrong:** "The file is not a definitions file" means the file is not a definitions export (nothing was analysed; choose another file). A file that is too large or cannot be read is refused with a fixed text.

## 2. Analyse a live 3.x broker

**You need:** a connection profile for the 3.x broker whose user has the **administrator** tag (the export of definitions needs it). The app detects a 3.x broker by itself and marks the profile "RabbitMQ 3.x: migration source only".

1. Connect the 3.x profile (Connections).
2. Open **Migrate from 3.x** (from the connection's popup entry "Migrate from 3.x", or the Migrations screen). Choose **Live connection**, then **Analyse live connection**.
3. Wait: the app reads the definitions, the index version of the classic queues (a few reads, one second apart) and the deprecated features in use. Only GET requests are sent.

**You should see:** a report whose source card says it is live and read only, with "Analyse again" while the connection stays connected. You can then connect to the 4.x broker; the report stays.

**If it goes wrong:** "This user cannot export definitions: the administrator tag is needed" — use another user or how-to 1. "... could not be reached" or "The broker could not return its definitions" — check the connection; **Try again**. Only a 3.x broker can be analysed live.

## 3. Review the report and generate migration files

**You need:** a report from how-to 1 or 2.

1. Read the **message-loss block** at the top, if there is one: classic queues with the old index (version 1) would silently lose their messages on an in-place upgrade to RabbitMQ 4.3 or later. Do not upgrade in place (how-to 8). You can copy the queue names.
2. Use the severity chips to filter: **blocker** (will not work on 4.x as it is), **warning** (works, behaves differently), **info**. Open a group to see its items; passwords hashes are never shown.
3. Open each item that needs a decision ("N items need a decision" in the bar). Choose **Accept** (some need a tick, such as "Ordering by priority may change" when a classic queue becomes a quorum queue; an item whose only fix is manual cannot be accepted as it is), pick an **alternative**, **Skip** it (it moves to "Skipped by you" and can be put back), or **Replace** it with your own actions.
4. When the bar says every item is decided, click **Generate migration**. The first time the app asks you to choose the **migrations folder** (a git repository is fine; it remembers the folder for this machine).

**You should see:** "N migrations saved", one **topology file per source virtual host**, and, if the export holds users or permissions, one **access file** with the lowest number (how-to 5), with the list of what was left out (your skipped items, bindings that lost their queue). **Open Migrations** takes you to the next step.

**If it goes wrong:** with no usable folder, or if saving fails, the card says why; choose another folder, try again, or **Export the files** through a save dialog. Saved files are never overwritten; generating again after saving asks first and writes new versions. Export report (JSON) and Copy as text keep a record of the report.

## 4. Apply the migration to a 4.x broker

**You need:** a connection to the **4.x** broker. For a topology file: the connection's virtual host must match the file's, the configure permission, and the level **Declare and edit** or higher. Check the permission pill on the connection.

1. Open **Migrations**; the folder you chose lists the files with their status (pending, applied, modified).
2. Select a file, read the **dry run** (each action, allowed or blocked with its reason), then click **Apply pending** (or apply the selected file).
3. Watch the progress (one action at a time).

**You should see:** each file marked applied. Applying is safe to repeat: something that already exists with the same settings is a no-op. History (version and checksum) is kept per connection in the app's own data, not in your folder.

**If it goes wrong:** the run **stops at the first failing action**, names it with a fixed reason and does not mark the file applied; fix the cause and apply again. A **conflict** ("exists with other settings") means something with that name exists and was not overwritten; nothing is deleted. "Topology locked" or a virtual host mismatch is shown as the reason the button is disabled.

## 5. Migrate users, virtual hosts and permissions

**You need:** a full export (how-to 1 with a whole-broker file, or how-to 2). On the 4.x connection: the **administrator** tag and the level **Full control** (an access action is checked on its own; the virtual host of the connection does not matter).

1. Do how-to 3: the access file is generated with the topology files, numbered first, so it is applied first.
2. Read the generate result. The access line says how many virtual hosts, users and permissions it holds. **Users not migrated** lists `guest` (never migrated), users whose password hash uses an algorithm 4.x does not accept (set a new password for them on the broker, outside the app; the app has no password entry), and users without a hash. **Permissions left out** lists those of users that were not migrated. The default virtual host `/` is not declared; it always exists.
3. Keep the file secret (below), then apply it as in how-to 4. The access file applies from an administrator profile of any virtual host.

**You should see:** the virtual hosts, users (their old passwords still work) and permissions on 4.x. Applying twice changes nothing.

**Secrets.** The access file holds **password hashes**: anyone with the file can try to guess the passwords offline, and the old users come back alive on the new broker. Keep it out of git and shared or synced folders, and delete it when you are done. The app writes it readable by its owner only (best effort; not verified on Windows), warns when you save or export it, shows "hash hidden" in its raw view, and offers no Edit for a file that holds a hash (edit it outside the app).

**If it goes wrong:** a user that already exists with a different password hash, or permissions or a virtual host that exist with other settings, are conflicts: the apply stops and nothing is overwritten; resolve them on the broker. Without the administrator tag or Full control the Apply button is disabled and says which one is missing.

## 6. Move the messages of a queue with a Shovel

**You need:**

- a live analysis of the 3.x broker (how-to 2) and a connection to the 4.x broker;
- the destination queue **already on the 4.x broker** (apply the topology migration first), with the same name, in the virtual host of your 4.x connection;
- the Shovel plugins `rabbitmq_shovel` and `rabbitmq_shovel_management` enabled on the 4.x broker;
- on the 4.x broker: the policymaker and monitoring tags (or administrator), and the level **Declare and edit** with the configure permission.

1. In the report's app bar click **Forward messages**; find the queue and click **Forward messages** on its row (one queue at a time; there is no "forward everything").
2. Read the **plan** (a dry run, GET requests only): messages at the source, the destination and its type. Blocks (missing destination, plugins off, missing tag, locked level, an existing Shovel) are shown with the reason; fix the cause and pick the queue again. A destination that is not a quorum queue gets a warning.
3. Read the **confirmation**. It says: the messages are **moved** (they leave the source queue when the destination confirms; the app cannot undo it); the source queue is not deleted; the Shovel keeps the credentials on the 4.x broker until it finishes or is removed, **in clear text, readable by any administrator of that broker**. Check the AMQP host and port as the 4.x broker reaches the 3.x broker (prefilled). Optionally choose "Use another user for this Shovel" (typed for this run, in memory only) or "Keep the shovel running after the messages are moved" (it then forwards until you remove it). With TLS off on the 3.x profile you also see "Not encrypted: the shovel reaches the source over a plain connection."
4. **Type the source queue name.** The **Forward messages** button stays disabled until it matches exactly. Cancel sends nothing.

**You should see:** "Moved about N of M messages", updated every 2 seconds. By default the Shovel moves the messages that are there when it starts and then removes itself.

**If it goes wrong:** see how-to 7.

## 7. Check that a Shovel finished, or remove a failed one

**Check it finished.** While the forwarding view is open it polls the Shovel. A normal finish says "Finished", plus "About K messages stayed on the source because they were published meanwhile", or that none stayed. The Shovel removed itself, so no credentials remain and there is nothing to remove. Leaving the screen stops the progress display, not the Shovel: to check later, open Forward messages and pick the queue again; an existing Shovel is shown with its state and counts.

**If it failed.** The view says "The forwarding failed" with the broker's reason (for example that it could not connect to the source: check the AMQP host and port and the password).

1. If it says the Shovel was removed, no stored credentials remain; fix the cause and forward again.
2. If it says the Shovel **could not be removed here**, the Shovel and its stored password remain on the 4.x broker. Use a connection with the level **Full control** and click **Remove shovel**; it deletes only the Shovel, never a queue. (Below Full control the button is disabled with "Removing a shovel needs Full control".)
3. A Shovel that exists is never replaced; remove it first to start again.

## 8. A suggested order for a blue-green move

This order follows the behaviour measured for the app (see the spec); the client switch itself is outside the app and is your own procedure.

1. **Never upgrade the 3.x broker in place.** If the report has a message-loss block, an in-place upgrade to RabbitMQ 4.3 or later silently empties the classic queues listed there.
2. Build a **new 4.x broker** and enable the Shovel plugins on it.
3. Export or connect the 3.x broker and **analyse** it (how-to 1 or 2, with a full export or an administrator user).
4. **Review and generate** (how-to 3).
5. Apply the **access file** first (how-to 5), then the **topology files** (how-to 4), so users, virtual hosts and the destination queues exist.
6. For each queue whose messages matter, **forward** it (how-to 6) and check it (how-to 7). To avoid messages stranded on the old broker, stop the publishers of that queue first (messages published while a Shovel runs may stay on the source; the result tells you about how many). Forward again for what stayed.
7. **Switch your clients** to the 4.x broker (your own procedure).
8. Keep the 3.x broker until you are satisfied, remove any Shovel that is still around, and delete the access file.

## Limits

- Tested against 3.13 (source) and 4.x (destination).
- TLS between the brokers for the Shovel: the app uses `amqps` when the 3.x profile has TLS on, but TLS options (custom CA, verification settings) are not supported and not verified; a source the 4.x broker cannot verify makes the Shovel fail (and be removed).
- The access migration needs a full export; per-user skipping is not offered.
- Owner-only file mode is best effort and not verified on Windows.
- An exact cut-over needs the publishers stopped first.
- The in-place upgrade of a 3.x data directory is not supported and not done by the app.
