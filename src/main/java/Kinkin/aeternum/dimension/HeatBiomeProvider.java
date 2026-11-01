package Kinkin.aeternum.dimension;

import java.util.Arrays;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public final class HeatBiomeProvider extends BiomeProvider {
    private final long seed;
    private final List<Biome> heatBiomes;

    public HeatBiomeProvider(long seed) {
        super();
        this.heatBiomes = Arrays.asList(Biome.NETHER_WASTES, Biome.CRIMSON_FOREST, Biome.WARPED_FOREST, Biome.SOUL_SAND_VALLEY, Biome.BASALT_DELTAS);
        this.seed = seed;
    }

    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        long h = (long)x * 341873128712L + (long)z * 132897987541L + this.seed;
        int idx = Math.floorMod(h, this.heatBiomes.size());
        return (Biome)this.heatBiomes.get(idx);
    }

    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return this.heatBiomes;
    }
}
