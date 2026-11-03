package kinkin.aeternum.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.jetbrains.annotations.Nullable;

/**
 * 传送门框架材质分类器。
 *
 * <p>通过统计传送门包围盒四周方块的组成，将门分为原版（黑曜石）、
 * 霜原（萤石）、炽热（下界疣块）以及两者混合的自定义门，或未知类型。
 * 同时提供在指定位置附近查找最近传送门方块、计算传送落点与读取朝向的工具，
 * 被 {@link VanillaPortalIsolation} 等逻辑复用。</p>
 */
final class PortalFrameClassifier {
    private PortalFrameClassifier() {
        super();
    }

    /**
     * 分类给定传送门方块的框架类型；无法识别时返回 {@link Type#UNKNOWN}。
     */
    static Type classify(@Nullable Block portalBlock) {
        Bounds bounds = bounds(portalBlock);
        if (bounds == null) {
            return PortalFrameClassifier.Type.UNKNOWN;
        } else {
            World world = portalBlock.getWorld();
            int obsidian = 0;
            int glowstone = 0;
            int wart = 0;
            if (bounds.axis == Axis.X) {
                int z = bounds.minZ;

                for (int y = bounds.minY; y <= bounds.maxY; ++y) {
                    Material left = world.getBlockAt(bounds.minX - 1, y, z).getType();
                    Material right = world.getBlockAt(bounds.maxX + 1, y, z).getType();
                    obsidian += count(left, Material.OBSIDIAN) + count(right, Material.OBSIDIAN);
                    glowstone += count(left, Material.GLOWSTONE) + count(right, Material.GLOWSTONE);
                    wart += count(left, Material.NETHER_WART_BLOCK) + count(right, Material.NETHER_WART_BLOCK);
                }

                for (int x = bounds.minX; x <= bounds.maxX; ++x) {
                    Material bottom = world.getBlockAt(x, bounds.minY - 1, z).getType();
                    Material top = world.getBlockAt(x, bounds.maxY + 1, z).getType();
                    obsidian += count(bottom, Material.OBSIDIAN) + count(top, Material.OBSIDIAN);
                    glowstone += count(bottom, Material.GLOWSTONE) + count(top, Material.GLOWSTONE);
                    wart += count(bottom, Material.NETHER_WART_BLOCK) + count(top, Material.NETHER_WART_BLOCK);
                }
            } else {
                int x = bounds.minX;

                for (int y = bounds.minY; y <= bounds.maxY; ++y) {
                    Material north = world.getBlockAt(x, y, bounds.minZ - 1).getType();
                    Material south = world.getBlockAt(x, y, bounds.maxZ + 1).getType();
                    obsidian += count(north, Material.OBSIDIAN) + count(south, Material.OBSIDIAN);
                    glowstone += count(north, Material.GLOWSTONE) + count(south, Material.GLOWSTONE);
                    wart += count(north, Material.NETHER_WART_BLOCK) + count(south, Material.NETHER_WART_BLOCK);
                }

                for (int z = bounds.minZ; z <= bounds.maxZ; ++z) {
                    Material bottom = world.getBlockAt(x, bounds.minY - 1, z).getType();
                    Material top = world.getBlockAt(x, bounds.maxY + 1, z).getType();
                    obsidian += count(bottom, Material.OBSIDIAN) + count(top, Material.OBSIDIAN);
                    glowstone += count(bottom, Material.GLOWSTONE) + count(top, Material.GLOWSTONE);
                    wart += count(bottom, Material.NETHER_WART_BLOCK) + count(top, Material.NETHER_WART_BLOCK);
                }
            }

            if (glowstone > 0 && wart > 0) {
                return PortalFrameClassifier.Type.CUSTOM_MIXED;
            } else if (glowstone > 0) {
                return PortalFrameClassifier.Type.FROST;
            } else if (wart > 0) {
                return PortalFrameClassifier.Type.HEAT;
            } else {
                return obsidian > 0 ? PortalFrameClassifier.Type.VANILLA : PortalFrameClassifier.Type.UNKNOWN;
            }
        }
    }

