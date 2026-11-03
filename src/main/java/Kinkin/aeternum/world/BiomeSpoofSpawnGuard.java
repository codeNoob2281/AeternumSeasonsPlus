package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
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

import java.util.Locale;

/**
 * 生物群系伪装下的自然生成守卫（BiomeSpoofSpawnGuard）。
 *
 * <p>生物群系伪装会让服务端看到被替换后的群系，可能使本不该出现的生物（如丛林豹猫、雪原北极熊等）
 * 在自然生成时出现在错误环境。本监听器在主世界自然/区块生成生物时，查询该位置的“原始真实群系”，
 * 若该生物不属于原始群系的栖息地，则取消这次生成，从而让伪装只影响视觉而不破坏生态生成。</p>
 *
 * <p>依赖 {@link BiomeSpoofAdapter} 提供的原始群系近似查询，以及 Bukkit 事件系统。</p>
 */
public final class BiomeSpoofSpawnGuard implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final BiomeSpoofAdapter spoof;
    private boolean enabled = true;

    public BiomeSpoofSpawnGuard(AeternumSeasonsPlugin plugin, BiomeSpoofAdapter spoof) {
        super();
        this.plugin = plugin;
        this.spoof = spoof;
    }

    /**
     * 启用或停用本守卫。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 向 Bukkit 注册本监听器。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 从 Bukkit 注销本监听器。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 监听生物生成：对受群系限制的自然/区块生成生物，若其原始群系并非其栖息地则取消生成。
     */
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

    /**
     * 判断该生物类型是否属于需要按群系限制的自然生成生物。
     */
    private boolean hasBiomeRestrictedNaturalSpawn(EntityType type) {
        boolean restricted;
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
                restricted = true;
                break;
            default:
                restricted = false;
        }

        return restricted;
    }

    /**
     * 判断给定生物类型是否栖息于给定原始群系（群系名做关键词匹配）。
     */
    public static boolean isOriginalHabitat(EntityType type, Biome biome) {
        if (biome == null) {
            return false;
        } else {
            String name = biome.name().toUpperCase(Locale.ROOT);
            boolean habitat;
            switch (type) {
                case OCELOT:
                case PARROT:
                case PANDA:
                    habitat = name.contains("JUNGLE");
                    break;
                case MOOSHROOM:
                    habitat = "MUSHROOM_FIELDS".equals(name);
                    break;
                case POLAR_BEAR:
                    habitat = "SNOWY_PLAINS".equals(name) || "ICE_SPIKES".equals(name) || name.contains("FROZEN_OCEAN");
                    break;
                case GOAT:
                    habitat = "SNOWY_SLOPES".equals(name) || "JAGGED_PEAKS".equals(name) || "FROZEN_PEAKS".equals(name);
                    break;
                case FROG:
                case BOGGED:
                    habitat = name.contains("SWAMP") || name.contains("MANGROVE");
                    break;
                case AXOLOTL:
                    habitat = "LUSH_CAVES".equals(name);
                    break;
                case TROPICAL_FISH:
                    habitat = "LUSH_CAVES".equals(name) || name.contains("WARM_OCEAN") || name.contains("LUKEWARM_OCEAN");
                    break;
                case PUFFERFISH:
                    habitat = name.contains("WARM_OCEAN") || name.contains("LUKEWARM_OCEAN");
                    break;
                case TURTLE:
                    habitat = name.contains("BEACH");
                    break;
                case ARMADILLO:
                    habitat = name.contains("SAVANNA") || name.contains("BADLANDS");
                    break;
                case HUSK:
                    habitat = name.contains("DESERT");
                    break;
                case STRAY:
                    habitat = name.contains("SNOW") || name.contains("FROZEN") || "ICE_SPIKES".equals(name) || "GROVE".equals(name);
                    break;
                case DROWNED:
                    habitat = name.contains("OCEAN") || name.contains("RIVER");
                    break;
                default:
                    habitat = true;
            }

            return habitat;
        }
    }
}
