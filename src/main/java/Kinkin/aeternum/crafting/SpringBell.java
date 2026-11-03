package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BellRingEvent;
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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 春之铃（Spring Bell）。
 *
 * <p>本类负责注册并维护可放置的“春之铃”方块物品：
 * <ul>
 *     <li>注册由紫水晶碎片 + 铃铛 + 粉色花瓣合成的有序配方，并在合成预览时校验材料；</li>
 *     <li>为合成的铃铛写入名称、描述、隐藏标记并打上持久化标签，作为身份识别依据；</li>
 *     <li>记录玩家放置的春之铃位置（持久化到数据文件），破坏时掉落对应的自定义物品；</li>
 *     <li>玩家敲响春之铃时（受冷却限制），触发粒子效果、催熟周围作物、并安抚附近蜜蜂/给予幸运。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}、{@link SeasonService} 提供语言文本与季节状态。</p>
 */
public final class SpringBell implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedBells = Collections.synchronizedSet(new HashSet());
    private static final long COOLDOWN_MS = 20000L;
    private final Map<String, Long> lastRing = new HashMap();
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public SpringBell(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "spring_bell_item");
        this.recipeKey = new NamespacedKey(plugin, "spring_bell");
        this.dataFile = new File(plugin.getDataFolder(), "spring_bell_data.yml");
        this.loadData();
    }

    /**
     * 从数据文件读取已放置的春之铃位置；若只有旧路径的数据文件，则迁移并保存到新路径。
     */
    private void loadData() {
        this.placedBells.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedBells.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/spring_bell_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedBells.addAll(list);
                this.saveData();
            }

        }
    }

    /**
     * 将已放置的春之铃位置写入数据文件。
     */
    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedBells));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar spring_bell_data.yml: " + ex.getMessage());
        }

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
     * 注册春之铃的合成配方：B 铃铛、A 紫水晶碎片、F 粉色花瓣。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.BELL);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"AFA", "FBF", "AFA"});
        recipe.setIngredient('B', Material.BELL);
        recipe.setIngredient('A', Material.AMETHYST_SHARD);
        recipe.setIngredient('F', Material.PINK_PETALS);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为春之铃配方时，
     * 校验摆放的材料（恰好 1 个铃铛、至少 4 个紫水晶碎片与 4 个粉色花瓣），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    int bells = 0;
                    int shards = 0;
                    int petals = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (type == Material.BELL) {
                                bells += stack.getAmount();
                            } else if (type == Material.AMETHYST_SHARD) {
                                shards += stack.getAmount();
                            } else {
                                if (type != Material.PINK_PETALS) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                petals += stack.getAmount();
                            }
                        }
                    }

                    if (bells == 1 && shards >= 4 && petals >= 4) {
                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            crafter = player;
                        }

                        ItemStack result = new ItemStack(Material.BELL);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.spring_bell.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.spring_bell.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.spring_bell.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.spring_bell.lore3");
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
     * 判断物品是否为春之铃。优先读取持久化标签；若缺失则根据显示名与描述推断，
     * 命中后补写标签并返回 true。
     */
    private boolean isSpringBellItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.BELL) {
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
                    boolean looksLikeSpringBell = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_SB"))) {
                        looksLikeSpringBell = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String firstLore = ChatColor.stripColor((String) lore.get(0));
                        String firstLoreLower = firstLore.toLowerCase(Locale.ROOT);
                        if (firstLoreLower.contains("spring") || firstLoreLower.contains("primavera")) {
                            looksLikeSpringBell = true;
                        }
                    }

                    if (looksLikeSpringBell) {
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
     * 监听方块放置：放置的是春之铃时，记录该位置并保存数据。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (this.isSpringBellItem(inHand)) {
            Block block = event.getBlockPlaced();
            if (block.getType() == Material.BELL) {
                this.placedBells.add(this.blockKey(block));
                this.saveData();
            }
        }
    }

    /**
     * 监听方块破坏：破坏的是已登记的春之铃时，取消原掉落并改为掉落对应的自定义物品。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.BELL) {
            String key = this.blockKey(block);
            if (this.placedBells.remove(key)) {
                this.lastRing.remove(key);
                this.saveData();
                event.setDropItems(false);
                Player breaker = event.getPlayer();
                ItemStack drop = new ItemStack(Material.BELL);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.spring_bell.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.spring_bell.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.spring_bell.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.spring_bell.lore3");
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
     * 判断方块是否为已登记的春之铃。
     */
    private boolean isSpringBellBlock(Block block) {
        return block.getType() == Material.BELL && this.placedBells.contains(this.blockKey(block));
    }

    /**
     * 监听铃铛敲响：当被敲响的是已登记的春之铃时，
     * 冷却期内仅播放粒子；冷却结束后触发粒子、催熟作物并安抚蜜蜂。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBellRing(BellRingEvent event) {
        Block block = event.getBlock();
        if (this.isSpringBellBlock(block)) {
            Entity entity = event.getEntity();
            if (entity instanceof Player) {
                Player player = (Player) entity;
                String key = this.blockKey(block);
                long now = System.currentTimeMillis();
                Long last = (Long) this.lastRing.get(key);
                if (last != null && now - last < 20000L) {
                    block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add((double) 0.5F, (double) 1.0F, (double) 0.5F), 8, 0.3, 0.4, 0.3);
                } else {
                    this.lastRing.put(key, now);
                    CalendarState state = this.seasons.getStateCopy(block.getWorld());
                    boolean isSpring = state.season == Season.SPRING;
                    this.spawnBellParticles(block, isSpring);
                    this.bloomCropsAround(block, isSpring);
                    this.calmBeesAround(block, player, isSpring);
                }
            }
        }
    }

    /**
     * 在铃铛周围播放灵魂粒子与花瓣粒子，并播放使用音效（春季时效果更强）。
     */
    private void spawnBellParticles(Block bell, boolean isSpring) {
        World world = bell.getWorld();
        Location loc = bell.getLocation().add((double) 0.5F, (double) 1.0F, (double) 0.5F);
        world.spawnParticle(Particle.SOUL, loc, isSpring ? 20 : 12, (double) 0.5F, (double) 0.5F, (double) 0.5F);
        world.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, loc, isSpring ? 14 : 8, 0.4, 0.7, 0.4);
        world.playSound(loc, Sound.BLOCK_BELL_USE, 1.0F, isSpring ? 1.4F : 1.1F);
    }

    /**
     * 在铃铛周围随机尝试催熟作物（春季时范围更大、尝试次数更多）。
     */
    private void bloomCropsAround(Block bell, boolean isSpring) {
        World world = bell.getWorld();
        int radius = isSpring ? 8 : 5;
        int attempts = isSpring ? 50 : 30;
        int baseY = bell.getY();

        for (int i = 0; i < attempts; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            int x = bell.getX() + dx;
            int z = bell.getZ() + dz;
            boolean upgraded = false;

            for (int dy = -3; dy <= 1; ++dy) {
                Block cropBlock = world.getBlockAt(x, baseY + dy, z);
                BlockData blockData = cropBlock.getBlockData();
                if (blockData instanceof Ageable ageable) {
                    if (ageable.getAge() < ageable.getMaximumAge()) {
                        int step = isSpring ? (this.rnd.nextBoolean() ? 2 : 1) : 1;
                        int newAge = Math.min(ageable.getAge() + step, ageable.getMaximumAge());
                        ageable.setAge(newAge);
                        cropBlock.setBlockData(ageable, false);
                        world.spawnParticle(Particle.SOUL, cropBlock.getLocation().add((double) 0.5F, 0.4, (double) 0.5F), 6, 0.2, 0.3, 0.2);
                        upgraded = true;
                        break;
                    }
                }
            }

            if (upgraded && this.rnd.nextDouble() < 0.15) {
                Block floor = world.getBlockAt(x, baseY - 1, z);
                world.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, floor.getLocation().add((double) 0.5F, (double) 1.0F, (double) 0.5F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    /**
     * 安抚铃铛附近的蜜蜂（附加再生与缓降），并给予敲响者幸运（春季时更强）。
     */
    private void calmBeesAround(Block bell, Player source, boolean isSpring) {
        World world = bell.getWorld();
        Location center = bell.getLocation().add((double) 0.5F, (double) 1.0F, (double) 0.5F);
        int radius = isSpring ? 12 : 8;

        for (Entity entity : world.getNearbyEntities(center, (double) radius, (double) radius, (double) radius)) {
            if (entity instanceof Bee bee) {
                bee.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, isSpring ? 120 : 80, 0, true, false, true));
                bee.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, isSpring ? 120 : 80, 0, true, false, true));
                world.spawnParticle(Particle.SOUL, bee.getLocation().add((double) 0.0F, 0.4, (double) 0.0F), 6, 0.3, 0.3, 0.3);
            }
        }

        source.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, isSpring ? 200 : 120, 0, true, false, true));
    }

    static {
        PERSISTENT_LORE_TAG = ChatColor.BLACK + "AET_CUSTOM_SB";
    }
}
