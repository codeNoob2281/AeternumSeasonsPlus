package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

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

    public void reloadFromConfig() {
        ConfigurationSection y = this.plugin.cfg.climate.getConfigurationSection("real_snow.canopy");
        if (y == null) {
            this.enabled = false;
        } else {
            this.enabled = this.plugin.cfg.climate.getBoolean("real_snow.enabled", true) && y.getBoolean("enabled", true);
            this.attemptsPerTick = Math.max(1, y.getInt("attempts_per_tick", 40));
            this.radiusBlocks = Math.max(4, y.getInt("radius_blocks", 32));
            this.maxLeafScanHeight = Math.max(2, y.getInt("max_leaf_scan_height", 6));
            this.onlyInColdBiomes = y.getBoolean("only_in_cold_biomes", true);
        }
    }

    public void register() {
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 60L, 10L);
        }
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
    }

    public void run() {
        if (this.enabled) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if (this.attemptsPerTick > 0 && this.radiusBlocks > 0) {
                for(Player p : Bukkit.getOnlinePlayers()) {
                    World w = p.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL) {
                        CalendarState st = this.seasons.getStateCopy(w);
                        if (st.season == Season.WINTER && w.hasStorm()) {
                            int px = p.getLocation().getBlockX();
                            int pz = p.getLocation().getBlockZ();
                            int minY = w.getMinHeight();
                            int maxY = w.getMaxHeight();

                            for(int i = 0; i < this.attemptsPerTick; ++i) {
                                int x = px + rnd.nextInt(-this.radiusBlocks, this.radiusBlocks + 1);
                                int z = pz + rnd.nextInt(-this.radiusBlocks, this.radiusBlocks + 1);
                                if (this.onlyInColdBiomes) {
                                    int cx = x >> 4;
                                    int cz = z >> 4;
                                    boolean markedCold = BiomeSpoofAdapter.isChunkNaturallySnowy(w, cx, cz);
                                    boolean biomeCold = this.isNaturallySnowyBiome(w, x, z);
                                    if (!markedCold && !biomeCold) {
                                        continue;
                                    }
                                }

                                int highest = w.getHighestBlockYAt(x, z) - 1;
                                if (highest >= minY) {
                                    int scanMinY = Math.max(minY, highest - 16);
                                    Block ground = null;

                                    for(int y = highest; y >= scanMinY; --y) {
                                        Block candidate = w.getBlockAt(x, y, z);
                                        Material t = candidate.getType();
                                        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
                                            ground = null;
                                            break;
                                        }

                                        if (this.groundTypes.contains(t)) {
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
                                        if ((aboveType.isAir() || aboveGround.isPassable()) && this.hasLeavesAbove(w, x, groundY + 2, z, maxY) && WinterWorldGuardHelper.canSnowFall(aboveGround)) {
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

    private boolean hasLeavesAbove(World w, int x, int startY, int z, int maxWorldY) {
        int maxY = Math.min(startY + this.maxLeafScanHeight, maxWorldY - 1);

        for(int y = startY; y <= maxY; ++y) {
            Block b = w.getBlockAt(x, y, z);
            Material type = b.getType();
            if (this.isLeaf(type)) {
                return true;
            }

            if (type.isSolid() && !this.isLeaf(type)) {
                return false;
            }
        }

        return false;
    }

    private boolean isLeaf(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES");
    }

    private boolean isNaturallySnowyBiome(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        Biome biome = w.getBiome(x, y, z);
        String name = biome.name().toUpperCase();
        return name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE") || name.contains("TAIGA") || name.contains("GROVE") || name.contains("PEAK") || name.contains("MOUNTAIN");
    }
}
