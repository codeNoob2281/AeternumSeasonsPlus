package Kinkin.aeternum.farming;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 温室服务。
 *
 * <p>负责判定农作物是否处于“温室”保护之下，以及是否直接暴露于天空/降雨中。
 * 判定支持两种温室识别方式：</p>
 * <ul>
 *     <li>作物正上方一定高度内存在玻璃屋顶（快速路径）；</li>
 *     <li>在配置的半径与高度范围内，统计玻璃方块数量并（可选地）要求存在核心方块。</li>
 * </ul>
 * <p>依赖 {@link SeasonalCropConfig} 提供的半径、高度、玻璃方块集合、核心方块等参数。</p>
 */
public final class GreenhouseService {
    private final SeasonalCropConfig config;

    public GreenhouseService(SeasonalCropConfig config) {
        super();
        this.config = config;
    }

    /**
     * 判断指定作物方块是否处于温室内。
     *
     * @param crop 待判定的作物方块
     * @return 若温室功能被禁用则返回 false；否则依据玻璃屋顶与玻璃数量等条件判定
     */
    public boolean isInGreenhouse(Block crop) {
        if (!this.config.isGreenhouseEnabled()) {
            return false;
        } else if (this.hasGlassRoof(crop)) {
            return true;
        } else if (this.config.getGreenhouseBlocks().isEmpty()) {
            return false;
        } else {
            int glassCount = 0;
            boolean hasCore = !this.config.isGreenhouseRequireCore();
            World world = crop.getWorld();
            int cropY = crop.getY();
            int maxY = Math.min(cropY + this.config.getMaxRoofHeight(), world.getMaxHeight() - 1);

            for(int dx = -this.config.getGreenhouseRadius(); dx <= this.config.getGreenhouseRadius(); ++dx) {
                for(int dz = -this.config.getGreenhouseRadius(); dz <= this.config.getGreenhouseRadius(); ++dz) {
                    for(int y = cropY + 1; y <= maxY; ++y) {
                        Material mat = world.getBlockAt(crop.getX() + dx, y, crop.getZ() + dz).getType();
                        if (this.config.getGreenhouseBlocks().contains(mat)) {
                            ++glassCount;
                        }

                        if (!hasCore && mat == this.config.getGreenhouseCoreBlock()) {
                            hasCore = true;
                        }
                    }
                }
            }

            return glassCount >= this.config.getGreenhouseMinGlass() && hasCore;
        }
    }

    /**
     * 判断指定作物方块是否能够直接看到天空（即其正上方直到世界高度上限之间没有非空气方块）。
     *
     * @param crop 待判定的作物方块
     * @return 若正上方全为空气则返回 true
     */
    public boolean canSeeSky(Block crop) {
        World world = crop.getWorld();
        int cropX = crop.getX();
        int cropZ = crop.getZ();
        int startY = crop.getY() + 1;

        for(int y = world.getMaxHeight() - 1; y >= startY; --y) {
            Material mat = world.getBlockAt(cropX, y, cropZ).getType();
            if (!mat.isAir()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查作物正上方一定高度范围内是否存在玻璃方块，用于快速判定温室。
     * 若中途遇到不透明固体方块则判定失败。
     *
     * @param crop 待判定的作物方块
     * @return 在允许的屋顶高度内找到玻璃方块则返回 true
     */
    private boolean hasGlassRoof(Block crop) {
        World world = crop.getWorld();
        int cropX = crop.getX();
        int cropZ = crop.getZ();
        int startY = crop.getY() + 1;
        int maxY = Math.min(startY + this.config.getMaxRoofHeight(), world.getMaxHeight() - 1);
        boolean foundGlass = false;

        for(int y = startY; y <= maxY; ++y) {
            Block block = world.getBlockAt(cropX, y, cropZ);
            Material mat = block.getType();
            if (mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR) {
                if (this.config.getGreenhouseBlocks().contains(mat)) {
                    foundGlass = true;
                    break;
                }

                if (mat != Material.SNOW && mat != Material.SNOW_BLOCK && mat.isSolid()) {
                    return false;
                }
            }
        }

        return foundGlass;
    }

    /**
     * 判断作物是否直接处于降雨中（世界正在下雨、作物不在温室内且能看到天空）。
     *
     * @param crop 待判定的作物方块
     * @return 若满足降雨条件则返回 true
     */
    public boolean isDirectlyUnderRain(Block crop) {
        World world = crop.getWorld();
        if (!world.hasStorm()) {
            return false;
        } else {
            return this.isInGreenhouse(crop) ? false : this.canSeeSky(crop);
        }
    }
}
