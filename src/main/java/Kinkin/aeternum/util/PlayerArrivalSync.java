package Kinkin.aeternum.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PlayerArrivalSync implements Listener {
    private final Plugin plugin;
    private final Consumer<Player> refresh;
    private final Map<UUID, List<BukkitTask>> pending = new HashMap();

    public PlayerArrivalSync(Plugin plugin, Consumer<Player> refresh) {
        super();
        this.plugin = plugin;
        this.refresh = refresh;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        HandlerList.unregisterAll(this);
        this.pending.values().forEach((tasks) -> tasks.forEach(BukkitTask::cancel));
        this.pending.clear();
    }

    private void cancel(UUID id) {
        List<BukkitTask> tasks = (List)this.pending.remove(id);
        if (tasks != null) {
            tasks.forEach(BukkitTask::cancel);
        }

    }

    private void schedule(Player player) {
        UUID id = player.getUniqueId();
        this.cancel(id);
        List<BukkitTask> tasks = new ArrayList();
        this.pending.put(id, tasks);

        for(long delay : new long[]{1L, 5L, 20L}) {
            tasks.add(Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (this.pending.get(id) == tasks) {
                    if (!player.isOnline()) {
                        this.cancel(id);
                    } else {
                        this.refresh.accept(player);
                        if (delay == 20L) {
                            this.pending.remove(id);
                        }

                    }
                }
            }, delay));
        }

    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        this.schedule(event.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onWorldChange(PlayerChangedWorldEvent event) {
        this.schedule(event.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onRespawn(PlayerRespawnEvent event) {
        this.schedule(event.getPlayer());
    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onQuit(PlayerQuitEvent event) {
        this.cancel(event.getPlayer().getUniqueId());
    }
}
