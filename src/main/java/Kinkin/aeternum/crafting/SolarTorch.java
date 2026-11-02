package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 太阳火把（Solar Torch）。
 *
 * <p>本类负责注册并维护可放置的“太阳火把”方块物品，并周期性驱动其效果：
 * <ul>
 *     <li>注册由烈焰粉 + 火把 + 向日葵合成的有序配方，并在合成预览时校验材料；</li>
 *     <li>为合成的火把写入名称、描述、隐藏标记并打上持久化标签；</li>
 *     <li>记录玩家放置的太阳火把位置（持久化到数据文件），破坏时掉落对应的自定义物品；</li>
 *     <li>{@link #run()} 定时扫描已放置的火把：夏季白天催熟周围作物，夜晚驱散附近怪物。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}、{@link SeasonService} 提供语言文本与季节状态。</p>
 */
public final class SolarTorch implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedTorches = Collections.synchronizedSet(new HashSet());
    private BukkitTask task;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public SolarTorch(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "solar_torch_item");
        this.recipeKey = new NamespacedKey(plugin, "solar_torch");
        this.dataFile = new File(plugin.getDataFolder(), "solar_torch_data.yml");
        this.loadData();
    }

    /** 从数据文件读取已放置的太阳火把位置；若只有旧路径的数据文件，则迁移并保存到新路径。 */
    private void loadData() {
        this.placedTorches.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedTorches.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/solar_torch_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedTorches.addAll(list);
                this.saveData();
            }

        }
    }

    /** 将已放置的太阳火把位置写入数据文件。 */
    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedTorches));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar solar_torch_data.yml: " + ex.getMessage());
        }

    }

    /** 向服务器注册事件监听器与合成配方，并启动周期任务。 */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.startTask();
    }

    /** 注销事件监听器、停止周期任务并移除已注册的合成配方。 */
    public void unregister() {
        HandlerList.unregisterAll(this);
        this.stopTask();
        Bukkit.removeRecipe(this.recipeKey);
    }

    /** 启动周期任务（每 40 tick 执行一次）。 */
    private void startTask() {
        this.stopTask();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 40L);
    }

    /** 停止周期任务（若正在运行）。 */
    private void stopTask() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

    }

    /** 注册太阳火把的合成配方：B 烈焰粉、T 火把、F 向日葵。 */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.TORCH);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"BBB", " T ", " F "});
        recipe.setIngredient('B', Material.BLAZE_POWDER);
        recipe.setIngredient('T', Material.TORCH);
        recipe.setIngredient('F', Material.SUNFLOWER);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为太阳火把配方时，
     * 校验摆放的材料（至少各 1 个烈焰粉、火把与向日葵），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe)recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    int blaze = 0;
                    int torch = 0;
                    int flower = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (type == Material.BLAZE_POWDER) {
                                blaze += stack.getAmount();
                            } else if (type == Material.TORCH) {
                                torch += stack.getAmount();
                            } else {
                                if (type != Material.SUNFLOWER) {
                                    event.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                flower += stack.getAmount();
                            }
                        }
                    }

                    if (blaze >= 1 && torch >= 1 && flower >= 1) {
                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player)viewer;
                            crafter = player;
                        }

                        ItemStack result = new ItemStack(Material.TORCH);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.solar_torch.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.solar_torch.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.solar_torch.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.solar_torch.lore3");
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

    /**
     * 判断物品是否为太阳火把。优先读取持久化标签；若缺失则根据显示名与描述推断，
     * 命中后补写标签并返回 true。
     */
    private boolean isSolarTorchItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.TORCH) {
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
                    boolean looksLikeSolar = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_ST"))) {
                        looksLikeSolar = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String firstLore = ChatColor.stripColor((String)lore.get(0));
                        String firstLoreLower = firstLore.toLowerCase(Locale.ROOT);
                        if (firstLoreLower.contains("solar") || firstLoreLower.contains("sol")) {
                            looksLikeSolar = true;
                        }
                    }

                    if (looksLikeSolar) {
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

    /** 监听方块放置：放置的是太阳火把时，记录该位置并保存数据。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (this.isSolarTorchItem(inHand)) {
            Block block = event.getBlockPlaced();
            Material type = block.getType();
            if (type == Material.TORCH || type == Material.WALL_TORCH) {
                this.placedTorches.add(this.blockKey(block));
                this.saveData();
            }
        }
    }

    /** 监听方块破坏：破坏的是已登记的太阳火把时，取消原掉落并改为掉落对应的自定义物品。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.TORCH || block.getType() == Material.WALL_TORCH) {
            String key = this.blockKey(block);
            if (this.placedTorches.remove(key)) {
                event.setDropItems(false);
                this.saveData();
                Player breaker = event.getPlayer();
                ItemStack drop = new ItemStack(Material.TORCH);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.solar_torch.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.solar_torch.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.solar_torch.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.solar_torch.lore3");
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

    /**
     * 周期任务：遍历已登记的太阳火把，清理失效记录（格式错误、世界不存在、方块已消失）；
     * 对有效的火把在夏季白天催熟作物，在夜晚驱散怪物。
     */
    public void run() {
        if (!this.placedTorches.isEmpty()) {
            for(String key : new ArrayList<>(this.placedTorches)) {
                String[] parts = key.split(";");
                if (parts.length != 4) {
                    this.placedTorches.remove(key);
                    this.saveData();
                } else {
                    World world = Bukkit.getWorld(parts[0]);
                    if (world == null) {
                        this.placedTorches.remove(key);
                        this.saveData();
                    } else {
                        int x;
                        int y;
                        int z;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                            z = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException ignored) {
                            this.placedTorches.remove(key);
                            this.saveData();
                            continue;
                        }

                        Block block = world.getBlockAt(x, y, z);
                        if (!this.isSolarTorchBlock(block)) {
                            this.placedTorches.remove(key);
                            this.saveData();
                        } else {
                            long time = world.getTime();
                            boolean isDay = time >= 0L && time < 12000L;
                            Season season = this.seasons.getStateCopy(world).season;
                            if (season == Season.SUMMER && isDay) {
                                this.boostNearbyCrops(block);
                            }

                            if (!isDay) {
                                this.repelMobs(block);
                            }
                        }
                    }
                }
            }

        }
    }

    /** 在火把周围随机尝试催熟作物（夏季白天的效果）。 */
    private void boostNearbyCrops(Block center) {
        World world = center.getWorld();
        int radius = 4;

        for(int i = 0; i < 10; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            Block soil = world.getBlockAt(center.getX() + dx, center.getY() - 1, center.getZ() + dz);
            Block cropBlock = soil.getRelative(0, 1, 0);
            BlockData blockData = cropBlock.getBlockData();
            if (blockData instanceof Ageable ageable) {
                if (ageable.getAge() < ageable.getMaximumAge() && this.rnd.nextDouble() < 0.35) {
                    ageable.setAge(ageable.getAge() + 1);
                    cropBlock.setBlockData(ageable, false);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, cropBlock.getLocation().add((double)0.5F, 0.4, (double)0.5F), 4, 0.2, 0.2, 0.2);
                }
            }
        }

    }

    /** 驱散火把附近的怪物：附加缓慢效果，并有概率造成少量伤害。 */
    private void repelMobs(Block center) {
        World world = center.getWorld();
        int radius = 6;

        for(Entity entity : world.getNearbyEntities(center.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), (double)radius, (double)radius, (double)radius)) {
            if (entity instanceof Monster mob) {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
                if (this.rnd.nextDouble() < 0.1) {
                    mob.damage((double)0.5F, (Entity)null);
                }

                world.spawnParticle(Particle.END_ROD, mob.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    /** 判断方块是否为已登记的太阳火把（火把或墙上的火把）。 */
    private boolean isSolarTorchBlock(Block block) {
        Material type = block.getType();
        return (type == Material.TORCH || type == Material.WALL_TORCH) && this.placedTorches.contains(this.blockKey(block));
    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_ST";
    }
}
