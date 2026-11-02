package Kinkin.aeternum.dimension;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * 极寒维度区块生成器。
 *
 * <p>作为极寒维度的 {@link ChunkGenerator}：启用原版的地形/噪声/洞穴/装饰/生物/结构生成，
 * 并将生物群系来源替换为 {@link FrostOnlyBiomeProvider}（只产出寒冷群系）。</p>
 */
public final class FrostWorldGenerator extends ChunkGenerator {
    public FrostWorldGenerator() {
        super();
    }

    /** 返回极寒维度使用的生物群系提供器（以世界种子初始化）。 */
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new FrostOnlyBiomeProvider(worldInfo.getSeed());
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
}
