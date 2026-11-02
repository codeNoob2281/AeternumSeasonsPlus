package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * 永霜世界首领「格拉西利斯（Glacialis）」管理器。
 *
 * <p>职责包括：
 * <ul>
 *     <li>以定时任务监听永霜世界日历跨天，按概率在玩家附近生成一只被特殊标记、属性强化的
 *     凋灵首领（生命 600、攻击 20、移速提升）；</li>
 *     <li>约束首领行为：爆炸时不破坏方块而是铺撒细雪并冻结附近玩家，且不可改变方块；</li>
 *     <li>首领死亡时清空常规掉落，改为掉落「冰川之核」与若干「永霜碎片」；</li>
 *     <li>注册并校验「永霜护甲」合成配方（以永霜碎片与冰川之核为材料），
 *     并通过持久化标记区分正版材料，防止伪造。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}（插件、语言、配置）与 {@link SeasonService}（日历状态）。</p>
 */
public final class FrostBossManager implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private static final double DAILY_BOSS_CHANCE = 0.37;
    private final Random random = new Random();
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasonService;
    private final NamespacedKey BOSS_KEY;
    private final NamespacedKey EVERFROST_SHARD_KEY;
    private final NamespacedKey GLACIALIS_CORE_KEY;
    private final NamespacedKey HEAT_ARMOR_KEY;
    private BukkitTask calendarTask;
    private UUID currentBossId;

    public FrostBossManager(AeternumSeasonsPlugin plugin, SeasonService seasonService) {
        super();
        this.plugin = plugin;
        this.seasonService = seasonService;
        this.BOSS_KEY = new NamespacedKey(plugin, "glacialis_boss");
        this.EVERFROST_SHARD_KEY = new NamespacedKey(plugin, "everfrost_shard");
        this.GLACIALIS_CORE_KEY = new NamespacedKey(plugin, "glacialis_core");
        this.HEAT_ARMOR_KEY = new NamespacedKey(plugin, "heat_armor");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.startCalendarTask();
        this.registerRecipes();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        if (this.calendarTask != null) {
            this.calendarTask.cancel();
        }

    }

    /**
     * 启动每 10 秒轮询一次的日历任务：当永霜世界的日期（天/年）发生变化时触发每日逻辑。
     */
    private void startCalendarTask() {
        this.calendarTask = (new BukkitRunnable() {
            private int lastDay = -1;
            private int lastYear = -1;

            public void run() {
                World frost = Bukkit.getWorld("aeternum_frost");
                if (frost != null) {
                    CalendarState state = FrostBossManager.this.seasonService.getStateCopy(frost);
                    if (state != null) {
                        if (state.day != this.lastDay || state.year != this.lastYear) {
                            this.lastDay = state.day;
                            this.lastYear = state.year;
                            FrostBossManager.this.onNewDay(state);
                        }

                    }
                }
            }
        }).runTaskTimer(this.plugin, 200L, 200L);
    }

    /**
     * 每日检查：若当前无存活首领，且永霜世界有玩家，则按概率挑选一名玩家在其附近生成首领。
     */
    private void onNewDay(CalendarState state) {
        if (this.currentBossId != null) {
            Entity existing = this.findCurrentBoss();
            if (existing != null && !existing.isDead()) {
                return;
            }

            this.currentBossId = null;
        }

        World frost = Bukkit.getWorld("aeternum_frost");
        if (frost != null) {
            List<Player> candidates = frost.getPlayers();
            if (!candidates.isEmpty()) {
                if (!(this.random.nextDouble() >= 0.37)) {
                    Player target = (Player)candidates.get(this.random.nextInt(candidates.size()));
                    this.spawnGlacialisNear(target);
                }
            }
        }
    }

    /**
     * 在目标玩家所在位置上方约 15 格（不超过世界最高限制）生成并初始化首领，同时广播提示。
     */
    private void spawnGlacialisNear(Player player) {
        World world = player.getWorld();
        Location base = player.getLocation();
        Location spawn = base.clone();
        int y = world.getHighestBlockYAt(base) + 15;
        y = Math.min(y, world.getMaxHeight() - 5);
        spawn.setY((double)y);
        Wither boss = (Wither)world.spawn(spawn, Wither.class, (wither) -> {
            this.markAsBoss(wither);
            this.buffBossStats(wither);
            String name = this.plugin.lang.tr(player, "boss.glacialis.name");
            wither.setCustomName(name);
            wither.setCustomNameVisible(true);
            wither.setRemoveWhenFarAway(false);
        });
        this.currentBossId = boss.getUniqueId();
        String msg = this.plugin.lang.tr(player, "boss.glacialis.spawn");

        for(Player online : world.getPlayers()) {
            online.sendMessage(msg);
            online.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0F, 0.5F);
        }

    }

    /**
     * 在实体的持久化数据中写入首领标记。
     */
    private void markAsBoss(Entity entity) {
        entity.getPersistentDataContainer().set(this.BOSS_KEY, PersistentDataType.BYTE, (byte)1);
    }

    /**
     * 判断实体是否带有首领标记。
     */
    private boolean isBoss(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = (Byte)pdc.get(this.BOSS_KEY, PersistentDataType.BYTE);
        return flag != null && flag == 1;
    }

    /**
     * 通过已记录的首领 UUID 查找当前首领实体，不存在则返回 null。
     */
    private Entity findCurrentBoss() {
        return this.currentBossId == null ? null : Bukkit.getEntity(this.currentBossId);
    }

    /**
     * 强化首领属性：生命 600、攻击 20、移速提升 20%。
     */
    private void buffBossStats(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue((double)600.0F);
            entity.setHealth(maxHealth.getBaseValue());
        }

        AttributeInstance attack = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue((double)20.0F);
        }

        AttributeInstance movement = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * 1.2);
        }

    }

    /**
     * 首领（或其发射的凋灵头颅）爆炸时：取消方块破坏，在落点铺撒细雪、冻结附近玩家并播放特效。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossExplode(EntityExplodeEvent event) {
        Entity source = event.getEntity();
        Entity owner = null;
        if (source instanceof Wither wither) {
            owner = wither;
        } else if (source instanceof WitherSkull skull) {
            ProjectileSource shooter = skull.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                owner = shooterEntity;
            }
        }

        if (owner instanceof Wither wither) {
            if (this.isBoss(wither)) {
                event.blockList().clear();
                World world = source.getWorld();
                Location center = source.getLocation();
                int radius = 4;

                for(int dx = -radius; dx <= radius; ++dx) {
                    for(int dz = -radius; dz <= radius; ++dz) {
                        double distSq = (double)(dx * dx + dz * dz);
                        if (!(distSq > (double)(radius * radius))) {
                            int x = center.getBlockX() + dx;
                            int z = center.getBlockZ() + dz;
                            int y = center.getBlockY();
                            Block ground = world.getBlockAt(x, y - 1, z);
                            Block target = world.getBlockAt(x, y, z);
                            if (target.getType().isAir() && ground.getType().isSolid()) {
                                target.setType(Material.POWDER_SNOW, false);
                            }
                        }
                    }
                }

                for(Entity nearby : world.getNearbyEntities(center, (double)5.0F, (double)5.0F, (double)5.0F)) {
                    if (nearby instanceof Player) {
                        Player player = (Player)nearby;
                        player.setFreezeTicks(Math.min(player.getFreezeTicks() + 100, 200));
                    }
                }

                world.spawnParticle(Particle.SNOWFLAKE, center, 120, (double)2.0F, (double)1.0F, (double)2.0F, 0.1);
                world.playSound(center, Sound.BLOCK_POWDER_SNOW_FALL, 1.5F, 0.7F);
                return;
            }
        }

    }

    /**
     * 首领凋灵尝试改变方块时取消该行为。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Wither wither) {
            if (this.isBoss(wither)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * 首领死亡时清空默认掉落，改为掉落冰川之核与随机数量的永霜碎片，并广播挑战完成。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Wither wither) {
            if (this.isBoss(wither)) {
                this.currentBossId = null;
                event.getDrops().clear();
                World world = wither.getWorld();
                Location loc = wither.getLocation();
                Player killer = wither.getKiller();
                world.dropItemNaturally(loc, this.createGlacialisCore(killer));
                int shards = 4 + (new Random()).nextInt(3);

                for(int i = 0; i < shards; ++i) {
                    world.dropItemNaturally(loc, this.createEverfrostShard(killer));
                }

                String msg = this.plugin.lang.tr((Player)null, "boss.glacialis.defeated");

                for(Player player : world.getPlayers()) {
                    player.sendMessage(msg);
                    player.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
                }

                event.setDroppedExp(300);
            }
        }
    }

    /**
     * 生成一个「永霜碎片」物品（海晶碎片外观，带本地化名称/描述与材料标记）。
     */
    public ItemStack createEverfrostShard(Player viewer) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, "item.everfrost_shard.name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, "item.everfrost_shard.lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, "item.everfrost_shard.lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.EVERFROST_SHARD_KEY, PersistentDataType.BYTE, (byte)1);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * 生成一个「冰川之核」物品（海洋之心外观，带本地化名称/描述与材料标记）。
     */
    public ItemStack createGlacialisCore(Player viewer) {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, "item.glacialis_core.name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, "item.glacialis_core.lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, "item.glacialis_core.lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.GLACIALIS_CORE_KEY, PersistentDataType.BYTE, (byte)1);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * 生成一件「永霜护甲」部件（指定材质，名称/描述由语言键前缀拼装，带护甲标记）。
     */
    private ItemStack createHeatArmorPiece(Player viewer, Material material, String nameKeyPrefix) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.lang.tr(viewer, nameKeyPrefix + ".name"));
            List<String> lore = new ArrayList();
            lore.add(this.plugin.lang.tr(viewer, nameKeyPrefix + ".lore_line1"));
            lore.add(this.plugin.lang.tr(viewer, nameKeyPrefix + ".lore_line2"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.HEAT_ARMOR_KEY, PersistentDataType.BYTE, (byte)1);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * 注册永霜护甲四件套（头盔、胸甲、护腿、靴子）的合成配方。
     */
    private void registerRecipes() {
        ShapedRecipe helmetRecipe = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_helmet"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_HELMET, "item.heat_armor.helmet"));
        helmetRecipe.shape(new String[]{"SSS", "SCS", " H "});
        helmetRecipe.setIngredient('S', Material.PRISMARINE_SHARD);
        helmetRecipe.setIngredient('C', Material.HEART_OF_THE_SEA);
        helmetRecipe.setIngredient('H', Material.DIAMOND_HELMET);
        Bukkit.addRecipe(helmetRecipe);
        ShapedRecipe chestRecipe = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_chest"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_CHESTPLATE, "item.heat_armor.chest"));
        chestRecipe.shape(new String[]{" C ", "SAS", "S S"});
        chestRecipe.setIngredient('S', Material.PRISMARINE_SHARD);
        chestRecipe.setIngredient('C', Material.HEART_OF_THE_SEA);
        chestRecipe.setIngredient('A', Material.DIAMOND_CHESTPLATE);
        Bukkit.addRecipe(chestRecipe);
        ShapedRecipe legsRecipe = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_legs"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_LEGGINGS, "item.heat_armor.legs"));
        legsRecipe.shape(new String[]{"SCS", "SAS", "SAS"});
        legsRecipe.setIngredient('S', Material.PRISMARINE_SHARD);
        legsRecipe.setIngredient('C', Material.HEART_OF_THE_SEA);
        legsRecipe.setIngredient('A', Material.DIAMOND_LEGGINGS);
        Bukkit.addRecipe(legsRecipe);
        ShapedRecipe bootsRecipe = new ShapedRecipe(new NamespacedKey(this.plugin, "everfrost_boots"), this.createHeatArmorPiece((Player)null, Material.DIAMOND_BOOTS, "item.heat_armor.boots"));
        bootsRecipe.shape(new String[]{" C ", "SAS", "S S"});
        bootsRecipe.setIngredient('S', Material.PRISMARINE_SHARD);
        bootsRecipe.setIngredient('C', Material.HEART_OF_THE_SEA);
        bootsRecipe.setIngredient('A', Material.DIAMOND_BOOTS);
        Bukkit.addRecipe(bootsRecipe);
    }

    /**
     * 判断物品是否为带护甲标记的永霜护甲部件。
     */
    public boolean isHeatArmorPiece(ItemStack item) {
        if (item == null) {
            return false;
        } else if (!item.hasItemMeta()) {
            return false;
        } else {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte)meta.getPersistentDataContainer().get(this.HEAT_ARMOR_KEY, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        }
    }

    /**
     * 判断物品是否带有指定键的持久化标记（值等于 1）。
     */
    private boolean hasFlag(ItemStack item, NamespacedKey key) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte)meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        } else {
            return false;
        }
    }

    /**
     * 判断配方是否为名称以 {@code everfrost_} 开头的永霜护甲配方。
     */
    private boolean isEverfrostRecipe(Recipe recipe) {
        if (!(recipe instanceof ShapedRecipe shapedRecipe)) {
            return false;
        } else {
            NamespacedKey key = shapedRecipe.getKey();
            return key != null && key.getKey() != null && key.getKey().startsWith("everfrost_");
        }
    }

    /**
     * 从合成界面查看者中取出第一个玩家（用于本地化显示）。
     */
    private Player firstViewer(PrepareItemCraftEvent event) {
        for(HumanEntity viewer : event.getViewers()) {
            if (viewer instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    /**
     * 校验合成矩阵中的材料是否均带有正确的持久化标记：海晶碎片须为永霜碎片，
     * 海洋之心须为冰川之核，其余材料忽略。
     */
    private boolean matrixHasValidPdc(ItemStack[] matrix) {
        if (matrix == null) {
            return false;
        } else {
            for(ItemStack item : matrix) {
                if (item != null && item.getType() != Material.AIR) {
                    if (item.getType() == Material.PRISMARINE_SHARD) {
                        if (!this.hasFlag(item, this.EVERFROST_SHARD_KEY)) {
                            return false;
                        }
                    } else if (item.getType() == Material.HEART_OF_THE_SEA && !this.hasFlag(item, this.GLACIALIS_CORE_KEY)) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    /**
     * 根据配方键返回对应的本地化护甲成品；非永霜护甲配方返回 null。
     */
    private ItemStack localizedResultFor(Recipe recipe, Player viewer) {
        if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shapedRecipe = (ShapedRecipe)recipe;
            ItemStack result;
            switch (shapedRecipe.getKey().getKey()) {
                case "everfrost_helmet" -> result = this.createHeatArmorPiece(viewer, Material.DIAMOND_HELMET, "item.heat_armor.helmet");
                case "everfrost_chest" -> result = this.createHeatArmorPiece(viewer, Material.DIAMOND_CHESTPLATE, "item.heat_armor.chest");
                case "everfrost_legs" -> result = this.createHeatArmorPiece(viewer, Material.DIAMOND_LEGGINGS, "item.heat_armor.legs");
                case "everfrost_boots" -> result = this.createHeatArmorPiece(viewer, Material.DIAMOND_BOOTS, "item.heat_armor.boots");
                default -> result = null;
            }

            return result;
        } else {
            return null;
        }
    }

    /**
     * 预览合成结果时：若材料标记无效则清空结果，否则替换为本地化成品。
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPrepareEverfrostCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (this.isEverfrostRecipe(recipe)) {
            CraftingInventory inventory = event.getInventory();
            ItemStack[] matrix = inventory.getMatrix();
            if (!this.matrixHasValidPdc(matrix)) {
                inventory.setResult((ItemStack)null);
            } else {
                Player viewer = this.firstViewer(event);
                inventory.setResult(this.localizedResultFor(recipe, viewer));
            }
        }
    }

    /**
     * 实际合成时再次校验材料标记，无效则取消合成并清空结果，防止伪造材料。
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onCraftEverfrost(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (this.isEverfrostRecipe(recipe)) {
            CraftingInventory inventory = event.getInventory();
            if (inventory instanceof CraftingInventory) {
                if (!this.matrixHasValidPdc(inventory.getMatrix())) {
                    event.setCancelled(true);
                    inventory.setResult((ItemStack)null);
                }

            }
        }
    }
}
