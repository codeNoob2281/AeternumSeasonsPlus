package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
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

/**
 * 冬季效果与 WorldGuard 区域保护的兼容助手。
 *
 * <p>当服务器安装 WorldGuard 且开启区域保护时，本类会按方块所在区域查询一系列自定义的
 * {@code aeternum-*} 状态旗帜（降雪、融雪、结冰、融冰、霜冰形成/融化、冬季涂抹），
 * 决定是否允许对某个方块执行相应的冬季变化；若未安装 WorldGuard 或未开启区域保护则一律放行。</p>
 *
 * <p>同时整合 {@link GPFlagsCompat}，使 GPFlags 的 NoBlockForm/NoIceForm/NoSnowForm 旗帜也参与判定。</p>
 */
public final class WinterWorldGuardHelper {
    private static boolean hooked = false;
    private static boolean respectRegions = false;
    private static boolean defaultSnowMeltInRegions = false;

    private WinterWorldGuardHelper() {
        super();
    }

    /**
     * 从配置初始化区域保护开关，并尝试 hook WorldGuard 与 GPFlags。
     */
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

    /**
     * 是否允许该方块降雪。
     */
    public static boolean canSnowFall(Block b) {
        return checkByFlagName(b, "aeternum-snow-fall", true, false, false) && GPFlagsCompat.canSnowForm(b);
    }

    /**
     * 是否允许该方块融雪。
     */
    public static boolean canSnowMelt(Block b) {
        return checkByFlagName(b, "aeternum-snow-melt", true, defaultSnowMeltInRegions, false);
    }

    /**
     * 是否允许该方块结冰。
     */
    public static boolean canIceForm(Block b) {
        return checkByFlagName(b, "aeternum-ice-form", true, false, false) && GPFlagsCompat.canIceForm(b);
    }

    /**
     * 是否允许该方块融冰。
     */
    public static boolean canIceMelt(Block b) {
        return checkByFlagName(b, "aeternum-ice-melt", true, false, false);
    }

    /**
     * 是否允许该方块形成霜冰。
     */
    public static boolean canFrostedIceForm(Block b) {
        return checkByFlagName(b, "aeternum-frosted-ice-form", true, false, false) && GPFlagsCompat.canIceForm(b);
    }

    /**
     * 是否允许该方块融化霜冰。
     */
    public static boolean canFrostedIceMelt(Block b) {
        return checkByFlagName(b, "aeternum-frosted-ice-melt", true, false, false);
    }

    /**
     * 是否允许对该方块执行冬季涂抹（如替换为雪层等）。
     */
    public static boolean canWinterPaint(Block b) {
        return checkByFlagName(b, "aeternum-winter-paint", true, false, false);
    }

    /**
     * 按方块当前材质推断应查询哪一类冬季旗帜，综合判断是否允许修改该方块。
     */
    public static boolean canModify(Block b) {
        if (hooked && respectRegions && b != null) {
            Material material = b.getType();
            if (material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR) {
                if (material != Material.SNOW && material != Material.SNOW_BLOCK) {
                    if (material != Material.ICE && material != Material.PACKED_ICE && material != Material.BLUE_ICE) {
                        return material == Material.FROSTED_ICE ? canFrostedIceMelt(b) : canWinterPaint(b);
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

    /**
     * 查询方块所在区域针对指定旗帜的许可结果。
     *
     * @param flagName                          旗帜名称
     * @param defaultOutsideRegions             方块不在任何区域内时的默认结果
     * @param defaultInRegionsWhenNull          在区域内但旗帜未设置时的默认结果
     * @param fallbackIfFlagMissingInsideRegion 在区域内但旗帜未注册时的回退结果
     */
    private static boolean checkByFlagName(Block b, String flagName, boolean defaultOutsideRegions, boolean defaultInRegionsWhenNull, boolean fallbackIfFlagMissingInsideRegion) {
        if (hooked && respectRegions && b != null) {
            Location location = b.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return true;
            } else {
                ApplicableRegionSet regionSet = getRegionSet(world, location);
                if (regionSet != null && regionSet.size() != 0) {
                    StateFlag stateFlag = resolveStateFlag(flagName);
                    if (stateFlag == null) {
                        return fallbackIfFlagMissingInsideRegion;
                    } else {
                        StateFlag.State state = regionSet.queryState((RegionAssociable) null, new StateFlag[]{stateFlag});
                        if (state == State.DENY) {
                            return false;
                        } else {
                            return state == State.ALLOW || defaultInRegionsWhenNull;
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

    /**
     * 解析指定名称的状态旗帜；兼容带/不带 {@code aeternum-} 前缀的注册方式。
     */
    private static StateFlag resolveStateFlag(String name) {
        try {
            FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
            Flag<?> flag = flagRegistry.get(name);
            if (flag instanceof StateFlag stateFlag) {
                return stateFlag;
            }

            if (name.startsWith("aeternum-")) {
                flag = flagRegistry.get(name.substring("aeternum-".length()));
                if (flag instanceof StateFlag) {
                    StateFlag strippedStateFlag = (StateFlag) flag;
                    return strippedStateFlag;
                }
            }

            if (!name.startsWith("aeternum-")) {
                flag = flagRegistry.get("aeternum-" + name);
                if (flag instanceof StateFlag) {
                    StateFlag prefixedStateFlag = (StateFlag) flag;
                    return prefixedStateFlag;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 获取方块位置所属世界的可适用区域集合，世界未加载时返回 null。
     */
    private static ApplicableRegionSet getRegionSet(World bukkitWorld, Location location) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(bukkitWorld));
        if (manager == null) {
            return null;
        } else {
            BlockVector3 blockVector = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            return manager.getApplicableRegions(blockVector);
        }
    }
}
