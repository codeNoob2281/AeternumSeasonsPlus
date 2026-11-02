package Kinkin.aeternum.dimension;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * 极寒维度专用生物群系提供器。
 *
 * <p>仅从一组寒冷群系（雪原、冰刺、冻洋等）中，根据 x/z 坐标与种子做确定性散列选取，
 * 保证同一坐标始终得到相同群系，从而向极寒维度提供稳定的寒冷地貌。</p>
 */
public final class FrostOnlyBiomeProvider extends BiomeProvider {
    /** 可选的寒冷群系集合。 */
    private static final Biome[] COLD_BIOMES;
    /** 寒冷群系列表（供 {@link #getBiomes} 返回）。 */
    private static final List<Biome> COLD_BIOMES_LIST;
    private final long seed;

    public FrostOnlyBiomeProvider(long seed) {
        super();
        this.seed = seed;
    }

    /** 根据坐标与种子做散列，确定性地选取一个寒冷群系。 */
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        long hash = this.seed;
        hash ^= (long)x * 341873128712L;
        hash ^= (long)z * 132897987541L;
        hash ^= hash >>> 32;
        int index = (int)((hash & Long.MAX_VALUE) % (long)COLD_BIOMES.length);
        return COLD_BIOMES[index];
    }

    /** 返回本维度可用的全部群系。 */
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return COLD_BIOMES_LIST;
    }

    static {
        COLD_BIOMES = new Biome[]{Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.ICE_SPIKES, Biome.FROZEN_RIVER, Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.SNOWY_SLOPES};
        COLD_BIOMES_LIST = List.of(COLD_BIOMES);
    }
}
