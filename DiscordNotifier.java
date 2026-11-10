package dev.serveroptimizer.alert;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Webhook I/O runs on a dedicated daemon executor, never on the server tick thread. */
public final class DiscordNotifier {
    private final JavaPlugin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerOptimizer-Discord"); t.setDaemon(true); return t;
    });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .executor(executor).build();
    private final AtomicLong lastSent = new AtomicLong();

    public DiscordNotifier(JavaPlugin plugin) { this.plugin = plugin; }

    public void send(String message) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        String url = plugin.getConfig().getString("discord.webhook-url", "").trim();
        if (!url.startsWith("https://discord.com/api/webhooks/")
                && !url.startsWith("https://discordapp.com/api/webhooks/")) return;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("discord.cooldown-seconds", 300)) * 1000L;
        long previous = lastSent.get();
        if (now - previous < cooldown || !lastSent.compareAndSet(previous, now)) return;
        String payload = "{\"content\":\"" + escape(message) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null) plugin.getLogger().warning("Discord webhook failed: " + error.getClass().getSimpleName());
                else if (response.statusCode() >= 300) plugin.getLogger().warning("Discord webhook HTTP " + response.statusCode());
            });
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Discord webhook URL is invalid.");
        }
    }

    public void shutdown() { executor.shutdownNow(); }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
