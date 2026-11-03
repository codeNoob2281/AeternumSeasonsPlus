package kinkin.aeternum.fauna;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.world.BiomeSpoofAdapter;
import kinkin.aeternum.world.BiomeSpoofSpawnGuard;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Predicate;

/**
 * 动物季节性迁徙服务。
 *
 * <p>本服务让被管理的动物随季节“迁徙”到更适宜的群系，并在必要时进行软移除与补偿生成。
 * 主要行为与依赖：
 * <ul>
 *     <li>监听生物自然生成，按季节/群系概率额外补充同种动物，并在冬季清理寒冷群系中的暖气候动物；</li>
 *     <li>周期任务按预算扫描玩家附近的受管动物，执行春/夏/秋/冬及水生、下界的迁徙逻辑；</li>
 *     <li>当某区域动物数量不足时，按群系与季节权重“补偿生成”；</li>
 *     <li>区分玩家保护/已驯服/围栏内的动物，避免误迁或被清理。</li>
 * </ul>
 * 依赖 {@link SeasonService} 提供季节状态、{@link BiomeSpoofAdapter} 与
 * {@link BiomeSpoofSpawnGuard} 获取“原始群系”信息。</p>
 */
public final class AnimalMigrationService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey managedFaunaKey;
    private final NamespacedKey playerProtectedFaunaKey;
    private BukkitTask task;
    private final Random random = new Random();
    private boolean enabled;
    private long tickPeriod;
    private int animalsPerTick;
    private int searchRadiusBlocks;
    private boolean showParticles;
    private double springSpawnBoostChance;
    private double summerSpawnBoostChance;
    private double autumnSpawnBoostChance;
    private double winterSpawnBoostChance;
    private double winterWarmAnimalCullChance;
    private boolean winterCleanupEnabled;
    private int winterCleanupMaxDays;
    private int winterCleanupBaseRadius;
    private int winterCleanupRadiusStep;
    private int winterCleanupPerTick;
    private int compensationRadiusBlocks;
    private int compensationMinFauna;
    private int compensationMaxSpawnsPerCycle;
    private int compensationMaxSpawnsPerPlayer;
    private int compensationZoneCooldownCycles;
    private double outOfSeasonSpawnMultiplier;
    private long cycleCounter = 0L;
    private final Map<Long, Long> areaCooldowns = new HashMap();
    private final EnumSet<EntityType> springBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> summerBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> autumnBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> winterBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> managedFauna = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> farmFauna = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> forestFauna = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> warmClimateAnimals = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> coldClimateAnimals = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> waterFauna = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> netherFauna = EnumSet.noneOf(EntityType.class);

    public AnimalMigrationService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.managedFaunaKey = new NamespacedKey(plugin, "seasonal_managed_fauna");
        this.playerProtectedFaunaKey = new NamespacedKey(plugin, "player_protected_fauna");
        this.initSets();
        this.reloadFromConfig();
    }

    /**
     * 初始化各分类的生物类型集合与季节加成集合。
     */
    private void initSets() {
        this.farmFauna.addAll(Arrays.asList(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN, EntityType.RABBIT, EntityType.HORSE, EntityType.DONKEY, EntityType.LLAMA, EntityType.MOOSHROOM, EntityType.GOAT, EntityType.BEE));
        this.forestFauna.addAll(Arrays.asList(EntityType.WOLF, EntityType.FOX, EntityType.OCELOT, EntityType.PARROT, EntityType.PANDA, EntityType.GOAT, EntityType.POLAR_BEAR));
        this.warmClimateAnimals.addAll(Arrays.asList(EntityType.PARROT, EntityType.PANDA, EntityType.OCELOT, EntityType.FROG, EntityType.TROPICAL_FISH, EntityType.TURTLE, EntityType.DOLPHIN, EntityType.AXOLOTL, EntityType.ARMADILLO, EntityType.HOGLIN, EntityType.STRIDER, EntityType.ZOGLIN));
        this.coldClimateAnimals.addAll(Arrays.asList(EntityType.WOLF, EntityType.FOX, EntityType.GOAT, EntityType.POLAR_BEAR, EntityType.RABBIT, EntityType.COD, EntityType.SALMON, EntityType.SQUID, EntityType.GLOW_SQUID));
        this.waterFauna.addAll(Arrays.asList(EntityType.TROPICAL_FISH, EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL, EntityType.SQUID, EntityType.GLOW_SQUID));
        this.netherFauna.addAll(Arrays.asList(EntityType.STRIDER, EntityType.HOGLIN, EntityType.ZOGLIN));
        this.managedFauna.addAll(this.farmFauna);
        this.managedFauna.addAll(this.forestFauna);
        this.managedFauna.addAll(this.warmClimateAnimals);
        this.managedFauna.addAll(this.coldClimateAnimals);
        this.managedFauna.addAll(this.waterFauna);
        this.managedFauna.add(EntityType.FROG);
        this.managedFauna.add(EntityType.ARMADILLO);
        this.springBoost.addAll(Arrays.asList(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN, EntityType.RABBIT, EntityType.HORSE, EntityType.DONKEY, EntityType.LLAMA, EntityType.BEE));
        this.summerBoost.addAll(Arrays.asList(EntityType.TROPICAL_FISH, EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL, EntityType.FROG, EntityType.PARROT, EntityType.PANDA, EntityType.OCELOT, EntityType.ARMADILLO, EntityType.SQUID, EntityType.GLOW_SQUID));
        this.autumnBoost.addAll(Arrays.asList(EntityType.FOX, EntityType.WOLF, EntityType.GOAT, EntityType.MOOSHROOM, EntityType.OCELOT, EntityType.LLAMA, EntityType.RABBIT));
        this.winterBoost.addAll(Arrays.asList(EntityType.WOLF, EntityType.FOX, EntityType.GOAT, EntityType.POLAR_BEAR, EntityType.RABBIT, EntityType.COD, EntityType.SALMON, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.STRIDER));
    }

    /**
     * 从 climate 配置读取迁徙、补偿与冬季清理相关参数。
     */
    private void reloadFromConfig() {
        FileConfiguration y = this.plugin.cfg.climate;
        this.enabled = y.getBoolean("migration.enabled", true);
        this.tickPeriod = Math.max(40L, y.getLong("migration.tick_period", 200L));
        this.animalsPerTick = Math.max(8, y.getInt("migration.animals_per_tick", 40));
        this.searchRadiusBlocks = Math.max(24, y.getInt("migration.search_radius_blocks", 160));
        this.showParticles = y.getBoolean("migration.particles", true);
        this.springSpawnBoostChance = this.clamp01(y.getDouble("migration.spawn.spring_boost_chance", (double) 0.25F));
        this.summerSpawnBoostChance = this.clamp01(y.getDouble("migration.spawn.summer_boost_chance", (double) 0.25F));
        this.autumnSpawnBoostChance = this.clamp01(y.getDouble("migration.spawn.autumn_boost_chance", 0.2));
        this.winterSpawnBoostChance = this.clamp01(y.getDouble("migration.spawn.winter_boost_chance", 0.18));
        this.winterWarmAnimalCullChance = this.clamp01(y.getDouble("migration.soft_despawn.warm_in_winter_chance", (double) 0.25F));
        this.winterCleanupEnabled = y.getBoolean("migration.winter_cleanup.enabled", false);
        this.winterCleanupMaxDays = Math.max(1, y.getInt("migration.winter_cleanup.days", 3));
        this.winterCleanupBaseRadius = Math.max(24, y.getInt("migration.winter_cleanup.base_radius_blocks", 64));
        this.winterCleanupRadiusStep = Math.max(16, y.getInt("migration.winter_cleanup.radius_step_blocks", 64));
        this.winterCleanupPerTick = Math.max(4, y.getInt("migration.winter_cleanup.max_per_tick", this.animalsPerTick));
        this.compensationRadiusBlocks = Math.max(24, y.getInt("migration.compensation.radius_blocks", 48));
        this.compensationMinFauna = Math.max(4, y.getInt("migration.compensation.min_fauna", 8));
        this.compensationMaxSpawnsPerCycle = Math.max(1, y.getInt("migration.compensation.max_spawns_per_cycle", 6));
        this.compensationMaxSpawnsPerPlayer = Math.max(1, y.getInt("migration.compensation.max_spawns_per_player", 2));
        this.compensationZoneCooldownCycles = Math.max(1, y.getInt("migration.compensation.zone_cooldown_cycles", 3));
        this.outOfSeasonSpawnMultiplier = this.clamp01(y.getDouble("migration.compensation.out_of_season_multiplier", 0.16));
    }

    /**
     * 注册事件监听，并在启用时启动周期迁徙任务。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 80L, this.tickPeriod);
        }
    }

    /**
     * 取消周期任务、注销事件监听并清空区域冷却记录。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        HandlerList.unregisterAll(this);
        this.areaCooldowns.clear();
    }

    /**
     * 生物生成监听：记录玩家主动生成的生物；对自然/区块生成/增援的受管动物按季节概率补充生成，并在冬季清理寒冷群系中的暖气候动物。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (this.enabled) {
            CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
            if (this.isPlayerProtectedSpawnReason(reason)) {
                e.getEntity().getPersistentDataContainer().set(this.playerProtectedFaunaKey, PersistentDataType.BYTE, (byte) 1);
            } else if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN || reason == SpawnReason.REINFORCEMENTS) {
                World world = e.getLocation().getWorld();
                if (world != null) {
                    if (world.getEnvironment() == Environment.NORMAL || world.getEnvironment() == Environment.NETHER) {
                        EntityType type = e.getEntityType();
                        if (this.isManagedFauna(type) || this.netherFauna.contains(type)) {
                            CalendarState st = this.seasons.getStateCopy(world);
                            Biome biome = this.biomeAt(e.getLocation());
                            double chance = this.getExtraSpawnChance(st.season, type, biome, world.getEnvironment());
                            if (chance > (double) 0.0F) {
                                this.handleBoost(e, chance);
                            }

                            if (st.season == Season.WINTER && world.getEnvironment() == Environment.NORMAL && this.warmClimateAnimals.contains(type) && this.isColdBiome(biome) && this.random.nextDouble() < this.winterWarmAnimalCullChance) {
                                e.setCancelled(true);
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 计算指定群系/季节下额外生成的触发概率；下界生物与非当季生物会按比例缩减。
     */
    private double getExtraSpawnChance(Season season, EntityType type, Biome biome, World.Environment env) {
        double seasonChance;
        switch (season) {
            case SPRING -> seasonChance = this.springSpawnBoostChance;
            case SUMMER -> seasonChance = this.summerSpawnBoostChance;
            case AUTUMN -> seasonChance = this.autumnSpawnBoostChance;
            case WINTER -> seasonChance = this.winterSpawnBoostChance;
            default -> throw new MatchException((String) null, (Throwable) null);
        }

        double base = seasonChance;
        if (env == Environment.NETHER) {
            if (!this.netherFauna.contains(type)) {
                return (double) 0.0F;
            } else {
                return season == Season.WINTER && type == EntityType.STRIDER ? base : base * 0.35;
            }
        } else {
            EnumSet boostSet;
            switch (season) {
                case SPRING -> boostSet = this.springBoost;
                case SUMMER -> boostSet = this.summerBoost;
                case AUTUMN -> boostSet = this.autumnBoost;
                case WINTER -> boostSet = this.winterBoost;
                default -> throw new MatchException((String) null, (Throwable) null);
            }

            EnumSet<EntityType> seasonalSet = boostSet;
            if (seasonalSet.contains(type)) {
                return base;
            } else {
                return this.isBiomeSuitableForType(type, biome) ? base * this.outOfSeasonSpawnMultiplier : (double) 0.0F;
            }
        }
    }

    /**
     * 按概率在生成点附近寻找合适位置并额外生成一只同种动物。
     */
    private void handleBoost(CreatureSpawnEvent e, double chance) {
        if (!(this.random.nextDouble() >= chance)) {
            Location loc = e.getLocation();
            Location extra = this.findSpawnLocationForType(loc.clone().add(this.randomOffset(6), (double) 0.0F, this.randomOffset(6)), e.getEntityType(), 8);
            if (extra != null) {
                this.spawnManaged(extra, e.getEntityType(), true);
            }
        }
    }

    /**
     * 返回 [-max, max] 范围内的随机偏移。
     */
    private double randomOffset(int max) {
        return (this.random.nextDouble() * (double) 2.0F - (double) 1.0F) * (double) max;
    }

    /**
     * 周期任务：执行冬季清理、玩家附近动物迁徙与补偿生成（受每 tick 预算约束）。
     */
    @Override
    public void run() {
        if (this.enabled) {
            ++this.cycleCounter;
            if ((this.cycleCounter & 63L) == 0L) {
                this.trimCooldowns();
            }

            CalendarState st = this.seasons.getStateCopy();
            if (st.season == Season.WINTER && this.winterCleanupEnabled && st.day <= this.winterCleanupMaxDays) {
                this.performWinterCleanup(st);
            }

            int migrationBudget = this.animalsPerTick;
            int compensationBudget = this.compensationMaxSpawnsPerCycle;
            Set<UUID> visited = new HashSet();

            for (World world : Bukkit.getWorlds()) {
                if (migrationBudget <= 0 && compensationBudget <= 0) {
                    break;
                }

                if (!world.getPlayers().isEmpty()) {
                    World.Environment env = world.getEnvironment();
                    if (env == Environment.NORMAL || env == Environment.NETHER) {
                        CalendarState worldState = this.seasons.getStateCopy(world);

                        for (Player player : world.getPlayers()) {
                            if (migrationBudget > 0) {
                                migrationBudget = this.processNearbyFauna(player, worldState, visited, migrationBudget);
                            }

                            if (env == Environment.NORMAL && compensationBudget > 0) {
                                compensationBudget = this.compensateNearPlayer(player, worldState, compensationBudget);
                            }

                            if (migrationBudget <= 0 && compensationBudget <= 0) {
                                break;
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * 扫描玩家附近受管动物并逐个执行迁徙；返回剩余预算。
     */
    private int processNearbyFauna(Player player, CalendarState st, Set<UUID> visited, int budget) {
        if (budget <= 0) {
            return 0;
        } else {
            World world = player.getWorld();

            for (Entity entity : world.getNearbyEntities(player.getLocation(), (double) this.searchRadiusBlocks, (double) Math.max(24, this.searchRadiusBlocks / 2), (double) this.searchRadiusBlocks)) {
                if (budget <= 0) {
                    break;
                }

                if (entity instanceof LivingEntity) {
                    LivingEntity le = (LivingEntity) entity;
                    if (le.isValid() && !le.isDead() && this.isInterestingAnimal(le) && !this.isProtectedFromMigration(le) && visited.add(le.getUniqueId())) {
                        this.handleMigrationFor(st, le);
                        --budget;
                    }
                }
            }

            return budget;
        }
    }

    /**
     * 冬季前期清理寒冷群系中的暖气候动物（随天数扩大半径）。
     */
    private void performWinterCleanup(CalendarState st) {
        int dayIndex = Math.max(1, Math.min(st.day, this.winterCleanupMaxDays));
        int radius = this.winterCleanupBaseRadius + (dayIndex - 1) * this.winterCleanupRadiusStep;
        int budget = this.winterCleanupPerTick;
        if (budget > 0) {
            Set<UUID> visited = new HashSet();

            for (World world : Bukkit.getWorlds()) {
                if (budget <= 0) {
                    break;
                }

                if (world.getEnvironment() == Environment.NORMAL) {
                    for (Player player : world.getPlayers()) {
                        if (budget <= 0) {
                            break;
                        }

                        for (Entity entity : world.getNearbyEntities(player.getLocation(), (double) radius, (double) Math.max(20, radius / 2), (double) radius)) {
                            if (budget <= 0) {
                                break;
                            }

                            if (entity instanceof LivingEntity) {
                                LivingEntity le = (LivingEntity) entity;
                                if (le.isValid() && !le.isDead() && this.isInterestingAnimal(le) && !this.isProtectedFromMigration(le) && visited.add(le.getUniqueId())) {
                                    EntityType type = le.getType();
                                    if (this.warmClimateAnimals.contains(type) && !this.coldClimateAnimals.contains(type) && !this.hasRestrictedOriginalHabitat(type)) {
                                        Biome biome = this.biomeAt(le.getLocation());
                                        if ((!this.isWarmBiome(biome) || !(this.random.nextDouble() < 0.7)) && (!this.isTemperateBiome(biome) || !(this.random.nextDouble() < 0.2)) && this.softRemove(le)) {
                                            --budget;
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
     * 当玩家周围动物数量不足时，按群系/季节权重补偿生成；返回剩余预算。
     */
    private int compensateNearPlayer(Player player, CalendarState st, int budget) {
        if (budget <= 0) {
            return 0;
        } else {
            World world = player.getWorld();
            if (world.getEnvironment() != Environment.NORMAL) {
                return budget;
            } else {
                Location center = player.getLocation();
                long zoneKey = this.zoneKey(center);
                Long lastCycle = (Long) this.areaCooldowns.get(zoneKey);
                if (lastCycle != null && this.cycleCounter - lastCycle < (long) this.compensationZoneCooldownCycles) {
                    return budget;
                } else {
                    boolean aquaticZone = this.isAquaticZone(center);
                    int radius = this.compensationRadiusBlocks;
                    Collection<Entity> nearby = world.getNearbyEntities(center, (double) radius, (double) Math.max(20, radius / 2), (double) radius);
                    int current = 0;
                    EnumMap<EntityType, Integer> localCounts = new EnumMap(EntityType.class);

                    for (Entity entity : nearby) {
                        if (entity instanceof LivingEntity) {
                            LivingEntity le = (LivingEntity) entity;
                            if (le.isValid() && !le.isDead()) {
                                EntityType type = le.getType();
                                if (this.isCountRelevantForZone(type, aquaticZone)) {
                                    ++current;
                                    localCounts.merge(type, 1, Integer::sum);
                                }
                            }
                        }
                    }

                    int target = aquaticZone ? Math.max(6, this.compensationMinFauna - 1) : this.compensationMinFauna;
                    if (current >= target) {
                        return budget;
                    } else {
                        int attempts = Math.min(this.compensationMaxSpawnsPerPlayer, Math.min(budget, target - current));
                        boolean spawnedAny = false;

                        for (int i = 0; i < attempts; ++i) {
                            EntityType type = this.pickCompensationType(center, st.season, aquaticZone);
                            if (type != null && (Integer) localCounts.getOrDefault(type, 0) < this.typeLocalCap(type)) {
                                Location spawnLoc = this.findSpawnLocationForType(center, type, radius);
                                if (spawnLoc != null && this.isSafeDistanceFromPlayers(spawnLoc, (double) 12.0F)) {
                                    Entity spawned = this.spawnManaged(spawnLoc, type, false);
                                    if (spawned != null) {
                                        localCounts.merge(type, 1, Integer::sum);
                                        ++current;
                                        --budget;
                                        spawnedAny = true;
                                        if (budget <= 0 || current >= target) {
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        if (spawnedAny) {
                            this.areaCooldowns.put(zoneKey, this.cycleCounter);
                        }

                        return budget;
                    }
                }
            }
        }
    }

    private boolean isCountRelevantForZone(EntityType type, boolean aquaticZone) {
        if (aquaticZone) {
            return this.waterFauna.contains(type) || type == EntityType.FROG;
        } else {
            return this.managedFauna.contains(type) && !this.waterFauna.contains(type) && !this.netherFauna.contains(type);
        }
    }

    /**
     * 从对应群系与季节的加权池中随机挑选一种补偿动物。
     */
    private EntityType pickCompensationType(Location center, Season season, boolean aquaticZone) {
        List<EntityType> pool = new ArrayList(96);
        Biome biome = this.biomeAt(center);
        if (aquaticZone) {
            this.buildAquaticPool(pool, biome, season);
        } else {
            this.buildLandPool(pool, biome, season);
        }

        return pool.isEmpty() ? null : (EntityType) pool.get(this.random.nextInt(pool.size()));
    }

    /**
     * 按陆地群系构建补偿动物的加权池。
     */
    private void buildLandPool(List<EntityType> pool, Biome biome, Season season) {
        if (this.isMushroomBiome(biome)) {
            this.addWeighted(pool, EntityType.MOOSHROOM, this.seasonWeight(season, Season.SPRING, 8, 4));
        } else if (this.isBeachBiome(biome)) {
            this.addWeighted(pool, EntityType.TURTLE, this.seasonWeight(season, Season.SUMMER, 8, 2));
            this.addWeighted(pool, EntityType.RABBIT, this.seasonWeight(season, Season.AUTUMN, 4, 2));
            this.addWeighted(pool, EntityType.CHICKEN, this.seasonWeight(season, Season.SPRING, 3, 1));
        } else if (this.isColdBiome(biome)) {
            this.addWeighted(pool, EntityType.WOLF, this.seasonWeight(season, Season.WINTER, 8, 3));
            this.addWeighted(pool, EntityType.FOX, this.seasonWeight(season, Season.WINTER, 7, 3));
            if (BiomeSpoofSpawnGuard.isOriginalHabitat(EntityType.GOAT, biome)) {
                this.addWeighted(pool, EntityType.GOAT, this.seasonWeight(season, Season.WINTER, 8, 4));
            }

            if (BiomeSpoofSpawnGuard.isOriginalHabitat(EntityType.POLAR_BEAR, biome)) {
                this.addWeighted(pool, EntityType.POLAR_BEAR, this.seasonWeight(season, Season.WINTER, 6, 2));
            }

            this.addWeighted(pool, EntityType.RABBIT, this.seasonWeight(season, Season.WINTER, 5, 2));
            this.addWeighted(pool, EntityType.SHEEP, 2);
        } else if (this.isTaigaOrMountainBiome(biome)) {
            this.addWeighted(pool, EntityType.FOX, this.seasonWeight(season, Season.AUTUMN, 7, 3));
            this.addWeighted(pool, EntityType.WOLF, this.seasonWeight(season, Season.AUTUMN, 7, 3));
            this.addWeighted(pool, EntityType.GOAT, this.seasonWeight(season, Season.AUTUMN, 8, 4));
            this.addWeighted(pool, EntityType.RABBIT, 4);
            this.addWeighted(pool, EntityType.LLAMA, 3);
            this.addWeighted(pool, EntityType.SHEEP, 4);
        } else if (this.isJungleBiome(biome)) {
            this.addWeighted(pool, EntityType.PARROT, this.seasonWeight(season, Season.SUMMER, 8, 3));
            this.addWeighted(pool, EntityType.PANDA, this.seasonWeight(season, Season.SUMMER, 6, 2));
            this.addWeighted(pool, EntityType.OCELOT, this.seasonWeight(season, Season.SUMMER, 5, 2));
            this.addWeighted(pool, EntityType.FROG, this.seasonWeight(season, Season.SUMMER, 4, 1));
            this.addWeighted(pool, EntityType.CHICKEN, 2);
            this.addWeighted(pool, EntityType.RABBIT, 2);
        } else if (this.isDryWarmBiome(biome)) {
            if (BiomeSpoofSpawnGuard.isOriginalHabitat(EntityType.ARMADILLO, biome)) {
                this.addWeighted(pool, EntityType.ARMADILLO, this.seasonWeight(season, Season.SUMMER, 7, 3));
            }

            this.addWeighted(pool, EntityType.LLAMA, this.seasonWeight(season, Season.AUTUMN, 4, 2));
            this.addWeighted(pool, EntityType.HORSE, this.seasonWeight(season, Season.SPRING, 4, 2));
            this.addWeighted(pool, EntityType.DONKEY, this.seasonWeight(season, Season.SPRING, 3, 2));
            this.addWeighted(pool, EntityType.RABBIT, 4);
            this.addWeighted(pool, EntityType.GOAT, 2);
            this.addWeighted(pool, EntityType.CHICKEN, 1);
        } else {
            this.addWeighted(pool, EntityType.COW, this.seasonWeight(season, Season.SPRING, 7, 3));
            this.addWeighted(pool, EntityType.SHEEP, this.seasonWeight(season, Season.SPRING, 8, 3));
            this.addWeighted(pool, EntityType.PIG, this.seasonWeight(season, Season.SPRING, 7, 3));
            this.addWeighted(pool, EntityType.CHICKEN, this.seasonWeight(season, Season.SPRING, 7, 3));
            this.addWeighted(pool, EntityType.RABBIT, this.seasonWeight(season, Season.SPRING, 5, 2));
            this.addWeighted(pool, EntityType.HORSE, this.seasonWeight(season, Season.SPRING, 5, 2));
            this.addWeighted(pool, EntityType.DONKEY, this.seasonWeight(season, Season.SPRING, 4, 2));
            this.addWeighted(pool, EntityType.BEE, this.seasonWeight(season, Season.SPRING, 7, 2));
            if (this.isForestBiome(biome)) {
                this.addWeighted(pool, EntityType.WOLF, this.seasonWeight(season, Season.AUTUMN, 2, 1));
                this.addWeighted(pool, EntityType.FOX, this.seasonWeight(season, Season.AUTUMN, 2, 1));
            }

        }
    }

    /**
     * 判断该生成原因是否属于玩家主动生成（应受保护、不被迁徙）。
     */
    private boolean isPlayerProtectedSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        if (reason.name().contains("BUCKET")) {
            return true;
        } else {
            boolean protectedReason;
            switch (reason) {
                case BREEDING:
                case SPAWNER_EGG:
                case EGG:
                case DISPENSE_EGG:
                case COMMAND:
                case BEEHIVE:
                case OCELOT_BABY:
                    protectedReason = true;
                    break;
                default:
                    protectedReason = false;
            }

            return protectedReason;
        }
    }

    /**
     * 按水域群系构建补偿动物的加权池。
     */
    private void buildAquaticPool(List<EntityType> pool, Biome biome, Season season) {
        if (this.isLushCavesBiome(biome)) {
            this.addWeighted(pool, EntityType.AXOLOTL, this.seasonWeight(season, Season.SUMMER, 8, 4));
            this.addWeighted(pool, EntityType.TROPICAL_FISH, this.seasonWeight(season, Season.SUMMER, 6, 3));
        } else if (this.isWarmOceanBiome(biome)) {
            this.addWeighted(pool, EntityType.TROPICAL_FISH, this.seasonWeight(season, Season.SUMMER, 10, 3));
            this.addWeighted(pool, EntityType.PUFFERFISH, this.seasonWeight(season, Season.SUMMER, 6, 2));
            this.addWeighted(pool, EntityType.DOLPHIN, this.seasonWeight(season, Season.SUMMER, 6, 2));
            this.addWeighted(pool, EntityType.TURTLE, this.seasonWeight(season, Season.SUMMER, 6, 2));
            this.addWeighted(pool, EntityType.SQUID, 2);
        } else if (this.isRiverOrSwampBiome(biome)) {
            this.addWeighted(pool, EntityType.SALMON, this.seasonWeight(season, Season.SPRING, 5, 3));
            this.addWeighted(pool, EntityType.COD, this.seasonWeight(season, Season.SPRING, 5, 3));
            if (BiomeSpoofSpawnGuard.isOriginalHabitat(EntityType.FROG, biome)) {
                this.addWeighted(pool, EntityType.FROG, this.seasonWeight(season, Season.SUMMER, 6, 2));
            }

            this.addWeighted(pool, EntityType.SQUID, 4);
            this.addWeighted(pool, EntityType.GLOW_SQUID, 2);
        } else if (this.isColdBiome(biome)) {
            this.addWeighted(pool, EntityType.SALMON, this.seasonWeight(season, Season.WINTER, 7, 3));
            this.addWeighted(pool, EntityType.COD, this.seasonWeight(season, Season.WINTER, 7, 3));
            this.addWeighted(pool, EntityType.SQUID, this.seasonWeight(season, Season.WINTER, 5, 3));
            this.addWeighted(pool, EntityType.GLOW_SQUID, this.seasonWeight(season, Season.WINTER, 4, 2));
        } else {
            this.addWeighted(pool, EntityType.COD, this.seasonWeight(season, Season.SPRING, 6, 3));
            this.addWeighted(pool, EntityType.SALMON, this.seasonWeight(season, Season.SPRING, 6, 3));
            this.addWeighted(pool, EntityType.SQUID, 4);
            this.addWeighted(pool, EntityType.GLOW_SQUID, 2);
            this.addWeighted(pool, EntityType.DOLPHIN, this.seasonWeight(season, Season.SUMMER, 4, 2));
        }
    }

    /**
     * 当季返回当季权重，否则返回非当季权重。
     */
    private int seasonWeight(Season current, Season dominant, int inSeason, int outOfSeason) {
        return current == dominant ? inSeason : outOfSeason;
    }

    private void addWeighted(List<EntityType> pool, EntityType type, int weight) {
        for (int i = 0; i < Math.max(0, weight); ++i) {
            pool.add(type);
        }

    }

    /**
     * 返回单一类型在局部区域内的数量上限。
     */
    private int typeLocalCap(EntityType type) {
        byte cap;
        switch (type) {
            case POLAR_BEAR:
            case PANDA:
            case CAMEL:
            case HORSE:
            case DONKEY:
            case MULE:
            case LLAMA:
            case MOOSHROOM:
            case ARMADILLO:
                cap = 2;
                break;
            case DOLPHIN:
            case TURTLE:
            case AXOLOTL:
            case GOAT:
            case WOLF:
            case FOX:
            case OCELOT:
            case PARROT:
                cap = 3;
                break;
            case TROPICAL_FISH:
            case COD:
            case SALMON:
            case PUFFERFISH:
            case SQUID:
            case GLOW_SQUID:
                cap = 8;
                break;
            default:
                cap = 4;
        }

        return cap;
    }

    /**
     * 清理已过期的区域补偿冷却记录。
     */
    private void trimCooldowns() {
        long cutoff = this.cycleCounter - (long) this.compensationZoneCooldownCycles * 4L;
        this.areaCooldowns.entrySet().removeIf((entry) -> (Long) entry.getValue() < cutoff);
    }

    /**
     * 将位置映射为 32x32 区块的区域键，用于冷却记录。
     */
    private long zoneKey(Location loc) {
        long worldBits = loc.getWorld().getUID().getLeastSignificantBits();
        long gx = (long) (loc.getBlockX() >> 5);
        long gz = (long) (loc.getBlockZ() >> 5);
        return worldBits ^ gx << 32 ^ gz & 4294967295L;
    }

    /**
     * 判断位置与所有玩家是否都保持安全距离。
     */
    private boolean isSafeDistanceFromPlayers(Location loc, double minDistance) {
        double minDistSq = minDistance * minDistance;

        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) < minDistSq) {
                return false;
            }
        }

        return true;
    }

    private boolean isInterestingAnimal(LivingEntity le) {
        EntityType t = le.getType();
        if (t != EntityType.PLAYER && t != EntityType.IRON_GOLEM) {
            return this.isManagedFauna(t) || this.netherFauna.contains(t);
        } else {
            return false;
        }
    }

    private boolean isProtectedFromMigration(LivingEntity entity) {
        if (entity.getCustomName() != null) {
            return true;
        } else if (entity.isLeashed()) {
            return true;
        } else if (!entity.isInsideVehicle() && entity.getPassengers().isEmpty()) {
            if (entity.getPersistentDataContainer().has(this.playerProtectedFaunaKey, PersistentDataType.BYTE)) {
                return true;
            } else {
                if (entity instanceof Tameable) {
                    Tameable tameable = (Tameable) entity;
                    if (tameable.isTamed()) {
                        return true;
                    }
                }

                return this.isLikelyEnclosed(entity.getLocation());
            }
        } else {
            return true;
        }
    }

    /**
     * 判断该位置周围是否存在栅栏/围墙，视为已圈养。
     */
    private boolean isLikelyEnclosed(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        } else {
            int baseX = location.getBlockX();
            int baseY = location.getBlockY();
            int baseZ = location.getBlockZ();

            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    for (int dy = -1; dy <= 2; ++dy) {
                        String material = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz).getType().name();
                        if (material.endsWith("_FENCE") || material.endsWith("_FENCE_GATE") || material.endsWith("_WALL")) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    private boolean isManagedFauna(EntityType t) {
        return this.managedFauna.contains(t);
    }

    /**
     * 根据生物类别与当前季节分派到具体的迁徙逻辑。
     */
    private void handleMigrationFor(CalendarState st, LivingEntity entity) {
        if (entity.isValid() && !entity.isDead()) {
            World world = entity.getWorld();
            EntityType type = entity.getType();
            Biome biome = this.biomeAt(entity.getLocation());
            if (world.getEnvironment() == Environment.NETHER) {
                this.migrateNether(entity, biome, type);
            } else if (this.hasRestrictedOriginalHabitat(type)) {
                if (!BiomeSpoofSpawnGuard.isOriginalHabitat(type, biome)) {
                    Location destination = this.findSpawnLocationForType(entity.getLocation(), type, this.searchRadiusBlocks);
                    if (destination != null) {
                        this.teleportWithEffect(entity, destination);
                    } else {
                        this.softRemove(entity);
                    }
                }

            } else if (this.waterFauna.contains(type)) {
                this.migrateAquatic(st.season, entity, biome, type);
            } else {
                switch (st.season) {
                    case SPRING -> this.migrateSpring(entity, biome, type);
                    case SUMMER -> this.migrateSummer(entity, biome, type);
                    case AUTUMN -> this.migrateAutumn(entity, biome, type);
                    case WINTER -> this.migrateWinter(entity, biome, type);
                }

            }
        }
    }

    /**
     * 春季迁徙逻辑。
     */
    private void migrateSpring(LivingEntity entity, Biome biome, EntityType type) {
        if (!this.farmFauna.contains(type) && type != EntityType.BEE) {
            if (this.coldClimateAnimals.contains(type) && this.random.nextDouble() < 0.04) {
                Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isTemperateBiome);
                if (dst != null) {
                    this.teleportWithEffect(entity, dst);
                }
            }

        } else {
            if (!this.isSpringDestinationBiome(biome) && this.random.nextDouble() < 0.06) {
                Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isSpringDestinationBiome);
                if (dst != null) {
                    this.teleportWithEffect(entity, dst);
                }
            }

        }
    }

    /**
     * 夏季迁徙逻辑。
     */
    private void migrateSummer(LivingEntity entity, Biome biome, EntityType type) {
        if (!this.warmClimateAnimals.contains(type) && type != EntityType.FROG) {
            if (this.coldClimateAnimals.contains(type) && this.isColdBiome(biome) && this.random.nextDouble() < 0.05) {
                Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isTemperateOrWarmBiome);
                if (dst != null) {
                    this.teleportWithEffect(entity, dst);
                }
            }

        } else {
            if (!this.isWarmOrWetSummerBiome(biome) && this.random.nextDouble() < 0.08) {
                Predicate<Biome> target = type != EntityType.PARROT && type != EntityType.PANDA && type != EntityType.OCELOT ? this::isWarmBiome : this::isJungleBiome;
                Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, target);
                if (dst != null) {
                    this.teleportWithEffect(entity, dst);
                }
            }

        }
    }

    /**
     * 秋季迁徙逻辑。
     */
    private void migrateAutumn(LivingEntity entity, Biome biome, EntityType type) {
        if ((type == EntityType.FOX || type == EntityType.WOLF || type == EntityType.GOAT || type == EntityType.CAT || type == EntityType.OCELOT || type == EntityType.LLAMA || type == EntityType.MULE || type == EntityType.RABBIT) && !this.isTaigaOrMountainBiome(biome) && !this.isForestBiome(biome) && this.random.nextDouble() < 0.08) {
            Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, (candidateBiome) -> this.isTaigaOrMountainBiome(candidateBiome) || this.isForestBiome(candidateBiome));
            if (dst != null) {
                this.teleportWithEffect(entity, dst);
            }
        }

    }

    /**
     * 冬季迁徙逻辑。
     */
    private void migrateWinter(LivingEntity entity, Biome biome, EntityType type) {
        if (!this.warmClimateAnimals.contains(type) && type != EntityType.FROG) {
            if (this.coldClimateAnimals.contains(type)) {
                if (!this.isColdBiome(biome) && this.random.nextDouble() < 0.12) {
                    Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isColdBiome);
                    if (dst != null) {
                        this.teleportWithEffect(entity, dst);
                    }
                }

            } else {
                if (this.farmFauna.contains(type) && this.isColdBiome(biome) && this.random.nextDouble() < 0.06) {
                    Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isTemperateBiome);
                    if (dst != null) {
                        this.teleportWithEffect(entity, dst);
                    }
                }

            }
        } else {
            if (this.isColdBiome(biome) && this.random.nextDouble() < 0.16) {
                Predicate<Biome> target = type != EntityType.PARROT && type != EntityType.PANDA && type != EntityType.OCELOT ? this::isTemperateOrWarmBiome : this::isJungleBiome;
                Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, target);
                if (dst != null) {
                    this.teleportWithEffect(entity, dst);
                } else if (this.random.nextDouble() < 0.18) {
                    this.softRemove(entity);
                }
            }

        }
    }

    /**
     * 水生动物迁徙逻辑。
     */
    private void migrateAquatic(Season season, LivingEntity entity, Biome biome, EntityType type) {
        Predicate<Biome> target;
        if (type != EntityType.TROPICAL_FISH && type != EntityType.PUFFERFISH && type != EntityType.DOLPHIN && type != EntityType.TURTLE) {
            if (type == EntityType.AXOLOTL) {
                target = season == Season.SUMMER ? this::isRiverOrSwampBiome : this::isAquaticBiome;
            } else {
                target = season == Season.WINTER ? this::isColdOrTemperateWaterBiome : this::isAquaticBiome;
            }
        } else {
            target = season == Season.WINTER ? this::isAquaticBiome : this::isWarmOceanBiome;
        }

        if (!target.test(biome) && this.random.nextDouble() < 0.1) {
            Location dst = this.findNearbyWaterSpot(entity.getLocation(), target, this.searchRadiusBlocks, 28);
            if (dst != null) {
                this.teleportWithEffect(entity, dst);
            }
        }

    }

    /**
     * 下界动物迁徙逻辑。
     */
    private void migrateNether(LivingEntity entity, Biome biome, EntityType type) {
        if (this.netherFauna.contains(type)) {
            if (type == EntityType.HOGLIN && !this.containsAny(biome, "CRIMSON")) {
                if (this.random.nextDouble() < 0.08) {
                    Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, (candidateBiome) -> this.containsAny(candidateBiome, "CRIMSON"));
                    if (dst != null) {
                        this.teleportWithEffect(entity, dst);
                    }
                }

            } else {
                if (type == EntityType.STRIDER && !this.containsAny(biome, "NETHER", "BASALT", "SOUL") && this.random.nextDouble() < 0.08) {
                    Location dst = this.findNearbyLandSpot(entity.getLocation(), this.searchRadiusBlocks, this::isNetherBiome);
                    if (dst != null) {
                        this.teleportWithEffect(entity, dst);
                    }
                }

            }
        }
    }

    /**
     * 获取位置处的（优先原始）群系。
     */
    private Biome biomeAt(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, loc.getBlockY()));
        int z = loc.getBlockZ();
        if (w.getEnvironment() == Environment.NORMAL) {
            BiomeSpoofAdapter spoof = BiomeSpoofAdapter.instanceOrNull();
            if (spoof != null) {
                return spoof.getOriginalBiomeApprox(w, x, y, z);
            }
        }

        return w.getBiome(x, y, z);
    }

    private String biomeKey(Biome biome) {
        return biome.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * 判断群系键是否包含任一分词。
     */
    private boolean containsAny(Biome biome, String... tokens) {
        String key = this.biomeKey(biome);

        for (String token : tokens) {
            if (key.contains(token)) {
                return true;
            }
        }

        return false;
    }

    private boolean isWarmBiome(Biome biome) {
        return this.containsAny(biome, "DESERT", "SAVANNA", "BADLANDS", "JUNGLE", "BAMBOO_JUNGLE", "MANGROVE", "SWAMP");
    }

    private boolean isDryWarmBiome(Biome biome) {
        return this.containsAny(biome, "DESERT", "SAVANNA", "BADLANDS");
    }

    private boolean isJungleBiome(Biome biome) {
        return this.containsAny(biome, "JUNGLE", "BAMBOO_JUNGLE");
    }

    private boolean isForestBiome(Biome biome) {
        return this.containsAny(biome, "FOREST", "BIRCH", "DARK_FOREST", "FLOWER_FOREST");
    }

    private boolean isTemperateBiome(Biome biome) {
        return this.containsAny(biome, "PLAINS", "FOREST", "BIRCH_FOREST", "DARK_FOREST", "FLOWER_FOREST", "MEADOW", "CHERRY_GROVE");
    }

    private boolean isTemperateOrWarmBiome(Biome biome) {
        return this.isTemperateBiome(biome) || this.isWarmBiome(biome);
    }

    private boolean isColdBiome(Biome biome) {
        return this.containsAny(biome, "SNOW", "FROZEN", "COLD", "ICE", "PEAKS", "SLOPES", "GROVE");
    }

    private boolean isTaigaOrMountainBiome(Biome biome) {
        return this.containsAny(biome, "TAIGA", "WINDSWEPT", "JAGGED_PEAKS", "FROZEN_PEAKS", "STONY_PEAKS", "MOUNTAIN", "GROVE", "SLOPES", "PEAKS");
    }

    private boolean isWarmOceanBiome(Biome biome) {
        return this.containsAny(biome, "WARM_OCEAN", "LUKEWARM_OCEAN");
    }

    private boolean isAquaticBiome(Biome biome) {
        return this.containsAny(biome, "OCEAN", "RIVER", "BEACH", "SWAMP", "MANGROVE");
    }

    private boolean isColdOrTemperateWaterBiome(Biome biome) {
        return this.containsAny(biome, "OCEAN", "RIVER", "SWAMP", "COLD", "FROZEN", "BEACH");
    }

    private boolean isRiverOrSwampBiome(Biome biome) {
        return this.containsAny(biome, "RIVER", "SWAMP", "MANGROVE");
    }

    private boolean isBeachBiome(Biome biome) {
        return this.containsAny(biome, "BEACH", "SHORE");
    }

    private boolean isMushroomBiome(Biome biome) {
        return this.containsAny(biome, "MUSHROOM");
    }

    private boolean isLushCavesBiome(Biome biome) {
        return this.containsAny(biome, "LUSH_CAVES");
    }

    private boolean isNetherBiome(Biome biome) {
        return this.containsAny(biome, "NETHER_WASTES", "CRIMSON", "WARPED", "BASALT", "SOUL");
    }

    private boolean isSpringDestinationBiome(Biome biome) {
        return this.containsAny(biome, "PLAINS", "SUNFLOWER_PLAINS", "FLOWER_FOREST", "MEADOW", "CHERRY_GROVE", "BIRCH_FOREST");
    }

    private boolean isWarmOrWetSummerBiome(Biome biome) {
        return this.isWarmBiome(biome) || this.isRiverOrSwampBiome(biome) || this.isBeachBiome(biome);
    }

    /**
     * 判断该位置是否属于水域生态区。
     */
    private boolean isAquaticZone(Location center) {
        if (center.getBlock().isLiquid()) {
            return true;
        } else {
            Biome biome = this.biomeAt(center);
            return this.isAquaticBiome(biome) || this.isLushCavesBiome(biome) || this.isNearWater(center, 4);
        }
    }

    /**
     * 判断位置附近是否存在水方块。
     */
    private boolean isNearWater(Location center, int radius) {
        World world = center.getWorld();
        int bx = center.getBlockX();
        int by = center.getBlockY();
        int bz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; ++dx) {
            int dz = -radius;

            while (dz <= radius) {
                Block block = world.getBlockAt(bx + dx, by, bz + dz);
                if (!block.isLiquid() && block.getType() != Material.WATER) {
                    Block below = world.getBlockAt(bx + dx, by - 1, bz + dz);
                    if (!below.isLiquid() && below.getType() != Material.WATER) {
                        ++dz;
                        continue;
                    }

                    return true;
                }

                return true;
            }
        }

        return false;
    }

    /**
     * 判断某生物类型是否适合当前群系。
     */
    private boolean isBiomeSuitableForType(EntityType type, Biome biome) {
        if (this.hasRestrictedOriginalHabitat(type)) {
            return BiomeSpoofSpawnGuard.isOriginalHabitat(type, biome);
        } else if (type != EntityType.CAMEL && type != EntityType.SNIFFER && type != EntityType.MULE) {
            if (type != EntityType.WOLF && type != EntityType.FOX) {
                if (type == EntityType.BEE) {
                    return this.isSpringDestinationBiome(biome) || this.isForestBiome(biome);
                } else if (this.waterFauna.contains(type)) {
                    return this.isAquaticBiome(biome);
                } else {
                    return this.isTemperateOrWarmBiome(biome) || this.isTaigaOrMountainBiome(biome);
                }
            } else {
                return this.isTaigaOrMountainBiome(biome) || this.isForestBiome(biome) || this.isColdBiome(biome);
            }
        } else {
            return false;
        }
    }

    /**
     * 判断该生物是否只能在特定原始群系中生存。
     */
    private boolean hasRestrictedOriginalHabitat(EntityType type) {
        boolean restricted;
        switch (type) {
            case POLAR_BEAR:
            case PANDA:
            case MOOSHROOM:
            case ARMADILLO:
            case TURTLE:
            case AXOLOTL:
            case GOAT:
            case OCELOT:
            case PARROT:
            case TROPICAL_FISH:
            case PUFFERFISH:
            case FROG:
                restricted = true;
                break;
            case CAMEL:
            case HORSE:
            case DONKEY:
            case MULE:
            case LLAMA:
            case DOLPHIN:
            case WOLF:
            case FOX:
            case COD:
            case SALMON:
            case SQUID:
            case GLOW_SQUID:
            default:
                restricted = false;
        }

        return restricted;
    }

    /**
     * 为指定生物类型在附近寻找合适的生成位置。
     */
    private Location findSpawnLocationForType(Location origin, EntityType type, int radius) {
        if (type == EntityType.TURTLE) {
            return this.findNearbyBeachSpot(origin, radius);
        } else if (this.waterFauna.contains(type)) {
            Predicate<Biome> predicate;
            if (this.hasRestrictedOriginalHabitat(type)) {
                predicate = (biome) -> BiomeSpoofSpawnGuard.isOriginalHabitat(type, biome);
            } else if (type == EntityType.DOLPHIN) {
                predicate = this::isAquaticBiome;
            } else {
                predicate = this::isAquaticBiome;
            }

            return this.findNearbyWaterSpot(origin, predicate, radius, 24);
        } else if (type == EntityType.POLAR_BEAR) {
            return this.findNearbyLandSpot(origin, radius, this::isColdBiome);
        } else if (type == EntityType.CAMEL) {
            return this.findNearbyLandSpot(origin, radius, this::isDryWarmBiome);
        } else if (type != EntityType.PARROT && type != EntityType.PANDA && type != EntityType.OCELOT) {
            if (type == EntityType.FROG) {
                return this.findNearbyLandSpot(origin, radius, (biome) -> BiomeSpoofSpawnGuard.isOriginalHabitat(type, biome));
            } else if (type == EntityType.ARMADILLO) {
                return this.findNearbyLandSpot(origin, radius, (biome) -> BiomeSpoofSpawnGuard.isOriginalHabitat(type, biome));
            } else if (type == EntityType.GOAT) {
                return this.findNearbyLandSpot(origin, radius, (candidateBiome) -> this.isTaigaOrMountainBiome(candidateBiome) || this.isColdBiome(candidateBiome));
            } else if (type != EntityType.WOLF && type != EntityType.FOX) {
                return type == EntityType.MOOSHROOM ? this.findNearbyLandSpot(origin, radius, this::isMushroomBiome) : this.findNearbyLandSpot(origin, radius, this::isBiomeSafeForGenericFauna);
            } else {
                return this.findNearbyLandSpot(origin, radius, (b) -> this.isTaigaOrMountainBiome(b) || this.isForestBiome(b) || this.isColdBiome(b));
            }
        } else {
            return this.findNearbyLandSpot(origin, radius, this::isJungleBiome);
        }
    }

    private boolean isBiomeSafeForGenericFauna(Biome biome) {
        return !this.isNetherBiome(biome);
    }

    /**
     * 在附近随机采样寻找满足群系条件且可站立的陆地位置。
     */
    private Location findNearbyLandSpot(Location origin, int radius, Predicate<Biome> biomePredicate) {
        World world = origin.getWorld();
        int tries = 24;

        for (int i = 0; i < tries; ++i) {
            int dx = this.random.nextInt(radius * 2 + 1) - radius;
            int dz = this.random.nextInt(radius * 2 + 1) - radius;
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;
            int cx = x >> 4;
            int cz = z >> 4;
            if (world.isChunkLoaded(cx, cz)) {
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y > world.getMinHeight() + 1 && y < world.getMaxHeight() - 2) {
                    Biome biome = this.biomeAt(new Location(world, (double) x, (double) y, (double) z));
                    if (biomePredicate.test(biome)) {
                        Block feet = world.getBlockAt(x, y, z);
                        Block head = world.getBlockAt(x, y + 1, z);
                        Block base = world.getBlockAt(x, y - 1, z);
                        if (base.getType().isSolid() && !base.isLiquid() && feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()) {
                            return new Location(world, (double) x + (double) 0.5F, (double) y, (double) z + (double) 0.5F);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 在附近随机采样寻找沙滩/温水海洋的生成位置。
     */
    private Location findNearbyBeachSpot(Location origin, int radius) {
        World world = origin.getWorld();
        int tries = 24;

        for (int i = 0; i < tries; ++i) {
            int dx = this.random.nextInt(radius * 2 + 1) - radius;
            int dz = this.random.nextInt(radius * 2 + 1) - radius;
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;
            int cx = x >> 4;
            int cz = z >> 4;
            if (world.isChunkLoaded(cx, cz)) {
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y > world.getMinHeight() + 1 && y < world.getMaxHeight() - 2) {
                    Biome biome = this.biomeAt(new Location(world, (double) x, (double) y, (double) z));
                    if (this.isBeachBiome(biome) || this.isWarmOceanBiome(biome)) {
                        Block base = world.getBlockAt(x, y - 1, z);
                        Block feet = world.getBlockAt(x, y, z);
                        Block head = world.getBlockAt(x, y + 1, z);
                        Material baseType = base.getType();
                        if ((baseType == Material.SAND || baseType == Material.RED_SAND) && feet.isPassable() && head.isPassable() && this.isNearWater(new Location(world, (double) x, (double) y, (double) z), 3)) {
                            return new Location(world, (double) x + (double) 0.5F, (double) y, (double) z + (double) 0.5F);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 在附近随机采样寻找满足群系条件的水面位置。
     */
    private Location findNearbyWaterSpot(Location origin, Predicate<Biome> biomePredicate, int radius, int tries) {
        World world = origin.getWorld();

        for (int i = 0; i < tries; ++i) {
            int dx = this.random.nextInt(radius * 2 + 1) - radius;
            int dz = this.random.nextInt(radius * 2 + 1) - radius;
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;
            int cx = x >> 4;
            int cz = z >> 4;
            if (world.isChunkLoaded(cx, cz)) {
                int floorY = world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
                int startY = Math.min(world.getSeaLevel() + 3, world.getMaxHeight() - 2);
                int endY = Math.max(world.getMinHeight() + 1, floorY);

                for (int y = startY; y >= endY; --y) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.WATER) {
                        Biome biome = this.biomeAt(new Location(world, (double) x, (double) y, (double) z));
                        if (biomePredicate.test(biome)) {
                            Block above = world.getBlockAt(x, y + 1, z);
                            if (above.getType() == Material.WATER || above.isPassable()) {
                                return new Location(world, (double) x + (double) 0.5F, (double) y + 0.15, (double) z + (double) 0.5F);
                            }
                        }
                        break;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 生成一只受管动物（带标记、可能为幼体并播放粒子）。
     */
    private Entity spawnManaged(Location loc, EntityType type, boolean fromBoost) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        } else {
            Biome originalBiome = this.biomeAt(loc);
            if (!this.isBiomeSuitableForType(type, originalBiome)) {
                return null;
            } else {
                try {
                    Entity spawned = world.spawnEntity(loc, type);
                    spawned.getPersistentDataContainer().set(this.managedFaunaKey, PersistentDataType.BYTE, (byte) 1);
                    if (spawned instanceof Ageable) {
                        Ageable ageable = (Ageable) spawned;
                        if (this.random.nextDouble() < (fromBoost ? (double) 0.5F : 0.35)) {
                            ageable.setBaby();
                        }
                    }

                    if (this.showParticles) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, loc, fromBoost ? 8 : 10, 0.45, 0.45, 0.45, 0.01);
                    }

                    return spawned;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
    }

    /**
     * 带粒子与音效地将动物传送到目标位置。
     */
    private void teleportWithEffect(LivingEntity entity, Location dst) {
        if (entity.isValid() && !entity.isDead()) {
            Location src = entity.getLocation().clone();
            entity.teleport(dst);
            if (this.showParticles) {
                World world = src.getWorld();
                world.spawnParticle(Particle.CLOUD, src, 16, 0.45, 0.45, 0.45, 0.01);
                world.spawnParticle(Particle.CLOUD, dst, 16, 0.45, 0.45, 0.45, 0.01);
            }

            entity.getWorld().playSound(dst, Sound.ENTITY_ENDERMAN_TELEPORT, 0.35F, 1.25F);
        }
    }

    /**
     * 以烟雾效果“软移除”受管动物（若受保护则不移除）。
     */
    private boolean softRemove(LivingEntity entity) {
        if (entity.isValid() && !entity.isDead()) {
            if (this.isProtectedFromMigration(entity)) {
                return false;
            } else if (!entity.getPersistentDataContainer().has(this.managedFaunaKey, PersistentDataType.BYTE)) {
                return false;
            } else {
                if (this.showParticles) {
                    entity.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, entity.getLocation().add((double) 0.0F, (double) 0.5F, (double) 0.0F), 10, (double) 0.25F, (double) 0.25F, (double) 0.25F, 0.01);
                }

                entity.remove();
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * 把数值限制在 [0,1] 区间。
     */
    private double clamp01(double value) {
        return Math.max((double) 0.0F, Math.min((double) 1.0F, value));
    }
}
