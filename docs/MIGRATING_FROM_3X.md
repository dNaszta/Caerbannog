# Migrating from RabbitMQ 3.x to 4.x with Caerbannog

This guide explains what the app does for a move from RabbitMQ 3.x to 4.x, step by step, and what it will not do. The short version is in the [README](../README.md). The design decisions behind it are in [`docs/spec.md`](spec.md) ("Migrating from RabbitMQ 3.x (M8)").

## What it does, and what it does not

**It does:**

- read the topology of a 3.x broker (live, or from an exported `definitions.json`) and tell you what RabbitMQ 4.x will not accept as it is;
- propose a 4.x equivalent for each problem, let you decide per item, and write your decisions as ordinary migration files;
- carry virtual hosts, users (with their existing password hashes), permissions and topic permissions over, in a separate access file;
- move the messages of one queue into a queue on the 4.x broker with a broker-side Shovel, after a typed confirmation.

**It does not:**

- change anything on the 3.x broker. Every request the app sends to a 3.x broker is a GET. A 3.x connection is a migration source only: the stream features, publishing and topology editing are off for it.
- upgrade a broker in place. The recommended path is a **new 4.x broker**, the generated migrations, and a Shovel for the messages.
- create, show or generate passwords. Password hashes are passed through from the export into the access file; the app never computes or displays one.
- convert or move anything by itself: every item is your decision, and nothing is sent to a broker until you apply a migration or confirm a forward.

Tested against RabbitMQ 3.13 as the source and 4.x as the destination. Other 3.x versions are not verified.

## Before you start

| You need | For |
| --- | --- |
| a connection profile for the 3.x broker (its user needs the **administrator** tag to export definitions live) or an exported `definitions.json` | the analysis |
| a connection profile for the new 4.x broker | applying migrations and forwarding messages |
| level **Declare and edit** (and the configure permission) on the 4.x profile | applying topology migrations and starting a Shovel |
| the **administrator** tag and level **Full control** on the 4.x profile | applying the access migration; removing a Shovel needs Full control too |
| the Shovel plugins (`rabbitmq_shovel`, `rabbitmq_shovel_management`) on the 4.x broker, and a user with the policymaker and monitoring tags (or administrator) | forwarding messages |

To export a file from a 3.x broker: in the management UI, Overview, "Export definitions"; or `rabbitmqctl export_definitions definitions.json`. A file exported for one virtual host only has no users, virtual hosts or permissions; the app then asks which virtual host name to use and cannot generate an access migration.

## Step 1: choose the source

Open **Migrate from 3.x**. Choose **Definitions file** (nothing is read until you pick a file; files over 64 MB are refused) or **Live connection** (offered when a 3.x broker is connected; the app reads its definitions and the index version of its classic queues with GET requests only). If the user of the live connection cannot export definitions, the app says so and offers the file import.

The analysis stays in memory while the app runs, so you can analyse the 3.x broker and then connect to the 4.x broker without losing the report.

## Step 2: read the report

At the top you see the queues that would **silently lose their messages** if you upgraded the 3.x broker in place to RabbitMQ 4.3 or later (classic queues with the old index, version 1). The advice there is: do not upgrade in place; build a new 4.x broker, apply the generated migration, and move the messages with a Shovel. The list can be copied.

Below, findings are grouped by rule, with chips to filter by severity:

- **Blocker:** will not work on 4.x as it is (for example mirroring policy keys, transient or non-durable queues).
- **Warning:** works, but behaves differently and you should decide (for example a classic queue becoming a quorum queue changes ordering by priority).
- **Info:** nothing to do, or a manual step.

The summary line says how many items need a decision and how many are compatible as they are (those are not listed). Passwords hashes are never shown ("hash hidden"). You can **Export report** (JSON) or **Copy as text**.

## Step 3: review

Items that need a decision show a status. Open one to see what the app found, why, and what it proposes:

- **Accept** the proposal (some need a tick, for example "Ordering by priority may change"; an item whose only fix is manual cannot be accepted as it is);
- choose an **alternative** where there is one;
- **Skip** the item (it goes to a "Skipped by you" group and can be put back);
- **Replace** it with your own migration actions (the app checks them; it never writes a delete).

The bar shows "N items need a decision" until every item is decided; **Generate migration** is enabled when none is left.

## Step 4: generate

Generate writes migration files into your **migrations folder**. The first time, the app asks you to choose the folder (a git repository is fine); it remembers it for this machine. Saved files are never overwritten or deleted, and generating again after saving asks first and writes new versions.

You get:

