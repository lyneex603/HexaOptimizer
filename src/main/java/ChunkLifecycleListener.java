package dev.serveroptimizer.listener;

import dev.serveroptimizer.ServerOptimizerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

/** Keeps the chunk cache from retaining data for chunks that have unloaded. */
public final class ChunkLifecycleListener implements Listener {
    private final ServerOptimizerPlugin plugin;
    public ChunkLifecycleListener(ServerOptimizerPlugin plugin) { this.plugin = plugin; }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.getMonitor().forgetChunk(event.getChunk());
    }
}
