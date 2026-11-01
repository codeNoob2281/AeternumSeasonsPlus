package Kinkin.aeternum.dimension;

import java.util.List;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public final class HeatWorldGenerator extends ChunkGenerator {
    public HeatWorldGenerator() {
        super();
    }

    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new HeatBiomeProvider(worldInfo.getSeed());
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

    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new HeatRuinsPopulator());
    }
}
