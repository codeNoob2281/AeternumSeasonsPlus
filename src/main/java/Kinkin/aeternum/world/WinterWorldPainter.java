package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 冬季世界绘制器（Real Snow）。
 *
 * <p>负责在冬季为玩家周围的地表“上雪/结冰”，并在非冬季（或季节切换时）融化这些由本插件铺设的雪与冰。
 * 关键子流程包括：
 * <ul>
 *     <li>飘雪铺设：按配置预算在玩家附近放置雪层/雪块，冻结水面；暴风雪时按倍率提升预算、半径与概率；</li>
 *     <li>优先级融化：季节切换后优先清理历史记录的积雪/结冰；</li>
 *     <li>启动融化：异步读取区块快照，扫描可能的雪/冰/草方块候选再分片融化，避免主线程卡顿；</li>
 *     <li>雪结构的保护与转化：识别玩家建造的雪结构并转成方解石（Calcite），非冬季再恢复；</li>
 *     <li>玩家放置的雪/冰打保护标记，防止被自动融化；标记持久化到 {@code protected_snow_ice.yml}。</li>
 * </ul>
 * 区域权限统一委托 {@link WinterWorldGuardHelper}。</p>
 */
public final class WinterWorldPainter implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private BukkitTask task;
    private BukkitTask startupScanTask;
    private BukkitTask priorityMeltTask;
    private static final Method WORLD_GET_TEMP_XYZ;
    /** 已铺设的雪方块键集合（用于融化时定位）。 */
    private final Set<String> paintedSnow = Collections.synchronizedSet(new HashSet());
    /** 已铺设的冰方块键集合。 */
    private final Set<String> paintedIce = Collections.synchronizedSet(new HashSet());
    /** 由树冠形成的积雪方块键集合。 */
    private final Set<String> canopySnow = Collections.synchronizedSet(new HashSet());
    /** 玩家放置、需要保护的雪方块键集合。 */
    private final Set<String> protectedSnow = Collections.synchronizedSet(new HashSet());
    /** 玩家放置、需要保护的冰方块键集合。 */
    private final Set<String> protectedIce = Collections.synchronizedSet(new HashSet());
    /** 被临时转化为方解石的雪结构方块键集合。 */
    private final Set<String> seasonalCalcite = Collections.synchronizedSet(new HashSet());
    private final Set<Biome> excludedSnowBiomes = new HashSet();
    private final File protectionFile;
    private volatile boolean protectionDirty = false;
    private long lastProtectionSaveMs = 0L;
    private static final long PROTECTION_SAVE_COOLDOWN_MS = 5000L;
    private long scheduledPeriodTicks = 10L;
    private long meltAccumulatedTicks = 0L;
    private boolean startupMeltEnabled;
    private int startupMeltChunksPerTick;
    private final Deque<ChunkRef> startupQueue = new ArrayDeque();
    private final Set<ChunkRef> startupQueuedChunks = new HashSet();
    private final Queue<StartupSnapshot> startupSnapshots = new ConcurrentLinkedQueue();
    private final Queue<BlockRef> startupCandidates = new ConcurrentLinkedQueue();
    private final Queue<BlockRef> startupSurfaceCandidates = new ConcurrentLinkedQueue();
    private final AtomicInteger startupSnapshotsInFlight = new AtomicInteger();
    private final AtomicInteger startupGeneration = new AtomicInteger();
    private boolean startupRunning = false;
    private final Deque<MeltColumn> meltColumns = new ArrayDeque();
    private final Deque<SeasonalMeltTarget> priorityMeltQueue = new ArrayDeque();
    private final Set<SeasonalMeltTarget> queuedPriorityMelt = new HashSet();
    private static final long MELT_WORK_BUDGET_NANOS = 2000000L;
    private static final long PRIORITY_MELT_BUDGET_NANOS = 1500000L;
    private static final int MAX_PENDING_MELT_COLUMNS = 64;
    private static final int STARTUP_CANDIDATE_BACKPRESSURE = 10000;
    private static final BlockFace[] STRUCTURE_FACES;
    private volatile boolean protectionSaveInProgress;
    private volatile long protectionSaveGeneration;
    private boolean enabled;
    private long period;
    private int budget;
    private int radius;
    private double placeChance;
    private double addLayerChance;
    private boolean freezeWater;
    private boolean stormBoostEnabled;
    private double stormBudgetMultiplier;
    private double stormPlaceMultiplier;
    private double stormLayerMultiplier;
    private int stormRadiusBonus;
    private boolean meltWhenNotWinter;
    private long meltPeriod;
    private int meltBudgetPerTick;
    private boolean meltAlsoIce;
    private boolean respectNaturallySnowyBiomes;

    /** 获取指定坐标的温度（优先反射调用三参数 getTemperature，失败回退到普通接口）。 */
    private double getTemperatureAt(World w, int x, int y, int z) {
        try {
            if (WORLD_GET_TEMP_XYZ != null) {
                Object value = WORLD_GET_TEMP_XYZ.invoke(w, x, y, z);
                if (value instanceof Number) {
                    return ((Number)value).doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            return w.getTemperature(x, z);
        } catch (Throwable ignored) {
            return (double)1.0F;
        }
    }

    public WinterWorldPainter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.protectionFile = new File(plugin.getDataFolder(), "protected_snow_ice.yml");
        this.loadProtectionFromDisk();
        this.startupMeltEnabled = plugin.cfg.climate.getBoolean("real_snow.startup_melt.enabled", true);
        this.startupMeltChunksPerTick = Math.max(1, plugin.cfg.climate.getInt("real_snow.startup_melt.chunks_per_tick", 2));
        this.reloadFromConfig();
    }

    /** 初始化区域保护、注册事件并按配置调度各周期任务。 */
    public void register() {
        WinterWorldGuardHelper.init(this.plugin);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.schedule();
        this.prepareStartupMelt();
        if (this.seasons.getStateCopy().season != Season.WINTER && this.meltWhenNotWinter) {
            this.preparePriorityMelt();
        }

    }

    /** 注销：保存保护数据、取消所有任务并注销事件。 */
    public void unregister() {
        this.maybeSaveProtection(true);
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.startupScanTask != null) {
            this.startupScanTask.cancel();
        }

        if (this.priorityMeltTask != null) {
            this.priorityMeltTask.cancel();
        }

        HandlerList.unregisterAll(this);
    }

    /** 取消旧任务并（在启用时）重新调度主循环、异步扫描与优先融化任务。 */
    private void schedule() {
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.startupScanTask != null) {
            this.startupScanTask.cancel();
        }

        if (this.priorityMeltTask != null) {
            this.priorityMeltTask.cancel();
        }

        if (this.enabled) {
            long periodTicks = Math.max(1L, this.period);
            this.scheduledPeriodTicks = periodTicks;
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, periodTicks);
            this.startupScanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::scanStartupSnapshotAsync, 40L, 1L);
            this.priorityMeltTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::priorityMeltTick, 40L, 1L);
        }
    }

    /** 从配置重新读取全部参数并重新调度任务。 */
    public void reloadFromConfig() {
        this.enabled = this.plugin.cfg.climate.getBoolean("real_snow.enabled", true);
        this.period = this.plugin.cfg.climate.getLong("real_snow.tick_period_ticks", 10L);
        this.budget = this.plugin.cfg.climate.getInt("real_snow.max_columns_per_tick", 24);
        this.radius = this.plugin.cfg.climate.getInt("real_snow.radius_blocks", 40);
        this.placeChance = this.plugin.cfg.climate.getDouble("real_snow.place_chance", 0.2);
        this.addLayerChance = this.plugin.cfg.climate.getDouble("real_snow.add_layer_chance", 0.3);
        this.freezeWater = this.plugin.cfg.climate.getBoolean("real_snow.freeze_water", true);
        this.stormBoostEnabled = this.plugin.cfg.climate.getBoolean("real_snow.storm_boost.enabled", true);
        this.stormBudgetMultiplier = clamp(this.plugin.cfg.climate.getDouble("real_snow.storm_boost.budget_multiplier", (double)2.0F), (double)1.0F, (double)20.0F);
        this.stormPlaceMultiplier = clamp(this.plugin.cfg.climate.getDouble("real_snow.storm_boost.place_multiplier", (double)1.5F), (double)1.0F, (double)10.0F);
        this.stormLayerMultiplier = clamp(this.plugin.cfg.climate.getDouble("real_snow.storm_boost.layer_multiplier", (double)1.5F), (double)1.0F, (double)10.0F);
        this.stormRadiusBonus = Math.max(0, this.plugin.cfg.climate.getInt("real_snow.storm_boost.radius_bonus_blocks", 8));
        this.meltWhenNotWinter = this.plugin.cfg.climate.getBoolean("real_snow.melt.enabled", true);
        this.meltPeriod = this.plugin.cfg.climate.getLong("real_snow.melt.tick_period_ticks", 0L);
        this.meltBudgetPerTick = this.plugin.cfg.climate.getInt("real_snow.melt.budget_blocks_per_tick", 300);
        this.meltAlsoIce = this.plugin.cfg.climate.getBoolean("real_snow.melt.also_ice", true);
        this.respectNaturallySnowyBiomes = this.plugin.cfg.climate.getBoolean("real_snow.melt.respect_naturally_snowy_biomes", true);
        this.excludedSnowBiomes.clear();

        for(String biomeName : this.plugin.cfg.climate.getStringList("biome_spoof.excluded_biomes")) {
            if (biomeName != null && !biomeName.isBlank()) {
                try {
                    this.excludedSnowBiomes.add(Biome.valueOf(biomeName.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    this.plugin.getLogger().warning("[WinterWorldPainter] Invalid excluded biome '" + biomeName + "' at biome_spoof.excluded_biomes (ignored)");
                }
            }
        }

        this.budget = Math.min(this.budget, 40);
        this.meltBudgetPerTick = Math.min(this.meltBudgetPerTick, 400);
        this.schedule();
    }

    /** 将数值限制在 [lo, hi] 区间。 */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 监听季节更新：进入非冬季时准备优先/启动融化，进入冬季时取消并清空融化队列。 */
    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent e) {
        CalendarState state = this.seasons.getStateCopy();
        if (state.season != Season.WINTER && this.meltWhenNotWinter) {
            this.preparePriorityMelt();
            this.prepareStartupMelt();
        } else if (state.season == Season.WINTER) {
            this.cancelStartupMelt();
            this.meltColumns.clear();
            this.priorityMeltQueue.clear();
            this.queuedPriorityMelt.clear();
        }

    }

    /** 监听区块加载：非冬季且启用启动融化时，将新加载区块加入融化扫描队列。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onChunkLoad(ChunkLoadEvent e) {
        if (this.enabled && this.startupMeltEnabled && this.meltWhenNotWinter) {
            if (e.getWorld().getEnvironment() == Environment.NORMAL) {
                if (this.seasons.getStateCopy(e.getWorld()).season != Season.WINTER) {
                    Chunk chunk = e.getChunk();
                    ChunkRef ref = new ChunkRef(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
                    if (this.startupQueuedChunks.add(ref)) {
                        this.startupQueue.addLast(ref);
                        this.startupRunning = true;
                    }

                }
            }
        }
    }

    /** 监听实体导致方块形成：冬季按配置决定是否放行雪/冰，非冬季则阻止在受保护区域形成。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onEntityBlockForm(EntityBlockFormEvent e) {
        if (this.enabled) {
            Material newType = e.getNewState().getType();
            if (newType == Material.SNOW || newType == Material.SNOW_BLOCK || newType == Material.ICE || newType == Material.FROSTED_ICE) {
                if (newType != Material.SNOW || !(e.getEntity() instanceof Snowman)) {
                    if (newType != Material.FROSTED_ICE || !(e.getEntity() instanceof Player)) {
                        Block block = e.getBlock();
                        World world = block.getWorld();
                        if (world.getEnvironment() == Environment.NORMAL) {
                            CalendarState state = this.seasons.getStateCopy(world);
                            if (state.season == Season.WINTER) {
                                if (this.isExcludedSnowBiome(world, block.getX(), block.getY(), block.getZ())) {
                                    e.setCancelled(true);
                                } else {
                                    if (!this.freezeWater && newType == Material.ICE) {
                                        e.setCancelled(true);
                                    }

                                }
                            } else if (WinterWorldGuardHelper.canModify(block)) {
                                e.setCancelled(true);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 监听雪傀儡造雪：把雪傀儡生成的雪记为受保护积雪，避免被自动融化。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onSnowmanSnowForm(EntityBlockFormEvent e) {
        if (e.getEntity() instanceof Snowman && e.getNewState().getType() == Material.SNOW && this.protectedSnow.add(this.key(e.getBlock()))) {
            this.markProtectionDirty();
        }

    }

    /** 监听自然/季节方块形成：冬季记录本插件产生的雪或冰，用于后续融化。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onSeasonalNaturalForm(BlockFormEvent e) {
        if (this.enabled) {
            CalendarState state = this.seasons.getStateCopy(e.getBlock().getWorld());
            if (state.season == Season.WINTER) {
                Material type = e.getNewState().getType();
                if (e instanceof EntityBlockFormEvent) {
                    EntityBlockFormEvent entityForm = (EntityBlockFormEvent)e;
                    if (entityForm.getEntity() instanceof Snowman) {
                        return;
                    }

                    if (type == Material.FROSTED_ICE && entityForm.getEntity() instanceof Player) {
                        return;
                    }
                }

                if (type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
                    if (type == Material.ICE) {
                        this.markIce(e.getBlock());
                    }
                } else {
                    this.markSnow(e.getBlock());
                }

            }
        }
    }

    /** 监听方块形成：冬季按配置与生物群系放行雪/冰形成，非冬季阻止在受保护区域形成。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onBlockForm(BlockFormEvent e) {
        if (this.enabled) {
            Material newType = e.getNewState().getType();
            if (newType == Material.SNOW || newType == Material.SNOW_BLOCK || newType == Material.ICE || newType == Material.FROSTED_ICE) {
                if (newType == Material.SNOW && e instanceof EntityBlockFormEvent) {
                    EntityBlockFormEvent entityForm = (EntityBlockFormEvent)e;
                    if (entityForm.getEntity() instanceof Snowman) {
                        return;
                    }
                }

                if (newType != Material.FROSTED_ICE) {
                    Block block = e.getBlock();
                    World world = block.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL) {
                        CalendarState state = this.seasons.getStateCopy(world);
                        if (state.season == Season.WINTER) {
                            if (this.isExcludedSnowBiome(world, block.getX(), block.getY(), block.getZ())) {
                                e.setCancelled(true);
                            } else {
                                if (!this.freezeWater && newType == Material.ICE) {
                                    e.setCancelled(true);
                                }

                            }
                        } else if (WinterWorldGuardHelper.canModify(block)) {
                            e.setCancelled(true);
                        }
                    }
                }
            }
        }
    }

    /** 主循环：按季节分派启动融化、全量融化或冬季铺雪逻辑。 */
    public void run() {
        if (this.enabled) {
            this.maybeSaveProtection(false);
            CalendarState state = this.seasons.getStateCopy();
            Season season = state.season;
            boolean isWinter = season == Season.WINTER;
            if (!isWinter && this.startupRunning) {
                this.startupMeltStep();
            }

            if (!isWinter) {
                if (this.meltWhenNotWinter) {
                    if (this.meltPeriod > 0L) {
                        this.meltAccumulatedTicks += this.scheduledPeriodTicks;
                        if (this.meltAccumulatedTicks >= this.meltPeriod) {
                            this.meltAccumulatedTicks = 0L;
                            this.meltAllStep();
                        }
                    } else {
                        this.meltAllStep();
                    }
                }

            } else {
                this.meltAccumulatedTicks = 0L;
                this.restoreSeasonalCalciteStep(Math.max(32, Math.min(200, this.meltBudgetPerTick)));
                this.spawnWinterSnowAndIce();
            }
        }
    }

    /** 将历史记录的积雪/结冰加入优先融化队列。 */
    private void preparePriorityMelt() {
        this.priorityMeltQueue.clear();
        this.queuedPriorityMelt.clear();
        synchronized(this.paintedSnow) {
            for(String stored : this.paintedSnow) {
                this.enqueueStoredPriorityTarget(stored);
            }
        }

        synchronized(this.paintedIce) {
            for(String stored : this.paintedIce) {
                this.enqueueStoredPriorityTarget(stored);
            }

        }
    }

    /** 解析存储字符串并把目标加入优先融化队列（跳过常冬世界）。 */
    private void enqueueStoredPriorityTarget(String stored) {
        String[] parts = stored.split(";", 4);
        if (parts.length == 4) {
            World world = Bukkit.getWorld(parts[0]);
            if (world != null) {
                if (!this.seasons.isPermanentWinterWorld(world)) {
                    try {
                        SeasonalMeltTarget target = new SeasonalMeltTarget(world.getUID(), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                        if (this.queuedPriorityMelt.add(target)) {
                            this.priorityMeltQueue.addLast(target);
                        }
                    } catch (NumberFormatException ignored) {
                    }

                }
            }
        }
    }

    /** 优先融化任务：在预算与时间片内处理优先队列中的目标。 */
    private void priorityMeltTick() {
        if (this.enabled && this.meltWhenNotWinter && !this.priorityMeltQueue.isEmpty()) {
            if (this.seasons.getStateCopy().season != Season.WINTER) {
                World firstWorld = Bukkit.getWorld(((SeasonalMeltTarget)this.priorityMeltQueue.peekFirst()).worldId());
                int allowance = this.priorityMeltAllowance(firstWorld);
                long deadline = System.nanoTime() + 1500000L;
                int processed = 0;

                while(processed < allowance && !this.priorityMeltQueue.isEmpty() && System.nanoTime() < deadline) {
                    SeasonalMeltTarget target = (SeasonalMeltTarget)this.priorityMeltQueue.pollFirst();
                    this.queuedPriorityMelt.remove(target);
                    World world = Bukkit.getWorld(target.worldId());
                    if (world == null) {
                        ++processed;
                    } else if (this.seasons.isPermanentWinterWorld(world)) {
                        ++processed;
                    } else if (!world.isChunkLoaded(target.x() >> 4, target.z() >> 4)) {
                        if (this.queuedPriorityMelt.add(target)) {
                            this.priorityMeltQueue.addLast(target);
                        }

                        ++processed;
                    } else {
                        Block block = world.getBlockAt(target.x(), target.y(), target.z());
                        String stored = this.key(block);
                        boolean naturallySnowy = this.isNaturallySnowyArea(world, target.x(), target.z());
                        boolean trackedCanopySnow = this.canopySnow.contains(stored);
                        boolean protectedByClimate = this.respectNaturallySnowyBiomes && naturallySnowy && !trackedCanopySnow;
                        boolean protectedByOwner = this.protectedSnow.contains(stored) || this.protectedIce.contains(stored);
                        if (!protectedByClimate && !protectedByOwner) {
                            this.tryVisualMeltBlock(block, naturallySnowy);
                        }

                        this.paintedSnow.remove(stored);
                        this.paintedIce.remove(stored);
                        if (this.canopySnow.remove(stored)) {
                            this.markProtectionDirty();
                        }

                        ++processed;
                    }
                }

            }
        }
    }

    /** 计算优先融化每 tick 的处理配额（受日照系数影响）。 */
    private int priorityMeltAllowance(World world) {
        int baseline = Math.max(4, Math.min(64, Math.max(1, this.meltBudgetPerTick) / 4));
        if (world == null) {
            return baseline;
        } else {
            double boost = (double)1.0F + this.sunlightFactor(world);
            return Math.max(baseline, Math.min(128, (int)Math.round((double)baseline * boost)));
        }
    }

    /** 计算自适应融化检查预算（受各世界日照系数影响）。 */
    private int adaptiveMeltInspectionBudget() {
        double multiplier = (double)1.0F;

        for(World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(world)) {
                double candidate = (double)1.0F + (double)3.0F * this.sunlightFactor(world);
                multiplier = Math.max(multiplier, candidate);
            }
        }

        return Math.max(1, Math.min(4000, (int)Math.ceil((double)Math.max(1, this.meltBudgetPerTick) * multiplier)));
    }

    /** 根据世界时间计算日照系数（0~1，夜间为 0，暴雨衰减）。 */
    private double sunlightFactor(World world) {
        long time = Math.floorMod(world.getTime(), 24000L);
        if (time > 12000L) {
            return (double)0.0F;
        } else {
            double sunlight = Math.sin(Math.PI * (double)time / (double)12000.0F);
            if (world.hasStorm()) {
                sunlight *= 0.55;
            }

            return Math.max((double)0.0F, Math.min((double)1.0F, sunlight));
        }
    }

    /** 启动融化的一步：捕获快照并处理候选方块。 */
    private void startupMeltStep() {
        this.captureStartupSnapshots();
        int remaining = this.adaptiveMeltInspectionBudget();
        long deadline = System.nanoTime() + 2000000L;
        int generation = this.startupGeneration.get();

        while(remaining-- > 0 && System.nanoTime() < deadline) {
            BlockRef ref = (BlockRef)this.startupCandidates.poll();
            if (ref == null) {
                ref = (BlockRef)this.startupSurfaceCandidates.poll();
            }

            if (ref == null) {
                break;
            }

            if (ref.generation() == generation) {
                World world = Bukkit.getWorld(ref.worldId());
                if (world != null && world.isChunkLoaded(ref.x() >> 4, ref.z() >> 4) && !this.seasons.isPermanentWinterWorld(world)) {
                    boolean naturallySnowy = this.isNaturallySnowyArea(world, ref.x(), ref.z());
                    if (!naturallySnowy || !this.respectNaturallySnowyBiomes) {
                        Block block = world.getBlockAt(ref.x(), ref.y(), ref.z());
                        this.tryVisualMeltBlock(block, naturallySnowy);
                    }
                }
            }
        }

        if (this.startupQueue.isEmpty() && this.startupSnapshots.isEmpty() && this.startupSnapshotsInFlight.get() == 0 && this.startupCandidates.isEmpty() && this.startupSurfaceCandidates.isEmpty()) {
            this.startupRunning = false;
        }

    }

    /** 从启动队列取出区块生成快照，交给异步扫描。 */
    private void captureStartupSnapshots() {
        if (this.startupSnapshots.size() < 8 && this.startupCandidates.size() + this.startupSurfaceCandidates.size() < 10000) {
            int remaining = this.startupMeltChunksPerTick;
            int generation = this.startupGeneration.get();

            while(remaining-- > 0 && !this.startupQueue.isEmpty()) {
                ChunkRef ref = (ChunkRef)this.startupQueue.pollFirst();
                if (ref == null) {
                    break;
                }

                this.startupQueuedChunks.remove(ref);
                World world = Bukkit.getWorld(ref.worldId());
                if (world != null && world.getEnvironment() == Environment.NORMAL && world.isChunkLoaded(ref.chunkX(), ref.chunkZ())) {
                    Chunk chunk = world.getChunkAt(ref.chunkX(), ref.chunkZ());
                    ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                    this.startupSnapshotsInFlight.incrementAndGet();
                    this.startupSnapshots.add(new StartupSnapshot(generation, world.getUID(), ref.chunkX(), ref.chunkZ(), world.getMinHeight(), world.getMaxHeight(), snapshot));
                }
            }

        }
    }

    /** 异步扫描区块快照，收集潜在需要融化的方块候选。 */
    private void scanStartupSnapshotAsync() {
        if (this.startupCandidates.size() + this.startupSurfaceCandidates.size() < 10000) {
            StartupSnapshot work = (StartupSnapshot)this.startupSnapshots.poll();
            if (work != null) {
                try {
                    if (work.generation() == this.startupGeneration.get()) {
                        int baseX = work.chunkX() << 4;
                        int baseZ = work.chunkZ() << 4;

                        for(int x = 0; x < 16; ++x) {
                            for(int z = 0; z < 16; ++z) {
                                for(int y = work.maxY() - 1; y >= work.minY(); --y) {
                                    Material material = work.snapshot().getBlockType(x, y, z);
                                    if (this.isPotentialMeltMaterial(material)) {
                                        BlockRef candidate = new BlockRef(work.generation(), work.worldId(), baseX + x, y, baseZ + z);
                                        if (this.isSnowableSurfaceMaterial(material)) {
                                            this.startupSurfaceCandidates.add(candidate);
                                        } else {
                                            this.startupCandidates.add(candidate);
                                        }
                                    }
                                }
                            }
                        }

                        return;
                    }
                } finally {
                    this.startupSnapshotsInFlight.decrementAndGet();
                }

            }
        }
    }

    /** 判断材质是否为潜在需要融化的方块。 */
    private boolean isPotentialMeltMaterial(Material material) {
        return material == Material.SNOW || material == Material.SNOW_BLOCK || material == Material.POWDER_SNOW || material == Material.ICE || material == Material.FROSTED_ICE || material == Material.GRASS_BLOCK || material == Material.PODZOL || material == Material.MYCELIUM;
    }

    /** 旧版启动融化实现（同步逐区块，保留但未使用）。 */
    private void startupMeltStepLegacy() {
        int chunks = this.startupMeltChunksPerTick;

        while(chunks-- > 0 && !this.startupQueue.isEmpty()) {
            ChunkRef ref = (ChunkRef)this.startupQueue.poll();
            if (ref != null) {
                World world = Bukkit.getWorld(ref.worldId());
                if (world != null && world.isChunkLoaded(ref.chunkX(), ref.chunkZ())) {
                    Chunk chunk = world.getChunkAt(ref.chunkX(), ref.chunkZ());
                    if (world.getEnvironment() == Environment.NORMAL) {
                        int minY = world.getMinHeight();
                        int maxY = world.getMaxHeight();
                        int baseX = chunk.getX() << 4;
                        int baseZ = chunk.getZ() << 4;

                        for(int x = 0; x < 16; ++x) {
                            for(int z = 0; z < 16; ++z) {
                                int wx = baseX + x;
                                int wz = baseZ + z;
                                boolean naturallySnowyArea = this.isNaturallySnowyArea(world, wx, wz);
                                if (!naturallySnowyArea || !this.respectNaturallySnowyBiomes) {
                                    for(int y = maxY - 1; y >= minY; --y) {
                                        Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                                        this.tryVisualMeltBlock(block, naturallySnowyArea);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (this.startupQueue.isEmpty()) {
        }

    }

    /** 判断该地面方块是否应阻止在其上放置积雪。 */
    private boolean shouldBlockSnow(Block ground) {
        Material type = ground.getType();
        if (type == Material.SNOW) {
            return false;
        } else {
            String name = type.name();
            if (!name.contains("GLOWSTONE") && !name.contains("SEA_LANTERN") && !name.contains("SHROOMLIGHT") && !name.contains("REDSTONE_LAMP") && type != Material.LIGHT) {
                if (!Tag.CROPS.isTagged(type) && !Tag.FLOWERS.isTagged(type) && !Tag.SAPLINGS.isTagged(type) && !name.startsWith("POTTED_") && !name.contains("MUSHROOM") && !name.contains("FERN") && type != Material.DEAD_BUSH && type != Material.BAMBOO && type != Material.CACTUS && type != Material.SWEET_BERRY_BUSH) {
                    if (!type.isOccluding() && type != Material.SNOW) {
                        return true;
                    } else {
                        return !type.isSolid();
                    }
                } else {
                    return true;
                }
            } else {
                return true;
            }
        }
    }

    /** 冬季铺雪与结冰：在玩家周围按预算放置雪、加厚雪层并冻结水面。 */
    private void spawnWinterSnowAndIce() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int remainingGlobal = this.budget;
        if (remainingGlobal > 0) {
            List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
            if (!players.isEmpty()) {
                Collections.shuffle(players, ThreadLocalRandom.current());

                for(Player player : players) {
                    if (remainingGlobal <= 0) {
                        break;
                    }

                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL) {
                        Location playerLocation = player.getLocation();
                        boolean anyCold = this.isColdAround(world, playerLocation, Math.min(24, this.radius));
                        boolean stormingForBoost = world.hasStorm() && anyCold;
                        boolean rainingNow = world.hasStorm();
                        if (rainingNow) {
                            int thisBudget = remainingGlobal;
                            int thisRadius = this.radius;
                            double thisPlace = this.placeChance;
                            double thisAddLayer = this.addLayerChance;
                            if (stormingForBoost && this.stormBoostEnabled) {
                                thisBudget = (int)Math.ceil((double)remainingGlobal * this.stormBudgetMultiplier);
                                thisRadius += this.stormRadiusBonus;
                                thisPlace = clamp(thisPlace * this.stormPlaceMultiplier, (double)0.0F, (double)1.0F);
                                thisAddLayer = clamp(thisAddLayer * this.stormLayerMultiplier, (double)0.0F, (double)1.0F);
                            }

                            thisBudget = Math.min(thisBudget, remainingGlobal);

                            for(int i = 0; i < thisBudget && remainingGlobal > 0; ++i) {
                                int dx = rnd.nextInt(-thisRadius, thisRadius + 1);
                                int dz = rnd.nextInt(-thisRadius, thisRadius + 1);
                                int x = playerLocation.getBlockX() + dx;
                                int z = playerLocation.getBlockZ() + dz;
                                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                                Block highest = world.getBlockAt(x, y, z);
                                if (!this.isExcludedSnowBiome(world, x, highest.getY(), z)) {
                                    if (this.freezeWater && rainingNow && highest.getType() == Material.WATER) {
                                        BlockData blockData = highest.getBlockData();
                                        if (blockData instanceof Levelled) {
                                            Levelled lvl = (Levelled)blockData;
                                            if (lvl.getLevel() == 0 && WinterWorldGuardHelper.canIceForm(highest)) {
                                                --remainingGlobal;
                                                highest.setType(Material.ICE, false);
                                                this.markIce(highest);
                                                Block aboveIce = highest.getRelative(BlockFace.UP);
                                                if (aboveIce.getType().isAir()) {
                                                    boolean willVanillaSnow = stormingForBoost && this.isColdAt(world, x, z);
                                                    double pc = willVanillaSnow ? Math.max((double)0.75F, thisPlace) : thisPlace;
                                                    if (rnd.nextDouble() < pc && WinterWorldGuardHelper.canSnowFall(aboveIce)) {
                                                        aboveIce.setType(Material.SNOW, false);
                                                        this.markSnow(aboveIce);
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Block snowBlock = null;
                                        if (highest.getType() == Material.SNOW) {
                                            snowBlock = highest;
                                        } else {
                                            Block aboveHighest = highest.getRelative(BlockFace.UP);
                                            if (aboveHighest.getType() == Material.SNOW) {
                                                snowBlock = aboveHighest;
                                            }
                                        }

                                        boolean willVanillaSnow = stormingForBoost && this.isColdAt(world, x, z);
                                        double pc = willVanillaSnow ? Math.max((double)0.75F, thisPlace) : thisPlace;
                                        double lc = willVanillaSnow ? Math.max((double)0.75F, thisAddLayer) : thisAddLayer;
                                        if (snowBlock != null) {
                                            if (WinterWorldGuardHelper.canSnowFall(snowBlock)) {
                                                --remainingGlobal;
                                                if (rnd.nextDouble() < lc) {
                                                    Snow data = (Snow)snowBlock.getBlockData();
                                                    if (data.getLayers() < data.getMaximumLayers()) {
                                                        data.setLayers(data.getLayers() + 1);
                                                        snowBlock.setBlockData(data, false);
                                                        this.markSnow(snowBlock);
                                                    }
                                                }
                                            }
                                        } else {
                                            Block air = highest.getRelative(BlockFace.UP);
                                            if (air.getType().isAir() && !this.shouldBlockSnow(highest) && WinterWorldGuardHelper.canSnowFall(air)) {
                                                --remainingGlobal;
                                                if (rnd.nextDouble() < pc) {
                                                    air.setType(Material.SNOW, false);
                                                    this.markSnow(air);
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
        }
    }

    /** 准备启动融化：把玩家可见范围与已加载区块排入有序队列（非冬季时）。 */
    private void prepareStartupMelt() {
        if (this.startupMeltEnabled) {
            CalendarState state = this.seasons.getStateCopy();
            if (state.season != Season.WINTER) {
                this.cancelStartupMelt();
                this.meltColumns.clear();
                LinkedHashSet<ChunkRef> ordered = new LinkedHashSet();
                int visibleRadius = Bukkit.getViewDistance() + 1;

                for(Player player : Bukkit.getOnlinePlayers()) {
                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(world)) {
                        Location location = player.getLocation();
                        int centerX = location.getBlockX() >> 4;
                        int centerZ = location.getBlockZ() >> 4;

                        for(int distance = 0; distance <= visibleRadius; ++distance) {
                            for(int dx = -distance; dx <= distance; ++dx) {
                                for(int dz = -distance; dz <= distance; ++dz) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) == distance) {
                                        int chunkX = centerX + dx;
                                        int chunkZ = centerZ + dz;
                                        if (world.isChunkLoaded(chunkX, chunkZ)) {
                                            ordered.add(new ChunkRef(world.getUID(), chunkX, chunkZ));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                for(World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(world)) {
                        for(Chunk chunk : world.getLoadedChunks()) {
                            ordered.add(new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ()));
                        }
                    }
                }

                this.startupQueue.addAll(ordered);
                this.startupQueuedChunks.addAll(ordered);
                this.startupRunning = !this.startupQueue.isEmpty();
                if (this.startupRunning) {
                }

            }
        }
    }

    /** 全量融化的一步：从融化列队列中逐列向下查找并融化方块。 */
    private void meltAllStep() {
        this.enqueueMeltColumns();
        int remainingInspections = this.adaptiveMeltInspectionBudget();
        long deadline = System.nanoTime() + 2000000L;

        while(remainingInspections > 0 && !this.meltColumns.isEmpty() && System.nanoTime() < deadline) {
            MeltColumn column = (MeltColumn)this.meltColumns.peekFirst();
            World world = Bukkit.getWorld(column.worldId);
            if (world != null && world.isChunkLoaded(column.x >> 4, column.z >> 4)) {
                if (this.seasons.isPermanentWinterWorld(world)) {
                    this.meltColumns.pollFirst();
                } else {
                    boolean finished = false;

                    while(column.nextY >= column.minY && remainingInspections-- > 0) {
                        Block block = world.getBlockAt(column.x, column.nextY--, column.z);
                        if (this.tryVisualMeltBlock(block, column.naturallySnowy)) {
                            finished = true;
                            break;
                        }

                        if (System.nanoTime() >= deadline) {
                            break;
                        }
                    }

                    if (!finished && column.nextY >= column.minY) {
                        break;
                    }

                    this.meltColumns.pollFirst();
                }
            } else {
                this.meltColumns.pollFirst();
            }
        }

    }

    /** 向融化列队列补充玩家周围的新列。 */
    private void enqueueMeltColumns() {
        if (this.meltColumns.size() < 64) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
            if (!players.isEmpty()) {
                Collections.shuffle(players, random);
                int radiusBlocks = this.radius * 2;

                for(Player player : players) {
                    if (this.meltColumns.size() >= 64) {
                        break;
                    }

                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(world)) {
                        Location playerLocation = player.getLocation();
                        int columnsForPlayer = Math.min(8, 64 - this.meltColumns.size());

                        for(int i = 0; i < columnsForPlayer; ++i) {
                            int x = playerLocation.getBlockX() + random.nextInt(-radiusBlocks, radiusBlocks + 1);
                            int z = playerLocation.getBlockZ() + random.nextInt(-radiusBlocks, radiusBlocks + 1);
                            boolean naturallySnowy = this.isNaturallySnowyArea(world, x, z);
                            if (!naturallySnowy || !this.respectNaturallySnowyBiomes) {
                                int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                                int startY = Math.min(world.getMaxHeight() - 1, surfaceY + 1);
                                this.meltColumns.addLast(new MeltColumn(world.getUID(), x, z, world.getMinHeight(), startY, naturallySnowy));
                            }
                        }
                    }
                }

            }
        }
    }

    /** 旧版全量融化实现（同步逐列，保留但未使用）。 */
    private void meltAllStepLegacy() {
        int remaining = this.meltBudgetPerTick;
        if (remaining > 0) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
            if (!players.isEmpty()) {
                Collections.shuffle(players, rnd);

                for(Player player : players) {
                    if (remaining <= 0) {
                        break;
                    }

                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(world)) {
                        int px = player.getLocation().getBlockX();
                        int pz = player.getLocation().getBlockZ();
                        int rad = this.radius * 2;
                        int colsPerPlayer = Math.min(remaining, 32);

                        for(int i = 0; i < colsPerPlayer && remaining > 0; ++i) {
                            int x = px + rnd.nextInt(-rad, rad + 1);
                            int z = pz + rnd.nextInt(-rad, rad + 1);
                            boolean naturallySnowyArea = this.isNaturallySnowyArea(world, x, z);
                            if (!naturallySnowyArea || !this.respectNaturallySnowyBiomes) {
                                int topY = world.getMaxHeight() - 1;
                                int minY = world.getMinHeight();

                                for(int y = topY; y >= minY && remaining > 0; --y) {
                                    Block block = world.getBlockAt(x, y, z);
                                    if (this.tryVisualMeltBlock(block, naturallySnowyArea)) {
                                        --remaining;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    /** 尝试对单个方块执行“可视化融化”（雪→空气/草土、冰→水等），返回是否发生了改变。 */
    private boolean tryVisualMeltBlock(Block b, boolean naturallySnowyArea) {
        if (this.seasons.isPermanentWinterWorld(b.getWorld())) {
            return false;
        } else {
            Material type = b.getType();
            boolean snow = type == Material.SNOW || type == Material.SNOW_BLOCK || type == Material.POWDER_SNOW;
            boolean ice = type == Material.ICE || type == Material.FROSTED_ICE;
            if (!snow && !ice && !this.isSnowableSurfaceMaterial(type)) {
                return false;
            } else {
                String storedKey = !snow && !ice ? null : this.key(b);
                if (snow && this.protectedSnow.contains(storedKey)) {
                    return false;
                } else if (ice && this.protectedIce.contains(storedKey)) {
                    return false;
                } else if (type == Material.SNOW) {
                    if (!WinterWorldGuardHelper.canSnowMelt(b)) {
                        return false;
                    } else {
                        b.setType(Material.AIR, false);
                        this.clearSnowyBelow(b);
                        return true;
                    }
                } else if (type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
                    if (type == Material.ICE) {
                        if (!this.meltAlsoIce) {
                            return false;
                        } else if (!WinterWorldGuardHelper.canIceMelt(b)) {
                            return false;
                        } else {
                            b.setType(Material.WATER, false);
                            return true;
                        }
                    } else if (type == Material.FROSTED_ICE) {
                        return false;
                    } else {
                        BlockData blockData = b.getBlockData();
                        if (blockData instanceof Snowable) {
                            Snowable snowData = (Snowable)blockData;
                            if (snowData.isSnowy()) {
                                Block above = b.getRelative(BlockFace.UP);
                                Material aboveType = above.getType();
                                if (aboveType != Material.SNOW && aboveType != Material.SNOW_BLOCK) {
                                    if (!WinterWorldGuardHelper.canSnowMelt(b)) {
                                        return false;
                                    }

                                    snowData.setSnowy(false);
                                    b.setBlockData(snowData, false);
                                    return true;
                                }
                            }
                        }

                        return false;
                    }
                } else if (!WinterWorldGuardHelper.canSnowMelt(b)) {
                    return false;
                } else if (this.isProtectedSnowStructureBlock(b)) {
                    this.convertConnectedSnowStructureToCalcite(b);
                    return true;
                } else {
                    Material replacement = this.chooseNaturalSnowReplacement(b);
                    b.setType(replacement, false);
                    this.stabilizeSupportBelow(b, replacement);
                    this.clearSnowyBelow(b);
                    return true;
                }
            }
        }
    }

    /** 判断材质是否为可积雪的地表方块（草方块、灰化土、菌丝体）。 */
    private boolean isSnowableSurfaceMaterial(Material type) {
        return type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.MYCELIUM;
    }

    /** 将与起点相连的雪结构整体转化为方解石，便于非冬季恢复。 */
    private void convertConnectedSnowStructureToCalcite(Block start) {
        Queue<Block> queue = new ArrayDeque();
        Set<Long> visited = new HashSet();
        queue.add(start);
        visited.add(packedBlockPosition(start.getX(), start.getY(), start.getZ()));
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        boolean changed = false;

        while(!queue.isEmpty() && visited.size() <= 256) {
            Block block = (Block)queue.poll();
            Material type = block.getType();
            if (type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
                if (type != Material.CALCITE) {
                    continue;
                }
            } else if (WinterWorldGuardHelper.canSnowMelt(block)) {
                block.setType(Material.CALCITE, false);
                this.seasonalCalcite.add(this.key(block));
                changed = true;
            }

            for(BlockFace face : faces) {
                Block neighbor = block.getRelative(face);
                Material neighborType = neighbor.getType();
                if (neighborType == Material.SNOW_BLOCK || neighborType == Material.POWDER_SNOW || neighborType == Material.CALCITE) {
                    long neighborKey = packedBlockPosition(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                    if (visited.add(neighborKey)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        if (changed) {
            this.markProtectionDirty();
        }

    }

    /** 为雪块融化后选择合适的地表替代材质（草方块/泥土/石头等）。 */
    private Material chooseNaturalSnowReplacement(Block b) {
        Block above = b.getRelative(BlockFace.UP);
        Block below = b.getRelative(BlockFace.DOWN);
        Material aboveType = above.getType();
        Material belowType = below.getType();
        if (aboveType != Material.DIRT_PATH && aboveType != Material.FARMLAND && aboveType != Material.ROOTED_DIRT && aboveType != Material.COARSE_DIRT && aboveType != Material.MOSS_BLOCK) {
            if (this.supportsGrassSurface(aboveType)) {
                return !this.isRockyMaterial(belowType) && !this.isRockyContext(b) ? Material.GRASS_BLOCK : Material.DIRT;
            } else {
                return !this.isRockyMaterial(belowType) && !this.isRockyContext(b) ? Material.DIRT : Material.STONE;
            }
        } else {
            return Material.DIRT;
        }
    }

    /** 判断上方材质是否可支持草方块表面。 */
    private boolean supportsGrassSurface(Material type) {
        if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
            if (Tag.FLOWERS.isTagged(type)) {
                return true;
            } else if (Tag.SAPLINGS.isTagged(type)) {
                return true;
            } else {
                String name = type.name();
                return name.contains("GRASS") || name.contains("FERN") || type == Material.DEAD_BUSH || type == Material.SWEET_BERRY_BUSH || type == Material.BAMBOO;
            }
        } else {
            return true;
        }
    }

    /** 判断方块周围是否处于多岩石环境（用于选择石头/泥土替代）。 */
    private boolean isRockyContext(Block b) {
        int score = 0;
        if (this.isRockyMaterial(b.getRelative(BlockFace.DOWN).getType())) {
            score += 2;
        }

        if (this.isRockyMaterial(b.getRelative(BlockFace.NORTH).getType())) {
            ++score;
        }

        if (this.isRockyMaterial(b.getRelative(BlockFace.SOUTH).getType())) {
            ++score;
        }

        if (this.isRockyMaterial(b.getRelative(BlockFace.EAST).getType())) {
            ++score;
        }

        if (this.isRockyMaterial(b.getRelative(BlockFace.WEST).getType())) {
            ++score;
        }

        if (b.getY() >= 110) {
            ++score;
        }

        return score >= 3;
    }

    /** 判断材质是否属于岩石类。 */
    private boolean isRockyMaterial(Material type) {
        String name = type.name();
        return name.equals("STONE") || name.contains("DEEPSLATE") || name.contains("ANDESITE") || name.contains("DIORITE") || name.contains("GRANITE") || name.contains("TUFF") || name.contains("CALCITE") || name.contains("BASALT") || name.contains("BLACKSTONE") || name.contains("COBBLESTONE") || name.contains("GRAVEL") || name.contains("ORE");
    }

    /** 判断该雪块是否属于玩家建造的小型雪结构（需要用方解石保护）。 */
    private boolean isProtectedSnowStructureBlock(Block start) {
        Material startType = start.getType();
        if (startType != Material.SNOW_BLOCK && startType != Material.POWDER_SNOW) {
            return false;
        } else if (this.hasLargeConnectedSnowCluster(start, 64)) {
            return false;
        } else {
            int strongHints = 0;
            int softHints = 0;

            for(int dx = -6; dx <= 6; ++dx) {
                for(int dy = -4; dy <= 4; ++dy) {
                    for(int dz = -6; dz <= 6; ++dz) {
                        Block neighbor = start.getWorld().getBlockAt(start.getX() + dx, start.getY() + dy, start.getZ() + dz);
                        Material type = neighbor.getType();
                        if (this.isStrongStructureHintMaterial(type)) {
                            ++strongHints;
                        } else if (this.isStructureHintMaterial(type)) {
                            ++softHints;
                        }
                    }
                }
            }

            if (strongHints == 0) {
                return false;
            } else {
                Queue<Block> queue = new ArrayDeque();
                Set<Long> visited = new HashSet();
                queue.add(start);
                visited.add(packedBlockPosition(start.getX(), start.getY(), start.getZ()));
                int snowCount = 0;
                int minX = start.getX();
                int maxX = start.getX();
                int minY = start.getY();
                int maxY = start.getY();
                int minZ = start.getZ();
                int maxZ = start.getZ();
                BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

                while(!queue.isEmpty() && visited.size() <= 96) {
                    Block block = (Block)queue.poll();
                    Material blockType = block.getType();
                    if (blockType == Material.SNOW_BLOCK || blockType == Material.POWDER_SNOW) {
                        ++snowCount;
                        minX = Math.min(minX, block.getX());
                        maxX = Math.max(maxX, block.getX());
                        minY = Math.min(minY, block.getY());
                        maxY = Math.max(maxY, block.getY());
                        minZ = Math.min(minZ, block.getZ());
                        maxZ = Math.max(maxZ, block.getZ());

                        for(BlockFace face : faces) {
                            Block neighbor = block.getRelative(face);
                            Material neighborType = neighbor.getType();
                            if (neighborType == Material.SNOW_BLOCK || neighborType == Material.POWDER_SNOW) {
                                long neighborKey = packedBlockPosition(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                                if (visited.add(neighborKey)) {
                                    queue.add(neighbor);
                                }
                            }
                        }
                    }
                }

                int spanX = maxX - minX + 1;
                int spanY = maxY - minY + 1;
                int spanZ = maxZ - minZ + 1;
                if (snowCount > 64) {
                    return false;
                } else {
                    boolean smallSnowStructure = snowCount >= 6 && spanX >= 2 && spanX <= 8 && spanZ >= 2 && spanZ <= 8 && spanY >= 2 && spanY <= 6;
                    boolean tinySnowStructure = snowCount >= 3 && spanX >= 1 && spanX <= 5 && spanZ >= 1 && spanZ <= 5 && spanY >= 1 && spanY <= 4;
                    return smallSnowStructure || tinySnowStructure;
                }
            }
        }
    }

    /** 判断起点是否属于一个过大的连通雪块（超过 limit 即视为大型结构）。 */
    private boolean hasLargeConnectedSnowCluster(Block start, int limit) {
        Queue<Block> queue = new ArrayDeque();
        Set<Long> visited = new HashSet();
        queue.add(start);
        visited.add(packedBlockPosition(start.getX(), start.getY(), start.getZ()));

        while(!queue.isEmpty()) {
            Block block = (Block)queue.poll();

            for(BlockFace face : STRUCTURE_FACES) {
                Block neighbor = block.getRelative(face);
                Material type = neighbor.getType();
                if (type == Material.SNOW_BLOCK || type == Material.POWDER_SNOW) {
                    long neighborKey = packedBlockPosition(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                    if (visited.add(neighborKey)) {
                        if (visited.size() > limit) {
                            return true;
                        }

                        queue.add(neighbor);
                    }
                }
            }
        }

        return false;
    }

    /** 将方块坐标压缩为单个 long 键。 */
    private static long packedBlockPosition(int x, int y, int z) {
        return (long)(x & 67108863) << 38 | (long)(z & 67108863) << 12 | (long)y & 4095L;
    }

    /** 判断材质是否为（较弱）结构线索材质。 */
    private boolean isStructureHintMaterial(Material type) {
        if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
            if (type == Material.DIRT_PATH) {
                return true;
            } else {
                String name = type.name();
                return name.contains("DOOR") || name.contains("TRAPDOOR") || name.contains("BED") || name.contains("CARPET") || name.contains("LANTERN") || name.contains("TORCH") || name.contains("GLASS") || name.contains("PANE") || name.contains("FENCE") || name.contains("WALL") || name.contains("STAIRS") || name.contains("SLAB") || name.contains("PLANKS") || name.contains("BRICKS") || name.contains("CRAFTING_TABLE") || name.contains("FURNACE") || name.contains("CHEST") || name.contains("BARREL");
            }
        } else {
            return false;
        }
    }

    /** 判断材质是否为空气类。 */
    private boolean isAirLike(Material t) {
        return t == Material.AIR || t == Material.CAVE_AIR || t == Material.VOID_AIR;
    }

    /** 向下填补空气，稳定融化后地表的支撑。 */
    private void stabilizeSupportBelow(Block b, Material replacement) {
        Material fill = replacement == Material.GRASS_BLOCK ? Material.DIRT : replacement;
        Block below = b.getRelative(BlockFace.DOWN);

        for(int depth = 0; depth < 3 && this.isAirLike(below.getType()) && WinterWorldGuardHelper.canSnowMelt(below); ++depth) {
            below.setType(fill, false);
            below = below.getRelative(BlockFace.DOWN);
        }

    }

    /** 恢复被临时转化为方解石的雪结构（非冬季调用）。 */
    private void restoreSeasonalCalciteStep(int maxBlocks) {
        if (maxBlocks > 0 && !this.seasonalCalcite.isEmpty()) {
            int remaining = maxBlocks;
            boolean changed = false;

            for(String key : new ArrayList<>(this.seasonalCalcite)) {
                if (remaining-- <= 0) {
                    break;
                }

                String[] parts = key.split(";");
                World world = Bukkit.getWorld(parts[0]);
                if (world == null) {
                    this.seasonalCalcite.remove(key);
                    changed = true;
                } else {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.CALCITE && WinterWorldGuardHelper.canModify(block)) {
                        block.setType(Material.SNOW_BLOCK, false);
                    }

                    this.seasonalCalcite.remove(key);
                    changed = true;
                }
            }

            if (changed) {
                this.markProtectionDirty();
            }

        }
    }

    /** 判断坐标处是否为自然常雪生物群系（优先参考 BiomeSpoof 的原始生物群系）。 */
    private boolean isNaturallySnowyBiome(World w, int x, int y, int z) {
        BiomeSpoofAdapter adapter = BiomeSpoofAdapter.instanceOrNull();
        if (adapter != null) {
            Biome original = adapter.getOriginalBiomeApproxOrNull(w, x, y, z);
            if (original != null) {
                return this.isNaturallySnowyBiomeName(original, y);
            }
        }

        if (this.getTemperatureAt(w, x, y, z) <= 0.15) {
            return true;
        } else {
            Biome biome = w.getBiome(x, y, z);
            return this.isNaturallySnowyBiomeName(biome, y);
        }
    }

    /** 根据生物群系名称判断是否为常雪群系。 */
    private boolean isNaturallySnowyBiomeName(Biome biome, int y) {
        String name = biome.name().toUpperCase(Locale.ROOT);
        return name.startsWith("SNOWY_") || name.equals("ICE_SPIKES") || name.equals("FROZEN_RIVER") || name.contains("FROZEN_OCEAN") || name.equals("FROZEN_PEAKS") || name.equals("JAGGED_PEAKS") || name.equals("GROVE") || y >= 120 && (name.startsWith("WINDSWEPT_") || name.equals("MEADOW"));
    }

    /** 判断坐标所在列是否为自然常雪区域。 */
    private boolean isNaturallySnowyArea(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        return this.isNaturallySnowyBiome(w, x, y, z);
    }

    /** 标记保护数据已变更，等待节流保存。 */
    private void markProtectionDirty() {
        this.protectionDirty = true;
    }

    /** 按需保存保护数据：force 立即同步保存，否则按冷却时间异步保存。 */
    private void maybeSaveProtection(boolean force) {
        if (force) {
            ++this.protectionSaveGeneration;
            this.saveProtectionToDisk();
        } else if (this.protectionDirty) {
            long now = System.currentTimeMillis();
            if (now - this.lastProtectionSaveMs >= 5000L) {
                this.saveProtectionToDiskAsync();
            }
        }
    }

    /** 从磁盘加载保护数据文件。 */
    private void loadProtectionFromDisk() {
        try {
            this.plugin.getDataFolder().mkdirs();
        } catch (Throwable ignored) {
        }

        if (this.protectionFile != null && this.protectionFile.exists()) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(this.protectionFile);
                List<String> snow = yml.getStringList("snow");
                List<String> ice = yml.getStringList("ice");
                List<String> calcite = yml.getStringList("seasonal_calcite");
                List<String> canopy = yml.getStringList("seasonal_canopy_snow");
                synchronized(this.protectedSnow) {
                    this.protectedSnow.clear();
                    this.protectedSnow.addAll(snow);
                }

                synchronized(this.protectedIce) {
                    this.protectedIce.clear();
                    this.protectedIce.addAll(ice);
                }

                synchronized(this.seasonalCalcite) {
                    this.seasonalCalcite.clear();
                    this.seasonalCalcite.addAll(calcite);
                }

                synchronized(this.canopySnow) {
                    this.canopySnow.clear();
                    this.canopySnow.addAll(canopy);
                }

                this.paintedSnow.addAll(canopy);
                this.protectionDirty = false;
            } catch (Throwable error) {
                this.plugin.getLogger().warning("[AeternumSeasons] Failed to load protected snow/ice file: " + error.getMessage());
            }

        }
    }

    /** 同步保存保护数据到磁盘。 */
    private void saveProtectionToDisk() {
        try {
            this.plugin.getDataFolder().mkdirs();
            List<String> snow;
            synchronized(this.protectedSnow) {
                snow = new ArrayList(this.protectedSnow);
            }

            List<String> ice;
            synchronized(this.protectedIce) {
                ice = new ArrayList(this.protectedIce);
            }

            List<String> calcite;
            synchronized(this.seasonalCalcite) {
                calcite = new ArrayList(this.seasonalCalcite);
            }

            List<String> canopy;
            synchronized(this.canopySnow) {
                canopy = new ArrayList(this.canopySnow);
            }

            YamlConfiguration yml = new YamlConfiguration();
            yml.set("snow", snow);
            yml.set("ice", ice);
            yml.set("seasonal_calcite", calcite);
            yml.set("seasonal_canopy_snow", canopy);
            yml.save(this.protectionFile);
            this.protectionDirty = false;
            this.lastProtectionSaveMs = System.currentTimeMillis();
        } catch (IOException ioe) {
            this.plugin.getLogger().warning("[AeternumSeasons] Failed to save protected snow/ice file: " + ioe.getMessage());
        } catch (Throwable error) {
            this.plugin.getLogger().warning("[AeternumSeasons] Failed to save protected snow/ice file: " + error.getMessage());
        }

    }

    /** 判断该坐标是否位于被排除降雪的生物群系。 */
    private boolean isExcludedSnowBiome(World w, int x, int y, int z) {
        if (this.excludedSnowBiomes.isEmpty()) {
            return false;
        } else {
            Biome biome;
            try {
                biome = w.getBiome(x, y, z);
            } catch (Throwable ignored) {
                biome = w.getBiome(x, z);
            }

            return this.excludedSnowBiomes.contains(biome);
        }
    }

    /** 监听玩家放置方块：记录玩家放置的雪/冰以保护其不被自动融化。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        Material type = block.getType();
        if (type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
            if (type == Material.ICE) {
                this.protectedIce.add(this.key(block));
                this.markProtectionDirty();
            }
        } else {
            this.protectedSnow.add(this.key(block));
            this.markProtectionDirty();
        }

    }

    /** 监听玩家破坏方块：移除对应的保护/覆盖标记。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        Material type = block.getType();
        String key = this.key(block);
        if (type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
            if (type == Material.ICE) {
                this.protectedIce.remove(key);
                this.markProtectionDirty();
            } else if (type == Material.CALCITE) {
                this.seasonalCalcite.remove(key);
                this.markProtectionDirty();
            }
        } else {
            this.protectedSnow.remove(key);
            this.paintedSnow.remove(key);
            if (this.canopySnow.remove(key)) {
                this.markProtectionDirty();
            }

            this.markProtectionDirty();
        }

    }

    /** 异步保存保护数据（通过临时文件 + 原子替换）。 */
    private void saveProtectionToDiskAsync() {
        if (!this.protectionSaveInProgress) {
            List<String> snow;
            synchronized(this.protectedSnow) {
                snow = new ArrayList(this.protectedSnow);
            }

            List<String> ice;
            synchronized(this.protectedIce) {
                ice = new ArrayList(this.protectedIce);
            }

            List<String> calcite;
            synchronized(this.seasonalCalcite) {
                calcite = new ArrayList(this.seasonalCalcite);
            }

            List<String> canopy;
            synchronized(this.canopySnow) {
                canopy = new ArrayList(this.canopySnow);
            }

            long generation = ++this.protectionSaveGeneration;
            File parentDir = this.protectionFile.getParentFile();
            String fileName = this.protectionFile.getName();
            File temporary = new File(parentDir, fileName + "." + generation + ".tmp");
            this.protectionSaveInProgress = true;
            this.protectionDirty = false;
            this.lastProtectionSaveMs = System.currentTimeMillis();
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                try {
                    YamlConfiguration yml = new YamlConfiguration();
                    yml.set("snow", snow);
                    yml.set("ice", ice);
                    yml.set("seasonal_calcite", calcite);
                    yml.set("seasonal_canopy_snow", canopy);
                    Files.writeString(temporary.toPath(), yml.saveToString(), StandardCharsets.UTF_8);
                    if (generation == this.protectionSaveGeneration) {
                        try {
                            Files.move(temporary.toPath(), this.protectionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException ignored) {
                            Files.move(temporary.toPath(), this.protectionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (Throwable error) {
                    this.protectionDirty = true;
                    this.plugin.getLogger().warning("[AeternumSeasons] Failed to save protected snow/ice file: " + error.getMessage());
                } finally {
                    this.protectionSaveInProgress = false;

                    try {
                        Files.deleteIfExists(temporary.toPath());
                    } catch (IOException ignored) {
                    }

                }

            });
        }
    }

    /** 取消启动融化：递增世代并清空所有队列与在途计数。 */
    private void cancelStartupMelt() {
        this.startupGeneration.incrementAndGet();
        this.startupQueue.clear();
        this.startupQueuedChunks.clear();

        while(this.startupSnapshots.poll() != null) {
            this.startupSnapshotsInFlight.decrementAndGet();
        }

        this.startupCandidates.clear();
        this.startupSurfaceCandidates.clear();
        this.startupRunning = false;
    }

    /** 监听受保护方块消失（融化/破坏）：同步移除对应标记。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onProtectedBlockFade(BlockFadeEvent e) {
        Block block = e.getBlock();
        Material type = block.getType();
        String key = this.key(block);
        boolean changed = false;
        if (type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
            if (type == Material.ICE || type == Material.FROSTED_ICE) {
                changed = this.protectedIce.remove(key);
            }
        } else {
            changed = this.protectedSnow.remove(key);
            this.paintedSnow.remove(key);
            changed = this.canopySnow.remove(key) || changed;
        }

        if (changed) {
            this.markProtectionDirty();
        }

    }

    /** 判断玩家周围是否至少有某处足够寒冷（用于判断是否进入铺雪状态）。 */
    private boolean isColdAround(World w, Location center, int rad) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for(int i = 0; i < 16; ++i) {
            int x = center.getBlockX() + rnd.nextInt(-rad, rad + 1);
            int z = center.getBlockZ() + rnd.nextInt(-rad, rad + 1);
            if (this.isColdAt(w, x, z)) {
                return true;
            }
        }

        return false;
    }

    /** 判断坐标处是否足够寒冷（优先温度，异常时按生物群系名称判断）。 */
    private boolean isColdAt(World w, int x, int z) {
        try {
            return w.getTemperature(x, z) <= 0.15;
        } catch (Throwable ignored) {
            Biome biome = w.getBiome(x, w.getHighestBlockYAt(x, z), z);
            String name = biome.name();
            return name.contains("SNOW") || name.contains("FROZEN") || name.contains("GROVE") || name.contains("TAIGA");
        }
    }

    /** 记录方块为已铺设的雪。 */
    private void markSnow(Block b) {
        this.paintedSnow.add(this.key(b));
    }

    /** 记录方块为树冠积雪（同时计入已铺设雪集合）。 */
    void trackCanopySnow(Block b) {
        String stored = this.key(b);
        this.paintedSnow.add(stored);
        if (this.canopySnow.add(stored)) {
            this.markProtectionDirty();
        }

    }

    /** 记录方块为已铺设的冰。 */
    private void markIce(Block b) {
        this.paintedIce.add(this.key(b));
    }

    /** 生成方块在持久化文件中的键（世界名;x;y;z）。 */
    private String key(Block b) {
        String worldName = b.getWorld().getName();
        return worldName + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    /** 清除雪块下方方块的“snowy”状态。 */
    private void clearSnowyBelow(Block snowBlock) {
        Block below = snowBlock.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) {
            BlockData blockData = below.getBlockData();
            if (blockData instanceof Snowable) {
                Snowable snowData = (Snowable)blockData;
                if (snowData.isSnowy()) {
                    if (!WinterWorldGuardHelper.canModify(below)) {
                        return;
                    }

                    snowData.setSnowy(false);
                    below.setBlockData(snowData, false);
                }
            }
        }

    }

    /** 清除所有本插件铺设（且非玩家保护）的雪与冰。 */
    private void clearAllPainted() {
        for(String key : new HashSet<>(this.paintedSnow)) {
            if (!this.protectedSnow.contains(key)) {
                String[] parts = key.split(";");
                World world = Bukkit.getWorld(parts[0]);
                if (world != null) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    Block block = world.getBlockAt(x, y, z);
                    if ((block.getType() == Material.SNOW || block.getType() == Material.SNOW_BLOCK) && WinterWorldGuardHelper.canModify(block)) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }

        this.paintedSnow.clear();
        if (!this.canopySnow.isEmpty()) {
            this.canopySnow.clear();
            this.markProtectionDirty();
        }

        for(String key : new HashSet<>(this.paintedIce)) {
            if (!this.protectedIce.contains(key)) {
                String[] parts = key.split(";");
                World world = Bukkit.getWorld(parts[0]);
                if (world != null) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.ICE && WinterWorldGuardHelper.canModify(block)) {
                        block.setType(Material.WATER, false);
                    }
                }
            }
        }

        this.paintedIce.clear();
    }

    /** 判断材质是否为强结构线索材质（门、床、箱子、桶、熔炉、工作台等）。 */
    private boolean isStrongStructureHintMaterial(Material type) {
        return type == Material.DIRT_PATH || type.name().contains("DOOR") || type.name().contains("BED") || type.name().contains("CHEST") || type.name().contains("BARREL") || type.name().contains("FURNACE") || type.name().contains("CRAFTING_TABLE");
    }

    static {
        Method method = null;

        try {
            method = World.class.getMethod("getTemperature", Integer.TYPE, Integer.TYPE, Integer.TYPE);
        } catch (Throwable ignored) {
        }

        WORLD_GET_TEMP_XYZ = method;
        STRUCTURE_FACES = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    }

    /** 区块引用：世界 UUID + 区块坐标。 */
    private static record ChunkRef(UUID worldId, int chunkX, int chunkZ) {
        private ChunkRef {
        }
    }

    /** 启动融化用区块快照及其世代、范围信息。 */
    private static record StartupSnapshot(int generation, UUID worldId, int chunkX, int chunkZ, int minY, int maxY, ChunkSnapshot snapshot) {
        private StartupSnapshot {
        }
    }

    /** 方块引用：世代 + 世界 UUID + 坐标。 */
    private static record BlockRef(int generation, UUID worldId, int x, int y, int z) {
        private BlockRef {
        }
    }

    /** 优先融化目标：世界 UUID + 坐标。 */
    private static record SeasonalMeltTarget(UUID worldId, int x, int y, int z) {
        private SeasonalMeltTarget {
        }
    }

    /** 待融化的方块列，从 nextY 向下逐格检查至 minY。 */
    private static final class MeltColumn {
        final UUID worldId;
        final int x;
        final int z;
        final int minY;
        final boolean naturallySnowy;
        int nextY;

        MeltColumn(UUID worldId, int x, int z, int minY, int nextY, boolean naturallySnowy) {
            super();
            this.worldId = worldId;
            this.x = x;
            this.z = z;
            this.minY = minY;
            this.nextY = nextY;
            this.naturallySnowy = naturallySnowy;
        }
    }
}
