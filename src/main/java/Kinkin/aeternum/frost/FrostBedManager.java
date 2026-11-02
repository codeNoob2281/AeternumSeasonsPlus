package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult;
import org.jetbrains.annotations.Nullable;

/**
 * 永霜世界床位与重生点管理器。
 *
 * <p>由于永霜世界（{@code aeternum_frost}）与主世界（{@code world}）共享同一套床机制，
 * 本管理器分别记录玩家在这两个世界中使用过的床位置。玩家死亡后按其死亡所在世界
 * 选择对应的床作为重生点（并校验床上仍是床方块），从而实现跨维度的床重生。</p>
 *
 * <p>依赖 {@link AeternumSeasonsPlugin} 完成事件注册。</p>
 */
public final class FrostBedManager implements Listener {
    private static final String OVERWORLD_NAME = "world";
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    /** 玩家在主世界记录过的床位置。 */
    private final Map<UUID, Location> overworldBeds = new HashMap();
    /** 玩家在永霜世界记录过的床位置。 */
    private final Map<UUID, Location> frostBeds = new HashMap();
    /** 记录玩家最近一次死亡所在的世界名称。 */
    private final Map<UUID, String> lastDeathWorld = new HashMap();

    public FrostBedManager(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        this.overworldBeds.clear();
        this.frostBeds.clear();
        this.lastDeathWorld.clear();
    }

    /**
     * 玩家成功上床时，按所在世界记录床的位置（主世界或永霜世界）。
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == BedEnterResult.OK) {
            Player player = event.getPlayer();
            Location bedLoc = event.getBed().getLocation().clone().add((double)0.5F, 0.1, (double)0.5F);
            World world = bedLoc.getWorld();
            if (world != null) {
                String worldName = world.getName();
                if (worldName.equalsIgnoreCase("world")) {
                    this.overworldBeds.put(player.getUniqueId(), bedLoc);
                } else if (worldName.equalsIgnoreCase("aeternum_frost")) {
                    this.frostBeds.put(player.getUniqueId(), bedLoc);
                }

            }
        }
    }

    /**
     * 记录玩家死亡时所在的世界，供重生时选择对应床。
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        World world = player.getWorld();
        if (world != null) {
            this.lastDeathWorld.put(player.getUniqueId(), world.getName());
        }

    }

    /**
     * 玩家重生时，若死亡世界为永霜世界或主世界，则将其重生点指向对应世界记录过的床。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST
    )
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String deathWorld = (String)this.lastDeathWorld.remove(playerId);
        if (deathWorld != null) {
            if (deathWorld.equalsIgnoreCase("aeternum_frost")) {
                Location frostBed = this.getFrostBed(player);
                if (frostBed != null) {
                    event.setRespawnLocation(frostBed);
                }

            } else if (deathWorld.equalsIgnoreCase("world")) {
                Location overworldBed = this.getOverworldBed(player);
                if (overworldBed != null) {
                    event.setRespawnLocation(overworldBed);
                }

            }
        }
    }

    /**
     * 获取玩家在主世界记录的有效床位置，无效时返回 null。
     */
    public @Nullable Location getOverworldBed(Player player) {
        return this.validateBed((Location)this.overworldBeds.get(player.getUniqueId()), "world");
    }

    /**
     * 获取玩家在永霜世界记录的有效床位置，无效时返回 null。
     */
    public @Nullable Location getFrostBed(Player player) {
        return this.validateBed((Location)this.frostBeds.get(player.getUniqueId()), "aeternum_frost");
    }

    /**
     * 校验记录的位置仍指向期望世界中的床方块，否则返回 null。
     */
    private @Nullable Location validateBed(@Nullable Location loc, String expectedWorld) {
        if (loc == null) {
            return null;
        } else {
            World world = loc.getWorld();
            if (world != null && world.getName().equalsIgnoreCase(expectedWorld)) {
                Block block = world.getBlockAt(loc);
                return !Tag.BEDS.isTagged(block.getType()) ? null : loc;
            } else {
                return null;
            }
        }
    }
}
