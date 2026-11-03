package kinkin.aeternum.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * 传送门安全建造点选择工具。
 *
 * <p>在目标世界按螺旋环带（rings）搜索一块足够空旷、且不含受保护方块
 * （传送门、黑曜石、萤石、下界疣块、末地传送门、基岩等）的位置，
 * 供自定义传送门建造使用。{@link #findSafeSite} 会沿 X/Z 以固定间距逐环试探，
 * 并根据是否“沿地表”决定底部高度。所有方法均为无副作用的静态查询。</p>
 */
final class PortalBuildProtection {
    private static final int SITE_SPACING = 7;
    private static final int SEARCH_RINGS = 8;
    private static final int SAFETY_MARGIN = 2;

    private PortalBuildProtection() {
        super();
    }

    /**
     * 以首选坐标为中心逐环搜索可建造位置。
     *
     * @param world            目标世界
     * @param preferredX       首选中心 X
     * @param preferredBottomY 首选底部 Y（不沿地表时使用）
     * @param preferredZ       首选中心 Z
     * @param axis             传送门朝向
     * @param followSurface    是否贴合地表高度
     * @return 第一处可建造的站点；找不到时返回 null
     */
    static Site findSafeSite(World world, int preferredX, int preferredBottomY, int preferredZ, Axis axis, boolean followSurface) {
        for (int ring = 0; ring <= 8; ++ring) {
            for (int gridX = -ring; gridX <= ring; ++gridX) {
                for (int gridZ = -ring; gridZ <= ring; ++gridZ) {
                    if (ring <= 0 || Math.max(Math.abs(gridX), Math.abs(gridZ)) == ring) {
                        int centerX = preferredX + gridX * 7;
                        int centerZ = preferredZ + gridZ * 7;
                        int bottomY = followSurface ? world.getHighestBlockYAt(centerX, centerZ) + 1 : preferredBottomY;
                        bottomY = Math.max(world.getMinHeight() + 4, bottomY);
                        bottomY = Math.min(world.getMaxHeight() - 10, bottomY);
                        if (canBuildAt(world, centerX, bottomY, centerZ, axis)) {
                            return new Site(centerX, bottomY, centerZ);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 校验以给定中心/底部高度建造传送门时，其外扩 {@value #SAFETY_MARGIN} 格范围内是否安全且位于世界边界内。
     */
    private static boolean canBuildAt(World world, int centerX, int bottomY, int centerZ, Axis axis) {
        int minX = axis == Axis.X ? centerX - 2 : centerX;
        int maxX = axis == Axis.X ? centerX + 1 : centerX;
        int minZ = axis == Axis.Z ? centerZ - 2 : centerZ;
        int maxZ = axis == Axis.Z ? centerZ + 1 : centerZ;
        int minY = bottomY - 1;
        int maxY = bottomY + 3;
        if (minY - 2 >= world.getMinHeight() && maxY + 2 < world.getMaxHeight()) {
            if (world.getWorldBorder().isInside(new Location(world, (double) minX, (double) bottomY, (double) minZ)) && world.getWorldBorder().isInside(new Location(world, (double) maxX, (double) bottomY, (double) maxZ))) {
                for (int x = minX - 2; x <= maxX + 2; ++x) {
                    for (int y = minY - 2; y <= maxY + 2; ++y) {
                        for (int z = minZ - 2; z <= maxZ + 2; ++z) {
                            if (isProtected(world.getBlockAt(x, y, z).getType())) {
                                return false;
                            }
                        }
                    }
                }

                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * 判断材质是否属于“受保护/不可覆盖”的方块（建造站点必须避开）。
     */
    private static boolean isProtected(Material material) {
        return material == Material.NETHER_PORTAL || material == Material.OBSIDIAN || material == Material.CRYING_OBSIDIAN || material == Material.GLOWSTONE || material == Material.NETHER_WART_BLOCK || material == Material.END_PORTAL || material == Material.END_PORTAL_FRAME || material == Material.END_GATEWAY || material == Material.BEDROCK;
    }

    /**
     * 可建造站点：以中心 X/Z 与底部 Y 描述传送门落点。
     */
    record Site(int centerX, int bottomY, int centerZ) {
        Site {
        }
    }
}
