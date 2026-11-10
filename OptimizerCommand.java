package dev.serveroptimizer.command;

import dev.serveroptimizer.ServerOptimizerPlugin;
import dev.serveroptimizer.monitor.MetricsSnapshot;
import dev.serveroptimizer.monitor.MonitorService;
import org.bukkit.Bukkit;
import org.bukkit.command.*;

import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public final class OptimizerCommand implements TabExecutor {
    private final ServerOptimizerPlugin plugin;
    public OptimizerCommand(ServerOptimizerPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!allowed(sender, "serveroptimizer.reload")) return true;
            plugin.reloadPlugin(); sender.sendMessage("§aServerOptimizer config reloaded."); return true;
        }
        if (sub.equals("mode")) {
            if (!allowed(sender, "serveroptimizer.mode")) return true;
            if (args.length < 2 || !List.of("monitor", "recommend", "automatic").contains(args[1].toLowerCase(Locale.ROOT))) {
                sender.sendMessage("§eИспользование: /so mode <monitor|recommend|automatic>"); return true;
            }
            String mode = args[1].toUpperCase(Locale.ROOT);
            plugin.getConfig().set("mode", mode); plugin.saveConfig(); plugin.scheduleCleanup();
            plugin.audit("Optimization mode changed to " + mode + ".");
            sender.sendMessage("§aРежим установлен: " + mode + ". Автодействия требуют automatic.enabled и отдельного разрешения в config.yml.");
            return true;
        }
        if (sub.equals("itemcleaner")) {
            if (!allowed(sender, "serveroptimizer.cleaner")) return true;
            if (args.length < 2) { sender.sendMessage("§eИспользование: /so itemcleaner <start|stop>"); return true; }
            if (args[1].equalsIgnoreCase("start")) {
                if (plugin.startItemCleaner()) sender.sendMessage("§aОчистка предметов включена.");
                else sender.sendMessage("§cНе включено. Нужны mode: AUTOMATIC, automatic.enabled: true и automatic.allow-item-cleanup: true в config.yml.");
            } else if (args[1].equalsIgnoreCase("stop")) {
                plugin.stopItemCleaner(); sender.sendMessage("§aОчистка предметов остановлена.");
            } else sender.sendMessage("§eИспользование: /so itemcleaner <start|stop>");
            return true;
        }
        if (!allowed(sender, "serveroptimizer.status")) return true;
        MetricsSnapshot snapshot = latest();
        switch (sub) {
            case "status", "performance" -> report(sender, snapshot, false);
            case "report" -> report(sender, snapshot, true);
            case "tps" -> sender.sendMessage("§eTPS (1m): " + fmt(snapshot.tps1m()) + " | MSPT: " + fmt(snapshot.mspt()));
            case "mspt" -> sender.sendMessage("§eMSPT: " + fmt(snapshot.mspt()) + " ms");
            case "memory" -> memory(sender, snapshot);
            case "entities" -> entities(sender, snapshot);
            case "chunks" -> chunks(sender);
            case "alerts" -> sender.sendMessage("§eAlerts: " + (plugin.getConfig().getBoolean("alerts.enabled", true) ? "enabled" : "disabled")
                    + " | cooldown=" + plugin.getConfig().getLong("alerts.cooldown-seconds", 120) + "s");
            case "recommendations" -> recommendations(sender, snapshot);
            case "history" -> history(sender);
            default -> help(sender);
        }
        return true;
    }

    private MetricsSnapshot latest() {
        List<MetricsSnapshot> values = plugin.getMonitor().history();
        if (!values.isEmpty()) return values.get(values.size() - 1);
        Runtime runtime = Runtime.getRuntime();
        return new MetricsSnapshot(java.time.Instant.now(), -1, -1, runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(), ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
                Bukkit.getOnlinePlayers().size(), -1, 0, 0, 0, 0, 0,
                Bukkit.getScheduler().getPendingTasks().size(), -1, -1, 0, 0);
    }

    private void report(CommandSender sender, MetricsSnapshot s, boolean detailed) {
        sender.sendMessage("§6§lServerOptimizer §7[" + plugin.getConfig().getString("mode", "MONITOR") + "]");
        sender.sendMessage("§eTPS: " + fmt(s.tps1m()) + " | MSPT: " + fmt(s.mspt()) + " | Players: " + s.players()
                + (s.bedrockPlayers() >= 0 ? " (Bedrock: " + s.bedrockPlayers() + ")" : ""));
        sender.sendMessage("§eHeap: " + mb(s.heapUsed()) + "/" + mb(s.heapMax()) + " MB (" + fmt(s.memoryPercent())
                + "%) | Non-Heap: " + mb(s.nonHeapUsed()) + " MB");
        sender.sendMessage("§eChunks: " + s.loadedChunks() + " | Entities: " + s.entities() + " | Items: " + s.groundItems()
                + " | Mobs: " + s.mobs() + " | Projectiles: " + s.projectiles());
        sender.sendMessage("§ePending scheduler tasks: " + s.activeTasks() + " | CPU process/system: " + fmt(s.processCpu()) + "% / "
                + fmt(s.systemCpu()) + "%");
        if (detailed) {
            sender.sendMessage("§7GC total: " + s.gcCount() + " collections / " + s.gcTimeMs() + " ms");
            sender.sendMessage("§7Snapshot: " + s.time());
            sender.sendMessage("§7Chunk/entity totals use the latest incremental scan and may lag behind live state.");
        }
    }

    private void memory(CommandSender sender, MetricsSnapshot s) {
        sender.sendMessage("§eHeap: " + mb(s.heapUsed()) + "/" + mb(s.heapMax()) + " MB (" + fmt(s.memoryPercent()) + "%)");
        sender.sendMessage("§eNon-Heap used: " + mb(s.nonHeapUsed()) + " MB | GC: " + s.gcCount() + " runs / " + s.gcTimeMs() + " ms");
    }

    private void entities(CommandSender sender, MetricsSnapshot s) {
        sender.sendMessage("§eEntities: " + s.entities() + " | Items: " + s.groundItems() + " | Mobs: " + s.mobs()
                + " | Projectiles: " + s.projectiles());
        sender.sendMessage("§7Counts come from the latest incremental scan of loaded chunks.");
    }

    private void chunks(CommandSender sender) {
        List<MonitorService.ChunkStats> list = plugin.getMonitor().chunks().stream()
                .sorted(Comparator.comparingInt(MonitorService.ChunkStats::entities).reversed())
                .limit(Math.max(1, plugin.getConfig().getInt("chunks.report-top", 10))).toList();
        sender.sendMessage("§6Top loaded chunks by entity count:");
        if (list.isEmpty()) sender.sendMessage("§7Сканирование ещё не собрало данные.");
        for (var c : list) sender.sendMessage("§e" + c.world() + " [" + c.x() + ", " + c.z() + "] §7entities="
                + c.entities() + ", items=" + c.items() + ", mobs=" + c.mobs() + ", projectiles=" + c.projectiles());
    }

    private void history(CommandSender sender) {
        List<MetricsSnapshot> list = plugin.getMonitor().history();
        if (list.isEmpty()) { sender.sendMessage("§7История пока пуста."); return; }
        sender.sendMessage("§6Последние измерения (время сервера):");
        DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        int start = Math.max(0, list.size() - 10);
        for (int i = start; i < list.size(); i++) {
            MetricsSnapshot s = list.get(i);
            sender.sendMessage("§7" + format.format(s.time()) + " §eTPS=" + fmt(s.tps1m()) + " MSPT=" + fmt(s.mspt())
                    + " RAM=" + fmt(s.memoryPercent()) + "% entities=" + s.entities());
        }
    }

    private void recommendations(CommandSender sender, MetricsSnapshot s) {
        sender.sendMessage("§6Рекомендации (эвристика, не доказательство причины):");
        int count = 0;
        if (s.tps1m() >= 0 && s.tps1m() < plugin.getConfig().getDouble("thresholds.min-tps", 18)) {
            sender.sendMessage("§e• TPS снижен: сними spark-профиль во время нагрузки."); count++;
        }
        if (s.mspt() > plugin.getConfig().getDouble("thresholds.max-mspt", 50)) {
            sender.sendMessage("§e• MSPT высок: проверь spark-профиль и горячие участки тиков."); count++;
        }
        if (s.groundItems() > plugin.getConfig().getInt("thresholds.max-ground-items", 250)) {
            sender.sendMessage("§e• Много предметов на земле: проверь места скопления; очистка выключена по умолчанию."); count++;
        }
        if (s.memoryPercent() > plugin.getConfig().getDouble("thresholds.max-memory-percent", 90)) {
            sender.sendMessage("§e• Heap близок к лимиту: проверь GC-логи и удержание объектов."); count++;
        }
        if (count == 0) sender.sendMessage("§aПо текущим порогам критичных признаков не обнаружено.");
        sender.sendMessage("§7Точная атрибуция CPU/плагинов/сети требует внешнего профилировщика.");
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6ServerOptimizer commands:");
        sender.sendMessage("§e/so help, status, performance, tps, mspt, memory, entities, chunks, alerts");
        sender.sendMessage("§e/so recommendations, history, report, reload, mode <monitor|recommend|automatic>");
        sender.sendMessage("§e/so itemcleaner <start|stop>");
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission("serveroptimizer.admin") || sender.hasPermission(permission)) return true;
        sender.sendMessage("§cНедостаточно прав."); return false;
    }
    private String mb(long bytes) { return String.format(Locale.US, "%.1f", bytes / 1048576.0); }
    private String fmt(double value) { return value < 0 ? "n/a" : String.format(Locale.US, "%.2f", value); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("help", "status", "performance", "tps", "mspt", "memory", "entities", "chunks",
                "alerts", "recommendations", "history", "report", "reload", "mode", "itemcleaner").stream()
                .filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return List.of("monitor", "recommend", "automatic");
        if (args.length == 2 && args[0].equalsIgnoreCase("itemcleaner")) return List.of("start", "stop");
        return Collections.emptyList();
    }
}
