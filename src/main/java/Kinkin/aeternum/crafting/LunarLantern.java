package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.SeasonService;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 月光灯笼（Lunar Lantern）。
 *
 * <p>本类负责注册并维护可放置的“月光灯笼”方块物品，并周期性驱动其效果：
 * <ul>
 *     <li>注册由灯笼 + 红石 + 末影珍珠合成的有序配方，并在合成预览时精确校验材料数量；</li>
 *     <li>为合成的灯笼写入名称、描述、隐藏标记并打上持久化标签；</li>
 *     <li>记录玩家放置的月光灯笼位置（持久化到数据文件），破坏时掉落对应的自定义物品；</li>
 *     <li>{@link #run()} 定时扫描已放置的灯笼：白天播放附魔粒子；夜晚播放灵魂/末地烛粒子、
 *         催熟作物与蘑菇，并给予附近玩家夜视。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}、{@link SeasonService} 提供语言文本与季节状态。</p>
 */
public final class LunarLantern implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedLanterns = Collections.synchronizedSet(new HashSet());
    private BukkitTask task;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public LunarLantern(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "lunar_lantern_item");
        this.recipeKey = new NamespacedKey(plugin, "lunar_lantern");
        this.dataFile = new File(plugin.getDataFolder(), "lunar_lantern_data.yml");
        this.loadData();
    }

    /**
     * 从数据文件读取已放置的月光灯笼位置；若只有旧路径的数据文件，则迁移并保存到新路径。
     */
    private void loadData() {
        this.placedLanterns.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedLanterns.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/lunar_lantern_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedLanterns.addAll(list);
                this.saveData();
            }

        }
    }

    /**
     * 将已放置的月光灯笼位置写入数据文件。
     */
    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedLanterns));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar lunar_lantern_data.yml: " + ex.getMessage());
        }

    }

    /**
     * 向服务器注册事件监听器与合成配方，并启动周期任务。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.startTask();
    }

    /**
     * 注销事件监听器、停止周期任务并移除已注册的合成配方。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        this.stopTask();
        Bukkit.removeRecipe(this.recipeKey);
    }

    /**
     * 启动周期任务（每 40 tick 执行一次）。
     */
    private void startTask() {
        this.stopTask();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 40L);
    }

    /**
     * 停止周期任务（若正在运行）。
     */
    private void stopTask() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

    }

    /**
     * 注册月光灯笼的合成配方：L 灯笼、R 红石、E 末影珍珠。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.LANTERN);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"RLR", "RER", "RLR"});
        recipe.setIngredient('L', Material.LANTERN);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('E', Material.ENDER_PEARL);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为月光灯笼配方时，
     * 精确校验摆放的材料数量（2 个灯笼、6 个红石、1 个末影珍珠），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    int lanternSlots = 0;
                    int redstoneSlots = 0;
                    int pearlSlots = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (type == Material.LANTERN) {
                                ++lanternSlots;
                            } else if (type == Material.REDSTONE) {
                                ++redstoneSlots;
                            } else {
                                if (type != Material.ENDER_PEARL) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                ++pearlSlots;
                            }
                        }
                    }

                    if (lanternSlots == 2 && redstoneSlots == 6 && pearlSlots == 1) {
                        HumanEntity viewer = event.getView().getPlayer();
                        Player craftingPlayer;
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            craftingPlayer = player;
                        } else {
                            craftingPlayer = null;
                        }

                        Player crafter = craftingPlayer;
                        ItemStack result = new ItemStack(Material.LANTERN);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.lunar_lantern.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore3");
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
                        event.getInventory().setResult((ItemStack) null);
                    }
                }
            }
        }
    }

    /**
     * 判断物品是否为月光灯笼。优先读取持久化标签；若缺失则根据显示名与描述推断，
     * 命中后补写标签并返回 true。
     */
    private boolean isLunarLanternItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.LANTERN) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Byte flag = (Byte) pdc.get(this.itemTagKey, PersistentDataType.BYTE);
                if (flag != null && flag == 1) {
                    return true;
                } else {
                    String displayRaw = meta.hasDisplayName() ? meta.getDisplayName() : "";
                    String display = ChatColor.stripColor(displayRaw);
                    List<String> lore = meta.getLore() != null ? meta.getLore() : Collections.emptyList();
                    boolean looksLikeLunar = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_LL"))) {
                        looksLikeLunar = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String firstLore = ChatColor.stripColor((String) lore.get(0));
                        String firstLoreLower = firstLore.toLowerCase(Locale.ROOT);
                        if (firstLoreLower.contains("lunar") || firstLoreLower.contains("luna")) {
                            looksLikeLunar = true;
                        }
                    }

                    if (looksLikeLunar) {
                        pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);
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

    /**
     * 监听方块放置：放置的是月光灯笼时，记录该位置并保存数据。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (this.isLunarLanternItem(inHand)) {
            Block block = event.getBlockPlaced();
            if (block.getType() == Material.LANTERN) {
                this.placedLanterns.add(this.blockKey(block));
                this.saveData();
            }
        }
    }

    /**
     * 监听方块破坏：破坏的是已登记的月光灯笼时，取消原掉落并改为掉落对应的自定义物品。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.LANTERN) {
            String key = this.blockKey(block);
            if (this.placedLanterns.remove(key)) {
                event.setDropItems(false);
                this.saveData();
                Player breaker = event.getPlayer();
                ItemStack drop = new ItemStack(Material.LANTERN);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.lunar_lantern.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore3");
                        List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                        finalLore.add(PERSISTENT_LORE_TAG);
                        meta.setDisplayName(name);
                        meta.setLore(finalLore);
                    } catch (Throwable ignored) {
                    }

                    drop.setItemMeta(meta);
                }

                block.getWorld().dropItemNaturally(block.getLocation().add((double) 0.5F, 0.1, (double) 0.5F), drop);
            }
        }
    }

    /**
     * 生成方块的位置键：世界名;x;y;z。
     */
    private String blockKey(Block block) {
        String worldName = block.getWorld().getName();
        return worldName + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    /**
     * 判断方块是否为已登记的月光灯笼。
     */
    private boolean isLunarLanternBlock(Block block) {
        return block.getType() == Material.LANTERN && this.placedLanterns.contains(this.blockKey(block));
    }

    /**
     * 周期任务：遍历已登记的月光灯笼，清理失效记录（格式错误、世界不存在、方块已消失）；
     * 对有效的灯笼在白天播放待机粒子，在夜晚播放夜间粒子并催熟植物、祝福玩家。
     */
    @Override
    public void run() {
        if (!this.placedLanterns.isEmpty()) {
            for (String key : new ArrayList<>(this.placedLanterns)) {
                String[] parts = key.split(";");
                if (parts.length != 4) {
                    this.placedLanterns.remove(key);
                    this.saveData();
                } else {
                    World world = Bukkit.getWorld(parts[0]);
                    if (world == null) {
                        this.placedLanterns.remove(key);
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
                            this.placedLanterns.remove(key);
                            this.saveData();
                            continue;
                        }

                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() != Material.LANTERN) {
                            this.placedLanterns.remove(key);
                            this.saveData();
                        } else {
                            long time = world.getTime();
                            boolean isNight = time >= 13000L && time < 23000L;
                            if (!isNight) {
                                this.idleParticles(block);
                            } else {
                                this.nightParticles(block);
                                this.boostNocturnalPlants(block);
                                this.blessNearbyPlayers(block);
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * 白天待机效果：在灯笼上方少量播放附魔粒子。
     */
    private void idleParticles(Block center) {
        World world = center.getWorld();
        Location loc = center.getLocation().add((double) 0.5F, 0.8, (double) 0.5F);
        world.spawnParticle(Particle.ENCHANT, loc, 4, 0.15, 0.2, 0.15);
    }

    /**
     * 夜晚效果：在灯笼上方播放灵魂粒子与末地烛粒子。
     */
    private void nightParticles(Block center) {
        World world = center.getWorld();
        Location loc = center.getLocation().add((double) 0.5F, 0.9, (double) 0.5F);
        world.spawnParticle(Particle.SOUL, loc, 8, (double) 0.25F, 0.3, (double) 0.25F);
        world.spawnParticle(Particle.END_ROD, loc, 4, 0.15, 0.2, 0.15);
    }

    /**
     * 夜晚催熟灯笼周围的作物，并有机会让蘑菇增殖。
     */
    private void boostNocturnalPlants(Block center) {
        World world = center.getWorld();
        int radius = 5;
        int attempts = 30;
        int baseY = center.getY();

        for (int i = 0; i < attempts; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            for (int dy = -3; dy <= 3; ++dy) {
                Block block = world.getBlockAt(x, baseY + dy, z);
                Material type = block.getType();
                if (type == Material.RED_MUSHROOM || type == Material.BROWN_MUSHROOM) {
                    if (this.rnd.nextDouble() < 0.3) {
                        this.spreadMushroom(block);
                    } else {
                        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, block.getLocation().add((double) 0.5F, 0.3, (double) 0.5F), 4, 0.2, 0.2, 0.2);
                    }
                    break;
                }

                BlockData blockData = block.getBlockData();
                if (blockData instanceof Ageable ageable) {
                    if (ageable.getAge() < ageable.getMaximumAge()) {
                        if (this.rnd.nextDouble() < 0.35) {
                            ageable.setAge(ageable.getAge() + 1);
                            block.setBlockData(ageable, false);
                            world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, block.getLocation().add((double) 0.5F, 0.4, (double) 0.5F), 5, 0.2, 0.3, 0.2);
                        }
                        break;
                    }
                }
            }
        }

    }

    /**
     * 在蘑菇周围尝试将其扩散到附近合适的方块上。
     */
    private void spreadMushroom(Block source) {
        World world = source.getWorld();
        Material type = source.getType();
        int radius = 2;

        for (int i = 0; i < 6; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            Block ground = source.getRelative(dx, -1, dz);
            Block air = source.getRelative(dx, 0, dz);
            if (air.getType() == Material.AIR) {
                Material groundType = ground.getType();
                if (groundType == Material.DIRT || groundType == Material.GRASS_BLOCK || groundType == Material.MYCELIUM || groundType == Material.PODZOL || groundType == Material.NETHERRACK || groundType == Material.CRIMSON_NYLIUM || groundType == Material.WARPED_NYLIUM) {
                    air.setType(type, false);
                    world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, air.getLocation().add((double) 0.5F, 0.3, (double) 0.5F), 6, 0.2, 0.3, 0.2);
                    break;
                }
            }
        }

    }

    /**
     * 夜晚祝福灯笼周围的玩家：给予短暂夜视并播放附魔粒子。
     */
    private void blessNearbyPlayers(Block center) {
        World world = center.getWorld();
        Location loc = center.getLocation().add((double) 0.5F, 0.9, (double) 0.5F);
        int radius = 6;

        for (Entity entity : world.getNearbyEntities(loc, (double) radius, (double) radius, (double) radius)) {
            if (entity instanceof Player player) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 120, 0, true, false, true));
                world.spawnParticle(Particle.ENCHANT, player.getLocation().add((double) 0.0F, (double) 1.0F, (double) 0.0F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    static {
        PERSISTENT_LORE_TAG = ChatColor.BLACK + "AET_CUSTOM_LL";
    }
}
