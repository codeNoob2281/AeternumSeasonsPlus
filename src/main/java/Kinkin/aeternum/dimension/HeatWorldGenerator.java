package Kinkin.aeternum.dimension;

import java.util.List;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * 炽热维度区块生成器。
 *
 * <p>作为炽热维度的 {@link ChunkGenerator}：启用原版地形/噪声/洞穴/装饰/生物/结构生成，
 * 生物群系来源替换为 {@link HeatBiomeProvider}，并挂载 {@link HeatRuinsPopulator} 生成遗迹景观。</p>
 */
public final class HeatWorldGenerator extends ChunkGenerator {
    public HeatWorldGenerator() {
        super();
    }

    /** 返回炽热维度使用的生物群系提供器（以世界种子初始化）。 */
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new HeatBiomeProvider(worldInfo.getSeed());
    }

    /** 是否生成噪声地形。 */
    public boolean shouldGenerateNoise() {
        return true;
    }

    /** 是否生成地表层。 */
    public boolean shouldGenerateSurface() {
        return true;
    }

    /** 是否生成洞穴。 */
    public boolean shouldGenerateCaves() {
        return true;
    }

    /** 是否生成装饰物（植被等）。 */
    public boolean shouldGenerateDecorations() {
        return true;
    }

    /** 是否生成生物。 */
    public boolean shouldGenerateMobs() {
        return true;
    }

    /** 是否生成结构。 */
    public boolean shouldGenerateStructures() {
        return true;
    }

    /** 返回本维度的区块装饰器列表（遗迹生成）。 */
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new HeatRuinsPopulator());
    }
}