    /**
     * 在给定位置周围指定半径内查找距离最近的传送门方块。
     */
    static @Nullable Block findPortalBlock(Location location, int radius) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        } else {
            int bx = location.getBlockX();
            int by = location.getBlockY();
            int bz = location.getBlockZ();
            Block best = null;
            double bestDistance = Double.MAX_VALUE;

            for (int x = bx - radius; x <= bx + radius; ++x) {
                for (int y = Math.max(world.getMinHeight(), by - radius); y <= Math.min(world.getMaxHeight() - 1, by + radius); ++y) {
                    for (int z = bz - radius; z <= bz + radius; ++z) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.NETHER_PORTAL) {
                            double distance = block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F).distanceSquared(location);
                            if (distance < bestDistance) {
                                best = block;
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }

            return best;
        }
    }

    /**
     * 依据传送门包围盒计算其中心落点（门内中心略高于底部）。
     */
    static @Nullable Location teleportLocation(@Nullable Block portalBlock) {
        Bounds bounds = bounds(portalBlock);
        if (bounds == null) {
            return null;
        } else {
            double x = bounds.axis == Axis.X ? (double) (bounds.minX + bounds.maxX + 1) / (double) 2.0F : (double) bounds.minX + (double) 0.5F;
            double z = bounds.axis == Axis.Z ? (double) (bounds.minZ + bounds.maxZ + 1) / (double) 2.0F : (double) bounds.minZ + (double) 0.5F;
            return new Location(portalBlock.getWorld(), x, (double) bounds.minY + 0.1, z);
        }
    }

    /**
     * 读取传送门方块朝向；无法读取时回退为 X 轴。
     */
    static Axis axis(@Nullable Block portalBlock) {
        if (portalBlock != null) {
            BlockData blockData = portalBlock.getBlockData();
            if (blockData instanceof Orientable) {
                Orientable orientable = (Orientable) blockData;
                return orientable.getAxis();
            }
        }

        return Axis.X;
    }

    /**
     * 比较两个材质是否相同，相同返回 1，否则返回 0（用于计数累加）。
     */
    private static int count(Material actual, Material expected) {
        return actual == expected ? 1 : 0;
    }

    /**
     * 计算传送门方块所在门的轴对齐包围盒；非传送门方块返回 null。
     */
    private static @Nullable Bounds bounds(@Nullable Block portalBlock) {
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            World world = portalBlock.getWorld();
            Axis axis = axis(portalBlock);
            int minY = portalBlock.getY();

            int maxY;
            for (maxY = portalBlock.getY(); minY > world.getMinHeight() && world.getBlockAt(portalBlock.getX(), minY - 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minY) {
            }

            while (maxY < world.getMaxHeight() - 1 && world.getBlockAt(portalBlock.getX(), maxY + 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                ++maxY;
            }

            if (axis == Axis.X) {
                int minX = portalBlock.getX();

                int maxX;
                for (maxX = portalBlock.getX(); world.getBlockAt(minX - 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minX) {
                }

                while (world.getBlockAt(maxX + 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                    ++maxX;
                }

                return new Bounds(axis, minX, maxX, minY, maxY, portalBlock.getZ(), portalBlock.getZ());
            } else {
                int minZ = portalBlock.getZ();

                int maxZ;
                for (maxZ = portalBlock.getZ(); world.getBlockAt(portalBlock.getX(), portalBlock.getY(), minZ - 1).getType() == Material.NETHER_PORTAL; --minZ) {
                }

                while (world.getBlockAt(portalBlock.getX(), portalBlock.getY(), maxZ + 1).getType() == Material.NETHER_PORTAL) {
                    ++maxZ;
                }

                return new Bounds(axis, portalBlock.getX(), portalBlock.getX(), minY, maxY, minZ, maxZ);
            }
        } else {
            return null;
        }
    }

    /**
     * 传送门框架类型。
     */
    enum Type {
        VANILLA,
        FROST,
        HEAT,
        CUSTOM_MIXED,
        UNKNOWN;

        Type() {
        }

        /**
         * 是否为自定义门（霜原、炽热或两者混合）。
         */
        boolean isCustom() {
            return this == FROST || this == HEAT || this == CUSTOM_MIXED;
        }

        // $FF: synthetic method
        private static Type[] $values() {
            return new Type[]{VANILLA, FROST, HEAT, CUSTOM_MIXED, UNKNOWN};
        }
    }

    /**
     * 传送门的轴对齐包围盒（含朝向与各轴最小/最大坐标）。
     */
    private record Bounds(Axis axis, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private Bounds {
        }
    }
}
