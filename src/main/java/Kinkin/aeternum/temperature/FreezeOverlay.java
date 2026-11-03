package kinkin.aeternum.temperature;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冻结遮罩管理器。
 *
 * <p>通过反射调用 {@link Entity} 上可能存在的 {@code lockFreezeTicks}/{@code isFreezeTickingLocked}
 * 方法，在跨版本环境下“锁住”玩家的冻结 tick，避免原版逻辑把自定义冻结值提前消退。
 * 它会记录每个玩家被抬升到的冻结等级以及是否由自己持有锁，并在释放或 tick 校验时
 * 恢复/清理状态。若对应方法不存在则退化为仅设置冻结 tick。</p>
 */
final class FreezeOverlay {
    /**
     * 记录当前受控玩家及其冻结状态。
     */
    private final Map<UUID, Held> held;
    /**
     * 反射得到的“锁定冻结 tick”方法，可能为 null。
     */
    private final Method lock;
    /**
     * 反射得到的“是否已锁定冻结 tick”查询方法，可能为 null。
     */
    private final Method locked;

    FreezeOverlay() {
        this(find("lockFreezeTicks", Boolean.TYPE), find("isFreezeTickingLocked"));
    }

    FreezeOverlay(Method lock, Method locked) {
        super();
        this.held = new HashMap();
        this.lock = lock;
        this.locked = locked;
    }

    /**
     * 反射查找指定名称的方法，找不到时返回 null（兼容不同服务端版本）。
     */
    private static Method find(String name, Class<?>... args) {
        try {
            return Entity.class.getMethod(name, args);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /**
     * 将玩家的冻结 tick 抬升指定量（不超过最大冻结值），必要时尝试获取原版冻结锁。
     */
    void hold(Player player, int increase) {
        Held previous = (Held) this.held.get(player.getUniqueId());
        int prior = previous == null ? player.getFreezeTicks() : Math.max(previous.level(), player.getFreezeTicks());
        int level = (int) Math.min((long) player.getMaxFreezeTicks(), (long) prior + (long) increase);
        boolean ownsLock = previous != null && previous.ownsLock();
        if (!ownsLock && this.lock != null && this.locked != null) {
            try {
                if (!Boolean.TRUE.equals(this.locked.invoke(player))) {
                    this.lock.invoke(player, true);
                    ownsLock = true;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        player.setFreezeTicks(level);
        this.held.put(player.getUniqueId(), new Held(player, player.getWorld().getUID(), level, ownsLock));
    }

    /**
     * 释放对玩家的冻结控制：若当前持有冻结锁则解锁，并移除记录。
     */
    void release(Player player) {
        Held previous = (Held) this.held.remove(player.getUniqueId());
        if (previous != null && previous.ownsLock()) {
            try {
                this.lock.invoke(player, false);
            } catch (ReflectiveOperationException ignored) {
            }
        }

    }

    /**
     * 每个 tick 校验受控玩家：离线/死亡/创造旁观/切世界或冻结自然归零时释放，
     * 未持锁但冻结值被外部降低时重新抬升回目标等级。
     */
    void tick() {
        for (Held entry : List.copyOf(this.held.values())) {
            Player player = entry.player();
            if (player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR && entry.world().equals(player.getWorld().getUID())) {
                int current = player.getFreezeTicks();
                if (current == 0) {
                    this.release(player);
                } else if (!entry.ownsLock() && current < entry.level()) {
                    player.setFreezeTicks(entry.level());
                }
            } else {
                this.release(player);
            }
        }

    }

    /**
     * 释放并清空所有当前受控玩家。
     */
    void clear() {
        for (Held entry : List.copyOf(this.held.values())) {
            this.release(entry.player());
        }

    }

    /**
     * 记录单个玩家当前的冻结掌控状态。
     *
     * @param player   受控玩家
     * @param world    所在世界 UUID（用于检测换维度）
     * @param level    维持的冻结 tick 等级
     * @param ownsLock 是否由本遮罩持有原版冻结锁
     */
    private record Held(Player player, UUID world, int level, boolean ownsLock) {
        private Held {
        }
    }
}
