package Kinkin.aeternum.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.jetbrains.annotations.Nullable;

final class PortalFrameClassifier {
    private PortalFrameClassifier() {
        super();
    }

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

                for(int y = bounds.minY; y <= bounds.maxY; ++y) {
                    Material left = world.getBlockAt(bounds.minX - 1, y, z).getType();
                    Material right = world.getBlockAt(bounds.maxX + 1, y, z).getType();
                    obsidian += count(left, Material.OBSIDIAN) + count(right, Material.OBSIDIAN);
                    glowstone += count(left, Material.GLOWSTONE) + count(right, Material.GLOWSTONE);
                    wart += count(left, Material.NETHER_WART_BLOCK) + count(right, Material.NETHER_WART_BLOCK);
                }

                for(int x = bounds.minX; x <= bounds.maxX; ++x) {
                    Material bottom = world.getBlockAt(x, bounds.minY - 1, z).getType();
                    Material top = world.getBlockAt(x, bounds.maxY + 1, z).getType();
                    obsidian += count(bottom, Material.OBSIDIAN) + count(top, Material.OBSIDIAN);
                    glowstone += count(bottom, Material.GLOWSTONE) + count(top, Material.GLOWSTONE);
                    wart += count(bottom, Material.NETHER_WART_BLOCK) + count(top, Material.NETHER_WART_BLOCK);
                }
            } else {
                int x = bounds.minX;

                for(int y = bounds.minY; y <= bounds.maxY; ++y) {
                    Material north = world.getBlockAt(x, y, bounds.minZ - 1).getType();
                    Material south = world.getBlockAt(x, y, bounds.maxZ + 1).getType();
                    obsidian += count(north, Material.OBSIDIAN) + count(south, Material.OBSIDIAN);
                    glowstone += count(north, Material.GLOWSTONE) + count(south, Material.GLOWSTONE);
                    wart += count(north, Material.NETHER_WART_BLOCK) + count(south, Material.NETHER_WART_BLOCK);
                }

                for(int z = bounds.minZ; z <= bounds.maxZ; ++z) {
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

            for(int x = bx - radius; x <= bx + radius; ++x) {
                for(int y = Math.max(world.getMinHeight(), by - radius); y <= Math.min(world.getMaxHeight() - 1, by + radius); ++y) {
                    for(int z = bz - radius; z <= bz + radius; ++z) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.NETHER_PORTAL) {
                            double distance = block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F).distanceSquared(location);
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

    static @Nullable Location teleportLocation(@Nullable Block portalBlock) {
        Bounds bounds = bounds(portalBlock);
        if (bounds == null) {
            return null;
        } else {
            double x = bounds.axis == Axis.X ? (double)(bounds.minX + bounds.maxX + 1) / (double)2.0F : (double)bounds.minX + (double)0.5F;
            double z = bounds.axis == Axis.Z ? (double)(bounds.minZ + bounds.maxZ + 1) / (double)2.0F : (double)bounds.minZ + (double)0.5F;
            return new Location(portalBlock.getWorld(), x, (double)bounds.minY + 0.1, z);
        }
    }

    static Axis axis(@Nullable Block portalBlock) {
        if (portalBlock != null) {
            BlockData var2 = portalBlock.getBlockData();
            if (var2 instanceof Orientable) {
                Orientable orientable = (Orientable)var2;
                return orientable.getAxis();
            }
        }

        return Axis.X;
    }

    private static int count(Material actual, Material expected) {
        return actual == expected ? 1 : 0;
    }

    private static @Nullable Bounds bounds(@Nullable Block portalBlock) {
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            World world = portalBlock.getWorld();
            Axis axis = axis(portalBlock);
            int minY = portalBlock.getY();

            int maxY;
            for(maxY = portalBlock.getY(); minY > world.getMinHeight() && world.getBlockAt(portalBlock.getX(), minY - 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minY) {
            }

            while(maxY < world.getMaxHeight() - 1 && world.getBlockAt(portalBlock.getX(), maxY + 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                ++maxY;
            }

            if (axis == Axis.X) {
                int minX = portalBlock.getX();

                int maxX;
                for(maxX = portalBlock.getX(); world.getBlockAt(minX - 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minX) {
                }

                while(world.getBlockAt(maxX + 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                    ++maxX;
                }

                return new Bounds(axis, minX, maxX, minY, maxY, portalBlock.getZ(), portalBlock.getZ());
            } else {
                int minZ = portalBlock.getZ();

                int maxZ;
                for(maxZ = portalBlock.getZ(); world.getBlockAt(portalBlock.getX(), portalBlock.getY(), minZ - 1).getType() == Material.NETHER_PORTAL; --minZ) {
                }

                while(world.getBlockAt(portalBlock.getX(), portalBlock.getY(), maxZ + 1).getType() == Material.NETHER_PORTAL) {
                    ++maxZ;
                }

                return new Bounds(axis, portalBlock.getX(), portalBlock.getX(), minY, maxY, minZ, maxZ);
            }
        } else {
            return null;
        }
    }

    static enum Type {
        VANILLA,
        FROST,
        HEAT,
        CUSTOM_MIXED,
        UNKNOWN;

        private Type() {
        }

        boolean isCustom() {
            return this == FROST || this == HEAT || this == CUSTOM_MIXED;
        }

        // $FF: synthetic method
        private static Type[] $values() {
            return new Type[]{VANILLA, FROST, HEAT, CUSTOM_MIXED, UNKNOWN};
        }
    }

    private static record Bounds(Axis axis, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private Bounds {
        }
    }
}
