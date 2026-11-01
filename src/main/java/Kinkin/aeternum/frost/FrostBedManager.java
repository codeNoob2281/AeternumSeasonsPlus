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

public final class FrostBedManager implements Listener {
    private static final String OVERWORLD_NAME = "world";
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private final Map<UUID, Location> overworldBeds = new HashMap();
    private final Map<UUID, Location> frostBeds = new HashMap();
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

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() == BedEnterResult.OK) {
            Player p = e.getPlayer();
            Location bedLoc = e.getBed().getLocation().clone().add((double)0.5F, 0.1, (double)0.5F);
            World w = bedLoc.getWorld();
            if (w != null) {
                String wName = w.getName();
                if (wName.equalsIgnoreCase("world")) {
                    this.overworldBeds.put(p.getUniqueId(), bedLoc);
                } else if (wName.equalsIgnoreCase("aeternum_frost")) {
                    this.frostBeds.put(p.getUniqueId(), bedLoc);
                }

            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        World w = p.getWorld();
        if (w != null) {
            this.lastDeathWorld.put(p.getUniqueId(), w.getName());
        }

    }

    @EventHandler(
        priority = EventPriority.HIGHEST
    )
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String deathWorld = (String)this.lastDeathWorld.remove(id);
        if (deathWorld != null) {
            if (deathWorld.equalsIgnoreCase("aeternum_frost")) {
                Location frost = this.getFrostBed(p);
                if (frost != null) {
                    e.setRespawnLocation(frost);
                }

            } else if (deathWorld.equalsIgnoreCase("world")) {
                Location over = this.getOverworldBed(p);
                if (over != null) {
                    e.setRespawnLocation(over);
                }

            }
        }
    }

    public @Nullable Location getOverworldBed(Player p) {
        return this.validateBed((Location)this.overworldBeds.get(p.getUniqueId()), "world");
    }

    public @Nullable Location getFrostBed(Player p) {
        return this.validateBed((Location)this.frostBeds.get(p.getUniqueId()), "aeternum_frost");
    }

    private @Nullable Location validateBed(@Nullable Location loc, String expectedWorld) {
        if (loc == null) {
            return null;
        } else {
            World w = loc.getWorld();
            if (w != null && w.getName().equalsIgnoreCase(expectedWorld)) {
                Block b = w.getBlockAt(loc);
                return !Tag.BEDS.isTagged(b.getType()) ? null : loc;
            } else {
                return null;
            }
        }
    }
}
