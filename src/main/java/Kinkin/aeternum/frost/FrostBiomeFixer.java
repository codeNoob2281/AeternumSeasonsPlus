package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * 永霜世界生物群系修正器。
 *
 * <p>监听区块加载事件，仅在 {@code aeternum_frost} 世界中生效：若某列（以世界最低点为准）
 * 的生物群系不是寒冷类群系，则将该列按 8 格间隔重新写入为 {@link Biome#SNOWY_PLAINS}，
 * 以保证永霜世界整体呈现一致的雪原环境。依赖插件实例完成事件注册。</p>
 */
public final class FrostBiomeFixer implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;

    public FrostBiomeFixer(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 处理区块加载：对非寒冷群系的列，逐列向下重写为雪原群系。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getName().equalsIgnoreCase("aeternum_frost")) {
            Chunk chunk = event.getChunk();
            int worldMinY = world.getMinHeight();
            int worldMaxY = world.getMaxHeight();

            for(int cx = 0; cx < 16; ++cx) {
                for(int cz = 0; cz < 16; ++cz) {
                    int x = chunk.getX() * 16 + cx;
                    int z = chunk.getZ() * 16 + cz;
                    Biome current = world.getBiome(x, worldMinY, z);
                    if (!this.isColdBiome(current)) {
                        for(int y = worldMinY; y < worldMaxY; y += 8) {
                            world.setBiome(x, y, z, Biome.SNOWY_PLAINS);
                        }
                    }
                }
            }

        }
    }

    /**
     * 判断给定生物群系是否属于寒冷类别（雪原、雪林、冻河、冻洋等）。
     */
    private boolean isColdBiome(Biome biome) {
        return biome == Biome.SNOWY_PLAINS
                || biome == Biome.SNOWY_TAIGA
                || biome == Biome.GROVE
                || biome == Biome.SNOWY_SLOPES
                || biome == Biome.JAGGED_PEAKS
                || biome == Biome.FROZEN_PEAKS
                || biome == Biome.FROZEN_RIVER
                || biome == Biome.FROZEN_OCEAN
                || biome == Biome.DEEP_FROZEN_OCEAN;
    }
}
