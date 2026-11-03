package kinkin.aeternum.food;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 季节食物作物破坏监听器（Season Food Crop Destruction Listener）。
 *
 * <p>在方块被玩家破坏、被耕地踩踏、或被爆炸波及等场景下，负责清理由 {@link SeasonFoods}
 * 追踪的“人工作物”，并按作物成熟度生成对应掉落物；普通作物不受影响。</p>
 *
 * <p>依赖 {@link SeasonFoods} 提供的作物识别、登记注销与掉落物构建接口。</p>
 */
final class SeasonFoodCropDestructionListener implements Listener {
    private final SeasonFoods foods;

    SeasonFoodCropDestructionListener(SeasonFoods foods) {
        super();
        this.foods = foods;
    }

    /**
     * 监听玩家破坏方块：若破坏的正是人工作物，则直接清理并取消原版掉落；
     * 否则检查其正上方一格是否生长着人工作物（例如作物长在耕地上）。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        if (this.foods.getArtificialCropId(broken) != null) {
            if (this.destroyCrop(broken, event.getPlayer(), false)) {
                event.setDropItems(false);
            }

        } else {
            this.destroyCrop(broken.getRelative(BlockFace.UP), event.getPlayer(), true);
        }
    }

    /**
     * 监听实体把耕地踩成其他方块：若踩踏者是玩家，则一并清理耕地上方的人工作物。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && event.getTo() != Material.FARMLAND) {
            Entity sourceEntity = event.getEntity();
            Player playerOrNull;
            if (sourceEntity instanceof Player) {
                Player candidate = (Player) sourceEntity;
                playerOrNull = candidate;
            } else {
                playerOrNull = null;
            }

            Player player = playerOrNull;
            this.destroyCrop(event.getBlock().getRelative(BlockFace.UP), player, true);
        }
    }

    /**
     * 监听实体爆炸（如苦力怕、TNT）：清理被波及的人工作物。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onEntityExplosion(EntityExplodeEvent event) {
        this.destroyExplodedCrops(event.blockList());
    }

    /**
     * 监听方块爆炸（如床、重生锚）：清理被波及的人工作物。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBlockExplosion(BlockExplodeEvent event) {
        this.destroyExplodedCrops(event.blockList());
    }

    /**
     * 从爆炸波及的方块列表中找出人工作物（含其正上方一格），
     * 将其从原版掉落/破坏列表中移除并逐一销毁，避免重复掉落。
     */
    private void destroyExplodedCrops(List<Block> affectedBlocks) {
        Set<Block> crops = new LinkedHashSet();

        for (Block affected : List.copyOf(affectedBlocks)) {
            if (this.foods.getArtificialCropId(affected) != null) {
                crops.add(affected);
            }

            Block cropAbove = affected.getRelative(BlockFace.UP);
            if (this.foods.getArtificialCropId(cropAbove) != null) {
                crops.add(cropAbove);
            }
        }

        for (Block crop : crops) {
            affectedBlocks.remove(crop);
            this.destroyCrop(crop, (Player) null, true);
        }

    }

    /**
     * 销毁指定的人工作物：解除追踪，并按成熟度掉落物品。
     *
     * @param crop       目标作物方块
     * @param player     触发破坏的玩家，可为 {@code null}
     * @param clearBlock 是否把方块清空为空气
     * @return 是否确实销毁了一株人工作物
     */
    private boolean destroyCrop(Block crop, Player player, boolean clearBlock) {
        String cropId = this.foods.getArtificialCropId(crop);
        if (cropId == null) {
            return false;
        } else {
            int amount = this.dropAmount(crop);
            if (!this.foods.untrackArtificialLandCrop(crop, cropId)) {
                return false;
            } else {
                if (clearBlock) {
                    crop.setType(Material.AIR, false);
                }

                ItemStack drop = this.foods.createArtificialCropDrop(cropId, player, amount);
                if (drop != null) {
                    Location location = crop.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
                    crop.getWorld().dropItemNaturally(location, drop);
                }

                return true;
            }
        }
    }

    /**
     * 根据作物方块数据的成熟度计算掉落数量。
     */
    private int dropAmount(Block crop) {
        BlockData blockData = crop.getBlockData();
        if (blockData instanceof Ageable ageable) {
            if (ageable.getAge() >= ageable.getMaximumAge()) {
                return 2 + ThreadLocalRandom.current().nextInt(4);
            } else {
                return ageable.getAge() > 0 ? 1 + ThreadLocalRandom.current().nextInt(2) : 1;
            }
        } else {
            return 1;
        }
    }
}
