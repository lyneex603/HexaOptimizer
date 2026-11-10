# ServerOptimizer 1.1.2

A conservative diagnostics and administration plugin for **Purpur 26.3**, targeting the Bukkit/Paper-compatible API. It is designed for busy servers, including PvP servers with Geyser/Floodgate users. It observes server conditions and offers evidence-based hints; it does not claim that a threshold proves the root cause of lag.

## Target and build

- Server target: Purpur `26.3-2655-HEAD` / API build `26.3.build.2655-stable`.
- Java: **JDK 25** (Minecraft/Paper 26.1+ requires Java 25).
- Build: Maven 3.9+ with internet access to the Purpur Maven repository.

```bash
mvn clean package
```

Expected output: `target/ServerOptimizer-1.1.2.jar`.

## Install

1. Stop the server and back up the server folder.
2. Build the project with JDK 25 and Maven.
3. Copy `target/ServerOptimizer-1.1.2.jar` into `plugins/`.
4. Start the server once to create `plugins/ServerOptimizer/config.yml`.
5. Review the config. Defaults are monitor-only and do not change gameplay.

## Commands

- `/so` or `/so help`
- `/so status`, `/so performance`, `/so report`
- `/so tps`, `/so mspt`, `/so memory`, `/so entities`, `/so chunks`
- `/so alerts`, `/so recommendations`, `/so history`
- `/so mode <monitor|recommend|automatic>`
- `/so itemcleaner <start|stop>`
- `/so reload`

Permissions: `serveroptimizer.admin`, `serveroptimizer.status`, `serveroptimizer.alerts`, `serveroptimizer.reload`, `serveroptimizer.mode`, `serveroptimizer.cleaner`.

## Features implemented in this baseline

- Rolling in-memory snapshots: TPS (when exposed by server), MSPT (when exposed), heap, non-heap, GC count/time, CPU readings (when JVM/OS exposes them), online players, Bedrock player count (when optional API is present), loaded chunks, entities, dropped items, mobs, projectiles, and this plugin's pending tasks.
- Incremental loaded-chunk scan with a configurable chunks-per-tick budget and delay between completed scans. Top chunks can be listed with `/so chunks`.
- Threshold alerts for TPS, MSPT, heap pressure, dropped items, projectiles, and high-entity chunks. Alerts have per-key cooldowns.
- PvP event-volume observation (damage events, interactions, projectile launches, potion splash events). It does not cancel or modify events.
- Optional Discord webhook delivery on a background executor. Webhook URL is a secret.
- Optional item cleaner. It only removes dropped `Item` entities in already-loaded chunks, in batches. It does not remove players, mobs, blocks, vehicles, or XP orbs.

## Explicitly enabling item cleanup

Cleanup is disabled by default. To allow it, review the following settings in `config.yml`:

```yaml
mode: AUTOMATIC
automatic:
  enabled: true
  allow-item-cleanup: true
entities:
  item-cleaner:
    enabled: true
    interval-minutes: 60
    warnings-seconds: [300, 60, 10]
```

Then run `/so reload`. You can use `/so itemcleaner stop` to stop it. The cleaner counts removed item entities and broadcasts the total after a complete sweep. It only visits chunks that are loaded at the start of the sweep. Drops younger than five minutes are skipped. Named/lore-bearing or otherwise metadata-bearing stacks are protected by default, as are common high-value PvP materials (diamond/netherite gear and resources, elytra, totems, golden apples, end crystals and shulker boxes). Work is bounded by `entities-per-tick`. Review and customize the protected-material list before enabling cleanup; cleanup remains off by default.

## Safety and limits

- `MONITOR` is observe-only; `RECOMMEND` adds advice; `AUTOMATIC` still requires the separate global and per-action config flags.
- The plugin never changes PvP damage, attack speed, knockback, velocity, armor, enchantments, potion effects, or player interaction semantics.
- It never forcibly unloads chunks, changes JVM flags, edits Purpur settings, or changes Geyser/Floodgate settings.
- Chunk/entity totals come from incremental scans and can lag behind the live world. The scan budget trades freshness for low tick impact.
- TPS/MSPT methods vary by server implementation; unavailable values display as `n/a`.
- CPU is best-effort and can display `n/a` depending on JVM/OS permissions.
- Floodgate/Geyser detection is optional reflection-based integration; missing or changed APIs can cause Bedrock counts to be incomplete.
- Bukkit/Paper API does not expose reliable per-plugin CPU attribution, packet translation cost, database timing, exact chunk-load duration, or all reasons a chunk remains loaded. Use a compatible profiler such as the server's bundled Spark where available; this plugin does not assume an unverified Spark API.
- History is in memory and resets on restart.

## Verification status

The source project and configuration target the exact API version. This workspace does not have JDK 25, Maven, or network access to the Purpur repository, so a real Maven compile and live Purpur test could not be performed here. Do not treat this source archive as a verified production JAR. Build with JDK 25/Maven and test on a staging copy before installing on the live server.
