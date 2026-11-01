package Kinkin.aeternum.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

final class PortalBuildProtection {
    private static final int SITE_SPACING = 7;
    private static final int SEARCH_RINGS = 8;
    private static final int SAFETY_MARGIN = 2;

    private PortalBuildProtection() {
        super();
    }

    static Site findSafeSite(World world, int preferredX, int preferredBottomY, int preferredZ, Axis axis, boolean followSurface) {
        for(int ring = 0; ring <= 8; ++ring) {
            for(int gridX = -ring; gridX <= ring; ++gridX) {
                for(int gridZ = -ring; gridZ <= ring; ++gridZ) {
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

    private static boolean canBuildAt(World world, int centerX, int bottomY, int centerZ, Axis axis) {
        int minX = axis == Axis.X ? centerX - 2 : centerX;
        int maxX = axis == Axis.X ? centerX + 1 : centerX;
        int minZ = axis == Axis.Z ? centerZ - 2 : centerZ;
        int maxZ = axis == Axis.Z ? centerZ + 1 : centerZ;
        int minY = bottomY - 1;
        int maxY = bottomY + 3;
        if (minY - 2 >= world.getMinHeight() && maxY + 2 < world.getMaxHeight()) {
            if (world.getWorldBorder().isInside(new Location(world, (double)minX, (double)bottomY, (double)minZ)) && world.getWorldBorder().isInside(new Location(world, (double)maxX, (double)bottomY, (double)maxZ))) {
                for(int x = minX - 2; x <= maxX + 2; ++x) {
                    for(int y = minY - 2; y <= maxY + 2; ++y) {
                        for(int z = minZ - 2; z <= maxZ + 2; ++z) {
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

    private static boolean isProtected(Material material) {
        return material == Material.NETHER_PORTAL || material == Material.OBSIDIAN || material == Material.CRYING_OBSIDIAN || material == Material.GLOWSTONE || material == Material.NETHER_WART_BLOCK || material == Material.END_PORTAL || material == Material.END_PORTAL_FRAME || material == Material.END_GATEWAY || material == Material.BEDROCK;
    }

    static record Site(int centerX, int bottomY, int centerZ) {
        Site {
        }
    }
}
