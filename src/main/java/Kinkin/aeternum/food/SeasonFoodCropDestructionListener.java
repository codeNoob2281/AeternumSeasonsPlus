package Kinkin.aeternum.food;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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

final class SeasonFoodCropDestructionListener implements Listener {
    private final SeasonFoods foods;

    SeasonFoodCropDestructionListener(SeasonFoods foods) {
        super();
        this.foods = foods;
    }

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

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && event.getTo() != Material.FARMLAND) {
            Entity var4 = event.getEntity();
            Player var10000;
            if (var4 instanceof Player) {
                Player candidate = (Player)var4;
                var10000 = candidate;
            } else {
                var10000 = null;
            }

            Player player = var10000;
            this.destroyCrop(event.getBlock().getRelative(BlockFace.UP), player, true);
        }
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onEntityExplosion(EntityExplodeEvent event) {
        this.destroyExplodedCrops(event.blockList());
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onBlockExplosion(BlockExplodeEvent event) {
        this.destroyExplodedCrops(event.blockList());
    }

    private void destroyExplodedCrops(List<Block> affectedBlocks) {
        Set<Block> crops = new LinkedHashSet();

        for(Block affected : List.copyOf(affectedBlocks)) {
            if (this.foods.getArtificialCropId(affected) != null) {
                crops.add(affected);
            }

            Block cropAbove = affected.getRelative(BlockFace.UP);
            if (this.foods.getArtificialCropId(cropAbove) != null) {
                crops.add(cropAbove);
            }
        }

        for(Block crop : crops) {
            affectedBlocks.remove(crop);
            this.destroyCrop(crop, (Player)null, true);
        }

    }

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
                    Location location = crop.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
                    crop.getWorld().dropItemNaturally(location, drop);
                }

                return true;
            }
        }
    }

    private int dropAmount(Block crop) {
        BlockData var3 = crop.getBlockData();
        if (var3 instanceof Ageable ageable) {
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
