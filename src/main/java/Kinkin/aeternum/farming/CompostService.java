package kinkin.aeternum.farming;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.util.PlatformScheduler;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 堆肥方块服务。
 *
 * <p>实现一种可放置的“堆肥方块”系统：玩家用配方制作“生堆肥”，放置后在有水环境下
 * 经一段时间发酵为“可耕地堆肥”（可提升作物生长速度）；若被水淹没则会变为“水域堆肥”，
 * 用于在泥土上催生海带或甘蔗。方块状态持久化到 {@code compost-blocks.yml}，
 * 并以定时任务分批推进发酵与生长。</p>
 * <p>依赖插件提供的语言翻译与配置，以及 {@link PlatformScheduler} 进行定时/按区块调度。</p>
 */
public final class CompostService implements Listener, Runnable {
    private static final long FERMENT_TICKS = 48000L;
    private static final int WATER_RADIUS = 1;
    private static final double LAND_BOOST_CHANCE = 0.45;
    private static final int LAND_MAX_EXTRA_AGE = 1;
    private static final long LAND_COOLDOWN_TICKS = 0L;
    private static final int LAND_MAX_BOOSTS_PER_TICK = 5000;
    private static final int SUGARCANE_MAX_PUSHES_PER_RUN = 10;
    private static final double SUGARCANE_STEP_CHANCE = 0.45;
    private static final long SUGARCANE_COOLDOWN_TICKS = 120L;
    private static final int SUGARCANE_MAX_HEIGHT = 3;
    private final Map<Location, Long> landNextBoostAt = new ConcurrentHashMap();
    private final Map<Location, CompostData> compostBlocks = new ConcurrentHashMap();
    private PlatformScheduler.TaskHandle task;
    private static final Set<Material> LAND_CROPS;
    private static final Set<Material> KELP_TYPES;
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey KEY_STAGE;
    private final NamespacedKey KEY_RECIPE;
    private static final int KELP_MAX_PUSHES_PER_RUN = 10;
    private static final int KELP_MAX_STEPS_PER_PUSH = 1;
    private static final double KELP_STEP_CHANCE = 0.45;
    private static final long KELP_COOLDOWN_TICKS = 120L;
    private final File storageFile;

