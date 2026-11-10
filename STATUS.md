# Implementation status — 1.1.2

## Implemented in source
- Bukkit/Purpur plugin lifecycle and `/so` command executor.
- Rolling in-memory monitoring snapshots for TPS/MSPT when exposed, heap/non-heap, CPU when exposed, GC counts/time, players, optional Bedrock player count, loaded chunks, incremental entity/item/mob/projectile counts, and pending scheduler tasks.
- Incremental chunk scanner with a per-tick budget, scan interval, top-chunk report, and unload-cache cleanup.
- TPS/MSPT/memory/entity/chunk threshold alerts with per-key cooldowns.
- PvP event-volume observation for damage, interactions, projectile launches, and potion splashes; no event mutation.
- Optional Floodgate/Geyser detection through reflection.
- Discord webhook notifications on a background executor with cooldown and mention suppression.
- Explicitly gated dropped-item cleanup in already-loaded chunks, warnings at 300/60/10 seconds, bounded entity work per tick, minimum drop age, metadata protection, valuable-material protection, removed-item count, and action audit log with rotation.
- `MONITOR`, `RECOMMEND`, and `AUTOMATIC` mode setting. Automatic item cleanup additionally requires three separate config gates.

## Not implemented / cannot be reliably provided by Bukkit API alone
- Accurate per-plugin CPU attribution and individual listener execution timing.
- Geyser packet translation cost, packet-level network diagnosis, and client-version traffic profiling.
- Database query timing unless a database plugin exposes an integration API.
- Exact chunk-load duration and all chunk ticket/retention causes.
- Automatic chunk unloading, JVM tuning, or gameplay-changing optimization.
- Spark integration: no Spark API is linked because an API/compatibility contract has not been verified. Use the profiler already bundled by the server where available.

## Changes in 1.1.2
- Fixed `/so` command-level permission gating so users with a subcommand-specific permission can execute that subcommand; permissions are checked by the executor.
- `/so reload` now restarts monitoring tasks so changed sampling intervals and scan budgets take effect.
- Cleaner defaults now protect named/lore/meta-bearing drops, enforce a five-minute minimum age, and protect a list of high-value PvP materials.
- Cleaner entity processing is bounded per tick rather than processing every entity in several chunks in one tick.

## Validation in this workspace
- YAML config parsed successfully.
- Maven POM parsed as XML.
- Source ZIP integrity check passed.
- XML and YAML parsing and ZIP integrity can be checked locally. Full Java compilation cannot complete because Bukkit/Purpur API dependencies are absent.
- No JDK 25, Maven executable, or external network access is available here. Consequently no JAR has been built and no live Purpur 26.3 test has been run.
