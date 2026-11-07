package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 凋灵失控事件（Wither Loose）。
 *
 * <p>当下界有足够的合格玩家且通过概率判定时触发。事件开始时随机挑选 1~N 名
 * 玩家作为目标，倒计时结束后在目标附近生成一只被“标记”的凋灵 Boss：
 * <ul>
 *     <li>凋灵拥有配置的额外生命值、可选发光与自定义名称；</li>
 *     <li>若长时间未受到伤害，会被自动清除（防止卡住）；</li>
 *     <li>该凋灵被击杀时按概率额外掉落下界合金碎片、恶魂之泪、火焰保护附魔书。</li>
 * </ul>
 * 通过持久化数据标记来识别本事件生成的凋灵。配置来自 {@link YamlEvents}
 * 读取的 {@code events.yml}。</p>
 */
public final class WitherLooseEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 事件最短持续天数。
     */
    private int minDur;
    /**
     * 事件最长持续天数。
     */
    private int maxDur;
    /**
     * 每天触发事件的基础概率。
     */
    private double baseChance;
    /**
     * 挑选目标玩家的最少数量。
     */
    private int minTargets;
    /**
     * 挑选目标玩家的最多数量。
     */
    private int maxTargets;
    /**
     * 是否只挑选生存/冒险模式的玩家。
     */
    private boolean onlySurvivalAdventure;
    /**
     * 事件开始到生成凋灵的延迟秒数。
     */
    private int spawnDelaySeconds;
    /**
     * 倒计时提示的间隔秒数。
     */
    private int countdownIntervalSeconds;
    /**
     * 生成时距目标玩家的最小距离。
     */
    private int spawnMinDistance;
    /**
     * 生成时距目标玩家的最大距离。
     */
    private int spawnMaxDistance;
    /**
     * 凋灵的最大生命值。
     */
    private double witherMaxHealth;
    /**
     * 凋灵是否发光。
     */
    private boolean witherGlowing;
    /**
     * 凋灵无伤多少秒后自动清除。
     */
    private int idleKillSeconds;
    /**
     * 空闲检测的间隔秒数。
     */
    private int idleCheckSeconds;
    /**
     * 额外掉落下界合金碎片的概率。
     */
    private double extraLootChanceScrap;
    /**
     * 额外掉落下界合金碎片的最少数量。
     */
    private int extraLootScrapMin;
    /**
     * 额外掉落下界合金碎片的最多数量。
     */
    private int extraLootScrapMax;
    /**
     * 额外掉落火焰保护附魔书的概率。
     */
    private double extraLootChanceFireProtBook;
    /**
     * 额外掉落恶魂之泪的概率。
     */
    private double extraLootChanceGhastTear;
    /**
     * 本次事件选中的目标玩家 UUID 列表。
     */
    private final List<UUID> targets = new ArrayList();
    private BukkitTask countdownTask;
    private BukkitTask spawnTask;
    /**
     * 当前存活的事件凋灵 UUID。
     */
    private UUID activeWitherId = null;
    /**
     * 凋灵最近一次受到伤害的时间戳（毫秒）。
     */
    private long lastDamageAtMs = 0L;
    private BukkitTask idleTask = null;
    /**
     * 用于标记事件凋灵的持久化数据键。
     */
    private final NamespacedKey witherMarkKey;

    public WitherLooseEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.witherMarkKey = new NamespacedKey(plugin, "event_wither_loose");
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.wither_loose.enabled", true);
        this.minDur = config.getInt("events.wither_loose.min_duration_days", 1);
        this.maxDur = config.getInt("events.wither_loose.max_duration_days", 1);
        this.baseChance = config.getDouble("events.wither_loose.base_chance_per_day", 0.06);
        this.minTargets = config.getInt("events.wither_loose.targets.min_targets", 1);
        this.maxTargets = config.getInt("events.wither_loose.targets.max_targets", 3);
        this.onlySurvivalAdventure = config.getBoolean("events.wither_loose.targets.only_survival_adventure", true);
        this.spawnDelaySeconds = config.getInt("events.wither_loose.spawn_delay_seconds", 60);
        this.countdownIntervalSeconds = config.getInt("events.wither_loose.countdown_interval_seconds", 20);
        this.spawnMinDistance = config.getInt("events.wither_loose.spawn.min_distance", 18);
        this.spawnMaxDistance = config.getInt("events.wither_loose.spawn.max_distance", 28);
        this.witherMaxHealth = config.getDouble("events.wither_loose.wither.max_health", (double) 180.0F);
        this.witherGlowing = config.getBoolean("events.wither_loose.wither.glowing", true);
        this.idleKillSeconds = config.getInt("events.wither_loose.wither.idle_kill_seconds", 180);
        this.idleCheckSeconds = config.getInt("events.wither_loose.wither.idle_check_seconds", 10);
        this.extraLootChanceScrap = config.getDouble("events.wither_loose.loot.netherite_scrap_chance", 0.3);
        this.extraLootScrapMin = config.getInt("events.wither_loose.loot.netherite_scrap_min", 1);
        this.extraLootScrapMax = config.getInt("events.wither_loose.loot.netherite_scrap_max", 2);
        this.extraLootChanceFireProtBook = config.getDouble("events.wither_loose.loot.fire_prot_book_chance", 0.15);
        this.extraLootChanceGhastTear = config.getDouble("events.wither_loose.loot.ghast_tear_chance", (double) 0.25F);
    }

    @Override
    public String getId() {
        return "wither_loose";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.wither_loose.display_name", "&4Wither Loose");
    }

    @Override
    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    @Override
    public int getMinDurationDays() {
        return this.minDur;
    }

    @Override
    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /**
     * 仅当下界合格玩家数不少于最少目标数、且通过概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            int eligible = this.getEligibleNetherPlayers().size();
            if (eligible < Math.max(1, this.minTargets)) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /**
     * 事件开始时挑选目标、发送标题并启动倒计时与延迟生成凋灵的任务。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.stopTasks();
        List<Player> eligible = this.getEligibleNetherPlayers();
        if (!eligible.isEmpty()) {
            int targetCount = this.clamp(ThreadLocalRandom.current().nextInt(this.minTargets, this.maxTargets + 1), 1, eligible.size());
            Collections.shuffle(eligible, ThreadLocalRandom.current());
            this.targets.clear();

            for (int i = 0; i < targetCount; ++i) {
                this.targets.add(((Player) eligible.get(i)).getUniqueId());
            }

            this.forEachTarget((player) -> player.sendTitle(this.plugin.lang.tr(player, "event.wither_loose.title"), this.plugin.lang.tr(player, "event.wither_loose.subtitle"), 20, 80, 40));
            this.announceSeconds(this.spawnDelaySeconds);
            final int interval = Math.max(5, this.countdownIntervalSeconds);
            this.countdownTask = Bukkit.getScheduler().runTaskTimer(this.plugin, new Runnable() {
                int remaining;

                {
                    this.remaining = WitherLooseEvent.this.spawnDelaySeconds;
                }

                @Override
                public void run() {
                    this.remaining -= interval;
                    if (this.remaining <= 0) {
                        if (WitherLooseEvent.this.countdownTask != null) {
                            WitherLooseEvent.this.countdownTask.cancel();
                        }

                        WitherLooseEvent.this.countdownTask = null;
                    } else {
                        WitherLooseEvent.this.announceSeconds(this.remaining);
                    }
                }
            }, 20L * (long) interval, 20L * (long) interval);
            this.spawnTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                this.spawnTask = null;
                this.spawnWitherNearTargets();
            }, 20L * (long) Math.max(1, this.spawnDelaySeconds));
        }
    }

    /**
     * 事件结束时停止所有任务、清空目标，并通知身处下界的玩家。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.stopTasks();
        this.targets.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.wither_loose.end"));
            }
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 记录事件凋灵最近一次受到有效伤害的时间，用于空闲检测。
     */
    @EventHandler
    public void onWitherDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Wither wither) {
            if (wither.getWorld().getEnvironment() == Environment.NETHER) {
                PersistentDataContainer container = wither.getPersistentDataContainer();
                if (container.has(this.witherMarkKey, PersistentDataType.BYTE)) {
                    if (!event.isCancelled()) {
                        if (!(event.getFinalDamage() <= (double) 0.0F)) {
                            this.lastDamageAtMs = System.currentTimeMillis();
                        }
                    }
                }
            }
        }
    }

    /**
     * 事件凋灵死亡时按概率追加额外掉落，并清理其追踪状态。
     */
    @EventHandler
    public void onWitherDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Wither wither) {
            if (wither.getWorld().getEnvironment() == Environment.NETHER) {
                PersistentDataContainer container = wither.getPersistentDataContainer();
                if (container.has(this.witherMarkKey, PersistentDataType.BYTE)) {
                    this.activeWitherId = null;
                    this.lastDamageAtMs = 0L;
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    if (random.nextDouble() < this.extraLootChanceScrap) {
                        int amount = this.randomBetween(this.extraLootScrapMin, this.extraLootScrapMax, random);
                        if (amount > 0) {
                            event.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, amount));
                        }
                    }

                    if (random.nextDouble() < this.extraLootChanceGhastTear) {
                        event.getDrops().add(new ItemStack(Material.GHAST_TEAR, 1));
                    }

                    if (random.nextDouble() < this.extraLootChanceFireProtBook) {
                        event.getDrops().add(this.makeFireProtBook(4));
                    }

                }
            }
        }
    }

    /**
     * 在随机选中的下界目标附近生成一只被标记的凋灵并初始化其属性。
     */
    private void spawnWitherNearTargets() {
        Player chosen = this.pickActiveTargetInNether();
        if (chosen != null) {
            Location spawn = this.findSafeSpawnNear(chosen, this.spawnMinDistance, this.spawnMaxDistance, 40);
            if (spawn == null) {
                spawn = chosen.getLocation().clone().add((double) 4.0F, (double) 2.0F, (double) 4.0F);
            }

            World world = spawn.getWorld();
            if (world != null && world.getEnvironment() == Environment.NETHER) {
                Wither wither = (Wither) world.spawnEntity(spawn, EntityType.WITHER);
                wither.getPersistentDataContainer().set(this.witherMarkKey, PersistentDataType.BYTE, (byte) 1);
                AttributeInstance maxHealth = wither.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(this.witherMaxHealth);
                }

                wither.setHealth(Math.min(this.witherMaxHealth, wither.getMaxHealth()));
                wither.setGlowing(this.witherGlowing);
                wither.setCustomName(this.plugin.lang.trServer("event.wither_loose.wither_name"));
                wither.setCustomNameVisible(true);
                this.activeWitherId = wither.getUniqueId();
                this.lastDamageAtMs = System.currentTimeMillis();
                this.startIdleTaskIfNeeded();
                this.forEachTarget((player) -> {
                    player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0F, 0.8F);
                    player.sendTitle(this.plugin.lang.tr(player, "event.wither_loose.title"), this.plugin.lang.tr(player, "event.wither_loose.survive"), 10, 60, 20);
                });
                world.spawnParticle(Particle.SMOKE, spawn.clone().add((double) 0.0F, (double) 1.5F, (double) 0.0F), 24, 0.8, 0.7, 0.8, 0.01);
                world.spawnParticle(Particle.LAVA, spawn.clone().add((double) 0.0F, 1.2, (double) 0.0F), 10, 0.6, 0.4, 0.6, 0.01);
            }
        }
    }

    /**
     * 首次生成凋灵时启动空闲检测任务（若尚未启动）。
     */
    private void startIdleTaskIfNeeded() {
        if (this.idleTask == null) {
            long periodTicks = 20L * (long) Math.max(2, this.idleCheckSeconds);
            this.idleTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                if (this.activeWitherId != null) {
                    Wither found = null;
                    Entity entity = Bukkit.getEntity(this.activeWitherId);
                    if (entity instanceof Wither) {
                        Wither wither = (Wither) entity;
                        if (wither.getWorld().getEnvironment() == Environment.NETHER && !wither.isDead()) {
                            found = wither;
                        }
                    }

                    if (found == null) {
                        this.activeWitherId = null;
                        this.lastDamageAtMs = 0L;
                    } else {
                        long now = System.currentTimeMillis();
                        if (this.lastDamageAtMs <= 0L) {
                            this.lastDamageAtMs = now;
                        }

                        if (now - this.lastDamageAtMs >= (long) this.idleKillSeconds * 1000L) {
                            found.remove();
                            this.activeWitherId = null;
                            this.lastDamageAtMs = 0L;
                            this.forEachTarget((player) -> player.sendMessage(this.plugin.lang.tr(player, "event.wither_loose.idle_killed")));
                        }

                    }
                }
            }, periodTicks, periodTicks);
        }
    }

    /**
     * 向所有目标播报剩余秒数（使用对应档位的语言键）。
     */
    private void announceSeconds(int seconds) {
        this.forEachTarget((player) -> {
            String langKey;
            switch (seconds) {
                case 20 -> langKey = "event.wither_loose.countdown_20";
                case 40 -> langKey = "event.wither_loose.countdown_40";
                case 60 -> langKey = "event.wither_loose.countdown_60";
                default -> langKey = "event.wither_loose.countdown_generic";
            }

            String key = langKey;
            String msg = this.plugin.lang.trf(player, key, Map.of("seconds", String.valueOf(seconds)));
            player.sendMessage(msg);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.6F);
        });
    }

    /**
     * 取消倒计时、生成与空闲检测任务并清理相关状态。
     */
    private void stopTasks() {
        if (this.countdownTask != null) {
            this.countdownTask.cancel();
        }

        if (this.spawnTask != null) {
            this.spawnTask.cancel();
        }

        if (this.idleTask != null) {
            this.idleTask.cancel();
        }

        this.countdownTask = null;
        this.spawnTask = null;
        this.idleTask = null;
        this.activeWitherId = null;
        this.lastDamageAtMs = 0L;
    }

    /**
     * 对每个仍位于下界的目标玩家执行给定操作。
     */
    private void forEachTarget(Consumer<Player> action) {
        for (UUID id : this.targets) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().getEnvironment() == Environment.NETHER) {
                action.accept(player);
            }
        }

    }

    /**
     * 收集当前位于下界、且符合模式要求的在线玩家。
     */
    private List<Player> getEligibleNetherPlayers() {
        List<Player> result = new ArrayList();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER && player.getGameMode() != GameMode.SPECTATOR && (!this.onlySurvivalAdventure || player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
                result.add(player);
            }
        }

        return result;
    }

    /**
     * 从目标列表中随机挑选一名仍位于下界的存活玩家。
     */
    private Player pickActiveTargetInNether() {
        List<Player> alive = new ArrayList();

        for (UUID id : this.targets) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().getEnvironment() == Environment.NETHER && player.getGameMode() != GameMode.SPECTATOR) {
                alive.add(player);
            }
        }

        if (alive.isEmpty()) {
            return null;
        } else {
            return (Player) alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
        }
    }

    /**
     * 在目标附近寻找一个安全、可站立的生成点（脚下为固体方块、头和脚可通行）。
     */
    private Location findSafeSpawnNear(Player target, int minDist, int maxDist, int attempts) {
        World world = target.getWorld();
        if (world == null) {
            return null;
        } else {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location baseLocation = target.getLocation();
            int minY = Math.max(world.getMinHeight() + 5, 10);
            int maxY = Math.min(world.getMaxHeight() - 5, baseLocation.getBlockY() + 10);

            for (int attempt = 0; attempt < attempts; ++attempt) {
                int dx = random.nextInt(-maxDist, maxDist + 1);
                int dz = random.nextInt(-maxDist, maxDist + 1);
                if (dx * dx + dz * dz >= minDist * minDist) {
                    int x = baseLocation.getBlockX() + dx;
                    int z = baseLocation.getBlockZ() + dz;
                    int y = this.clamp(baseLocation.getBlockY() + random.nextInt(-6, 7), minY, maxY);

                    for (int dy = -4; dy <= 4; ++dy) {
                        int yy = this.clamp(y + dy, minY, world.getMaxHeight() - 5);
                        Block feet = world.getBlockAt(x, yy, z);
                        Block head = world.getBlockAt(x, yy + 1, z);
                        Block floor = world.getBlockAt(x, yy - 1, z);
                        if (feet.isPassable() && head.isPassable()) {
                            Material floorType = floor.getType();
                            if (floor.getType().isSolid() && floorType != Material.LAVA && floorType != Material.MAGMA_BLOCK && floorType != Material.FIRE && floorType != Material.SOUL_FIRE) {
                                Location candidate = new Location(world, (double) x + (double) 0.5F, (double) yy, (double) z + (double) 0.5F);
                                if (!(candidate.distanceSquared(baseLocation) < (double) (minDist * minDist))) {
                                    return candidate;
                                }
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    /**
     * 制作一本指定等级的火焰保护附魔书。
     */
    private ItemStack makeFireProtBook(int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta != null) {
            meta.addStoredEnchant(Enchantment.FIRE_PROTECTION, level, true);
            book.setItemMeta(meta);
        }

        return book;
    }

    /**
     * 在 [min, max] 范围内随机取值；若 max < min 则返回 min。
     */
    private int randomBetween(int min, int max, ThreadLocalRandom random) {
        if (max < min) {
            return min;
        } else {
            return min == max ? min : random.nextInt(min, max + 1);
        }
    }

    /**
     * 将给定值限制到 [min, max] 区间内。
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
