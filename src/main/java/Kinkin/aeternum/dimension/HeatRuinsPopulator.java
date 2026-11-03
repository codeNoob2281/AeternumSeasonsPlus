package kinkin.aeternum.dimension;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * 炽热维度遗迹装饰器。
 *
 * <p>在 {@code aeternum_heat} 世界按概率往区块中填充两类景观：
 * <ul>
 *     <li>主世界风格的地表补丁（草地/泥土/石头），并可能附带一棵焦黑枯树；</li>
 *     <li>残破的石头高塔遗迹，顶部随机点燃灵魂火或营火。</li>
 * </ul>
 * 所有放置都会先向下探测可用的地面高度，避免悬空或埋入液体。</p>
 */
public final class HeatRuinsPopulator extends BlockPopulator {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";

    public HeatRuinsPopulator() {
        super();
    }

    /**
     * 区块填充入口：按概率生成地表补丁与残塔。
     */
    @Override
    public void populate(@NotNull World world, @NotNull Random random, @NotNull Chunk chunk) {
        if ("aeternum_heat".equalsIgnoreCase(world.getName())) {
            if (random.nextDouble() < 0.1) {
                this.generateOverworldPatch(world, chunk, random);
            }

            if (random.nextDouble() < 0.06) {
                this.generateRuinTower(world, chunk, random);
            }

        }
    }

    /**
     * 向下探测某列可放置实体的地面高度。
     *
     * @return 地面之上第一个可用 y 坐标；找不到时返回 {@link Integer#MIN_VALUE}
     */
    private int findHeatGround(World world, int x, int z) {
        int worldMin = world.getMinHeight();
        int worldMax = world.getMaxHeight();
        int minY = worldMin + 16;
        int maxY = Math.min(worldMax - 16, 110);
        int topSolid = -1;

        for (int y = maxY; y >= minY; --y) {
            Material blockType = world.getBlockAt(x, y, z).getType();
            if (blockType != Material.BEDROCK && !blockType.isAir() && blockType != Material.LAVA && blockType != Material.FIRE && blockType != Material.NETHER_PORTAL) {
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

    /**
     * 生成一片圆形地表补丁，并可能种下一棵焦黑枯树。
     */
    private void generateOverworldPatch(World world, Chunk chunk, Random random) {
        int baseX = (chunk.getX() << 4) + random.nextInt(16);
        int baseZ = (chunk.getZ() << 4) + random.nextInt(16);
        int y = this.findHeatGround(world, baseX, baseZ);
        if (y != Integer.MIN_VALUE) {
            Block base = world.getBlockAt(baseX, y - 1, baseZ);
            if (base.getType().isSolid()) {
                int radius = 3 + random.nextInt(3);

                for (int dx = -radius; dx <= radius; ++dx) {
                    for (int dz = -radius; dz <= radius; ++dz) {
                        if (dx * dx + dz * dz <= radius * radius) {
                            int patchX = baseX + dx;
                            int patchZ = baseZ + dz;
                            int groundY = this.findHeatGround(world, patchX, patchZ);
                            if (groundY != Integer.MIN_VALUE) {
                                Block top = world.getBlockAt(patchX, groundY - 1, patchZ);
                                double roll = random.nextDouble();
                                if (roll < 0.2) {
                                    top.setType(Material.STONE, false);
                                } else if (roll < (double) 0.5F) {
                                    top.setType(Material.DIRT, false);
                                } else {
                                    top.setType(Material.GRASS_BLOCK, false);
                                }
                            }
                        }
                    }
                }

                if (random.nextDouble() < 0.7) {
                    this.buildBurntTree(world, baseX, y, baseZ, random);
                }

            }
        }
    }

    /**
     * 以指定坐标为树根生成一棵焦黑枯树（深色橡木树干 + 稀疏树叶）。
     */
    private void buildBurntTree(World world, int x, int y, int z, Random random) {
        int height = 3 + random.nextInt(3);

        for (int i = 0; i < height; ++i) {
            world.getBlockAt(x, y + i, z).setType(Material.DARK_OAK_LOG, false);
        }

        for (int dx = -2; dx <= 2; ++dx) {
            for (int dy = height - 2; dy <= height; ++dy) {
                for (int dz = -2; dz <= 2; ++dz) {
                    if (Math.abs(dx) + Math.abs(dz) <= 3 && random.nextDouble() < 0.3) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.DARK_OAK_LEAVES, false);
                    }
                }
            }
        }

    }

    /**
     * 生成一座残破的石头高塔，顶部随机点燃灵魂火或放置营火。
     */
    private void generateRuinTower(World world, Chunk chunk, Random random) {
        int baseX = (chunk.getX() << 4) + 4 + random.nextInt(8);
        int baseZ = (chunk.getZ() << 4) + 4 + random.nextInt(8);
        int y = this.findHeatGround(world, baseX, baseZ);
        if (y != Integer.MIN_VALUE) {
            int height = 3 + random.nextInt(4);

            for (int i = 0; i < height; ++i) {
                Material brick = random.nextDouble() < (double) 0.5F ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
                world.getBlockAt(baseX, y + i, baseZ).setType(brick, false);
            }

            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    if (Math.abs(dx) + Math.abs(dz) == 1 && random.nextBoolean()) {
                        world.getBlockAt(baseX + dx, y, baseZ + dz).setType(Material.COBBLESTONE, false);
                    }
                }
            }

            Block top = world.getBlockAt(baseX, y + height, baseZ);
            if (random.nextBoolean()) {
                top.setType(Material.SOUL_FIRE, false);
            } else {
                top.setType(Material.CAMPFIRE, false);
            }

        }
    }
}
