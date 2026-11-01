package Kinkin.aeternum.dimension;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public final class FrostOnlyBiomeProvider extends BiomeProvider {
    private static final Biome[] COLD_BIOMES;
    private static final List<Biome> COLD_BIOMES_LIST;
    private final long seed;

    public FrostOnlyBiomeProvider(long seed) {
        super();
        this.seed = seed;
    }

    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        long h = this.seed;
        h ^= (long)x * 341873128712L;
        h ^= (long)z * 132897987541L;
        h ^= h >>> 32;
        int idx = (int)((h & Long.MAX_VALUE) % (long)COLD_BIOMES.length);
        return COLD_BIOMES[idx];
    }

    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return COLD_BIOMES_LIST;
    }

    static {
        COLD_BIOMES = new Biome[]{Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.ICE_SPIKES, Biome.FROZEN_RIVER, Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.SNOWY_SLOPES};
        COLD_BIOMES_LIST = List.of(COLD_BIOMES);
    }
}
