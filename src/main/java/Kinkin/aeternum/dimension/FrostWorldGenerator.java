package Kinkin.aeternum.dimension;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public final class FrostWorldGenerator extends ChunkGenerator {
    public FrostWorldGenerator() {
        super();
    }

    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new FrostOnlyBiomeProvider(worldInfo.getSeed());
    }

    public boolean shouldGenerateNoise() {
        return true;
    }

    public boolean shouldGenerateSurface() {
        return true;
    }

    public boolean shouldGenerateCaves() {
        return true;
    }

    public boolean shouldGenerateDecorations() {
        return true;
    }

    public boolean shouldGenerateMobs() {
        return true;
    }

    public boolean shouldGenerateStructures() {
        return true;
    }
}
