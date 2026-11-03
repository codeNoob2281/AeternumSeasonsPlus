package kinkin.aeternum.util;

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

import java.util.*;
import java.util.function.Consumer;

/**
 * 玩家到达同步器。
 *
 * <p>在玩家传送、切换世界或重生后，按固定延迟序列（1、5、20 tick）多次触发回调，
 * 让 HUD 等界面在客户端真正到位后重新刷新，避免因时机过早导致显示错乱。</p>
 *
 * <p>同一玩家的旧刷新任务会在新事件到来时被取消；玩家下线时也会清理。</p>
 */
public final class PlayerArrivalSync implements Listener {
    private final Plugin plugin;
    /**
     * 到达后触发的刷新回调。
     */
    private final Consumer<Player> refresh;
    /**
     * 每个玩家尚未执行完的刷新任务。
     */
    private final Map<UUID, List<BukkitTask>> pending = new HashMap();

    public PlayerArrivalSync(Plugin plugin, Consumer<Player> refresh) {
        super();
        this.plugin = plugin;
        this.refresh = refresh;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 注销监听器并取消所有待执行的刷新任务。
     */
    public void close() {
        HandlerList.unregisterAll(this);
        this.pending.values().forEach((tasks) -> tasks.forEach(BukkitTask::cancel));
        this.pending.clear();
    }

    /**
     * 取消指定玩家待执行的刷新任务。
     */
    private void cancel(UUID id) {
        List<BukkitTask> tasks = (List) this.pending.remove(id);
        if (tasks != null) {
            tasks.forEach(BukkitTask::cancel);
        }

    }

    /**
     * 为玩家重新安排 1/5/20 tick 三次延迟刷新。
     */
    private void schedule(Player player) {
        UUID id = player.getUniqueId();
        this.cancel(id);
        List<BukkitTask> tasks = new ArrayList();
        this.pending.put(id, tasks);

        for (long delay : new long[]{1L, 5L, 20L}) {
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

    /**
     * 玩家传送后重新同步。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        this.schedule(event.getPlayer());
    }

    /**
     * 玩家切换世界后重新同步。
     */
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onWorldChange(PlayerChangedWorldEvent event) {
        this.schedule(event.getPlayer());
    }

    /**
     * 玩家重生后重新同步。
     */
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onRespawn(PlayerRespawnEvent event) {
        this.schedule(event.getPlayer());
    }

    /**
     * 玩家下线时取消其待执行的刷新任务。
     */
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onQuit(PlayerQuitEvent event) {
        this.cancel(event.getPlayer().getUniqueId());
    }
}
