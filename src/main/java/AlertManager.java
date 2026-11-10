package dev.serveroptimizer.alert;

import dev.serveroptimizer.ServerOptimizerPlugin;
import dev.serveroptimizer.monitor.MetricsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public final class AlertManager {
    private final ServerOptimizerPlugin plugin;
    private final Map<String, Long> last = new HashMap<>();
    public AlertManager(ServerOptimizerPlugin plugin) { this.plugin = plugin; }
    public void clearCooldowns() { last.clear(); }

    public void evaluate(MetricsSnapshot snapshot) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;
        if (snapshot.tps1m() >= 0 && snapshot.tps1m() < plugin.getConfig().getDouble("thresholds.min-tps", 18)
                && plugin.getConfig().getBoolean("alerts.tps", true))
            alert("tps", String.format(java.util.Locale.US, "TPS низкий: %.2f", snapshot.tps1m()), true);
        if (snapshot.mspt() >= 0 && snapshot.mspt() > plugin.getConfig().getDouble("thresholds.max-mspt", 50)
                && plugin.getConfig().getBoolean("alerts.mspt", true))
            alert("mspt", String.format(java.util.Locale.US, "MSPT высокий: %.1f ms", snapshot.mspt()), true);
        if (snapshot.memoryPercent() > plugin.getConfig().getDouble("thresholds.max-memory-percent", 90)
                && plugin.getConfig().getBoolean("alerts.memory", true))
            alert("memory", String.format(java.util.Locale.US, "Heap memory использовано %.1f%%", snapshot.memoryPercent()), true);
        if (snapshot.groundItems() > plugin.getConfig().getInt("thresholds.max-ground-items", 250)
                && plugin.getConfig().getBoolean("alerts.entities", true))
            alert("items", "Много предметов на земле: " + snapshot.groundItems(), false);
        if (snapshot.projectiles() > plugin.getConfig().getInt("thresholds.max-projectiles", 300)
                && plugin.getConfig().getBoolean("alerts.entities", true))
            alert("projectiles", "Много снарядов/временных объектов: " + snapshot.projectiles(), false);
        plugin.getMonitor().chunks().stream()
                .filter(c -> c.entities() > plugin.getConfig().getInt("thresholds.max-entities-per-chunk", 80)
                        || c.mobs() > plugin.getConfig().getInt("thresholds.max-mobs-per-chunk", 40))
                .limit(2).forEach(c -> alert("chunk:" + c.world() + ':' + c.x() + ':' + c.z(),
                        "Подозрительно нагруженный чанк " + c.world() + " [" + c.x() + ", " + c.z()
                                + "]: entities=" + c.entities() + ", mobs=" + c.mobs(), false));
    }

    public void alert(String key, String message, boolean critical) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("alerts.cooldown-seconds", 120)) * 1000L;
        if (now - last.getOrDefault(key, 0L) < cooldown) return;
        last.put(key, now);
        String line = "[ServerOptimizer] " + message;
        plugin.audit("Alert: " + message);
        if (plugin.getConfig().getBoolean("alerts.console", true)) plugin.getLogger().warning(message);
        if (plugin.getConfig().getBoolean("alerts.admins", true))
            for (Player player : Bukkit.getOnlinePlayers()) if (player.hasPermission("serveroptimizer.alerts")) player.sendMessage("§c" + line);
        if (critical || !plugin.getConfig().getBoolean("discord.send-critical-only", true)) plugin.getDiscord().send(line);
    }
}
