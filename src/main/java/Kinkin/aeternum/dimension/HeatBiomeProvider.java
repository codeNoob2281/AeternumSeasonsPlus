package Kinkin.aeternum.dimension;

import java.util.Arrays;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * 炽热维度生物群系提供器。
 *
 * <p>从一组下界风格群系（荒芜之地、绯红森林、诡异森林、灵魂沙峡谷、玄武岩三角洲）中，
 * 根据 x/z 坐标与种子做确定性散列选取，保证同一坐标始终得到相同群系。</p>
 */
public final class HeatBiomeProvider extends BiomeProvider {
    private final long seed;
    /** 可选的炽热群系列表。 */
    private final List<Biome> heatBiomes;

    public HeatBiomeProvider(long seed) {
        super();
        this.heatBiomes = Arrays.asList(Biome.NETHER_WASTES, Biome.CRIMSON_FOREST, Biome.WARPED_FOREST, Biome.SOUL_SAND_VALLEY, Biome.BASALT_DELTAS);
        this.seed = seed;
    }

    /** 根据坐标与种子做散列，确定性地选取一个炽热群系。 */
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        long hash = (long)x * 341873128712L + (long)z * 132897987541L + this.seed;
        int index = Math.floorMod(hash, this.heatBiomes.size());
        return (Biome)this.heatBiomes.get(index);
    }

    /** 返回本维度可用的全部群系。 */
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return this.heatBiomes;
    }
}
