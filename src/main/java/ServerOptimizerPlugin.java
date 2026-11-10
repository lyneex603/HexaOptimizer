package dev.serveroptimizer;

import java.util.Iterator;
import java.util.Arrays;
import dev.serveroptimizer.alert.AlertManager;
import dev.serveroptimizer.alert.DiscordNotifier;
import dev.serveroptimizer.command.OptimizerCommand;
import dev.serveroptimizer.listener.ChunkLifecycleListener;
import dev.serveroptimizer.listener.PvpMonitorListener;
import dev.serveroptimizer.monitor.MonitorService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class ServerOptimizerPlugin extends JavaPlugin {
    private MonitorService monitor;
    private AlertManager alerts;
    private DiscordNotifier discord;
    private BukkitTask cleanupTask, itemSweepTask;
    private long nextCleanupAt;
    private final Set<Integer> sentWarnings = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        discord = new DiscordNotifier(this);
        alerts = new AlertManager(this);
        monitor = new MonitorService(this);
        monitor.start();
        Bukkit.getPluginManager().registerEvents(new PvpMonitorListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChunkLifecycleListener(this), this);
        OptimizerCommand command = new OptimizerCommand(this);
        if (getCommand("so") != null) {
            getCommand("so").setExecutor(command);
            getCommand("so").setTabCompleter(command);
        }
        scheduleCleanup();
        getLogger().info("ServerOptimizer enabled in " + getConfig().getString("mode", "MONITOR")
                + " mode. PvP/gameplay mechanics are not modified.");
    }

    @Override public void onDisable() {
        if (monitor != null) monitor.stop();
        if (cleanupTask != null) cleanupTask.cancel();
        if (itemSweepTask != null) itemSweepTask.cancel();
        if (discord != null) discord.shutdown();
        HandlerList.unregisterAll(this);
    }

    public MonitorService getMonitor() { return monitor; }
    public AlertManager getAlerts() { return alerts; }
    public DiscordNotifier getDiscord() { return discord; }

    public void reloadPlugin() {
        reloadConfig();
        alerts.clearCooldowns();
        if (monitor != null) monitor.restart();
        scheduleCleanup();
    }

    public boolean startItemCleaner() {
        if (!getConfig().getString("mode", "MONITOR").equalsIgnoreCase("AUTOMATIC")
                || !getConfig().getBoolean("automatic.enabled", false)
                || !getConfig().getBoolean("automatic.allow-item-cleanup", false)) return false;
        getConfig().set("entities.item-cleaner.enabled", true);
        saveConfig();
        audit("Item cleaner enabled by command.");
        scheduleCleanup();
        return cleanupTask != null;
    }

    public void stopItemCleaner() {
        getConfig().set("entities.item-cleaner.enabled", false);
        saveConfig();
        if (cleanupTask != null) cleanupTask.cancel();
        cleanupTask = null;
        if (itemSweepTask != null) itemSweepTask.cancel();
        itemSweepTask = null;
        sentWarnings.clear();
        audit("Item cleaner stopped by command.");
    }

    public void scheduleCleanup() {
        if (cleanupTask != null) cleanupTask.cancel();
        if (itemSweepTask != null) itemSweepTask.cancel();
        itemSweepTask = null;
        cleanupTask = null;
        sentWarnings.clear();
        if (!getConfig().getString("mode", "MONITOR").equalsIgnoreCase("AUTOMATIC")
                || !getConfig().getBoolean("automatic.enabled", false)
                || !getConfig().getBoolean("entities.item-cleaner.enabled", false)
                || !getConfig().getBoolean("automatic.allow-item-cleanup", false)) return;
        long period = Math.max(1, getConfig().getLong("entities.item-cleaner.interval-minutes", 60)) * 60_000L;
        nextCleanupAt = System.currentTimeMillis() + period;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long remaining = nextCleanupAt - System.currentTimeMillis();
            for (int seconds : getConfig().getIntegerList("entities.item-cleaner.warnings-seconds")) {
                if (seconds > 0 && remaining <= seconds * 1000L && sentWarnings.add(seconds))
                    broadcast("Очистка выброшенных предметов через " + seconds + " сек.");
            }
            if (remaining <= 0) {
                cleanLoadedItems();
                nextCleanupAt = System.currentTimeMillis() + period;
                sentWarnings.clear();
            }
        }, 20L, 20L);
    }

    private void cleanLoadedItems() {
        if (itemSweepTask != null) itemSweepTask.cancel();
        List<Chunk> queue = new ArrayList<>();
        List<String> worlds = getConfig().getStringList("entities.item-cleaner.worlds");
        for (World world : Bukkit.getWorlds()) {
            if (!worlds.isEmpty() && !worlds.contains(world.getName())) continue;
            Collections.addAll(queue, world.getLoadedChunks());
        }
        final int[] index = {0};
        final int[] removed = {0};
        final int[] visited = {0};
        final Iterator<Entity>[] entityIterator = new Iterator[]{null};
        final int entitiesPerTick = Math.max(25, getConfig().getInt("entities.item-cleaner.entities-per-tick", 200));
        final int minimumAge = Math.max(0, getConfig().getInt("entities.item-cleaner.minimum-age-ticks", 6000));
        final Set<String> protectedMaterials = new HashSet<>(getConfig().getStringList("entities.item-cleaner.protected-materials"));
        itemSweepTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int budget = entitiesPerTick;
            while (budget > 0) {
                if (entityIterator[0] == null || !entityIterator[0].hasNext()) {
                    entityIterator[0] = null;
                    if (index[0] >= queue.size()) break;
                    Chunk chunk = queue.get(index[0]++);
                    if (!chunk.isLoaded()) continue;
                    entityIterator[0] = Arrays.asList(chunk.getEntities()).iterator();
                    continue;
                }
                Entity entity = entityIterator[0].next();
                budget--;
                visited[0]++;
                if (!(entity instanceof Item item)) continue;
                if (item.getTicksLived() < minimumAge) continue;
                if (protectedMaterials.contains(item.getItemStack().getType().name())) continue;
                if (!getConfig().getBoolean("entities.item-cleaner.include-named-items", false)
                        && item.getCustomName() != null) continue;
                if (!getConfig().getBoolean("entities.item-cleaner.include-items-with-lore", false)
                        && item.getItemStack().hasItemMeta() && item.getItemStack().getItemMeta().hasLore()) continue;
                if (getConfig().getBoolean("entities.item-cleaner.protect-all-item-meta", true)
                        && item.getItemStack().hasItemMeta()) continue;
                item.remove();
                removed[0]++;
            }
            if (index[0] >= queue.size() && (entityIterator[0] == null || !entityIterator[0].hasNext())) {
                itemSweepTask.cancel();
                itemSweepTask = null;
                broadcast("Очистка завершена. Удалено предметов: " + removed[0]);
                getLogger().info("Item cleanup removed " + removed[0] + " dropped item entities.");
                audit("Automatic item cleanup visited " + visited[0] + " entities and removed " + removed[0] + " dropped item entities.");
            }
        }, 1L, 1L);
    }

    public void audit(String message) {
        if (!getConfig().getBoolean("logging.file", true)) return;
        String line = "[" + Instant.now() + "] " + message + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(getDataFolder().toPath());
                Path log = getDataFolder().toPath().resolve("actions.log");
                Path rotated = getDataFolder().toPath().resolve("actions.log.1");
                if (Files.exists(log) && Files.size(log) > 2L * 1024L * 1024L) {
                    Files.move(log, rotated, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(log, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (Exception ex) {
                getLogger().warning("Could not write actions.log: " + ex.getClass().getSimpleName());
            }
        });
    }

    private void broadcast(String message) { Bukkit.broadcastMessage("§6[ServerOptimizer] §e" + message); }
}
