package Kinkin.aeternum.dimension;

import java.util.Random;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.jetbrains.annotations.NotNull;

public final class HeatRuinsPopulator extends BlockPopulator {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";

    public HeatRuinsPopulator() {
        super();
    }

    public void populate(@NotNull World world, @NotNull Random random, @NotNull Chunk chunk) {
        if (world.getName().equalsIgnoreCase("aeternum_heat")) {
            if (random.nextDouble() < 0.1) {
                this.generateOverworldPatch(world, chunk, random);
            }

            if (random.nextDouble() < 0.06) {
                this.generateRuinTower(world, chunk, random);
            }

        }
    }

    private int findHeatGround(World world, int x, int z) {
        int worldMin = world.getMinHeight();
        int worldMax = world.getMaxHeight();
        int minY = worldMin + 16;
        int maxY = Math.min(worldMax - 16, 110);
        int topSolid = -1;

        for(int y = maxY; y >= minY; --y) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type != Material.BEDROCK && !type.isAir() && type != Material.LAVA && type != Material.FIRE && type != Material.NETHER_PORTAL) {
                topSolid = y;
                break;
            }
        }

        if (topSolid == -1) {
            return Integer.MIN_VALUE;
        } else {
            int groundY = topSolid + 1;
            groundY = Math.min(groundY, worldMax - 10);
            groundY = Math.max(groundY, worldMin + 4);
            return groundY;
        }
    }

    private void generateOverworldPatch(World world, Chunk chunk, Random random) {
        int bx = (chunk.getX() << 4) + random.nextInt(16);
        int bz = (chunk.getZ() << 4) + random.nextInt(16);
        int y = this.findHeatGround(world, bx, bz);
        if (y != Integer.MIN_VALUE) {
            Block base = world.getBlockAt(bx, y - 1, bz);
            if (base.getType().isSolid()) {
                int radius = 3 + random.nextInt(3);

                for(int dx = -radius; dx <= radius; ++dx) {
                    for(int dz = -radius; dz <= radius; ++dz) {
                        if (dx * dx + dz * dz <= radius * radius) {
                            int xx = bx + dx;
                            int zz = bz + dz;
                            int yy = this.findHeatGround(world, xx, zz);
                            if (yy != Integer.MIN_VALUE) {
                                Block top = world.getBlockAt(xx, yy - 1, zz);
                                double r = random.nextDouble();
                                if (r < 0.2) {
                                    top.setType(Material.STONE, false);
                                } else if (r < (double)0.5F) {
                                    top.setType(Material.DIRT, false);
                                } else {
                                    top.setType(Material.GRASS_BLOCK, false);
                                }
                            }
                        }
                    }
                }

                if (random.nextDouble() < 0.7) {
                    this.buildBurntTree(world, bx, y, bz, random);
                }

            }
        }
    }

    private void buildBurntTree(World world, int x, int y, int z, Random random) {
        int height = 3 + random.nextInt(3);

        for(int i = 0; i < height; ++i) {
            world.getBlockAt(x, y + i, z).setType(Material.DARK_OAK_LOG, false);
        }

        for(int dx = -2; dx <= 2; ++dx) {
            for(int dy = height - 2; dy <= height; ++dy) {
                for(int dz = -2; dz <= 2; ++dz) {
                    if (Math.abs(dx) + Math.abs(dz) <= 3 && random.nextDouble() < 0.3) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.DARK_OAK_LEAVES, false);
                    }
                }
            }
        }

    }

    private void generateRuinTower(World world, Chunk chunk, Random random) {
        int bx = (chunk.getX() << 4) + 4 + random.nextInt(8);
        int bz = (chunk.getZ() << 4) + 4 + random.nextInt(8);
        int y = this.findHeatGround(world, bx, bz);
        if (y != Integer.MIN_VALUE) {
            int height = 3 + random.nextInt(4);

            for(int i = 0; i < height; ++i) {
                Material m = random.nextDouble() < (double)0.5F ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
                world.getBlockAt(bx, y + i, bz).setType(m, false);
            }

            for(int dx = -1; dx <= 1; ++dx) {
                for(int dz = -1; dz <= 1; ++dz) {
                    if (Math.abs(dx) + Math.abs(dz) == 1 && random.nextBoolean()) {
                        world.getBlockAt(bx + dx, y, bz + dz).setType(Material.COBBLESTONE, false);
                    }
                }
            }

            Block top = world.getBlockAt(bx, y + height, bz);
            if (random.nextBoolean()) {
                top.setType(Material.SOUL_FIRE, false);
            } else {
                top.setType(Material.CAMPFIRE, false);
            }

        }
    }
}
