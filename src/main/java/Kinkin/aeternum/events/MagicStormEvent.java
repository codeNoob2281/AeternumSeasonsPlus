package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.world.WinterWorldGuardHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 魔法风暴事件（Magic Storm）。
 *
 * <p>在允许的季节按概率触发。事件激活期间，主世界会持续雷暴：
 * <ul>
 *     <li>靠近玩家的敌对生物有几率被“风暴化”——发光、获得速度/力量、改名，
 *         并在事件结束时恢复原始名称与状态；</li>
 *     <li>玩家击杀敌对生物时有几率额外掉落随机附魔书或附魔装备；</li>
 *     <li>阻止雷击破坏受保护区域内的方块。</li>
 * </ul>
 * 生物状态通过 {@link PersistentDataContainer} 暂存，区块加载时也会清理残留标记。</p>
 */
public final class MagicStormEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /** 是否启用全局粒子特效。 */
    private boolean globalParticlesEnabled;
    /** 事件最短持续天数。 */
    private int minDur;
    /** 事件最长持续天数。 */
    private int maxDur;
    /** 每天触发事件的基础概率。 */
    private double baseChance;
    /** 允许触发本事件的季节列表。 */
    private List<Season> allowedSeasons;
    /** 事件当前是否处于激活状态。 */
    private boolean active = false;
    /** 敌对生物被风暴化的概率。 */
    private double rareMobChance;
    /** 击杀时额外掉落战利品的概率。 */
    private double extraLootChance;
    /** 标记生物已被风暴化的键。 */
    private final NamespacedKey kStormMark;
    /** 暂存生物原有名称的键。 */
    private final NamespacedKey kPrevName;
    /** 暂存生物原有名称可见性的键。 */
    private final NamespacedKey kPrevNameVisible;
    /** 内部 tick 计数器，每 40 tick 执行一次附近生物扫描。 */
    private int tickCounter = 0;
    private static final Enchantment[] WEAPON_ENCHANTS;
    private static final Enchantment[] ARMOR_ENCHANTS;
    private static final Enchantment[] BOW_ENCHANTS;
    private static final Enchantment[] FISHING_ENCHANTS;
    private static final Enchantment[] TOOL_ENCHANTS;
    private static final Enchantment[] ANY_ENCHANTS;

    public MagicStormEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.kStormMark = new NamespacedKey(plugin, "magicstorm_mark");
        this.kPrevName = new NamespacedKey(plugin, "magicstorm_prev_name");
        this.kPrevNameVisible = new NamespacedKey(plugin, "magicstorm_prev_name_visible");
        this.reloadFromConfig();
    }

    /** 从 events.yml 重新读取本事件的配置项（含允许季节清单）。 */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.magic_storm.enabled", true);
        this.globalParticlesEnabled = config.getBoolean("events.visual_effects.particles_enabled", true);
        this.minDur = config.getInt("events.magic_storm.min_duration_days", 1);
        this.maxDur = config.getInt("events.magic_storm.max_duration_days", 1);
        this.baseChance = config.getDouble("events.magic_storm.base_chance_per_day", 0.08);
        this.rareMobChance = config.getDouble("events.magic_storm.rare_mob_chance", 0.35);
        this.extraLootChance = config.getDouble("events.magic_storm.extra_loot_chance", 0.4);
        this.allowedSeasons = new ArrayList();

        for(String seasonName : config.getStringList("events.magic_storm.allowed_seasons")) {
            try {
                this.allowedSeasons.add(Season.valueOf(seasonName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (this.allowedSeasons.isEmpty()) {
            this.allowedSeasons.addAll(Arrays.asList(Season.values()));
        }

    }

    public String getId() {
        return "magic_storm";
    }

    public String getDisplayName() {
        return "Magic Storm";
    }

    public boolean isSeasonAllowed(Season season) {
        return this.allowedSeasons.contains(season);
    }

    public int getMinDurationDays() {
        return this.minDur;
    }

    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /** 判定是否可开始：已启用、当前季节在允许列表中且通过基础概率。 */
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else if (!this.allowedSeasons.contains(state.season)) {
            return false;
        } else {
            return Math.random() < this.baseChance;
        }
    }

    /** 事件开始：置为激活，令主世界进入雷暴并向全服玩家发送标题。 */
    public void onStart(CalendarState state, EventContext context) {
        this.active = true;

        for(World world : context.overworlds()) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(24000);
            world.playSound(world.getSpawnLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6F, 0.4F);
        }

        for(Player player : Bukkit.getOnlinePlayers()) {
            String title = this.plugin.lang.tr(player, "event.magic_storm.title");
            String sub = this.plugin.lang.tr(player, "event.magic_storm.subtitle");
            player.sendTitle(title, sub, 20, 80, 40);
        }

    }

    /** 事件结束：取消激活、停止雷暴并清理所有风暴化生物，然后通知玩家。 */
    public void onEnd(CalendarState state, EventContext context) {
        this.active = false;

        for(World world : context.overworlds()) {
            if (world.getEnvironment() == Environment.NORMAL) {
                world.setThundering(false);
                world.setStorm(false);

                for(Monster mob : world.getEntitiesByClass(Monster.class)) {
                    PersistentDataContainer pdc = mob.getPersistentDataContainer();
                    if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                        String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
                        Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
                        mob.setGlowing(false);
                        mob.removePotionEffect(PotionEffectType.SPEED);
                        mob.removePotionEffect(PotionEffectType.STRENGTH);
                        if (prevName != null && !prevName.isEmpty()) {
                            mob.setCustomName(prevName);
                        } else {
                            mob.setCustomName((String)null);
                        }

                        mob.setCustomNameVisible(prevVis != null && prevVis == 1);
                        pdc.remove(this.kStormMark);
                        pdc.remove(this.kPrevName);
                        pdc.remove(this.kPrevNameVisible);
                    }
                }
            }
        }

        for(Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.magic_storm.end"));
        }

    }

    public void onDayTick(CalendarState state, EventContext context) {
    }

    /** 每 tick 维持雷暴天气并播放粒子；每 40 tick 扫描附近生物将其风暴化。 */
    public void onTick(CalendarState state, EventContext context) {
        if (this.active) {
            for(World world : context.overworlds()) {
                if (world.getEnvironment() == Environment.NORMAL && (!world.hasStorm() || !world.isThundering())) {
                    world.setStorm(true);
                    world.setThundering(true);
                }
            }

            for(Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().getEnvironment() == Environment.NORMAL && !player.isDead() && this.globalParticlesEnabled) {
                    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add((double)0.0F, (double)1.5F, (double)0.0F), 2, 0.3, 0.4, 0.3, 0.01);
                }
            }

            ++this.tickCounter;
            if (this.tickCounter >= 40) {
                this.tickCounter = 0;

                for(Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld().getEnvironment() == Environment.NORMAL) {
                        World world = player.getWorld();

                        for(Entity entity : world.getNearbyEntities(player.getLocation(), (double)24.0F, (double)16.0F, (double)24.0F)) {
                            if (entity instanceof Monster) {
                                Monster mob = (Monster)entity;
                                this.tryMakeRareMob(mob, player);
                            }
                        }
                    }
                }
            }

        }
    }

    /** 监听生物生成：主世界且事件激活时，将离玩家最近的生成生物尝试风暴化。 */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Monster mob) {
            World world = event.getLocation().getWorld();
            if (world != null) {
                if (world.getEnvironment() == Environment.NORMAL) {
                    if (this.active) {
                        Player closest = null;
                        double best = Double.MAX_VALUE;

                        for(Player player : world.getPlayers()) {
                            double distanceSq = player.getLocation().distanceSquared(mob.getLocation());
                            if (distanceSq < best) {
                                best = distanceSq;
                                closest = player;
                            }
                        }

                        this.tryMakeRareMob(mob, closest);
                    }
                }
            }
        }
    }

    /** 尝试把生物转化为“风暴生物”：记录原名、发光、加速加力、改名并定时移除发光。 */
    private void tryMakeRareMob(Monster mob, @Nullable Player viewer) {
        if (!mob.isDead()) {
            World world = mob.getWorld();
            if (world.getEnvironment() == Environment.NORMAL) {
                if (this.active) {
                    String existingName = mob.getCustomName();
                    if (existingName == null || existingName.isBlank()) {
                        PersistentDataContainer pdc = mob.getPersistentDataContainer();
                        if (!pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                            if (!(Math.random() > this.rareMobChance)) {
                                String stormName = viewer != null ? this.plugin.lang.tr(viewer, "event.magic_storm.mob_name") : this.plugin.lang.trServer("event.magic_storm.mob_name");
                                pdc.set(this.kPrevName, PersistentDataType.STRING, existingName == null ? "" : existingName);
                                pdc.set(this.kPrevNameVisible, PersistentDataType.BYTE, (byte)(mob.isCustomNameVisible() ? 1 : 0));
                                pdc.set(this.kStormMark, PersistentDataType.BYTE, (byte)1);
                                mob.setGlowing(true);
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6000, 1, true, false, true));
                                mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 6000, 0, true, false, true));
                                mob.setCustomName(stormName);
                                mob.setCustomNameVisible(true);
                                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                                    if (!mob.isDead()) {
                                        mob.setGlowing(false);
                                    }

                                }, 100L);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 监听生物死亡：事件激活时按概率额外掉落随机附魔书或附魔装备。 */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Monster) {
            World world = event.getEntity().getWorld();
            if (world.getEnvironment() == Environment.NORMAL) {
                if (this.active) {
                    if (!(Math.random() > this.extraLootChance)) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        ItemStack reward;
                        if (rnd.nextDouble() < (double)0.5F) {
                            reward = this.createRandomEnchantedBook(rnd);
                        } else {
                            reward = this.createRandomEnchantedGear(rnd);
                        }

                        event.getDrops().add(reward);
                    }
                }
            }
        }
    }

    /** 生成一本随机附魔的附魔书。 */
    private ItemStack createRandomEnchantedBook(ThreadLocalRandom rnd) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
        if (meta != null && ANY_ENCHANTS.length != 0) {
            Enchantment ench = this.randomFrom(ANY_ENCHANTS, rnd);
            int level = this.randomLevelFor(ench, false, rnd);
            meta.addStoredEnchant(ench, level, true);
            book.setItemMeta(meta);
            return book;
        } else {
            return book;
        }
    }

    /** 生成一件随机附魔的装备（武器/护甲/弓/钓竿/工具）。 */
    private ItemStack createRandomEnchantedGear(ThreadLocalRandom rnd) {
        double roll = rnd.nextDouble();
        ItemStack item;
        if (roll < 0.4) {
            Material type = rnd.nextBoolean() ? Material.IRON_SWORD : Material.IRON_AXE;
            item = new ItemStack(type);
            Enchantment ench = this.randomFrom(WEAPON_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (roll < 0.7) {
            Material[] armorTypes = new Material[]{Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
            item = new ItemStack(armorTypes[rnd.nextInt(armorTypes.length)]);
            Enchantment ench = this.randomFrom(ARMOR_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (roll < 0.85) {
            item = new ItemStack(Material.BOW);
            Enchantment ench = this.randomFrom(BOW_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else if (roll < 0.95) {
            item = new ItemStack(Material.FISHING_ROD);
            Enchantment ench = this.randomFrom(FISHING_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        } else {
            Material[] tools = new Material[]{Material.IRON_PICKAXE, Material.IRON_AXE, Material.IRON_SHOVEL};
            item = new ItemStack(tools[rnd.nextInt(tools.length)]);
            Enchantment ench = this.randomFrom(TOOL_ENCHANTS, rnd);
            this.applyEnchant(item, ench, rnd);
        }

        return item;
    }

    /** 对物品施加随机等级的附魔，并清除其耐久损耗。 */
    private void applyEnchant(ItemStack item, Enchantment ench, ThreadLocalRandom rnd) {
        int level = this.randomLevelFor(ench, true, rnd);
        item.addUnsafeEnchantment(ench, level);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
            item.setItemMeta(meta);
        }

    }

    /** 从给定附魔池中随机取一个附魔。 */
    private Enchantment randomFrom(Enchantment[] pool, ThreadLocalRandom rnd) {
        return pool[rnd.nextInt(pool.length)];
    }

    /** 计算附魔的随机等级（装备上限较小，书上限较大）。 */
    private int randomLevelFor(Enchantment ench, boolean gear, ThreadLocalRandom rnd) {
        int max = ench.getMaxLevel();
        if (gear) {
            max = Math.min(max, 3);
        } else {
            max = Math.min(max, 4);
        }

        int min = 1;
        return max <= min ? max : rnd.nextInt(min, max + 1);
    }

    /** 事件未激活时清理区块中残留的风暴化标记（例如跨存档残留）。 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() == Environment.NORMAL) {
            if (!this.active) {
                for(Entity entity : event.getChunk().getEntities()) {
                    if (entity instanceof Monster) {
                        Monster mob = (Monster)entity;
                        PersistentDataContainer pdc = mob.getPersistentDataContainer();
                        if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
                            String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
                            Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
                            mob.setGlowing(false);
                            mob.removePotionEffect(PotionEffectType.SPEED);
                            mob.removePotionEffect(PotionEffectType.STRENGTH);
                            if (prevName != null && !prevName.isEmpty()) {
                                mob.setCustomName(prevName);
                            } else {
                                mob.setCustomName((String)null);
                            }

                            mob.setCustomNameVisible(prevVis != null && prevVis == 1);
                            pdc.remove(this.kStormMark);
                            pdc.remove(this.kPrevName);
                            pdc.remove(this.kPrevNameVisible);
                        }
                    }
                }

            }
        }
    }

    /** 清除单个风暴化生物的所有影响并移除标记。 */
    private void cleanupStormMob(Monster mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (pdc.has(this.kStormMark, PersistentDataType.BYTE)) {
            String prevName = (String)pdc.get(this.kPrevName, PersistentDataType.STRING);
            Byte prevVis = (Byte)pdc.get(this.kPrevNameVisible, PersistentDataType.BYTE);
            mob.setGlowing(false);
            mob.removePotionEffect(PotionEffectType.SPEED);
            mob.removePotionEffect(PotionEffectType.STRENGTH);
            if (prevName != null && !prevName.isEmpty()) {
                mob.setCustomName(prevName);
            } else {
                mob.setCustomName((String)null);
            }

            mob.setCustomNameVisible(prevVis != null && prevVis == 1);
            pdc.remove(this.kStormMark);
            pdc.remove(this.kPrevName);
            pdc.remove(this.kPrevNameVisible);
        }
    }

    /** 事件激活时拦截会破坏受保护区域的雷击。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onLightningStrike(LightningStrikeEvent event) {
        if (this.active) {
            Location loc = event.getLightning().getLocation();
            World world = loc.getWorld();
            if (world != null && world.getEnvironment() == Environment.NORMAL) {
                Block block = world.getBlockAt(loc);
                if (!WinterWorldGuardHelper.canModify(block)) {
                    event.setCancelled(true);
                }

            }
        }
    }

    static {
        WEAPON_ENCHANTS = new Enchantment[]{Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS, Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE, Enchantment.KNOCKBACK, Enchantment.UNBREAKING, Enchantment.MENDING};
        ARMOR_ENCHANTS = new Enchantment[]{Enchantment.PROTECTION, Enchantment.PROJECTILE_PROTECTION, Enchantment.FIRE_PROTECTION, Enchantment.BLAST_PROTECTION, Enchantment.THORNS, Enchantment.UNBREAKING, Enchantment.MENDING};
        BOW_ENCHANTS = new Enchantment[]{Enchantment.POWER, Enchantment.PUNCH, Enchantment.FLAME, Enchantment.INFINITY, Enchantment.UNBREAKING, Enchantment.MENDING};
        FISHING_ENCHANTS = new Enchantment[]{Enchantment.LUCK_OF_THE_SEA, Enchantment.LURE, Enchantment.UNBREAKING, Enchantment.MENDING};
        TOOL_ENCHANTS = new Enchantment[]{Enchantment.EFFICIENCY, Enchantment.FORTUNE, Enchantment.SILK_TOUCH, Enchantment.UNBREAKING, Enchantment.MENDING};
        List<Enchantment> all = new ArrayList();
        all.addAll(Arrays.asList(WEAPON_ENCHANTS));
        all.addAll(Arrays.asList(ARMOR_ENCHANTS));
        all.addAll(Arrays.asList(BOW_ENCHANTS));
        all.addAll(Arrays.asList(FISHING_ENCHANTS));
        all.addAll(Arrays.asList(TOOL_ENCHANTS));
        Set<Enchantment> set = new LinkedHashSet(all);
        ANY_ENCHANTS = (Enchantment[])set.toArray(new Enchantment[0]);
    }
}
