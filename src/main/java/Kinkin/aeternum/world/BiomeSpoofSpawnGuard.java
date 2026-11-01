package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

public final class BiomeSpoofSpawnGuard implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final BiomeSpoofAdapter spoof;
    private boolean enabled = true;

    public BiomeSpoofSpawnGuard(AeternumSeasonsPlugin plugin, BiomeSpoofAdapter spoof) {
        super();
        this.plugin = plugin;
        this.spoof = spoof;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(
        priority = EventPriority.HIGH,
        ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (this.enabled) {
            CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
            if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN) {
                EntityType type = event.getEntityType();
                if (this.hasBiomeRestrictedNaturalSpawn(type)) {
                    Location location = event.getLocation();
                    World world = location.getWorld();
                    if (world != null && world.getEnvironment() == Environment.NORMAL) {
                        Biome original = this.spoof.getOriginalBiomeApprox(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
                        if (!isOriginalHabitat(type, original)) {
                            event.setCancelled(true);
                        }

                    }
                }
            }
        }
    }

    private boolean hasBiomeRestrictedNaturalSpawn(EntityType type) {
        boolean var10000;
        switch (type) {
            case OCELOT:
            case PARROT:
            case PANDA:
            case MOOSHROOM:
            case POLAR_BEAR:
            case GOAT:
            case FROG:
            case AXOLOTL:
            case TROPICAL_FISH:
            case PUFFERFISH:
            case TURTLE:
            case ARMADILLO:
            case HUSK:
            case STRAY:
            case BOGGED:
            case DROWNED:
                var10000 = true;
                break;
            default:
                var10000 = false;
        }

        return var10000;
    }

    public static boolean isOriginalHabitat(EntityType type, Biome biome) {
        if (biome == null) {
            return false;
        } else {
            String name = biome.name().toUpperCase(Locale.ROOT);
            boolean var10000;
            switch (type) {
                case OCELOT:
                case PARROT:
                case PANDA:
                    var10000 = name.contains("JUNGLE");
                    break;
                case MOOSHROOM:
                    var10000 = name.equals("MUSHROOM_FIELDS");
                    break;
                case POLAR_BEAR:
                    var10000 = name.equals("SNOWY_PLAINS") || name.equals("ICE_SPIKES") || name.contains("FROZEN_OCEAN");
                    break;
                case GOAT:
                    var10000 = name.equals("SNOWY_SLOPES") || name.equals("JAGGED_PEAKS") || name.equals("FROZEN_PEAKS");
                    break;
                case FROG:
                case BOGGED:
                    var10000 = name.contains("SWAMP") || name.contains("MANGROVE");
                    break;
                case AXOLOTL:
                    var10000 = name.equals("LUSH_CAVES");
                    break;
                case TROPICAL_FISH:
                    var10000 = name.equals("LUSH_CAVES") || name.contains("WARM_OCEAN") || name.contains("LUKEWARM_OCEAN");
                    break;
                case PUFFERFISH:
                    var10000 = name.contains("WARM_OCEAN") || name.contains("LUKEWARM_OCEAN");
                    break;
                case TURTLE:
                    var10000 = name.contains("BEACH");
                    break;
                case ARMADILLO:
                    var10000 = name.contains("SAVANNA") || name.contains("BADLANDS");
                    break;
                case HUSK:
                    var10000 = name.contains("DESERT");
                    break;
                case STRAY:
                    var10000 = name.contains("SNOW") || name.contains("FROZEN") || name.equals("ICE_SPIKES") || name.equals("GROVE");
                    break;
                case DROWNED:
                    var10000 = name.contains("OCEAN") || name.contains("RIVER");
                    break;
                default:
                    var10000 = true;
            }

            return var10000;
        }
    }
}
