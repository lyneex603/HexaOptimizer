package dev.serveroptimizer.listener;

import dev.serveroptimizer.ServerOptimizerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** Observes event volume only. It never edits/cancels gameplay events. */
public final class PvpMonitorListener implements Listener {
    private final ServerOptimizerPlugin plugin;
    public PvpMonitorListener(ServerOptimizerPlugin plugin) { this.plugin = plugin; }
    @EventHandler(ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent event) { plugin.getMonitor().countDamage(); }
    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent event) { plugin.getMonitor().countInteraction(); }
    @EventHandler(ignoreCancelled = true) public void onProjectile(ProjectileLaunchEvent event) { plugin.getMonitor().countProjectile(); }
    @EventHandler(ignoreCancelled = true) public void onPotion(PotionSplashEvent event) { plugin.getMonitor().countPotion(); }
}