    public CompostService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.KEY_STAGE = new NamespacedKey(plugin, "compost_stage");
        this.KEY_RECIPE = new NamespacedKey(plugin, "compost_block");
        this.storageFile = new File(plugin.getDataFolder(), "compost-blocks.yml");
    }

    /**
     * 注册事件监听、合成配方，加载已保存的方块状态并启动定时任务。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.loadState();
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, 100L, 400L);
    }

    /**
     * 取消定时任务、注销监听并保存当前方块状态。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.saveState();
        this.compostBlocks.clear();
    }

    /**
     * 将当前所有堆肥方块写入 {@code compost-blocks.yml}。
     */
    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        int index = 0;

        for (Map.Entry<Location, CompostData> entry : this.compostBlocks.entrySet()) {
            Location loc = (Location) entry.getKey();
            CompostData data = (CompostData) entry.getValue();
            int currentIndex = index++;
            String path = "blocks." + currentIndex;
            y.set(path + ".world", loc.getWorld().getName());
            y.set(path + ".x", loc.getBlockX());
            y.set(path + ".y", loc.getBlockY());
            y.set(path + ".z", loc.getBlockZ());
            y.set(path + ".stage", data.stage.name());
            y.set(path + ".readyTick", data.readyTick);
        }

        try {
            y.save(this.storageFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[Compost] Could not save compost-blocks.yml: " + ex.getMessage());
        }

    }

    /**
     * 从 {@code compost-blocks.yml} 读取并恢复堆肥方块状态。
     */
    private void loadState() {
        this.compostBlocks.clear();
        if (this.storageFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(this.storageFile);
            ConfigurationSection sec = y.getConfigurationSection("blocks");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String base = "blocks." + key;
                    String worldName = y.getString(base + ".world");
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        int posX = y.getInt(base + ".x");
                        int posY = y.getInt(base + ".y");
                        int posZ = y.getInt(base + ".z");
                        String stageStr = y.getString(base + ".stage", "RAW");
                        long readyTick = y.getLong(base + ".readyTick", 0L);
                        if ("READY".equalsIgnoreCase(stageStr)) {
                            stageStr = "READY_LAND";
                        }

                        Stage stage;
                        try {
                            stage = CompostService.Stage.valueOf(stageStr);
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }

                        Location loc = new Location(world, (double) posX, (double) posY, (double) posZ);
                        this.compostBlocks.put(loc, new CompostData(stage, readyTick));
                    }
                }

            }
        }
    }

    /**
     * 注册“生堆肥”的合成配方。
     */
    private void registerRecipe() {
        ItemStack result = this.createItem(CompostService.Stage.RAW);
        ShapedRecipe recipe = new ShapedRecipe(this.KEY_RECIPE, result);
        recipe.shape(new String[]{"DRR", "WWB", "BBB"});
        recipe.setIngredient('D', Material.DIRT);
        recipe.setIngredient('R', Material.ROTTEN_FLESH);
        recipe.setIngredient('B', Material.BONE_MEAL);
        recipe.setIngredient('W', Material.WHEAT);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createItem(Stage stage) {
        return this.createItem(stage, (Player) null);
    }

    /**
     * 创建指定阶段的堆肥物品，并根据查看者设置显示名称与 Lore。
     *
     * @param stage  堆肥阶段
     * @param viewer 语言上下文玩家，可为 null（使用服务端默认语言）
     * @return 构建出的物品栈
     */
    private ItemStack createItem(Stage stage, Player viewer) {
        ItemStack item = new ItemStack(Material.ROOTED_DIRT, 1);
        ItemMeta meta = item.getItemMeta();
        boolean isRaw = stage == CompostService.Stage.RAW;
        String nameKey = isRaw ? "farming.compost.raw_name" : "farming.compost.ready_name";
        String lore1Key = isRaw ? "farming.compost.raw_lore_1" : "farming.compost.ready_lore_1";
        String lore2Key = isRaw ? "farming.compost.raw_lore_2" : "farming.compost.ready_lore_2";
        String name;
        String lore1;
        String lore2;
        if (this.plugin.lang != null) {
            if (viewer != null) {
                name = this.plugin.lang.tr(viewer, nameKey);
                lore1 = this.plugin.lang.tr(viewer, lore1Key);
                lore2 = this.plugin.lang.tr(viewer, lore2Key);
            } else {
                name = this.plugin.lang.trServer(nameKey);
                lore1 = this.plugin.lang.trServer(lore1Key);
                lore2 = this.plugin.lang.trServer(lore2Key);
            }
        } else {
            name = nameKey;
            lore1 = lore1Key;
            lore2 = lore2Key;
        }

        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore1, lore2));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(this.KEY_STAGE, PersistentDataType.STRING, stage.name());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 从物品的持久化数据读取堆肥阶段；物品无标签或无法识别时返回 null。
     */
    private Stage readStage(ItemStack item) {
        if (item == null) {
            return null;
        } else if (!item.hasItemMeta()) {
            return null;
        } else {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            String s = (String) pdc.get(this.KEY_STAGE, PersistentDataType.STRING);
            if (s == null) {
                return null;
            } else if ("READY".equalsIgnoreCase(s)) {
                return CompostService.Stage.READY_LAND;
            } else {
                try {
                    return CompostService.Stage.valueOf(s);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
    }

    /**
     * 监听堆肥方块放置，按阶段初始化方块类型并记录状态。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        Stage stage = this.readStage(event.getItemInHand());
        if (stage != null) {
            Block block = event.getBlockPlaced();
            Location key = this.blockKey(block);
            long ready = 0L;
            if (stage == CompostService.Stage.RAW) {
                block.setType(Material.ROOTED_DIRT);
                ready = block.getWorld().getFullTime() + 48000L;
            } else if (stage == CompostService.Stage.READY_LAND) {
                block.setType(Material.FARMLAND);
            } else {
                block.setType(this.pickReadyWaterMaterial(key));
            }

            this.compostBlocks.put(key, new CompostData(stage, ready));
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add((double) 0.5F, 0.6, (double) 0.5F), 8, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /**
     * 监听堆肥方块被破坏，掉落对应阶段的物品。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        this.landNextBoostAt.remove(this.blockKey(block));
        Location key = this.blockKey(block);
        CompostData data = (CompostData) this.compostBlocks.remove(key);
        if (data != null) {
            event.setDropItems(false);
            ItemStack drop = this.createItem(data.stage, event.getPlayer());
            block.getWorld().dropItemNaturally(block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), drop);
        }
    }

    /**
     * 监听合成预览，为配方产物生成带玩家语言的堆肥物品。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        HumanEntity viewer = event.getView().getPlayer();
        if (viewer instanceof Player player) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof Keyed keyed) {
                if (keyed.getKey().equals(this.KEY_RECIPE)) {
                    CraftingInventory inventory = event.getInventory();
                    inventory.setResult(this.createItem(CompostService.Stage.RAW, player));
                }
            }
        }
    }

    /**
     * 监听用锄头右键水域堆肥，将其翻为可耕地堆肥。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onHoe(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getClickedBlock() != null) {
                if (event.getItem() != null) {
                    if (event.getHand() == EquipmentSlot.HAND) {
                        Material tool = event.getItem().getType();
                        if (this.isHoe(tool)) {
                            Block soil = event.getClickedBlock();
                            Location key = this.blockKey(soil);
                            CompostData data = (CompostData) this.compostBlocks.get(key);
                            if (data != null) {
                                if (data.stage == CompostService.Stage.READY_WATER) {
                                    if (!this.isFlooded(key)) {
                                        Block above = soil.getRelative(BlockFace.UP);
                                        if (above.getType().isAir()) {
                                            data.stage = CompostService.Stage.READY_LAND;
                                            soil.setType(Material.FARMLAND, false);
                                            soil.getWorld().playSound(soil.getLocation(), Sound.ITEM_HOE_TILL, 1.0F, 1.0F);
                                            soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, soil.getLocation().add((double) 0.5F, 0.8, (double) 0.5F), 10, (double) 0.25F, 0.2, (double) 0.25F, 0.01);
                                            event.setCancelled(true);
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
     * 监听作物生长，在可耕地堆肥上按倍率额外推进生长阶段。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGH
    )
    public void onGrow(BlockGrowEvent event) {
        Block crop = event.getBlock();
        Material newType = event.getNewState().getType();
        if (LAND_CROPS.contains(newType)) {
            Block farmland = crop.getRelative(BlockFace.DOWN);
            CompostData data = (CompostData) this.compostBlocks.get(this.blockKey(farmland));
            if (data != null && data.stage == CompostService.Stage.READY_LAND) {
                BlockData blockData = event.getNewState().getBlockData();
                if (blockData instanceof Ageable) {
                    Ageable age = (Ageable) blockData;
                    double multiplier = this.plugin.getConfig().getDouble("farming.fertilized_soil.growth_multiplier", (double) 2.0F);
                    if (!(multiplier <= (double) 1.0F)) {
                        int curAge = age.getAge();
                        int max = age.getMaximumAge();
                        if (curAge < max) {
                            int extraSteps = (int) (multiplier - (double) 1.0F);
                            int newAge = Math.min(curAge + extraSteps, max);
                            if (newAge != curAge) {
                                age.setAge(newAge);
                                event.getNewState().setBlockData(age);
                                if (this.plugin.getConfig().getBoolean("farming.compost.particles.enabled", true)) {
                                    crop.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, crop.getLocation().add((double) 0.5F, 0.2, (double) 0.5F), 5, 0.2, 0.2, 0.2, 0.02);
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 监听农田被踩踏，保护可耕地堆肥不被破坏。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND) {
            if (event.getTo() == Material.DIRT) {
                CompostData data = (CompostData) this.compostBlocks.get(this.blockKey(event.getBlock()));
                if (data != null && data.stage == CompostService.Stage.READY_LAND) {
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * 定时任务入口：按方块所在区块调度每个堆肥方块的推进逻辑。
     */
    @Override
    public void run() {
        if (!this.compostBlocks.isEmpty()) {
            List<Location> locs = new ArrayList(this.compostBlocks.keySet());
            AtomicInteger kelpBudget = new AtomicInteger(10);
            AtomicInteger caneBudget = new AtomicInteger(10);

            for (Location loc : locs) {
                if (loc != null) {
                    PlatformScheduler.executeAtLocation(this.plugin, loc, () -> this.tickOne(loc, kelpBudget, caneBudget));
                }
            }

        }
    }

    /**
     * 推进单个堆肥方块的状态：处理发酵、耕地/水域转换，以及海带/甘蔗催生。
     *
     * @param loc        方块位置
     * @param kelpBudget 本轮剩余的海带催生次数预算
     * @param caneBudget 本轮剩余的甘蔗催生次数预算
     */
    private void tickOne(Location loc, AtomicInteger kelpBudget, AtomicInteger caneBudget) {
        CompostData data = (CompostData) this.compostBlocks.get(loc);
        if (data != null) {
            World world = loc.getWorld();
            if (world != null && world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                Block block = world.getBlockAt(loc);
                if (block.getType() == Material.AIR) {
                    this.compostBlocks.remove(loc);
                } else if (data.stage == CompostService.Stage.READY_LAND) {
                    if (this.isFlooded(loc)) {
                        data.stage = CompostService.Stage.READY_WATER;
                        block.setType(Material.MUD, false);
                    } else if (block.getType() != Material.FARMLAND) {
                        data.stage = CompostService.Stage.READY_WATER;
                        block.setType(this.pickReadyWaterMaterial(loc), false);
                    } else {
                        Block crop = block.getRelative(BlockFace.UP);
                        if (LAND_CROPS.contains(crop.getType())) {
                            this.tryBoostCrop(crop);
                        }

                    }
                } else if (data.stage == CompostService.Stage.RAW) {
                    if (this.hasWaterNearby(loc)) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add((double) 0.5F, 0.6, (double) 0.5F), 6, (double) 0.25F, (double) 0.25F, (double) 0.25F, 0.01);
                        if (world.getFullTime() >= data.readyTick) {
                            data.stage = CompostService.Stage.READY_LAND;
                            block.setType(Material.FARMLAND, false);
                            world.playSound(block.getLocation(), Sound.BLOCK_COMPOSTER_READY, 1.0F, 1.0F);
                        }
                    }
                } else {
                    if (data.stage == CompostService.Stage.READY_WATER) {
                        Material want = this.pickReadyWaterMaterial(loc);
                        if (block.getType() != want) {
                            block.setType(want, false);
                        }

                        long now = world.getGameTime();
                        if (this.isFlooded(loc) && kelpBudget.get() > 0 && now >= data.kelpNextPushAt && this.tryPushKelpOnSoil(block)) {
                            kelpBudget.decrementAndGet();
                            data.kelpNextPushAt = now + 120L;
                        }

                        if (want == Material.MUD && caneBudget.get() > 0 && now >= data.caneNextPushAt && this.tryPushSugarCaneOnSoil(block)) {
                            caneBudget.decrementAndGet();
                            data.caneNextPushAt = now + 120L;
                        }
                    }

                }
            }
        }
    }

    /**
     * 在堆肥耕地上按概率额外催熟一次作物。
     */
    private void tryBoostCrop(Block crop) {
        if (!(Math.random() > 0.3)) {
            BlockData blockData = crop.getBlockData();
            if (blockData instanceof Ageable) {
                Ageable age = (Ageable) blockData;
                int max = age.getMaximumAge();
                if (age.getAge() < max) {
                    age.setAge(age.getAge() + 1);
                    crop.setBlockData(age);
                    crop.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, crop.getLocation().add((double) 0.5F, 0.3, (double) 0.5F), 3, 0.1, 0.1, 0.1, 0.02);
                }

            }
        }
    }

    /**
     * 尝试在水域堆肥上催生一节甘蔗。
     *
     * @param soil 堆肥方块
     * @return 处理完毕返回 true（不区分是否实际生长）
     */
    private boolean tryPushSugarCaneOnSoil(Block soil) {
        Block first = soil.getRelative(BlockFace.UP);
        if (first.getType() != Material.SUGAR_CANE) {
            return false;
        } else {
            Block top = first;
            int height = 1;

            while (top.getRelative(BlockFace.UP).getType() == Material.SUGAR_CANE) {
                top = top.getRelative(BlockFace.UP);
                ++height;
                if (height > 16) {
                    break;
                }
            }

            if (height >= 3) {
                return true;
            } else if (Math.random() > 0.45) {
                return true;
            } else {
                Block above = top.getRelative(BlockFace.UP);
                if (!above.getType().isAir()) {
                    return true;
                } else {
                    above.setType(Material.SUGAR_CANE, true);
                    soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, above.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), 4, 0.2, 0.2, 0.2, 0.01);
                    return true;
                }
            }
        }
    }

    /**
     * 判断材质是否为锄头。
     */
    private boolean isHoe(Material material) {
        return material == Material.WOODEN_HOE || material == Material.STONE_HOE || material == Material.IRON_HOE || material == Material.GOLDEN_HOE || material == Material.DIAMOND_HOE || material == Material.NETHERITE_HOE;
    }

    /**
     * 依据是否被水淹没/附近有水，选择水域堆肥应呈现的材质。
     */
    private Material pickReadyWaterMaterial(Location soilLoc) {
        if (this.isFlooded(soilLoc)) {
            return Material.MUD;
        } else {
            return this.hasWaterNearby(soilLoc) ? Material.MUD : Material.DIRT;
        }
    }

    /**
     * 判断堆肥方块是否被水淹没（正上方为水柱）。
     */
    private boolean isFlooded(Location soilLoc) {
        World world = soilLoc.getWorld();
        if (world == null) {
            return false;
        } else {
            Block soil = world.getBlockAt(soilLoc);
            Block up = soil.getRelative(BlockFace.UP);
            return this.isWaterColumn(up);
        }
    }

    /**
     * 判断指定位置附近（配置半径内）是否存在水源或含水方块。
     */
    private boolean hasWaterNearby(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            Block base = world.getBlockAt(loc);
            int radius = this.plugin.getConfig().getInt("farming.fertilized_soil.water_radius", 4);

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        Block block = base.getRelative(dx, dy, dz);
                        Material mat = block.getType();
                        if (mat == Material.WATER || mat == Material.BUBBLE_COLUMN) {
                            return true;
                        }

                        BlockData blockData = block.getBlockData();
                        if (blockData instanceof Waterlogged) {
                            Waterlogged waterlogged = (Waterlogged) blockData;
                            if (waterlogged.isWaterlogged()) {
                                return true;
                            }
                        }
                    }
                }
            }

            return false;
        }
    }

    /**
     * 判断单个方块是否构成水柱（水、含水植物或含水方块）。
     */
    private boolean isWaterColumn(Block b) {
        Material material = b.getType();
        if (material != Material.WATER && material != Material.BUBBLE_COLUMN) {
            if (material != Material.KELP && material != Material.KELP_PLANT) {
                if (material != Material.SEAGRASS && material != Material.TALL_SEAGRASS) {
                    BlockData blockData = b.getBlockData();
                    boolean isWater;
                    if (blockData instanceof Waterlogged) {
                        Waterlogged waterlogged = (Waterlogged) blockData;
                        if (waterlogged.isWaterlogged()) {
                            isWater = true;
                            return isWater;
                        }
                    }

                    isWater = false;
                    return isWater;
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    /**
     * 判断材质是否为海带类方块。
     */
    private boolean isKelp(Material material) {
        return KELP_TYPES.contains(material);
    }

    /**
     * 由方块位置生成用于映射/持久化的键。
     */
    private Location blockKey(Block b) {
        return new Location(b.getWorld(), (double) b.getX(), (double) b.getY(), (double) b.getZ());
    }

    /**
     * 尝试在水域堆肥上催生一节海带。
     *
     * @param soil 堆肥方块
     * @return 处理完毕返回 true（不区分是否实际生长）
     */
    private boolean tryPushKelpOnSoil(Block soil) {
        Block first = soil.getRelative(BlockFace.UP);
        Material firstMaterial = first.getType();
        if (firstMaterial != Material.KELP && firstMaterial != Material.KELP_PLANT) {
            return false;
        } else {
            Block top = first;
            int height = 1;

            do {
                Block up = top.getRelative(BlockFace.UP);
                Material upMaterial = up.getType();
                if (upMaterial != Material.KELP && upMaterial != Material.KELP_PLANT) {
                    break;
                }

                top = up;
                ++height;
            } while (height <= 32);

            if (height >= 25) {
                return true;
            } else {
                Block above = top.getRelative(BlockFace.UP);
                if (!this.isWaterColumn(above)) {
                    return true;
                } else if (Math.random() > 0.45) {
                    return true;
                } else {
                    if (top.getType() == Material.KELP) {
                        top.setType(Material.KELP_PLANT, true);
                    }

                    Block next = top.getRelative(BlockFace.UP);
                    if (!this.isWaterColumn(next)) {
                        return true;
                    } else {
                        next.setType(Material.KELP, true);
                        soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, next.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), 4, 0.2, 0.2, 0.2, 0.01);
                        return true;
                    }
                }
            }
        }
    }

    static {
        LAND_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP, Material.PUMPKIN_STEM, Material.MELON_STEM, Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM, Material.SWEET_BERRY_BUSH);
        KELP_TYPES = EnumSet.of(Material.KELP, Material.KELP_PLANT);
    }

    /**
     * 堆肥方块的阶段。
     */
    private enum Stage {
        RAW,
        READY_LAND,
        READY_WATER;

        Stage() {
        }

        // $FF: synthetic method
        private static Stage[] $values() {
            return new Stage[]{RAW, READY_LAND, READY_WATER};
        }
    }

    /**
     * 单个堆肥方块的运行时状态。
     */
    private static final class CompostData {
        Stage stage;
        long readyTick;
        long kelpNextPushAt;
        long caneNextPushAt;

        CompostData(Stage stage, long readyTick) {
            super();
            this.stage = stage;
            this.readyTick = readyTick;
            this.kelpNextPushAt = 0L;
            this.caneNextPushAt = 0L;
        }
    }
}
