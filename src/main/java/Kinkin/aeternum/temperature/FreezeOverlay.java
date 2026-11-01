package Kinkin.aeternum.temperature;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class FreezeOverlay {
    private final Map<UUID, Held> held;
    private final Method lock;
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

    private static Method find(String name, Class<?>... args) {
        try {
            return Entity.class.getMethod(name, args);
        } catch (NoSuchMethodException var3) {
            return null;
        }
    }

    void hold(Player player, int increase) {
        Held previous = (Held)this.held.get(player.getUniqueId());
        int prior = previous == null ? player.getFreezeTicks() : Math.max(previous.level(), player.getFreezeTicks());
        int level = (int)Math.min((long)player.getMaxFreezeTicks(), (long)prior + (long)increase);
        boolean ownsLock = previous != null && previous.ownsLock();
        if (!ownsLock && this.lock != null && this.locked != null) {
            try {
                if (!Boolean.TRUE.equals(this.locked.invoke(player))) {
                    this.lock.invoke(player, true);
                    ownsLock = true;
                }
            } catch (ReflectiveOperationException var8) {
            }
        }

        player.setFreezeTicks(level);
        this.held.put(player.getUniqueId(), new Held(player, player.getWorld().getUID(), level, ownsLock));
    }

    void release(Player player) {
        Held previous = (Held)this.held.remove(player.getUniqueId());
        if (previous != null && previous.ownsLock()) {
            try {
                this.lock.invoke(player, false);
            } catch (ReflectiveOperationException var4) {
            }
        }

    }

    void tick() {
        for(Held entry : List.copyOf(this.held.values())) {
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

    void clear() {
        for(Held entry : List.copyOf(this.held.values())) {
            this.release(entry.player());
        }

    }

    private static record Held(Player player, UUID world, int level, boolean ownsLock) {
        private Held {
        }
    }
}
