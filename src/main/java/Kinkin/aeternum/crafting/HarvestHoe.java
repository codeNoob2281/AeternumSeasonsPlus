package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.food.SeasonFoods;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 丰收锄（Harvest Hoe）。
 *
 * <p>本类负责注册并维护“丰收锄”自定义物品：
 * <ul>
 *     <li>注册由小麦 + 任意锄 + 绿宝石合成的有序配方，并在合成预览时校验材料；</li>
 *     <li>为合成产物写入名称、描述与持久化标签；</li>
 *     <li>玩家用丰收锄破坏成熟作物时，会以该方块为中心一次性收割周围 5x5 范围内的成熟作物；</li>
 *     <li>收割时会尝试消耗玩家背包中的种子自动补种，并消耗锄的耐久。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 与 {@link SeasonFoods} 提供语言文本、自定义作物支持。</p>
 */
public final class HarvestHoe implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final int HARVEST_RADIUS = 2;

    public HarvestHoe(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.itemTagKey = new NamespacedKey(plugin, "harvest_hoe_item");
        this.recipeKey = new NamespacedKey(plugin, "harvest_hoe");
    }

    /**
     * 向服务器注册事件监听器与合成配方。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    /**
     * 注销事件监听器并移除已注册的合成配方。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    /**
     * 注册丰收锄的合成配方：W 小麦、H 任意锄、E 绿宝石。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.IRON_HOE);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"WWW", "EHE", "EEE"});
        Material[] hoeMats = (Material[]) Arrays.stream(Material.values()).filter(Material::isItem).filter((material) -> material.name().endsWith("_HOE")).toArray((length) -> new Material[length]);
        RecipeChoice hoeChoice = new RecipeChoice.MaterialChoice(hoeMats);
        recipe.setIngredient('W', Material.WHEAT);
        recipe.setIngredient('H', hoeChoice);
        recipe.setIngredient('E', Material.EMERALD);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为丰收锄配方时，
     * 校验摆放的材料（一把锄、至少 1 个小麦与 1 个绿宝石），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    ItemStack foundHoe = null;
                    int wheat = 0;
                    int emerald = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (this.isHoe(type)) {
                                if (foundHoe != null) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                foundHoe = stack;
                            } else if (type == Material.WHEAT) {
                                wheat += stack.getAmount();
                            } else {
                                if (type != Material.EMERALD) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                emerald += stack.getAmount();
                            }
                        }
                    }

                    if (foundHoe != null && wheat >= 1 && emerald >= 1) {
                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            crafter = player;
                        }

                        ItemStack result = new ItemStack(foundHoe.getType());
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.harvest_hoe.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.harvest_hoe.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.harvest_hoe.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.harvest_hoe.lore3");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3));
                            } catch (Throwable ignored) {
                            }

                            result.setItemMeta(meta);
                        }

                        event.getInventory().setResult(result);
                    } else {
                        event.getInventory().setResult((ItemStack) null);
                    }
                }
            }
        }
    }

    /**
     * 判断给定材料是否为任意锄（含原版与自定义命名空间）。
     */
    private boolean isHoe(Material material) {
        if (material == null) {
            return false;
        } else if (material.name().endsWith("_HOE")) {
            return true;
        } else {
            NamespacedKey key = material.getKey();
            return key != null && "minecraft".equals(key.getNamespace()) && key.getKey().endsWith("_hoe");
        }
    }

    /**
     * 判断物品是否为带有丰收锄持久化标签的锄。
     */
    private boolean isHarvestHoe(ItemStack stack) {
        if (stack == null) {
            return false;
        } else if (!this.isHoe(stack.getType())) {
            return false;
        } else {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte) meta.getPersistentDataContainer().get(this.itemTagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        }
    }

    /**
     * 监听方块破坏：玩家手持丰收锄破坏成熟作物时，取消原事件并触发范围收割。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGH
    )
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            ItemStack hoe = player.getInventory().getItemInMainHand();
            if (this.isHarvestHoe(hoe)) {
                Block center = event.getBlock();
                BlockData blockData = center.getBlockData();
                if (blockData instanceof Ageable) {
                    Ageable ageableCenter = (Ageable) blockData;
                    if (this.isMature(ageableCenter)) {
                        event.setCancelled(true);
                        this.harvestArea(player, hoe, center);
                    }
                }
            }
        }
    }

    /**
     * 判断可生长方块是否已达到最大成熟度。
     */
    private boolean isMature(Ageable data) {
        return data.getAge() >= data.getMaximumAge();
    }

    /**
     * 以指定方块为中心，一次性收割周围范围内的成熟作物，
     * 并在种子充足时自动补种、扣除锄的耐久。
     */
    private void harvestArea(Player player, ItemStack hoe, Block center) {
        World world = center.getWorld();
        List<HarvestTarget> targets = new ArrayList();
        this.addIfMature(targets, center);

        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if (dx != 0 || dz != 0) {
                    Block block = world.getBlockAt(center.getX() + dx, center.getY(), center.getZ() + dz);
                    this.addIfMature(targets, block);
                }
            }
        }

        if (!targets.isEmpty()) {
            Map<SeedKey, Integer> need = new HashMap();

            for (HarvestTarget target : targets) {
                if (target.canReplant) {
                    need.merge(target.seed, 1, Integer::sum);
                }
            }

            Map<SeedKey, Integer> availableForPlant = new HashMap();

            for (Map.Entry<SeedKey, Integer> entry : need.entrySet()) {
                SeedKey seed = (SeedKey) entry.getKey();
                int required = (Integer) entry.getValue();
                int removed = this.removeUpToFromPlayer(player, seed, required);
                availableForPlant.put(seed, removed);
            }

            int harvested = 0;

            for (HarvestTarget target : targets) {
                Block block = target.block;
                int seedsLeft = target.canReplant ? (Integer) availableForPlant.getOrDefault(target.seed, 0) : 0;
                boolean replant = seedsLeft > 0;
                SeasonFoods seasonFoods = this.plugin.getSeasonFoods();
                Collection<ItemStack> drops;
                if (target.artificialCropId != null && seasonFoods != null) {
                    ItemStack customDrop = seasonFoods.createArtificialCropDrop(target.artificialCropId, player, 2 + ThreadLocalRandom.current().nextInt(4));
                    drops = customDrop != null ? List.of(customDrop) : List.of();
                } else {
                    drops = block.getDrops(hoe, player);
                }

                block.setType(Material.AIR, false);

                for (ItemStack drop : drops) {
                    if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                        world.dropItemNaturally(block.getLocation().add((double) 0.5F, 0.2, (double) 0.5F), drop);
                    }
                }

                if (target.artificialCropId != null && seasonFoods != null) {
                    seasonFoods.finishArtificialHarvest(block, target.artificialCropId, replant);
                } else if (replant) {
                    block.setType(target.cropType, false);
                    BlockData blockData = block.getBlockData();
                    if (blockData instanceof Ageable) {
                        Ageable newAge = (Ageable) blockData;
                        newAge.setAge(0);
                        block.setBlockData(newAge, false);
                    }
                }

                if (replant) {
                    availableForPlant.put(target.seed, seedsLeft - 1);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add((double) 0.5F, 0.4, (double) 0.5F), 4, 0.2, 0.2, 0.2);
                }

                ++harvested;
            }

            if (harvested > 0) {
                this.damageHoe(player, hoe, harvested);
                world.playSound(center.getLocation(), Sound.ITEM_HOE_TILL, 0.8F, 1.1F);
            }

        }
    }

    /**
     * 若方块为成熟作物，则将其加入待收割列表，并记录作物类型、种子与能否补种的信息。
     */
    private void addIfMature(List<HarvestTarget> out, Block block) {
        BlockData blockData = block.getBlockData();
        if (blockData instanceof Ageable age) {
            if (this.isMature(age)) {
                Material crop = block.getType();
                SeasonFoods seasonFoods = this.plugin.getSeasonFoods();
                String artificialCropId = seasonFoods != null ? seasonFoods.getArtificialCropId(block) : null;
                if (artificialCropId != null) {
                    Material seedMaterial = seasonFoods.getArtificialSeedMaterial(artificialCropId);
                    if (seedMaterial != null) {
                        Block below = block.getRelative(0, -1, 0);
                        out.add(new HarvestTarget(block, crop, new SeedKey(seedMaterial, artificialCropId), below.getType() == Material.FARMLAND, artificialCropId));
                    }
                } else {
                    Material seed = this.seedForCrop(crop);
                    if (seed == null) {
                        out.add(new HarvestTarget(block, crop, new SeedKey(Material.AIR, (String) null), false, (String) null));
                    } else {
                        Block below = block.getRelative(0, -1, 0);
                        boolean can = this.canReplantOn(crop, below.getType());
                        out.add(new HarvestTarget(block, crop, new SeedKey(seed, (String) null), can, (String) null));
                    }
                }
            }
        }
    }

    /**
     * 判断指定作物能否补种在其下方的方块上（下界疣需灵魂沙，其余需耕地）。
     */
    private boolean canReplantOn(Material crop, Material below) {
        if (crop == Material.NETHER_WART) {
            return below == Material.SOUL_SAND;
        } else {
            return below == Material.FARMLAND;
        }
    }

    /**
     * 返回指定作物对应的种子材料；无法识别时返回 null。
     */
    private Material seedForCrop(Material crop) {
        switch (crop) {
            case WHEAT -> {
                return Material.WHEAT_SEEDS;
            }
            case CARROTS -> {
                return Material.CARROT;
            }
            case POTATOES -> {
                return Material.POTATO;
            }
            case BEETROOTS -> {
                return Material.BEETROOT_SEEDS;
            }
            case NETHER_WART -> {
                return Material.NETHER_WART;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 从玩家背包（含副手）中移除至多指定数量的指定种子，返回实际移除的数量。
     */
    private int removeUpToFromPlayer(Player player, SeedKey seed, int amount) {
        if (amount <= 0) {
            return 0;
        } else {
            PlayerInventory inv = player.getInventory();
            int removed = 0;
            ItemStack[] storage = inv.getStorageContents();
            boolean changed = false;

            for (int i = 0; i < storage.length && removed < amount; ++i) {
                ItemStack stack = storage[i];
                if (this.matchesSeed(stack, seed)) {
                    int take = Math.min(stack.getAmount(), amount - removed);
                    stack.setAmount(stack.getAmount() - take);
                    removed += take;
                    changed = true;
                    if (stack.getAmount() <= 0) {
                        storage[i] = null;
                    }
                }
            }

            if (changed) {
                inv.setStorageContents(storage);
            }

            if (removed < amount) {
                ItemStack offHand = inv.getItemInOffHand();
                if (this.matchesSeed(offHand, seed)) {
                    int take = Math.min(offHand.getAmount(), amount - removed);
                    offHand.setAmount(offHand.getAmount() - take);
                    removed += take;
                    if (offHand.getAmount() <= 0) {
                        inv.setItemInOffHand((ItemStack) null);
                    } else {
                        inv.setItemInOffHand(offHand);
                    }
                }
            }

            return removed;
        }
    }

    /**
     * 判断物品是否与指定种子标识匹配（区分自定义作物种子与普通种子）。
     */
    private boolean matchesSeed(ItemStack stack, SeedKey seed) {
        if (stack != null && stack.getType() == seed.material) {
            SeasonFoods seasonFoods = this.plugin.getSeasonFoods();
            if (seed.artificialCropId != null) {
                return seasonFoods != null && seasonFoods.isArtificialSeed(stack, seed.artificialCropId);
            } else {
                return seasonFoods == null || !seasonFoods.isAnyArtificialSeed(stack);
            }
        } else {
            return false;
        }
    }

    /**
     * 依据本次收割的方块数扣除锄的耐久；耐久耗尽则移除手中物品并播放损坏音效。
     */
    private void damageHoe(Player player, ItemStack hoe, int harvestedBlocks) {
        if (hoe != null) {
            ItemMeta meta = hoe.getItemMeta();
            if (meta instanceof Damageable) {
                Damageable dmg = (Damageable) meta;
                int damage = dmg.getDamage();
                int toAdd = Math.max(1, harvestedBlocks);
                int newDamage = damage + toAdd;
                short max = hoe.getType().getMaxDurability();
                if (newDamage >= max) {
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
                    player.getInventory().setItemInMainHand((ItemStack) null);
                } else {
                    dmg.setDamage(newDamage);
                    hoe.setItemMeta(dmg);
                    player.getInventory().setItemInMainHand(hoe);
                }
            }
        }
    }

    /**
     * 种子标识：作物对应的种子材料，以及可选的（自定义）作物 id。
     */
    private record SeedKey(Material material, String artificialCropId) {
        private SeedKey {
        }
    }

    /**
     * 单个待收割方块的上下文：方块本体、作物类型、种子标识、能否补种、自定义作物 id。
     */
    private record HarvestTarget(Block block, Material cropType, SeedKey seed, boolean canReplant,
                                 String artificialCropId) {
        private HarvestTarget {
        }
    }
}
