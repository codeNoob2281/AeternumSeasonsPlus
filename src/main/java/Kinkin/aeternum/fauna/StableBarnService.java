package kinkin.aeternum.fauna;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.Season;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 稳定谷仓（Stable Barn）服务。
 *
 * <p>围绕“谷仓”这一玩家建造结构提供多项养殖增益，并维护马匹的耐力/亲密度系统：
 * <ul>
 *     <li>通过屋顶（实心/天光）与干草堆判断某位置是否处于谷仓内；</li>
 *     <li>谷仓内繁殖可获得额外幼崽；动物在谷仓内死亡/剪毛可额外掉落产物；</li>
 *     <li>马匹随骑乘与在谷仓休息增减耐力，耐力过低会获得“疲惫”减速，在谷仓内可回血；</li>
 *     <li>亲密度随骑乘、在谷仓、健康状态增长，并转化为速度/生命上限加成；</li>
 *     <li>动物 AI：冬寻庇护、夏寻阴凉，向最近的有顶棚位置轻微“推挤”。</li>
 * </ul>
 * 季节通过反射从插件中获取以兼容不同版本，依赖 {@link AeternumSeasonsPlugin} 提供配置与调度器。</p>
 */
public final class StableBarnService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private File faunaFile;
    private FileConfiguration fauna;
    private final NamespacedKey HORSE_STAMINA;
    private final NamespacedKey HORSE_TIRED;
    private final NamespacedKey HORSE_AFFINITY;
    private final NamespacedKey HORSE_AFFINITY_STEP;
    private static final UUID TIRED_MOD_UUID = UUID.fromString("2a0aa02d-9d6f-4d1c-9b2f-3f0c7c0dfd2a");
    private static final UUID AFFINITY_SPEED_MOD_UUID = UUID.fromString("db1d4d1e-8c08-4f08-906c-7c4b892b7c55");
    private static final UUID AFFINITY_HEALTH_MOD_UUID = UUID.fromString("c0dc8d67-bcb6-4675-9141-56d7d3ebd7e0");
    private BukkitTask winterTask;
    private BukkitTask horseTask;
    private BukkitTask aiTask;
    private final List<Horse> horseScan = new ArrayList();
    private int horseIdx = 0;
    private final List<Mob> aiScan = new ArrayList();
    private int aiIdx = 0;

    public StableBarnService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.HORSE_STAMINA = new NamespacedKey(plugin, "horse_stamina");
        this.HORSE_TIRED = new NamespacedKey(plugin, "horse_tired");
        this.HORSE_AFFINITY = new NamespacedKey(plugin, "horse_affinity");
        this.HORSE_AFFINITY_STEP = new NamespacedKey(plugin, "horse_affinity_step");
    }

    /**
     * 加载配置、注册事件监听并启动冬季/马匹/动物 AI 三类任务。
     */
    public void register() {
        this.loadFauna();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.startWinterTask();
        this.startHorseTask();
        this.startAiTask();
    }

    /**
     * 注销事件监听、取消所有任务并清空扫描缓存。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        if (this.winterTask != null) {
            this.winterTask.cancel();
        }

        if (this.horseTask != null) {
            this.horseTask.cancel();
        }

        if (this.aiTask != null) {
            this.aiTask.cancel();
        }

        this.winterTask = this.horseTask = this.aiTask = null;
        this.horseScan.clear();
        this.aiScan.clear();
        this.horseIdx = this.aiIdx = 0;
    }

    /**
     * 重新加载配置并重启全部任务。
     */
    public void reload() {
        this.loadFauna();
        this.startWinterTask();
        this.startHorseTask();
        this.startAiTask();
    }

    /**
     * 确保数据目录存在，并在 fauna.yml 缺失时尝试落盘默认资源或创建空文件。
     */
    private void loadFauna() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }

        this.faunaFile = new File(this.plugin.getDataFolder(), "fauna.yml");
        if (!this.faunaFile.exists()) {
            try {
                this.plugin.saveResource("fauna.yml", false);
            } catch (Throwable ignored) {
                try {
                    this.faunaFile.createNewFile();
                } catch (IOException ignoredToo) {
                }
            }
        }

        this.fauna = YamlConfiguration.loadConfiguration(this.faunaFile);
    }

    /**
     * 谷仓功能是否启用。
     */
    private boolean enabled() {
        return this.fauna.getBoolean("stables.enabled", true);
    }

    /**
     * 判断位置上方是否存在屋顶（天光或指定/任意实心方块）。
     */
    private boolean hasRoofNear(Location loc) {
        int minH = this.fauna.getInt("stables.roof.min_height", 1);
        int maxH = this.fauna.getInt("stables.roof.max_height", 6);
        boolean anySolid = this.fauna.getBoolean("stables.roof.allow_any_solid", true);
        int area = this.fauna.getInt("stables.roof.area_radius", 0);
        boolean useSky = this.fauna.getBoolean("stables.roof.use_skylight", true);
        int skyMax = this.fauna.getInt("stables.roof.skylight_max", 14);
        if (minH < 1) {
            minH = 1;
        }

        if (maxH < minH) {
            maxH = minH;
        }

        if (area < 0) {
            area = 0;
        }

        if (skyMax < 0) {
            skyMax = 0;
        }

        if (skyMax > 15) {
            skyMax = 15;
        }

        Set<Material> allow = new HashSet();

        for (String s : this.fauna.getStringList("stables.roof.allow")) {
            Material m = Material.matchMaterial(s);
            if (m != null) {
                allow.add(m);
            }
        }

        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();
            if (useSky) {
                int sky = w.getBlockAt(bx, by, bz).getLightFromSky();
                if (sky <= skyMax) {
                    return true;
                }
            }

            for (int dx = -area; dx <= area; ++dx) {
                for (int dz = -area; dz <= area; ++dz) {
                    for (int dy = minH; dy <= maxH; ++dy) {
                        Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                        Material type = b.getType();
                        if (type != Material.AIR) {
                            if (allow.contains(type)) {
                                return true;
                            }

                            if (anySolid && type.isSolid()) {
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
     * 判断位置是否处于谷仓内：有屋顶且附近干草堆数量达标。
     */
    private boolean isInBarn(Location loc) {
        int radius = this.fauna.getInt("stables.barn_radius", 6);
        int minHay = this.fauna.getInt("stables.min_hay_bales", 3);
        if (!this.hasRoofNear(loc)) {
            return false;
        } else {
            World w = loc.getWorld();
            if (w == null) {
                return false;
            } else {
                int cx = loc.getBlockX();
                int cy = loc.getBlockY();
                int cz = loc.getBlockZ();
                int hay = 0;

                for (int x = cx - radius; x <= cx + radius; ++x) {
                    for (int y = cy - 2; y <= cy + 2; ++y) {
                        for (int z = cz - radius; z <= cz + radius; ++z) {
                            if (w.getBlockAt(x, y, z).getType() == Material.HAY_BLOCK) {
                                ++hay;
                                if (hay >= minHay) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                return false;
            }
        }
    }

    /**
     * 繁殖监听：谷仓内繁殖可产出额外幼崽；若要求必须在谷仓内而条件不满足则取消繁殖。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBreed(EntityBreedEvent e) {
        if (this.enabled()) {
            LivingEntity mother = e.getMother();
            LivingEntity father = e.getFather();
            if (mother instanceof Animals && father instanceof Animals) {
                boolean requireBarn = this.fauna.getBoolean("stables.breeding.require_barn", false);
                boolean motherInBarn = this.isInBarn(mother.getLocation());
                boolean fatherInBarn = this.isInBarn(father.getLocation());
                boolean bothInBarn = motherInBarn && fatherInBarn;
                if (requireBarn && !bothInBarn) {
                    e.setCancelled(true);
                    LivingEntity breeder = e.getBreeder();
                    if (breeder instanceof Player) {
                        Player player = (Player) breeder;
                        player.sendMessage(color(this.plugin.lang.tr(player, "fauna.barn.breed_fail")));
                    }

                } else if (this.fauna.getBoolean("stables.breeding.barn_bonus_enabled", true)) {
                    if (bothInBarn) {
                        double chance = this.clamp01(this.fauna.getDouble("stables.breeding.bonus_baby_chance", 0.65));
                        if (!(ThreadLocalRandom.current().nextDouble() > chance)) {
                            int extra = this.randRange("stables.breeding.extra_babies_min", "stables.breeding.extra_babies_max", 1, 1);
                            this.spawnBonusBabies(e, extra);
                        }
                    }
                }
            }
        }
    }

    /**
     * 在幼崽位置额外生成若干只幼年动物并播放心形粒子与音效。
     */
    private void spawnBonusBabies(EntityBreedEvent e, int amount) {
        if (amount > 0) {
            Entity child = e.getEntity();
            if (child instanceof Ageable) {
                Ageable childAgeable = (Ageable) child;
                World world = child.getWorld();
                Location loc = child.getLocation();
                EntityType type = child.getType();

                for (int i = 0; i < amount; ++i) {
                    Entity spawned = world.spawnEntity(loc, type);
                    if (spawned instanceof Ageable) {
                        Ageable baby = (Ageable) spawned;
                        baby.setBaby();
                    }
                }

                world.spawnParticle(Particle.HEART, loc.clone().add((double) 0.0F, 0.8, (double) 0.0F), 8, 0.35, (double) 0.25F, 0.35, 0.02);
                world.playSound(loc, Sound.ENTITY_CHICKEN_EGG, 0.7F, 1.2F);
            }
        }
    }

    /**
     * 死亡监听：动物在谷仓内死亡时额外掉落对应产物。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDeath(EntityDeathEvent e) {
        if (this.enabled()) {
            if (this.fauna.getBoolean("stables.drops.enabled", true)) {
                LivingEntity ent = e.getEntity();
                if (ent instanceof Animals) {
                    if (this.isInBarn(ent.getLocation())) {
                        switch (ent.getType()) {
                            case COW:
                                int beef = this.randRange("stables.drops.cow.extra_beef_min", "stables.drops.cow.extra_beef_max", 1, 2);
                                int leather = this.randRange("stables.drops.cow.extra_leather_min", "stables.drops.cow.extra_leather_max", 0, 1);
                                if (beef > 0) {
                                    e.getDrops().add(new ItemStack(Material.BEEF, beef));
                                }

                                if (leather > 0) {
                                    e.getDrops().add(new ItemStack(Material.LEATHER, leather));
                                }
                                break;
                            case PIG:
                                int pork = this.randRange("stables.drops.pig.extra_pork_min", "stables.drops.pig.extra_pork_max", 1, 2);
                                if (pork > 0) {
                                    e.getDrops().add(new ItemStack(Material.PORKCHOP, pork));
                                }
                                break;
                            case CHICKEN:
                                int ch = this.randRange("stables.drops.chicken.extra_chicken_min", "stables.drops.chicken.extra_chicken_max", 1, 2);
                                int fe = this.randRange("stables.drops.chicken.extra_feather_min", "stables.drops.chicken.extra_feather_max", 0, 2);
                                if (ch > 0) {
                                    e.getDrops().add(new ItemStack(Material.CHICKEN, ch));
                                }

                                if (fe > 0) {
                                    e.getDrops().add(new ItemStack(Material.FEATHER, fe));
                                }
                        }

                    }
                }
            }
        }
    }

    /**
     * 剪毛监听：羊在谷仓内被剪毛时额外掉落羊毛。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onShear(PlayerShearEntityEvent e) {
        if (this.enabled()) {
            if (this.fauna.getBoolean("stables.drops.enabled", true)) {
                Entity entity = e.getEntity();
                if (entity instanceof Sheep) {
                    Sheep sheep = (Sheep) entity;
                    if (this.isInBarn(sheep.getLocation())) {
                        int extra = this.randRange("stables.drops.sheep.extra_wool_min", "stables.drops.sheep.extra_wool_max", 1, 2);
                        if (extra > 0) {
                            Material wool = this.woolFromColor(sheep.getColor());
                            sheep.getWorld().dropItemNaturally(sheep.getLocation().add((double) 0.0F, (double) 0.5F, (double) 0.0F), new ItemStack(wool, extra));
                        }
                    }
                }
            }
        }
    }

    /**
     * 把染料颜色映射为对应颜色的羊毛材质。
     */
    private Material woolFromColor(DyeColor dyeColor) {
        Material wool;
        switch (dyeColor) {
            case WHITE -> wool = Material.WHITE_WOOL;
            case ORANGE -> wool = Material.ORANGE_WOOL;
            case MAGENTA -> wool = Material.MAGENTA_WOOL;
            case LIGHT_BLUE -> wool = Material.LIGHT_BLUE_WOOL;
            case YELLOW -> wool = Material.YELLOW_WOOL;
            case LIME -> wool = Material.LIME_WOOL;
            case PINK -> wool = Material.PINK_WOOL;
            case GRAY -> wool = Material.GRAY_WOOL;
            case LIGHT_GRAY -> wool = Material.LIGHT_GRAY_WOOL;
            case CYAN -> wool = Material.CYAN_WOOL;
            case PURPLE -> wool = Material.PURPLE_WOOL;
            case BLUE -> wool = Material.BLUE_WOOL;
            case BROWN -> wool = Material.BROWN_WOOL;
            case GREEN -> wool = Material.GREEN_WOOL;
            case RED -> wool = Material.RED_WOOL;
            case BLACK -> wool = Material.BLACK_WOOL;
            default -> throw new MatchException((String) null, (Throwable) null);
        }

        return wool;
    }

    /**
     * 按配置的最小/最大值区间随机取整数。
     */
    private int randRange(String minKey, String maxKey, int defMin, int defMax) {
        int min = this.fauna.getInt(minKey, defMin);
        int max = this.fauna.getInt(maxKey, defMax);
        if (max < min) {
            max = min;
        }

        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * 启动（或重置）冬季任务（当前为空实现）。
     */
    private void startWinterTask() {
        if (this.winterTask != null) {
            this.winterTask.cancel();
        }

        this.winterTask = null;
    }

    /**
     * 启动马匹扫描任务，分批更新每匹马的耐力与亲密度。
     */
    private void startHorseTask() {
        if (this.horseTask != null) {
            this.horseTask.cancel();
        }

        if (this.enabled()) {
            if (this.fauna.getBoolean("horse_stable.enabled", true)) {
                int interval = this.fauna.getInt("horse_stable.tick_interval_ticks", 40);
                int perTick = this.fauna.getInt("horse_stable.per_tick", 40);
                this.horseTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                    if (this.horseScan.isEmpty() || this.horseIdx >= this.horseScan.size()) {
                        this.horseScan.clear();
                        this.horseIdx = 0;

                        for (World w : Bukkit.getWorlds()) {
                            for (Horse h : w.getEntitiesByClass(Horse.class)) {
                                this.horseScan.add(h);
                            }
                        }
                    }

                    int done = 0;

                    while (done < perTick && this.horseIdx < this.horseScan.size()) {
                        Horse h = (Horse) this.horseScan.get(this.horseIdx++);
                        ++done;
                        if (h != null && !h.isDead() && h.isValid()) {
                            this.tickHorse(h);
                        }
                    }

                }, (long) interval, (long) interval);
            }
        }
    }

    /**
     * 更新单匹马的耐力：骑乘/移动消耗、静止与谷仓恢复，并处理疲惫减速与回血。
     */
    private void tickHorse(Horse h) {
        PersistentDataContainer pdc = h.getPersistentDataContainer();
        double max = this.fauna.getDouble("horse_stable.stamina.max", (double) 100.0F);
        double drain = this.fauna.getDouble("horse_stable.stamina.drain_per_tick", (double) 2.0F);
        double regen = this.fauna.getDouble("horse_stable.stamina.regen_per_tick", (double) 3.0F);
        double barnBonus = this.fauna.getDouble("horse_stable.stamina.regen_in_barn_bonus", (double) 4.0F);
        double tiredTh = this.fauna.getDouble("horse_stable.stamina.tired_threshold", (double) 15.0F);
        double recTh = this.fauna.getDouble("horse_stable.stamina.recover_threshold", (double) 35.0F);
        double tiredMul = this.fauna.getDouble("horse_stable.stamina.tired_speed_multiplier", 0.7);
        double stamina = (Double) pdc.getOrDefault(this.HORSE_STAMINA, PersistentDataType.DOUBLE, max);
        boolean ridden = h.getPassengers().stream().anyMatch((passenger) -> passenger instanceof Player);
        boolean moving = h.getVelocity().lengthSquared() > 0.01;
        boolean inBarn = this.isInBarn(h.getLocation());
        if (ridden) {
            if (moving) {
                stamina -= drain;
            } else {
                stamina += regen;
            }
        } else {
            stamina += regen;
        }

        if (inBarn) {
            stamina += barnBonus;
        }

        stamina = Math.max((double) 0.0F, Math.min(max, stamina));
        pdc.set(this.HORSE_STAMINA, PersistentDataType.DOUBLE, stamina);
        boolean tired = (Byte) pdc.getOrDefault(this.HORSE_TIRED, PersistentDataType.BYTE, (byte) 0) == 1;
        if (!tired && stamina <= tiredTh) {
            this.applyTiredSpeed(h, tiredMul);
            pdc.set(this.HORSE_TIRED, PersistentDataType.BYTE, (byte) 1);

            for (Entity passenger : h.getPassengers()) {
                if (passenger instanceof Player) {
                    Player pl = (Player) passenger;
                    pl.sendMessage(color(this.plugin.lang.tr(pl, "fauna.horse.tired")));
                    break;
                }
            }
        } else if (tired && stamina >= recTh) {
            this.clearTiredSpeed(h);
            pdc.set(this.HORSE_TIRED, PersistentDataType.BYTE, (byte) 0);
        }

        if (this.fauna.getBoolean("horse_stable.regen_in_barn.enabled", true) && inBarn) {
            double heal = this.fauna.getDouble("horse_stable.regen_in_barn.heal_amount", (double) 1.0F);
            boolean onlyIfNotFull = this.fauna.getBoolean("horse_stable.regen_in_barn.only_if_not_full", true);
            AttributeInstance healthAttr = h.getAttribute(Attribute.MAX_HEALTH);
            if (healthAttr != null) {
                double maxHp = healthAttr.getValue();
                if (!onlyIfNotFull || h.getHealth() < maxHp) {
                    h.setHealth(Math.min(maxHp, h.getHealth() + heal));
                }
            }
        }

        this.tickHorseAffinity(h, ridden, moving, inBarn);
    }

    /**
     * 更新单匹马的亲密度并根据进度应用属性加成。
     */
    private void tickHorseAffinity(Horse h, boolean ridden, boolean moving, boolean inBarn) {
        if (this.fauna.getBoolean("horse_stable.affinity.enabled", true)) {
            PersistentDataContainer pdc = h.getPersistentDataContainer();
            double maxAffinity = Math.max((double) 1.0F, this.fauna.getDouble("horse_stable.affinity.max", (double) 100.0F));
            double gainRidingMove = this.fauna.getDouble("horse_stable.affinity.gain_riding_move", 0.3);
            double gainInBarn = this.fauna.getDouble("horse_stable.affinity.gain_in_barn", 0.1);
            double gainHealthy = this.fauna.getDouble("horse_stable.affinity.gain_healthy", 0.1);
            double decay = Math.max((double) 0.0F, this.fauna.getDouble("horse_stable.affinity.decay_when_neglected", (double) 0.0F));
            double notifyEvery = Math.max((double) 1.0F, this.fauna.getDouble("horse_stable.affinity.notify_every", (double) 10.0F));
            double affinity = (Double) pdc.getOrDefault(this.HORSE_AFFINITY, PersistentDataType.DOUBLE, (double) 0.0F);
            if (ridden && moving) {
                affinity += gainRidingMove;
            }

            if (inBarn) {
                affinity += gainInBarn;
            }

            AttributeInstance healthAttr = h.getAttribute(Attribute.MAX_HEALTH);
            if (healthAttr != null) {
                double currentMaxHealth = healthAttr.getValue();
                if (h.getHealth() >= Math.max((double) 1.0F, currentMaxHealth - (double) 1.0F)) {
                    affinity += gainHealthy;
                }
            }

            if (!ridden && !inBarn && decay > (double) 0.0F) {
                affinity -= decay;
            }

            affinity = Math.max((double) 0.0F, Math.min(maxAffinity, affinity));
            pdc.set(this.HORSE_AFFINITY, PersistentDataType.DOUBLE, affinity);
            this.applyHorseAffinityBonuses(h, affinity / maxAffinity);
            this.maybeNotifyHorseAffinity(h, affinity, notifyEvery);
        }
    }

    /**
     * 当亲密度跨越阈值时，向骑手播报并播放粒子音效。
     */
    private void maybeNotifyHorseAffinity(Horse h, double affinity, double notifyEvery) {
        PersistentDataContainer pdc = h.getPersistentDataContainer();
        int step = (int) Math.floor(affinity / notifyEvery);
        int oldStep = (Integer) pdc.getOrDefault(this.HORSE_AFFINITY_STEP, PersistentDataType.INTEGER, 0);
        if (step > oldStep) {
            pdc.set(this.HORSE_AFFINITY_STEP, PersistentDataType.INTEGER, step);
            Location loc = h.getLocation().clone().add((double) 0.0F, (double) 1.25F, (double) 0.0F);
            h.getWorld().spawnParticle(Particle.HEART, loc, 6, (double) 0.25F, (double) 0.25F, (double) 0.25F, 0.02);
            h.getWorld().playSound(loc, Sound.ENTITY_HORSE_AMBIENT, 0.8F, 1.15F);

            for (Entity passenger : h.getPassengers()) {
                if (passenger instanceof Player) {
                    Player player = (Player) passenger;
                    String affinityText = this.trimDouble(affinity);
                    player.sendMessage(color("&d❤ Afinidad del caballo: &f" + affinityText));
                    break;
                }
            }
        }

    }

    /**
     * 按亲密度进度为马匹施加/更新速度与生命上限修饰符。
     */
    private void applyHorseAffinityBonuses(Horse h, double progress) {
        progress = this.clamp(progress, (double) 0.0F, (double) 1.0F);
        double speedBonusAtMax = Math.max((double) 0.0F, this.fauna.getDouble("horse_stable.affinity.speed_bonus_at_max", 0.12));
        double healthBonusAtMax = Math.max((double) 0.0F, this.fauna.getDouble("horse_stable.affinity.health_bonus_at_max", (double) 6.0F));
        AttributeInstance speedAttr = h.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            this.removeModifier(speedAttr, AFFINITY_SPEED_MOD_UUID);
            double amount = speedBonusAtMax * progress;
            if (amount > 1.0E-4) {
                speedAttr.addModifier(new AttributeModifier(AFFINITY_SPEED_MOD_UUID, "aeternum_horse_affinity_speed", amount, Operation.MULTIPLY_SCALAR_1));
            }
        }

        AttributeInstance healthAttr = h.getAttribute(Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            double oldMax = healthAttr.getValue();
            this.removeModifier(healthAttr, AFFINITY_HEALTH_MOD_UUID);
            double amount = healthBonusAtMax * progress;
            if (amount > 1.0E-4) {
                healthAttr.addModifier(new AttributeModifier(AFFINITY_HEALTH_MOD_UUID, "aeternum_horse_affinity_health", amount, Operation.ADD_NUMBER));
            }

            double newMax = healthAttr.getValue();
            if (h.getHealth() > newMax) {
                h.setHealth(newMax);
            } else if (newMax > oldMax) {
                h.setHealth(Math.min(newMax, h.getHealth() + (newMax - oldMax)));
            }
        }

    }

    /**
     * 为疲惫的马匹施加移动速度减速修饰符。
     */
    private void applyTiredSpeed(Horse h, double multiplier) {
        AttributeInstance a = h.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a != null) {
            this.removeModifier(a, TIRED_MOD_UUID);
            double amount = multiplier - (double) 1.0F;
            AttributeModifier mod = new AttributeModifier(TIRED_MOD_UUID, "aeternum_horse_tired", amount, Operation.MULTIPLY_SCALAR_1);
            a.addModifier(mod);
        }
    }

    /**
     * 移除马匹的疲惫减速修饰符。
     */
    private void clearTiredSpeed(Horse h) {
        AttributeInstance a = h.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a != null) {
            this.removeModifier(a, TIRED_MOD_UUID);
        }
    }

    /**
     * 移除属性上指定 UUID 的全部修饰符。
     */
    private void removeModifier(AttributeInstance attr, UUID uuid) {
        for (AttributeModifier mod : new ArrayList<>(attr.getModifiers())) {
            if (uuid.equals(mod.getUniqueId())) {
                attr.removeModifier(mod);
            }
        }

    }

    /**
     * 启动动物 AI 任务：按季节让动物向庇护/阴凉处移动。
     */
    private void startAiTask() {
        if (this.aiTask != null) {
            this.aiTask.cancel();
        }

        if (this.enabled()) {
            if (this.fauna.getBoolean("fauna_ai.enabled", true)) {
                int interval = this.fauna.getInt("fauna_ai.tick_interval_ticks", 40);
                int perTick = this.fauna.getInt("fauna_ai.per_tick", 30);
                this.aiTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                    Season season = this.currentSeason();
                    if (season != null) {
                        if (this.aiScan.isEmpty() || this.aiIdx >= this.aiScan.size()) {
                            this.aiScan.clear();
                            this.aiIdx = 0;

                            for (World w : Bukkit.getWorlds()) {
                                for (Mob m : w.getEntitiesByClass(Mob.class)) {
                                    if (m instanceof Animals && !m.isDead() && m.isValid()) {
                                        this.aiScan.add(m);
                                    }
                                }
                            }
                        }

                        int done = 0;

                        while (done < perTick && this.aiIdx < this.aiScan.size()) {
                            Mob m = (Mob) this.aiScan.get(this.aiIdx++);
                            ++done;
                            if (m != null && !m.isDead() && m.isValid() && m instanceof Animals) {
                                Animals animal = (Animals) m;
                                if (m.getPassengers().isEmpty() && !m.isLeashed()) {
                                    this.tickAnimalAI(animal, season);
                                }
                            }
                        }

                    }
                }, (long) interval, (long) interval);
            }
        }
    }

    /**
     * 动物 AI：冬季寻找庇护、夏季白昼寻找阴凉并朝目标轻微移动。
     */
    private void tickAnimalAI(Animals a, Season season) {
        boolean winterSeek = this.fauna.getBoolean("fauna_ai.winter_seek_shelter", true);
        boolean summerSeek = this.fauna.getBoolean("fauna_ai.summer_seek_shade", true);
        Location loc = a.getLocation();
        if (loc.getWorld() != null) {
            int radius = this.fauna.getInt("fauna_ai.search_radius", 12);
            int samples = this.fauna.getInt("fauna_ai.random_samples", 10);
            double strength = this.fauna.getDouble("fauna_ai.nudge_strength", 0.12);
            if (season == Season.WINTER && winterSeek) {
                if (!this.hasRoofNear(loc)) {
                    Location target = this.findShelterSpot(loc, radius, samples, true);
                    this.nudgeTowards(a, target, strength);
                }

            } else {
                if (season == Season.SUMMER && summerSeek) {
                    long time = loc.getWorld().getTime();
                    boolean day = time >= 0L && time <= 12300L;
                    if (day && !this.hasRoofNear(loc)) {
                        Location target = this.findShelterSpot(loc, radius, samples, false);
                        this.nudgeTowards(a, target, strength);
                    }
                }

            }
        }
    }

    /**
     * 在附近随机采样寻找有顶棚的位置，评分时优先谷仓且偏好较近者。
     */
    private Location findShelterSpot(Location origin, int radius, int samples, boolean preferBarn) {
        World w = origin.getWorld();
        if (w == null) {
            return null;
        } else {
            Location best = null;
            int bestScore = Integer.MIN_VALUE;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            for (int i = 0; i < samples; ++i) {
                int dx = rnd.nextInt(-radius, radius + 1);
                int dz = rnd.nextInt(-radius, radius + 1);
                int x = origin.getBlockX() + dx;
                int z = origin.getBlockZ() + dz;
                int y = w.getHighestBlockYAt(x, z);
                Location cand = new Location(w, (double) x + (double) 0.5F, (double) y, (double) z + (double) 0.5F);
                if (this.hasRoofNear(cand)) {
                    int score = 10;
                    if (preferBarn && this.isInBarn(cand)) {
                        score += 25;
                    }

                    double dist = cand.distanceSquared(origin);
                    score -= (int) Math.min((double) 20.0F, dist / (double) 10.0F);
                    if (score > bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }

            return best;
        }
    }

    /**
     * 朝目标方向给动物施加一个受限的水平速度推挤。
     */
    private void nudgeTowards(Animals a, Location target, double strength) {
        if (target != null) {
            Location loc = a.getLocation();
            Vector dir = target.toVector().subtract(loc.toVector());
            dir.setY(0);
            if (!(dir.lengthSquared() < (double) 2.5F)) {
                dir.normalize().multiply(strength);
                Vector v = a.getVelocity();
                a.setVelocity(new Vector(this.clamp(v.getX() + dir.getX(), -0.35, 0.35), v.getY(), this.clamp(v.getZ() + dir.getZ(), -0.35, 0.35)));
            }
        }
    }

    /**
     * 把数值限制在 [min,max] 区间。
     */
    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 把数值限制在 [0,1] 区间。
     */
    private double clamp01(double value) {
        return this.clamp(value, (double) 0.0F, (double) 1.0F);
    }

    /**
     * 把数值格式化为保留一位小数的字符串。
     */
    private String trimDouble(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    /**
     * 通过反射从插件中解析当前季节（兼容多种方法/字段命名）。
     */
    private Season currentSeason() {
        try {
            for (String m : List.of("getSeasons", "getSeasonService", "seasons", "seasonService")) {
                try {
                    Method mm = this.plugin.getClass().getMethod(m);
                    Object service = mm.invoke(this.plugin);
                    Season s = this.seasonFromService(service);
                    if (s != null) {
                        return s;
                    }
                } catch (NoSuchMethodException ignoredNoMethod) {
                }
            }
        } catch (Throwable ignoredOuterThrowable) {
        }

        try {
            for (String f : List.of("seasons", "seasonService")) {
                try {
                    Field ff = this.plugin.getClass().getDeclaredField(f);
                    ff.setAccessible(true);
                    Object service = ff.get(this.plugin);
                    Season s = this.seasonFromService(service);
                    if (s != null) {
                        return s;
                    }
                } catch (NoSuchFieldException ignoredNoField) {
                }
            }
        } catch (Throwable ignoredFieldThrowable) {
        }

        return null;
    }

    /**
     * 从季节服务对象上通过反射调用并解析当前季节。
     */
    private Season seasonFromService(Object service) {
        if (service == null) {
            return null;
        } else {
            try {
                for (String m : List.of("getCurrentSeason", "getSeason", "currentSeason")) {
                    try {
                        Method mm = service.getClass().getMethod(m, World.class);
                        Object out = mm.invoke(service, Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
                        if (out instanceof Season) {
                            Season s = (Season) out;
                            return s;
                        }
                    } catch (NoSuchMethodException ignoredWorldMethod) {
                    }

                    try {
                        Method mm2 = service.getClass().getMethod(m);
                        Object out2 = mm2.invoke(service);
                        if (out2 instanceof Season) {
                            Season s = (Season) out2;
                            return s;
                        }
                    } catch (NoSuchMethodException ignoredNoArgMethod) {
                    }
                }
            } catch (Throwable ignoredOuter) {
            }

            return null;
        }
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
