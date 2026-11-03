package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 快速树叶腐烂服务。
 *
 * <p>监听玩家砍伐原木的事件：当一棵树被砍倒后，延迟一小段时间（一个 tick 后）扫描该原木周围一定半径内的树叶，
 * 对距离过远（≥7）、非持久化且通过随机判定的树叶提前触发自然腐烂，从而加快整棵树树叶的消失、避免残留悬浮树叶。</p>
 *
 * <p>同一 8×8×8 区域内的重复触发会通过 {@link DecayArea} 去重，避免连锁砍伐造成大量重复扫描。
 * 依赖 {@link AutumnSoilPainter}（可选）在腐烂前恢复被季节改造的树叶原始状态。</p>
 */
public final class FastLeafDecayService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    /**
     * 已排队等待延迟腐烂处理的区域集合，用于避免重复调度。
     */
    private final Set<DecayArea> pendingAreas = new HashSet();
    private boolean registered;
    /**
     * 可触发快速腐烂的原木方块类型。
     */
    private final Set<Material> logTypes;
    /**
     * 参与快速腐烂判定的树叶方块类型。
     */
    private final Set<Material> leafTypes;

    public FastLeafDecayService(AeternumSeasonsPlugin plugin) {
        super();
        this.logTypes = EnumSet.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.DARK_OAK_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.CHERRY_LOG, Material.MANGROVE_LOG);
        this.leafTypes = EnumSet.of(Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.DARK_OAK_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.CHERRY_LEAVES, Material.MANGROVE_LEAVES);
        this.plugin = plugin;
        this.addLeafIfPresent("RED_POPLAR_LEAVES");
        this.addLeafIfPresent("ORANGE_POPLAR_LEAVES");
        this.addLeafIfPresent("YELLOW_POPLAR_LEAVES");
    }

    /**
     * 若该名称对应的方块存在，则将其加入可腐烂树叶集合（用于兼容其他插件的自定义树叶）。
     */
    private void addLeafIfPresent(String name) {
        Material material = Material.matchMaterial(name);
        if (material != null) {
            this.leafTypes.add(material);
        }

    }

    /**
     * 注册监听器。
     */
    public void register() {
        this.registered = true;
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 注销监听器并清空待处理区域。
     */
    public void unregister() {
        this.registered = false;
        this.pendingAreas.clear();
        BlockBreakEvent.getHandlerList().unregister(this);
    }

    /**
     * 监听方块破坏事件：若被破坏的是原木，则在下一 tick 对周围树叶执行快速腐烂扫描。
     * 以 MONITOR 优先级、忽略已取消事件的方式运行，确保只在破坏实际发生后触发。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.MONITOR
    )
    public void onLogBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (this.logTypes.contains(block.getType())) {
            DecayArea area = FastLeafDecayService.DecayArea.from(block);
            if (this.pendingAreas.add(area)) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    try {
                        if (this.registered) {
                            this.fastDecayAround(block);
                        }
                    } finally {
                        this.pendingAreas.remove(area);
                    }

                }, 20L);
            }
        }
    }

    /**
     * 扫描以指定原木为中心、半径 7 的立方体区域，对满足条件的树叶触发自然腐烂。
     */
    private void fastDecayAround(Block originLog) {
        World world = originLog.getWorld();
        if (world != null) {
            AutumnSoilPainter autumnPainter = AutumnSoilPainter.instanceOrNull();
            int radius = 7;
            int originX = originLog.getX();
            int originY = originLog.getY();
            int originZ = originLog.getZ();
            int minY = Math.max(world.getMinHeight(), originY - radius);
            int maxY = Math.min(world.getMaxHeight() - 1, originY + radius);

            for (int x = originX - radius; x <= originX + radius; ++x) {
                for (int z = originZ - radius; z <= originZ + radius; ++z) {
                    if (world.isChunkLoaded(x >> 4, z >> 4)) {
                        for (int y = minY; y <= maxY; ++y) {
                            Block leaf = world.getBlockAt(x, y, z);
                            if (this.leafTypes.contains(leaf.getType())) {
                                BlockData blockData = leaf.getBlockData();
                                if (blockData instanceof Leaves) {
                                    Leaves leaves = (Leaves) blockData;
                                    if (!leaves.isPersistent() && leaves.getDistance() >= 7 && !(ThreadLocalRandom.current().nextDouble() >= 0.6)) {
                                        if (autumnPainter != null && autumnPainter.restoreOriginalLeafForDecay(leaf)) {
                                            blockData = leaf.getBlockData();
                                            if (!(blockData instanceof Leaves)) {
                                                continue;
                                            }

                                            Leaves refreshedLeaves = (Leaves) blockData;
                                            if (refreshedLeaves.isPersistent() || refreshedLeaves.getDistance() < 7) {
                                                continue;
                                            }
                                        }

                                        LeavesDecayEvent decayEvent = new LeavesDecayEvent(leaf);
                                        Bukkit.getPluginManager().callEvent(decayEvent);
                                        if (!decayEvent.isCancelled() && this.leafTypes.contains(leaf.getType())) {
                                            leaf.breakNaturally();
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
     * 用于去重的区域标识，按世界 UUID 与 8×8×8 区块坐标划分。
     */
    private record DecayArea(UUID worldId, int sectionX, int sectionY, int sectionZ) {
        private DecayArea {
        }

        /**
         * 根据被破坏原木的坐标构造其所属区域。
         */
        private static DecayArea from(Block block) {
            return new DecayArea(block.getWorld().getUID(), block.getX() >> 3, block.getY() >> 3, block.getZ() >> 3);
        }
    }
}
