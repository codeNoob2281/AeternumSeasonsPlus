package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 树冠积雪渲染器（CanopySnowPainter）。
 *
 * <p>作为周期任务运行，在冬季暴雪期间随机挑选玩家附近的落地点，若其上方存在树叶（形成“树冠遮蔽”），
 * 就在地面铺上一层雪，用来模拟树冠下与树冠边缘的积雪效果。可限制仅在“天然寒冷群系”内生效，
 * 并受每 tick 尝试次数、搜索半径、树叶扫描高度等配置约束。</p>
 *
 * <p>依赖 {@link SeasonService} 判断当前季节/天气，{@link WinterWorldPainter} 记录被本渲染器放置的雪，
 * 以及 {@link BiomeSpoofAdapter} 判断区块是否为天然雪区。</p>
 */
public final class CanopySnowPainter implements Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final WinterWorldPainter winterPainter;
    private BukkitTask task;
    private final Random random = new Random();
    private boolean enabled;
    private int attemptsPerTick;
    private int radiusBlocks;
    private int maxLeafScanHeight;
    private boolean onlyInColdBiomes;
    private final Set<Material> groundTypes;

    public CanopySnowPainter(AeternumSeasonsPlugin plugin, SeasonService seasons, WinterWorldPainter winterPainter) {
        super();
        this.groundTypes = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT, Material.MYCELIUM, Material.SNOW_BLOCK, Material.SNOW);
        this.plugin = plugin;
        this.seasons = seasons;
        this.winterPainter = winterPainter;
        this.reloadFromConfig();
    }

    /**
     * 从气候配置的 {@code real_snow.canopy} 段重新读取本渲染器的参数。
     */
    public void reloadFromConfig() {
        ConfigurationSection canopySection = this.plugin.cfg.climate.getConfigurationSection("real_snow.canopy");
        if (canopySection == null) {
            this.enabled = false;
        } else {
            this.enabled = this.plugin.cfg.climate.getBoolean("real_snow.enabled", true) && canopySection.getBoolean("enabled", true);
            this.attemptsPerTick = Math.max(1, canopySection.getInt("attempts_per_tick", 40));
            this.radiusBlocks = Math.max(4, canopySection.getInt("radius_blocks", 32));
            this.maxLeafScanHeight = Math.max(2, canopySection.getInt("max_leaf_scan_height", 6));
            this.onlyInColdBiomes = canopySection.getBoolean("only_in_cold_biomes", true);
        }
    }

    /**
     * 注册周期任务（先取消已有任务），仅在启用时按 init 延迟 60 tick、周期 10 tick 运行。
     */
    public void register() {
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 60L, 10L);
        }
    }

    /**
     * 取消并清空周期任务。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
    }

    /**
     * 周期任务主体：对每个处于主世界、冬季且正在下雪的玩家，尝试在其附近放置若干树冠积雪。
     */
    @Override
    public void run() {
        if (this.enabled) {
            ThreadLocalRandom threadRandom = ThreadLocalRandom.current();
            if (this.attemptsPerTick > 0 && this.radiusBlocks > 0) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL) {
                        CalendarState calendarState = this.seasons.getStateCopy(world);
                        if (calendarState.season == Season.WINTER && world.hasStorm()) {
                            int px = player.getLocation().getBlockX();
                            int pz = player.getLocation().getBlockZ();
                            int minY = world.getMinHeight();
                            int maxY = world.getMaxHeight();

                            for (int i = 0; i < this.attemptsPerTick; ++i) {
                                int x = px + threadRandom.nextInt(-this.radiusBlocks, this.radiusBlocks + 1);
                                int z = pz + threadRandom.nextInt(-this.radiusBlocks, this.radiusBlocks + 1);
                                if (this.onlyInColdBiomes) {
                                    int cx = x >> 4;
                                    int cz = z >> 4;
                                    boolean markedCold = BiomeSpoofAdapter.isChunkNaturallySnowy(world, cx, cz);
                                    boolean biomeCold = this.isNaturallySnowyBiome(world, x, z);
                                    if (!markedCold && !biomeCold) {
                                        continue;
                                    }
                                }

                                int highest = world.getHighestBlockYAt(x, z) - 1;
                                if (highest >= minY) {
                                    int scanMinY = Math.max(minY, highest - 16);
                                    Block ground = null;

                                    for (int y = highest; y >= scanMinY; --y) {
                                        Block candidate = world.getBlockAt(x, y, z);
                                        Material type = candidate.getType();
                                        if (type == Material.SNOW || type == Material.SNOW_BLOCK) {
                                            ground = null;
                                            break;
                                        }

                                        if (this.groundTypes.contains(type)) {
                                            Block above = candidate.getRelative(0, 1, 0);
                                            Material aboveType = above.getType();
                                            if (aboveType.isAir() || above.isPassable()) {
                                                ground = candidate;
                                            }
                                            break;
                                        }
                                    }

                                    if (ground != null) {
                                        int groundY = ground.getY();
                                        Block aboveGround = ground.getRelative(0, 1, 0);
                                        Material aboveType = aboveGround.getType();
                                        if ((aboveType.isAir() || aboveGround.isPassable()) && this.hasLeavesAbove(world, x, groundY + 2, z, maxY) && WinterWorldGuardHelper.canSnowFall(aboveGround)) {
                                            aboveGround.setType(Material.SNOW, false);
                                            this.winterPainter.trackCanopySnow(aboveGround);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * 从起始高度向上检查是否在扫描范围内存在树叶（遇到实心非树叶方块即停止）。
     */
    private boolean hasLeavesAbove(World world, int x, int startY, int z, int maxWorldY) {
        int maxY = Math.min(startY + this.maxLeafScanHeight, maxWorldY - 1);

        for (int y = startY; y <= maxY; ++y) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (this.isLeaf(type)) {
                return true;
            }

            if (type.isSolid() && !this.isLeaf(type)) {
                return false;
            }
        }

        return false;
    }

    /**
     * 通过材质名是否以 {@code _LEAVES} 结尾判断其是否为树叶。
     */
    private boolean isLeaf(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES");
    }

    /**
     * 判断给定水平坐标处的群系是否属于天然寒冷/积雪类群系。
     */
    private boolean isNaturallySnowyBiome(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        Biome biome = world.getBiome(x, y, z);
        String name = biome.name().toUpperCase();
        return name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE") || name.contains("TAIGA") || name.contains("GROVE") || name.contains("PEAK") || name.contains("MOUNTAIN");
    }
}
