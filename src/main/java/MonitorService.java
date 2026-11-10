package dev.serveroptimizer.monitor;

import dev.serveroptimizer.ServerOptimizerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Incremental, main-thread Bukkit sampling. No Bukkit objects escape to async tasks. */
public final class MonitorService {
    private final ServerOptimizerPlugin plugin;
    private final Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
    private final Map<String, ChunkStats> chunkStats = new HashMap<>();
    private BukkitTask monitorTask, chunkTask, pvpTask;
    private List<World> scanWorlds = List.of();
    private int worldIndex;
    private Iterator<Chunk> chunkIterator;
    private long scanGeneration;
    private long nextChunkScanAt;
    private boolean scanActive;
    private int damageEvents, interactionEvents, projectileEvents, potionEvents;

    public record ChunkStats(String world, int x, int z, int entities, int items, int mobs,
                             int projectiles, long generation) {}

    public MonitorService(ServerOptimizerPlugin plugin) { this.plugin = plugin; }

    public void start() {
        long interval = Math.max(2, plugin.getConfig().getLong("monitoring.interval-seconds", 10));
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L, interval * 20L);
        chunkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanChunkBatch, 40L, 1L);
        long pvpInterval = Math.max(10, plugin.getConfig().getLong("pvp.report-interval-seconds", 60)) * 20L;
        pvpTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reportPvpActivity, pvpInterval, pvpInterval);
    }

    public void restart() {
        stop();
        start();
    }

    public void stop() {
        if (monitorTask != null) monitorTask.cancel();
        if (chunkTask != null) chunkTask.cancel();
        if (pvpTask != null) pvpTask.cancel();
    }

    public void countDamage() { damageEvents++; }
    public void countInteraction() { interactionEvents++; }
    public void countProjectile() { projectileEvents++; }
    public void countPotion() { potionEvents++; }
    public List<MetricsSnapshot> history() { return List.copyOf(history); }
    public Collection<ChunkStats> chunks() { return List.copyOf(chunkStats.values()); }
    public void forgetChunk(Chunk chunk) { chunkStats.remove(key(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())); }

    private void sample() {
        Runtime rt = Runtime.getRuntime();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        int loadedChunks = 0;
        for (World world : Bukkit.getWorlds()) loadedChunks += world.getLoadedChunks().length;

        int entities = 0, items = 0, mobs = 0, projectiles = 0;
        for (ChunkStats c : chunkStats.values()) {
            entities += c.entities(); items += c.items(); mobs += c.mobs(); projectiles += c.projectiles();
        }

        double[] tps = readTps();
        double[] cpu = readCpu();
        long gcCount = 0, gcTime = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) gcTime += gc.getCollectionTime();
        }
        MetricsSnapshot s = new MetricsSnapshot(Instant.now(), tps[0], readMspt(),
                rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), Math.max(0, nonHeap.getUsed()),
                Bukkit.getOnlinePlayers().size(), countBedrock(), loadedChunks, entities, items, mobs,
                projectiles, countPendingTasks(), cpu[0], cpu[1], gcCount, gcTime);
        history.addLast(s);
        int max = (int) Math.max(6, plugin.getConfig().getLong("monitoring.history-minutes", 60) * 60
                / Math.max(2, plugin.getConfig().getLong("monitoring.interval-seconds", 10)));
        while (history.size() > max) history.pollFirst();
        plugin.getAlerts().evaluate(s);
    }

    private int countPendingTasks() {
        return Bukkit.getScheduler().getPendingTasks().size();
    }

    private void beginChunkScan() {
        scanWorlds = new ArrayList<>(Bukkit.getWorlds());
        worldIndex = 0;
        chunkIterator = null;
        scanGeneration++;
        scanActive = true;
    }

    private void scanChunkBatch() {
        if (!plugin.getConfig().getBoolean("chunks.monitor", true)) return;
        if (!scanActive) {
            if (System.currentTimeMillis() < nextChunkScanAt) return;
            beginChunkScan();
        }
        int budget = Math.max(1, plugin.getConfig().getInt("monitoring.chunks-per-tick", 6));
        while (budget-- > 0) {
            if (chunkIterator == null || !chunkIterator.hasNext()) {
                if (worldIndex >= scanWorlds.size()) {
                    chunkStats.values().removeIf(c -> c.generation() != scanGeneration);
                    scanActive = false;
                    long delay = Math.max(5, plugin.getConfig().getLong("monitoring.chunk-scan-interval-seconds", 60));
                    nextChunkScanAt = System.currentTimeMillis() + delay * 1000L;
                    return;
                }
                // Snapshot only the currently loaded chunks in this world.
                chunkIterator = Arrays.asList(scanWorlds.get(worldIndex++).getLoadedChunks()).iterator();
                continue;
            }
            Chunk chunk = chunkIterator.next();
            if (!chunk.isLoaded()) continue;
            int entities = 0, items = 0, mobs = 0, projectiles = 0;
            for (Entity entity : chunk.getEntities()) {
                entities++;
                if (entity instanceof Item) items++;
                if (entity instanceof Monster || entity instanceof Animals || entity instanceof WaterMob) mobs++;
                if (entity instanceof Projectile) projectiles++;
            }
            String key = key(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
            chunkStats.put(key, new ChunkStats(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                    entities, items, mobs, projectiles, scanGeneration));
        }
    }

    private void reportPvpActivity() {
        if (plugin.getConfig().getBoolean("pvp.monitor-event-volume", true)) {
            plugin.getLogger().info("Activity counters (last minute): damage=" + damageEvents
                    + ", interactions=" + interactionEvents + ", projectile launches=" + projectileEvents
                    + ", potion splash=" + potionEvents);
        }
        damageEvents = interactionEvents = projectileEvents = potionEvents = 0;
    }

    private int countBedrock() {
        if (!plugin.getConfig().getBoolean("bedrock.detect-with-floodgate", true)
                && !plugin.getConfig().getBoolean("bedrock.detect-with-geyser", true)) return -1;
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean bedrock = false;
            try {
                if (plugin.getConfig().getBoolean("bedrock.detect-with-floodgate", true)
                        && Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                    Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    Object instance = api.getMethod("getInstance").invoke(null);
                    bedrock = (boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, player.getUniqueId());
                }
            } catch (ReflectiveOperationException | LinkageError ignored) { }
            try {
                if (!bedrock && plugin.getConfig().getBoolean("bedrock.detect-with-geyser", true)
                        && Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) {
                    Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                    Object instance = api.getMethod("api").invoke(null);
                    bedrock = (boolean) api.getMethod("isBedrockPlayer", UUID.class).invoke(instance, player.getUniqueId());
                }
            } catch (ReflectiveOperationException | LinkageError ignored) { }
            if (bedrock) count++;
        }
        return count;
    }

    private double[] readTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] values = (double[]) method.invoke(Bukkit.getServer());
            return new double[]{values.length > 0 ? values[0] : -1, values.length > 1 ? values[1] : -1,
                    values.length > 2 ? values[2] : -1};
        } catch (ReflectiveOperationException | LinkageError ex) { return new double[]{-1, -1, -1}; }
    }

    private double readMspt() {
        try { return ((Number) Bukkit.getServer().getClass().getMethod("getAverageTickTime").invoke(Bukkit.getServer())).doubleValue(); }
        catch (ReflectiveOperationException | LinkageError ex) { return -1; }
    }

    private double[] readCpu() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
                double process = extended.getProcessCpuLoad();
                double system = extended.getCpuLoad();
                return new double[]{process < 0 ? -1 : process * 100, system < 0 ? -1 : system * 100};
            }
        } catch (LinkageError | RuntimeException ignored) { }
        return new double[]{-1, -1};
    }

    private static String key(String world, int x, int z) { return world + ':' + x + ':' + z; }
}
