package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class WinterWorldGuardHelper {
    private static boolean hooked = false;
    private static boolean respectRegions = false;
    private static boolean defaultSnowMeltInRegions = false;

    private WinterWorldGuardHelper() {
        super();
    }

    public static void init(AeternumSeasonsPlugin plugin) {
        respectRegions = plugin.cfg != null && plugin.cfg.climate != null ? plugin.cfg.climate.getBoolean("real_snow.worldguard_respect_regions", plugin.getConfig().getBoolean("winter_painter.worldguard_respect_regions", false)) : plugin.getConfig().getBoolean("winter_painter.worldguard_respect_regions", false);
        defaultSnowMeltInRegions = plugin.cfg != null && plugin.cfg.climate != null ? plugin.cfg.climate.getBoolean("real_snow.worldguard.default_melt_in_regions", false) : plugin.getConfig().getBoolean("real_snow.worldguard.default_melt_in_regions", false);
        hooked = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        GPFlagsCompat.init(plugin);
        if (hooked) {
            plugin.getLogger().info("[AeternumSeasons] WorldGuard detected, respect regions: " + respectRegions + " | default melt in regions: " + defaultSnowMeltInRegions);
        } else {
            plugin.getLogger().info("[AeternumSeasons] WorldGuard not found.");
        }

    }

    public static boolean canSnowFall(Block b) {
        return checkByFlagName(b, "aeternum-snow-fall", true, false, false) && GPFlagsCompat.canSnowForm(b);
    }

    public static boolean canSnowMelt(Block b) {
        return checkByFlagName(b, "aeternum-snow-melt", true, defaultSnowMeltInRegions, false);
    }

    public static boolean canIceForm(Block b) {
        return checkByFlagName(b, "aeternum-ice-form", true, false, false) && GPFlagsCompat.canIceForm(b);
    }

    public static boolean canIceMelt(Block b) {
        return checkByFlagName(b, "aeternum-ice-melt", true, false, false);
    }

    public static boolean canFrostedIceForm(Block b) {
        return checkByFlagName(b, "aeternum-frosted-ice-form", true, false, false) && GPFlagsCompat.canIceForm(b);
    }

    public static boolean canFrostedIceMelt(Block b) {
        return checkByFlagName(b, "aeternum-frosted-ice-melt", true, false, false);
    }

    public static boolean canWinterPaint(Block b) {
        return checkByFlagName(b, "aeternum-winter-paint", true, false, false);
    }

    public static boolean canModify(Block b) {
        if (hooked && respectRegions && b != null) {
            Material t = b.getType();
            if (t != Material.AIR && t != Material.CAVE_AIR && t != Material.VOID_AIR) {
                if (t != Material.SNOW && t != Material.SNOW_BLOCK) {
                    if (t != Material.ICE && t != Material.PACKED_ICE && t != Material.BLUE_ICE) {
                        return t == Material.FROSTED_ICE ? canFrostedIceMelt(b) : canWinterPaint(b);
                    } else {
                        return canIceForm(b);
                    }
                } else {
                    return canSnowMelt(b);
                }
            } else {
                return canSnowFall(b);
            }
        } else {
            return true;
        }
    }

    private static boolean checkByFlagName(Block b, String flagName, boolean defaultOutsideRegions, boolean defaultInRegionsWhenNull, boolean fallbackIfFlagMissingInsideRegion) {
        if (hooked && respectRegions && b != null) {
            Location loc = b.getLocation();
            World w = loc.getWorld();
            if (w == null) {
                return true;
            } else {
                ApplicableRegionSet set = getRegionSet(w, loc);
                if (set != null && set.size() != 0) {
                    StateFlag sf = resolveStateFlag(flagName);
                    if (sf == null) {
                        return fallbackIfFlagMissingInsideRegion;
                    } else {
                        StateFlag.State st = set.queryState((RegionAssociable)null, new StateFlag[]{sf});
                        if (st == State.DENY) {
                            return false;
                        } else {
                            return st == State.ALLOW ? true : defaultInRegionsWhenNull;
                        }
                    }
                } else {
                    return defaultOutsideRegions;
                }
            }
        } else {
            return true;
        }
    }

    private static StateFlag resolveStateFlag(String name) {
        try {
            FlagRegistry reg = WorldGuard.getInstance().getFlagRegistry();
            Flag<?> f = reg.get(name);
            if (f instanceof StateFlag sf) {
                return sf;
            }

            if (name.startsWith("aeternum-")) {
                f = reg.get(name.substring("aeternum-".length()));
                if (f instanceof StateFlag) {
                    StateFlag sf2 = (StateFlag)f;
                    return sf2;
                }
            }

            if (!name.startsWith("aeternum-")) {
                f = reg.get("aeternum-" + name);
                if (f instanceof StateFlag) {
                    StateFlag sf3 = (StateFlag)f;
                    return sf3;
                }
            }
        } catch (Throwable var4) {
        }

        return null;
    }

    private static ApplicableRegionSet getRegionSet(World bw, Location loc) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(bw));
        if (manager == null) {
            return null;
        } else {
            BlockVector3 vec = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            return manager.getApplicableRegions(vec);
        }
    }
}