- **one topology file per source virtual host** (queues, exchanges, bindings, policies as you decided);
- **one access file**, numbered *before* the topology files so it is applied first, when the export holds virtual hosts, users or permissions.

The result lists what was left out: items you skipped, bindings that lost their queue, and in the access section the **users not migrated** (`guest` is never migrated; users whose password hash uses an algorithm 4.x does not accept need a new password set on the broker, outside the app; users without a password hash) and the **permissions left out** because their user is not migrated. The default virtual host `/` is not declared (it always exists on 4.x).

If no folder can be used, or saving fails, you can **Export the files** through a save dialog instead.

### The access file is a secret

It contains password hashes. Anyone who has the file can try to guess the passwords offline, and the old users come back alive on the new broker. So: keep it out of git and shared or synced folders, and delete it when the migration is done. The app writes it readable by its owner only (best effort; not verified on Windows) and shows a warning when it is saved or exported. Its raw view in the Migrations screen shows "hash hidden", and a file that holds a hash cannot be edited in the app (edit it outside the app).

## Step 5: apply on the 4.x broker

Connect the 4.x broker, open **Migrations** (button "Open Migrations" in the result) and use the dry run, then **Apply pending**. Each topology file applies only on a connection whose virtual host matches. The access file applies from an administrator profile of any virtual host and needs the level Full control.

- Applying a migration is safe to repeat: something that already exists with the same settings is a no-op.
- If something already exists with **other settings**, the apply stops with a conflict and nothing is overwritten (a user with a different password hash, other permissions, a virtual host with another description are conflicts).
- Applied versions and checksums are kept per connection in the app's own data, not in your folder.

## Moving messages with a Shovel

When you have a live analysis and a connected 4.x broker, **Forward messages** (in the report's app bar) lists the queues of the analysis. Pick one; the app runs a **dry run** (GET requests only) and shows the message count at the source, the destination queue, whether the Shovel plugins are on, and whether a Shovel of that name exists.

**What you must know**

- **It moves messages.** They leave the source queue when the destination confirms. This is not a copy, and the app cannot undo it. The source queue itself is never deleted.
- The Shovel runs **on the 4.x broker** and pulls from the 3.x queue. The app sends the 3.x broker only GET requests.
- The Shovel keeps the 3.x user's **credentials in clear text on the 4.x broker** until it finishes or is removed; any administrator of the 4.x broker can read them. By default the profile's user is used; you can use another user for this run (typed, kept in memory only, never saved).
- The destination is the **same queue name** in the virtual host of your 4.x connection and must **already exist** (apply the migration first). If it does not, forwarding is blocked, because a Shovel would create a plain classic queue.
- Delivery uses `on-confirm` acknowledgement only, one queue at a time (there is no "forward everything").
- A Shovel of that name that already exists is never replaced.

**The flow**

1. The confirmation names the source and the destination, repeats the points above, asks for the AMQP host and port as the 4.x broker reaches the 3.x broker (prefilled), and offers "Use another user" and "Keep the shovel running after the messages are moved". With TLS off on the 3.x profile it adds "Not encrypted: the shovel reaches the source over a plain connection."
2. You **type the source queue name**. The Forward button stays disabled until it matches exactly. Cancel sends nothing.
3. The app starts the Shovel and shows progress ("Moved about N of M messages"), polling every 2 seconds while the view is open. By default the Shovel moves the messages that are there when it starts and then removes itself. Messages published to the source queue meanwhile stay there; the result says about how many.
4. **Remove shovel** (Full control) deletes only the Shovel, never a queue. After a normal finish there is nothing to remove.

**Leaving the screen** stops the progress display, not the Shovel. Open the queue again to see the existing Shovel and its state.

**If it fails.** The app shows the broker's reason (for example it could not connect to the source: check the AMQP host, port and password), and removes the Shovel so no credentials stay behind. If it cannot remove it (for example the level is below what removing needs), it says so: the Shovel and its stored password remain on the 4.x broker and a user with Full control must remove it. A missing destination, a missing plugin, a missing tag or a locked level are shown as reasons before anything starts.

## Limits

- Tested against 3.13 (source) and 4.x (destination).
- TLS between the brokers for the Shovel: the app uses `amqps` when the 3.x profile has TLS on, but TLS options (custom CA, verification settings) are not supported and not verified; a source the 4.x broker cannot verify makes the Shovel fail (and be removed).
- The access migration needs a full export; per-user skipping is not offered.
- Owner-only file mode is best effort and not verified on Windows.
- Queue messages published while a Shovel runs may stay on the source; an exact cut-over needs the publishers stopped first.
- The in-place upgrade of a 3.x data directory is not supported and not done by the app.
