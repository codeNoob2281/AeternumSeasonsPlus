package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 高级堆肥桶（Advanced Composter）。
 *
 * <p>本类负责注册并维护可放置的“高级堆肥桶”方块物品，并扩展原版堆肥桶的行为：
 * <ul>
 *     <li>注册由树叶 + 漏斗 + 堆肥桶 + 南瓜合成的有序配方，并在合成预览时校验材料；</li>
 *     <li>为合成的堆肥桶写入名称、描述、隐藏标记并打上持久化标签；</li>
 *     <li>记录玩家放置的高级堆肥桶位置（持久化到数据文件），破坏时掉落对应的自定义物品；</li>
 *     <li>允许用更多种类的可堆肥物（种子、作物、树叶等）手动填充，秋季成功率更高；</li>
 *     <li>支持与漏斗之间的自动输入/输出，并在满层时产骨粉。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}、{@link SeasonService} 提供语言文本与季节状态。</p>
 */
public final class AdvancedComposter implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> advancedComposters = Collections.synchronizedSet(new HashSet());
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;
    private final Map<String, BukkitTask> pendingTransfers = new HashMap();
    private static final Set<Material> COMPOSTABLE;

    public AdvancedComposter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "advanced_composter_item");
        this.recipeKey = new NamespacedKey(plugin, "advanced_composter");
        this.dataFile = new File(plugin.getDataFolder(), "advanced_composter_data.yml");
        this.loadData();
    }

    /** 从数据文件读取已放置的高级堆肥桶位置；若只有旧路径的数据文件，则迁移并保存到新路径。 */
    private void loadData() {
        this.advancedComposters.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.advancedComposters.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/advanced_composter_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.advancedComposters.addAll(list);
                this.saveData();
            }

        }
    }

    /** 将已放置的高级堆肥桶位置写入数据文件。 */
    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.advancedComposters));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar advanced_composter_data.yml: " + ex.getMessage());
        }

    }

    /** 向服务器注册事件监听器与合成配方。 */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    /** 注销事件监听器、取消所有待处理的漏斗传输任务并移除已注册的合成配方。 */
    public void unregister() {
        this.pendingTransfers.values().forEach(BukkitTask::cancel);
        this.pendingTransfers.clear();
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    /** 注册高级堆肥桶的合成配方：L 树叶、H 漏斗、C 堆肥桶、P 南瓜。 */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.COMPOSTER);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"LHL", "PCP", "LHL"});
        RecipeChoice leavesChoice = new RecipeChoice.MaterialChoice(new Material[]{Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES});
        recipe.setIngredient('L', leavesChoice);
        recipe.setIngredient('H', Material.HOPPER);
        recipe.setIngredient('C', Material.COMPOSTER);
        recipe.setIngredient('P', Material.PUMPKIN);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为高级堆肥桶配方时，
     * 校验摆放的材料（恰好 1 个堆肥桶、至少 2 个漏斗、2 个南瓜与 4 个树叶），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe)recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    int composters = 0;
                    int hoppers = 0;
                    int pumpkins = 0;
                    int leaves = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (type == Material.COMPOSTER) {
                                composters += stack.getAmount();
                            } else if (type == Material.HOPPER) {
                                hoppers += stack.getAmount();
                            } else if (type == Material.PUMPKIN) {
                                pumpkins += stack.getAmount();
                            } else {
                                if (!this.isLeaves(type)) {
                                    event.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                leaves += stack.getAmount();
                            }
                        }
                    }

                    if (composters == 1 && hoppers >= 2 && pumpkins >= 2 && leaves >= 4) {
                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player)viewer;
                            crafter = player;
                        }

                        ItemStack result = new ItemStack(Material.COMPOSTER);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.advanced_composter.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore3");
                                List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                                finalLore.add(PERSISTENT_LORE_TAG);
                                meta.setDisplayName(name);
                                meta.setLore(finalLore);
                            } catch (Throwable ignored) {
                            }

                            result.setItemMeta(meta);
                        }

                        event.getInventory().setResult(result);
                    } else {
                        event.getInventory().setResult((ItemStack)null);
                    }
                }
            }
        }
    }

    /** 判断给定材料是否为树叶。 */
    private boolean isLeaves(Material material) {
        return material.name().endsWith("_LEAVES");
    }

    /**
     * 判断物品是否为高级堆肥桶。优先读取持久化标签；若缺失则根据显示名与描述推断，
     * 命中后补写标签并返回 true。
     */
    private boolean isAdvancedComposterItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.COMPOSTER) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Byte flag = (Byte)pdc.get(this.itemTagKey, PersistentDataType.BYTE);
                if (flag != null && flag == 1) {
                    return true;
                } else {
                    String displayRaw = meta.hasDisplayName() ? meta.getDisplayName() : "";
                    String display = ChatColor.stripColor(displayRaw);
                    List<String> lore = meta.getLore() != null ? meta.getLore() : Collections.emptyList();
                    boolean looksLikeAdvanced = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_AC"))) {
                        looksLikeAdvanced = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String firstLore = ChatColor.stripColor((String)lore.get(0));
                        String firstLoreLower = firstLore.toLowerCase(Locale.ROOT);
                        if (firstLoreLower.contains("composter") || firstLoreLower.contains("compostador")) {
                            looksLikeAdvanced = true;
                        }
                    }

                    if (looksLikeAdvanced) {
                        pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);
                        stack.setItemMeta(meta);
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else {
            return false;
        }
    }

    /** 监听方块放置：放置的是高级堆肥桶时，记录该位置并保存数据。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (this.isAdvancedComposterItem(inHand)) {
            Block block = event.getBlockPlaced();
            if (block.getType() == Material.COMPOSTER) {
                this.advancedComposters.add(this.blockKey(block));
                this.saveData();
            }
        }
    }

    /** 监听方块破坏：破坏的是已登记的高级堆肥桶时，取消原掉落并改为掉落对应的自定义物品。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.COMPOSTER) {
            String key = this.blockKey(block);
            if (this.advancedComposters.remove(key)) {
                event.setDropItems(false);
                this.saveData();
                Player breaker = event.getPlayer();
                ItemStack drop = new ItemStack(Material.COMPOSTER);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.advanced_composter.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore3");
                        List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                        finalLore.add(PERSISTENT_LORE_TAG);
                        meta.setDisplayName(name);
                        meta.setLore(finalLore);
                    } catch (Throwable ignored) {
                    }

                    drop.setItemMeta(meta);
                }

                block.getWorld().dropItemNaturally(block.getLocation().add((double)0.5F, 0.1, (double)0.5F), drop);
            }
        }
    }

    /** 生成方块的位置键：世界名;x;y;z。 */
    private String blockKey(Block block) {
        String worldName = block.getWorld().getName();
        return worldName + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    /** 判断方块是否为已登记的高级堆肥桶。 */
    private boolean isAdvancedComposterBlock(Block block) {
        return block.getType() == Material.COMPOSTER && this.advancedComposters.contains(this.blockKey(block));
    }

    /** 由容器对应的方块位置反查方块；若所在区块未加载则返回 null。 */
    private Block inventoryBlock(Inventory inventory) {
        Location location = inventory.getLocation();
        return location != null && location.getWorld() != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) ? location.getBlock() : null;
    }

    /**
     * 监听漏斗物品传输：当传输的一端是高级堆肥桶时，取消原版传输，
     * 改为在下一 tick 自行处理——将物品堆肥进桶，或在桶满层时把骨粉输出到漏斗。
     * 通过 pendingTransfers 防止同一堆肥桶上的重复排队。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onHopperTransfer(InventoryMoveItemEvent event) {
        Block destination = this.inventoryBlock(event.getDestination());
        Block source = this.inventoryBlock(event.getSource());
        boolean inserting = destination != null && this.isAdvancedComposterBlock(destination);
        Block composter = inserting ? destination : source;
        if (composter != null && this.isAdvancedComposterBlock(composter)) {
            event.setCancelled(true);
            String key = this.blockKey(composter);
            if (!this.pendingTransfers.containsKey(key)) {
                ItemStack item = event.getItem().clone();
                item.setAmount(1);
                Inventory other = inserting ? event.getSource() : event.getDestination();
                this.pendingTransfers.put(key, Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.pendingTransfers.remove(key);
                    if (composter.getWorld().isChunkLoaded(composter.getX() >> 4, composter.getZ() >> 4) && this.isAdvancedComposterBlock(composter)) {
                        Block otherBlock = this.inventoryBlock(other);
                        if (otherBlock != null) {
                            BlockState otherState = otherBlock.getState();
                            if (otherState instanceof Hopper) {
                                Hopper hopper = (Hopper)otherState;
                                BlockData otherBlockData = otherBlock.getBlockData();
                                if (otherBlockData instanceof org.bukkit.block.data.type.Hopper) {
                                    org.bukkit.block.data.type.Hopper hopperData = (org.bukkit.block.data.type.Hopper)otherBlockData;
                                    if (hopperData.isEnabled()) {
                                        if (inserting && !otherBlock.getRelative(hopperData.getFacing()).equals(composter)) {
                                            return;
                                        }

                                        if (!inserting && !otherBlock.getRelative(BlockFace.UP).equals(composter)) {
                                            return;
                                        }

                                        Inventory inventory = hopper.getInventory();
                                        Levelled data = (Levelled)composter.getBlockData();
                                        boolean autumn = this.seasons.getStateCopy(composter.getWorld()).season == Season.AUTUMN;
                                        if (inserting) {
                                            if (data.getLevel() >= data.getMaximumLevel() || !COMPOSTABLE.contains(item.getType())) {
                                                return;
                                            }

                                            if (!inventory.containsAtLeast(item, 1)) {
                                                return;
                                            }

                                            if (!inventory.removeItem(new ItemStack[]{item.clone()}).isEmpty()) {
                                                return;
                                            }

                                            int increment = (this.tryCompost(item.getType(), autumn) ? 1 : 0) + (this.tryCompost(item.getType(), autumn) ? 1 : 0);
                                            data.setLevel(Math.min(data.getMaximumLevel(), data.getLevel() + increment));
                                        } else {
                                            if (data.getLevel() < data.getMaximumLevel()) {
                                                return;
                                            }

                                            ItemStack output = new ItemStack(Material.BONE_MEAL, this.rnd.nextDouble() < (autumn ? 0.6 : 0.35) ? 2 : 1);
                                            int capacity = 0;

                                            for(ItemStack slot : inventory.getStorageContents()) {
                                                if (slot != null && !slot.getType().isAir()) {
                                                    if (slot.isSimilar(output)) {
                                                        capacity += Math.max(0, Math.min(inventory.getMaxStackSize(), slot.getMaxStackSize()) - slot.getAmount());
                                                    }
                                                } else {
                                                    capacity += Math.min(inventory.getMaxStackSize(), output.getMaxStackSize());
                                                }
                                            }

                                            if (capacity < output.getAmount()) {
                                                return;
                                            }

                                            inventory.addItem(new ItemStack[]{output});
                                            data.setLevel(0);
                                        }

                                        composter.setBlockData(data, true);
                                        return;
                                    }
                                }

                                return;
                            }
                        }

                    }
                }));
            }
        }
    }

    /**
     * 监听玩家右键点击方块：点击的是高级堆肥桶时取消原版交互，
     * 满层则取出骨粉，否则消耗手中可堆肥物填充桶（秋季概率更高）。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null) {
                if (this.isAdvancedComposterBlock(clicked)) {
                    event.setCancelled(true);
                    BlockData blockData = clicked.getBlockData();
                    if (blockData instanceof Levelled) {
                        Levelled data = (Levelled)blockData;
                        int level = data.getLevel();
                        int max = data.getMaximumLevel();
                        Player player = event.getPlayer();
                        CalendarState state = this.seasons.getStateCopy(clicked.getWorld());
                        boolean isAutumn = state.season == Season.AUTUMN;
                        ItemStack hand = event.getItem();
                        Material handType = hand != null ? hand.getType() : Material.AIR;
                        if (level >= max) {
                            this.extractBoneMeal(clicked, data, player, isAutumn);
                        } else if (hand != null && handType != Material.AIR) {
                            if (COMPOSTABLE.contains(handType)) {
                                this.consumeOneFromHand(player, event.getHand(), hand);
                                int inc = 0;
                                if (this.tryCompost(handType, isAutumn)) {
                                    ++inc;
                                }

                                if (this.tryCompost(handType, isAutumn)) {
                                    ++inc;
                                }

                                if (inc <= 0) {
                                    clicked.getWorld().spawnParticle(Particle.ASH, clicked.getLocation().add((double)0.5F, 0.8, (double)0.5F), 4, 0.2, 0.2, 0.2);
                                    clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_COMPOSTER_FILL, 0.6F, 0.4F);
                                } else {
                                    level += inc;
                                    if (level > max) {
                                        level = max;
                                    }

                                    data.setLevel(level);
                                    clicked.setBlockData(data, true);
                                    clicked.getWorld().spawnParticle(Particle.CHERRY_LEAVES, clicked.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), 12, 0.4, 0.3, 0.4);
                                    clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.8F, 1.0F);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 从玩家手中扣除一个物品（按主手/副手分别写回）。 */
    private void consumeOneFromHand(Player player, EquipmentSlot slot, ItemStack hand) {
        if (hand != null) {
            int amount = hand.getAmount();
            if (amount <= 1) {
                if (slot == EquipmentSlot.HAND) {
                    player.getInventory().setItemInMainHand((ItemStack)null);
                } else if (slot == EquipmentSlot.OFF_HAND) {
                    player.getInventory().setItemInOffHand((ItemStack)null);
                }
            } else {
                hand.setAmount(amount - 1);
                if (slot == EquipmentSlot.HAND) {
                    player.getInventory().setItemInMainHand(hand);
                } else if (slot == EquipmentSlot.OFF_HAND) {
                    player.getInventory().setItemInOffHand(hand);
                }
            }

        }
    }

    /** 按概率判定一次堆肥是否成功（秋季成功率更高）。 */
    private boolean tryCompost(Material material, boolean isAutumn) {
        double base = 0.55;
        if (isAutumn) {
            base += 0.2;
        }

        return this.rnd.nextDouble() < base;
    }

    /** 从满层的高级堆肥桶中取出骨粉并重置层数，秋季额外产量概率更高。 */
    private void extractBoneMeal(Block composter, Levelled data, Player player, boolean isAutumn) {
        World world = composter.getWorld();
        double extraChance = isAutumn ? 0.6 : 0.35;
        int amount = 1;
        if (this.rnd.nextDouble() < extraChance) {
            ++amount;
        }

        ItemStack boneMeal = new ItemStack(Material.BONE_MEAL, amount);
        world.dropItemNaturally(composter.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), boneMeal);
        data.setLevel(0);
        composter.setBlockData(data, true);
        world.spawnParticle(Particle.CHERRY_LEAVES, composter.getLocation().add((double)0.5F, 1.2, (double)0.5F), 16, (double)0.5F, 0.4, (double)0.5F);
        world.playSound(composter.getLocation(), Sound.BLOCK_COMPOSTER_EMPTY, 0.9F, 1.0F);
    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_AC";
        COMPOSTABLE = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.PUMPKIN_SEEDS, Material.MELON_SEEDS, Material.TORCHFLOWER_SEEDS, Material.PITCHER_POD, Material.WHEAT, Material.BEETROOT, Material.CARROT, Material.POTATO, Material.MELON_SLICE, Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, Material.GRASS_BLOCK, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.PINK_TULIP, Material.WHITE_TULIP, Material.RED_TULIP, Material.ORANGE_TULIP);
    }
}
