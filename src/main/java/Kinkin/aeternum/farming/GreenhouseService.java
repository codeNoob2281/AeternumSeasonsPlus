package Kinkin.aeternum.farming;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class GreenhouseService {
    private final SeasonalCropConfig config;

    public GreenhouseService(SeasonalCropConfig config) {
        super();
        this.config = config;
    }

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
            World w = crop.getWorld();
            int cy = crop.getY();
            int maxY = Math.min(cy + this.config.getMaxRoofHeight(), w.getMaxHeight() - 1);

            for(int dx = -this.config.getGreenhouseRadius(); dx <= this.config.getGreenhouseRadius(); ++dx) {
                for(int dz = -this.config.getGreenhouseRadius(); dz <= this.config.getGreenhouseRadius(); ++dz) {
                    for(int y = cy + 1; y <= maxY; ++y) {
                        Material mat = w.getBlockAt(crop.getX() + dx, y, crop.getZ() + dz).getType();
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

    public boolean canSeeSky(Block crop) {
        World w = crop.getWorld();
        int cx = crop.getX();
        int cz = crop.getZ();
        int startY = crop.getY() + 1;

        for(int y = w.getMaxHeight() - 1; y >= startY; --y) {
            Material mat = w.getBlockAt(cx, y, cz).getType();
            if (!mat.isAir()) {
                return false;
            }
        }

        return true;
    }

    private boolean hasGlassRoof(Block crop) {
        World w = crop.getWorld();
        int cx = crop.getX();
        int cz = crop.getZ();
        int startY = crop.getY() + 1;
        int maxY = Math.min(startY + this.config.getMaxRoofHeight(), w.getMaxHeight() - 1);
        boolean foundGlass = false;

        for(int y = startY; y <= maxY; ++y) {
            Block b = w.getBlockAt(cx, y, cz);
            Material mat = b.getType();
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

    public boolean isDirectlyUnderRain(Block crop) {
        World w = crop.getWorld();
        if (!w.hasStorm()) {
            return false;
        } else {
            return this.isInGreenhouse(crop) ? false : this.canSeeSky(crop);
        }
    }
}
