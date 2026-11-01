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

public final class WinterWorldPainter implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private BukkitTask task;
    private BukkitTask startupScanTask;
    private BukkitTask priorityMeltTask;
    private static final Method WORLD_GET_TEMP_XYZ;
    private final Set<String> paintedSnow = Collections.synchronizedSet(new HashSet());
    private final Set<String> paintedIce = Collections.synchronizedSet(new HashSet());
    private final Set<String> canopySnow = Collections.synchronizedSet(new HashSet());
    private final Set<String> protectedSnow = Collections.synchronizedSet(new HashSet());
    private final Set<String> protectedIce = Collections.synchronizedSet(new HashSet());
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

    private double getTemperatureAt(World w, int x, int y, int z) {
        try {
            if (WORLD_GET_TEMP_XYZ != null) {
                Object o = WORLD_GET_TEMP_XYZ.invoke(w, x, y, z);
                if (o instanceof Number) {
                    return ((Number)o).doubleValue();
                }
            }
        } catch (Throwable var7) {
        }

        try {
            return w.getTemperature(x, z);
        } catch (Throwable var6) {
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

    public void register() {
        WinterWorldGuardHelper.init(this.plugin);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.schedule();
        this.prepareStartupMelt();
        if (this.seasons.getStateCopy().season != Season.WINTER && this.meltWhenNotWinter) {
            this.preparePriorityMelt();
        }

    }

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

        for(String s : this.plugin.cfg.climate.getStringList("biome_spoof.excluded_biomes")) {
            if (s != null && !s.isBlank()) {
                try {
                    this.excludedSnowBiomes.add(Biome.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException var4) {
                    this.plugin.getLogger().warning("[WinterWorldPainter] Invalid excluded biome '" + s + "' at biome_spoof.excluded_biomes (ignored)");
                }
            }
        }

        this.budget = Math.min(this.budget, 40);
        this.meltBudgetPerTick = Math.min(this.meltBudgetPerTick, 400);
        this.schedule();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent e) {
        CalendarState st = this.seasons.getStateCopy();
        if (st.season != Season.WINTER && this.meltWhenNotWinter) {
            this.preparePriorityMelt();
            this.prepareStartupMelt();
        } else if (st.season == Season.WINTER) {
            this.cancelStartupMelt();
            this.meltColumns.clear();
            this.priorityMeltQueue.clear();
            this.queuedPriorityMelt.clear();
        }

    }

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

    @EventHandler(
        ignoreCancelled = true
    )
    public void onEntityBlockForm(EntityBlockFormEvent e) {
        if (this.enabled) {
            Material newType = e.getNewState().getType();
            if (newType == Material.SNOW || newType == Material.SNOW_BLOCK || newType == Material.ICE || newType == Material.FROSTED_ICE) {
                if (newType != Material.SNOW || !(e.getEntity() instanceof Snowman)) {
                    if (newType != Material.FROSTED_ICE || !(e.getEntity() instanceof Player)) {
                        Block b = e.getBlock();
                        World w = b.getWorld();
                        if (w.getEnvironment() == Environment.NORMAL) {
                            CalendarState st = this.seasons.getStateCopy(w);
                            if (st.season == Season.WINTER) {
                                if (this.isExcludedSnowBiome(w, b.getX(), b.getY(), b.getZ())) {
                                    e.setCancelled(true);
                                } else {
                                    if (!this.freezeWater && newType == Material.ICE) {
                                        e.setCancelled(true);
                                    }

                                }
                            } else if (WinterWorldGuardHelper.canModify(b)) {
                                e.setCancelled(true);
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onSnowmanSnowForm(EntityBlockFormEvent e) {
        if (e.getEntity() instanceof Snowman && e.getNewState().getType() == Material.SNOW && this.protectedSnow.add(this.key(e.getBlock()))) {
            this.markProtectionDirty();
        }

    }

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
                    Block b = e.getBlock();
                    World w = b.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL) {
                        CalendarState st = this.seasons.getStateCopy(w);
                        if (st.season == Season.WINTER) {
                            if (this.isExcludedSnowBiome(w, b.getX(), b.getY(), b.getZ())) {
                                e.setCancelled(true);
                            } else {
                                if (!this.freezeWater && newType == Material.ICE) {
                                    e.setCancelled(true);
                                }

                            }
                        } else if (WinterWorldGuardHelper.canModify(b)) {
                            e.setCancelled(true);
                        }
                    }
                }
            }
        }
    }

    public void run() {
        if (this.enabled) {
            this.maybeSaveProtection(false);
            CalendarState st = this.seasons.getStateCopy();
            Season season = st.season;
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
                    } catch (NumberFormatException var5) {
                    }

                }
            }
        }
    }

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

    private int priorityMeltAllowance(World world) {
        int baseline = Math.max(4, Math.min(64, Math.max(1, this.meltBudgetPerTick) / 4));
        if (world == null) {
            return baseline;
        } else {
            double boost = (double)1.0F + this.sunlightFactor(world);
            return Math.max(baseline, Math.min(128, (int)Math.round((double)baseline * boost)));
        }
    }

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

    private boolean isPotentialMeltMaterial(Material material) {
        return material == Material.SNOW || material == Material.SNOW_BLOCK || material == Material.POWDER_SNOW || material == Material.ICE || material == Material.FROSTED_ICE || material == Material.GRASS_BLOCK || material == Material.PODZOL || material == Material.MYCELIUM;
    }

    private void startupMeltStepLegacy() {
        int chunks = this.startupMeltChunksPerTick;

        while(chunks-- > 0 && !this.startupQueue.isEmpty()) {
            ChunkRef ref = (ChunkRef)this.startupQueue.poll();
            if (ref != null) {
                World w = Bukkit.getWorld(ref.worldId());
                if (w != null && w.isChunkLoaded(ref.chunkX(), ref.chunkZ())) {
                    Chunk ch = w.getChunkAt(ref.chunkX(), ref.chunkZ());
                    if (w.getEnvironment() == Environment.NORMAL) {
                        int minY = w.getMinHeight();
                        int maxY = w.getMaxHeight();
                        int baseX = ch.getX() << 4;
                        int baseZ = ch.getZ() << 4;

                        for(int x = 0; x < 16; ++x) {
                            for(int z = 0; z < 16; ++z) {
                                int wx = baseX + x;
                                int wz = baseZ + z;
                                boolean naturallySnowyArea = this.isNaturallySnowyArea(w, wx, wz);
                                if (!naturallySnowyArea || !this.respectNaturallySnowyBiomes) {
                                    for(int y = maxY - 1; y >= minY; --y) {
                                        Block b = w.getBlockAt(baseX + x, y, baseZ + z);
                                        this.tryVisualMeltBlock(b, naturallySnowyArea);
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

    private void spawnWinterSnowAndIce() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int remainingGlobal = this.budget;
        if (remainingGlobal > 0) {
            List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
            if (!players.isEmpty()) {
                Collections.shuffle(players, ThreadLocalRandom.current());

                for(Player p : players) {
                    if (remainingGlobal <= 0) {
                        break;
                    }

                    World w = p.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL) {
                        Location playerLocation = p.getLocation();
                        boolean anyCold = this.isColdAround(w, playerLocation, Math.min(24, this.radius));
                        boolean stormingForBoost = w.hasStorm() && anyCold;
                        boolean rainingNow = w.hasStorm();
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
                                int dx = r.nextInt(-thisRadius, thisRadius + 1);
                                int dz = r.nextInt(-thisRadius, thisRadius + 1);
                                int x = playerLocation.getBlockX() + dx;
                                int z = playerLocation.getBlockZ() + dz;
                                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                                Block highest = w.getBlockAt(x, y, z);
                                if (!this.isExcludedSnowBiome(w, x, highest.getY(), z)) {
                                    if (this.freezeWater && rainingNow && highest.getType() == Material.WATER) {
                                        BlockData var35 = highest.getBlockData();
                                        if (var35 instanceof Levelled) {
                                            Levelled lvl = (Levelled)var35;
                                            if (lvl.getLevel() == 0 && WinterWorldGuardHelper.canIceForm(highest)) {
                                                --remainingGlobal;
                                                highest.setType(Material.ICE, false);
                                                this.markIce(highest);
                                                Block aboveIce = highest.getRelative(BlockFace.UP);
                                                if (aboveIce.getType().isAir()) {
                                                    boolean willVanillaSnow = stormingForBoost && this.isColdAt(w, x, z);
                                                    double pc = willVanillaSnow ? Math.max((double)0.75F, thisPlace) : thisPlace;
                                                    if (r.nextDouble() < pc && WinterWorldGuardHelper.canSnowFall(aboveIce)) {
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

                                        boolean willVanillaSnow = stormingForBoost && this.isColdAt(w, x, z);
                                        double pc = willVanillaSnow ? Math.max((double)0.75F, thisPlace) : thisPlace;
                                        double lc = willVanillaSnow ? Math.max((double)0.75F, thisAddLayer) : thisAddLayer;
                                        if (snowBlock != null) {
                                            if (WinterWorldGuardHelper.canSnowFall(snowBlock)) {
                                                --remainingGlobal;
                                                if (r.nextDouble() < lc) {
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
                                                if (r.nextDouble() < pc) {
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

    private void prepareStartupMelt() {
        if (this.startupMeltEnabled) {
            CalendarState st = this.seasons.getStateCopy();
            if (st.season != Season.WINTER) {
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

                for(World w : Bukkit.getWorlds()) {
                    if (w.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(w)) {
                        for(Chunk chunk : w.getLoadedChunks()) {
                            ordered.add(new ChunkRef(w.getUID(), chunk.getX(), chunk.getZ()));
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

    private void meltAllStepLegacy() {
        int remaining = this.meltBudgetPerTick;
        if (remaining > 0) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
            if (!players.isEmpty()) {
                Collections.shuffle(players, rnd);

                for(Player p : players) {
                    if (remaining <= 0) {
                        break;
                    }

                    World w = p.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL && !this.seasons.isPermanentWinterWorld(w)) {
                        int px = p.getLocation().getBlockX();
                        int pz = p.getLocation().getBlockZ();
                        int rad = this.radius * 2;
                        int colsPerPlayer = Math.min(remaining, 32);

                        for(int i = 0; i < colsPerPlayer && remaining > 0; ++i) {
                            int x = px + rnd.nextInt(-rad, rad + 1);
                            int z = pz + rnd.nextInt(-rad, rad + 1);
                            boolean naturallySnowyArea = this.isNaturallySnowyArea(w, x, z);
                            if (!naturallySnowyArea || !this.respectNaturallySnowyBiomes) {
                                int topY = w.getMaxHeight() - 1;
                                int minY = w.getMinHeight();

                                for(int y = topY; y >= minY && remaining > 0; --y) {
                                    Block b = w.getBlockAt(x, y, z);
                                    if (this.tryVisualMeltBlock(b, naturallySnowyArea)) {
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
                String k = !snow && !ice ? null : this.key(b);
                if (snow && this.protectedSnow.contains(k)) {
                    return false;
                } else if (ice && this.protectedIce.contains(k)) {
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
                        BlockData data = b.getBlockData();
                        if (data instanceof Snowable) {
                            Snowable snowData = (Snowable)data;
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

    private boolean isSnowableSurfaceMaterial(Material type) {
        return type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.MYCELIUM;
    }

    private void convertConnectedSnowStructureToCalcite(Block start) {
        Queue<Block> queue = new ArrayDeque();
        Set<Long> visited = new HashSet();
        queue.add(start);
        visited.add(packedBlockPosition(start.getX(), start.getY(), start.getZ()));
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        boolean changed = false;

        while(!queue.isEmpty() && visited.size() <= 256) {
            Block b = (Block)queue.poll();
            Material type = b.getType();
            if (type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
                if (type != Material.CALCITE) {
                    continue;
                }
            } else if (WinterWorldGuardHelper.canSnowMelt(b)) {
                b.setType(Material.CALCITE, false);
                this.seasonalCalcite.add(this.key(b));
                changed = true;
            }

            for(BlockFace face : faces) {
                Block nb = b.getRelative(face);
                Material nt = nb.getType();
                if (nt == Material.SNOW_BLOCK || nt == Material.POWDER_SNOW || nt == Material.CALCITE) {
                    long nk = packedBlockPosition(nb.getX(), nb.getY(), nb.getZ());
                    if (visited.add(nk)) {
                        queue.add(nb);
                    }
                }
            }
        }

        if (changed) {
            this.markProtectionDirty();
        }

    }

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

    private boolean supportsGrassSurface(Material type) {
        if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
            if (Tag.FLOWERS.isTagged(type)) {
                return true;
            } else if (Tag.SAPLINGS.isTagged(type)) {
                return true;
            } else {
                String n = type.name();
                return n.contains("GRASS") || n.contains("FERN") || type == Material.DEAD_BUSH || type == Material.SWEET_BERRY_BUSH || type == Material.BAMBOO;
            }
        } else {
            return true;
        }
    }

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

    private boolean isRockyMaterial(Material type) {
        String n = type.name();
        return n.equals("STONE") || n.contains("DEEPSLATE") || n.contains("ANDESITE") || n.contains("DIORITE") || n.contains("GRANITE") || n.contains("TUFF") || n.contains("CALCITE") || n.contains("BASALT") || n.contains("BLACKSTONE") || n.contains("COBBLESTONE") || n.contains("GRAVEL") || n.contains("ORE");
    }

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
                        Block nb = start.getWorld().getBlockAt(start.getX() + dx, start.getY() + dy, start.getZ() + dz);
                        Material t = nb.getType();
                        if (this.isStrongStructureHintMaterial(t)) {
                            ++strongHints;
                        } else if (this.isStructureHintMaterial(t)) {
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
                    Block b = (Block)queue.poll();
                    Material bt = b.getType();
                    if (bt == Material.SNOW_BLOCK || bt == Material.POWDER_SNOW) {
                        ++snowCount;
                        minX = Math.min(minX, b.getX());
                        maxX = Math.max(maxX, b.getX());
                        minY = Math.min(minY, b.getY());
                        maxY = Math.max(maxY, b.getY());
                        minZ = Math.min(minZ, b.getZ());
                        maxZ = Math.max(maxZ, b.getZ());

                        for(BlockFace face : faces) {
                            Block nb = b.getRelative(face);
                            Material nt = nb.getType();
                            if (nt == Material.SNOW_BLOCK || nt == Material.POWDER_SNOW) {
                                long nk = packedBlockPosition(nb.getX(), nb.getY(), nb.getZ());
                                if (visited.add(nk)) {
                                    queue.add(nb);
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
                    long key = packedBlockPosition(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                    if (visited.add(key)) {
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

    private static long packedBlockPosition(int x, int y, int z) {
        return (long)(x & 67108863) << 38 | (long)(z & 67108863) << 12 | (long)y & 4095L;
    }

    private boolean isStructureHintMaterial(Material type) {
        if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
            if (type == Material.DIRT_PATH) {
                return true;
            } else {
                String n = type.name();
                return n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("BED") || n.contains("CARPET") || n.contains("LANTERN") || n.contains("TORCH") || n.contains("GLASS") || n.contains("PANE") || n.contains("FENCE") || n.contains("WALL") || n.contains("STAIRS") || n.contains("SLAB") || n.contains("PLANKS") || n.contains("BRICKS") || n.contains("CRAFTING_TABLE") || n.contains("FURNACE") || n.contains("CHEST") || n.contains("BARREL");
            }
        } else {
            return false;
        }
    }

    private boolean isAirLike(Material t) {
        return t == Material.AIR || t == Material.CAVE_AIR || t == Material.VOID_AIR;
    }

    private void stabilizeSupportBelow(Block b, Material replacement) {
        Material fill = replacement == Material.GRASS_BLOCK ? Material.DIRT : replacement;
        Block below = b.getRelative(BlockFace.DOWN);

        for(int depth = 0; depth < 3 && this.isAirLike(below.getType()) && WinterWorldGuardHelper.canSnowMelt(below); ++depth) {
            below.setType(fill, false);
            below = below.getRelative(BlockFace.DOWN);
        }

    }

    private void restoreSeasonalCalciteStep(int maxBlocks) {
        if (maxBlocks > 0 && !this.seasonalCalcite.isEmpty()) {
            int remaining = maxBlocks;
            boolean changed = false;

            for(String k : new ArrayList<>(this.seasonalCalcite)) {
                if (remaining-- <= 0) {
                    break;
                }

                String[] s = k.split(";");
                World w = Bukkit.getWorld(s[0]);
                if (w == null) {
                    this.seasonalCalcite.remove(k);
                    changed = true;
                } else {
                    int x = Integer.parseInt(s[1]);
                    int y = Integer.parseInt(s[2]);
                    int z = Integer.parseInt(s[3]);
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.CALCITE && WinterWorldGuardHelper.canModify(b)) {
                        b.setType(Material.SNOW_BLOCK, false);
                    }

                    this.seasonalCalcite.remove(k);
                    changed = true;
                }
            }

            if (changed) {
                this.markProtectionDirty();
            }

        }
    }

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

    private boolean isNaturallySnowyBiomeName(Biome biome, int y) {
        String name = biome.name().toUpperCase(Locale.ROOT);
        return name.startsWith("SNOWY_") || name.equals("ICE_SPIKES") || name.equals("FROZEN_RIVER") || name.contains("FROZEN_OCEAN") || name.equals("FROZEN_PEAKS") || name.equals("JAGGED_PEAKS") || name.equals("GROVE") || y >= 120 && (name.startsWith("WINDSWEPT_") || name.equals("MEADOW"));
    }

    private boolean isNaturallySnowyArea(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        return this.isNaturallySnowyBiome(w, x, y, z);
    }

    private void markProtectionDirty() {
        this.protectionDirty = true;
    }

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

    private void loadProtectionFromDisk() {
        try {
            this.plugin.getDataFolder().mkdirs();
        } catch (Throwable var16) {
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
            } catch (Throwable t) {
                this.plugin.getLogger().warning("[AeternumSeasons] Failed to load protected snow/ice file: " + t.getMessage());
            }

        }
    }

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
        } catch (Throwable t) {
            this.plugin.getLogger().warning("[AeternumSeasons] Failed to save protected snow/ice file: " + t.getMessage());
        }

    }

    private boolean isExcludedSnowBiome(World w, int x, int y, int z) {
        if (this.excludedSnowBiomes.isEmpty()) {
            return false;
        } else {
            Biome biome;
            try {
                biome = w.getBiome(x, y, z);
            } catch (Throwable var7) {
                biome = w.getBiome(x, z);
            }

            return this.excludedSnowBiomes.contains(biome);
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material t = b.getType();
        if (t != Material.SNOW && t != Material.SNOW_BLOCK && t != Material.POWDER_SNOW) {
            if (t == Material.ICE) {
                this.protectedIce.add(this.key(b));
                this.markProtectionDirty();
            }
        } else {
            this.protectedSnow.add(this.key(b));
            this.markProtectionDirty();
        }

    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material t = b.getType();
        String k = this.key(b);
        if (t != Material.SNOW && t != Material.SNOW_BLOCK && t != Material.POWDER_SNOW) {
            if (t == Material.ICE) {
                this.protectedIce.remove(k);
                this.markProtectionDirty();
            } else if (t == Material.CALCITE) {
                this.seasonalCalcite.remove(k);
                this.markProtectionDirty();
            }
        } else {
            this.protectedSnow.remove(k);
            this.paintedSnow.remove(k);
            if (this.canopySnow.remove(k)) {
                this.markProtectionDirty();
            }

            this.markProtectionDirty();
        }

    }

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
            File var10002 = this.protectionFile.getParentFile();
            String var10003 = this.protectionFile.getName();
            File temporary = new File(var10002, var10003 + "." + generation + ".tmp");
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
                        } catch (IOException var19) {
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
                    } catch (IOException var18) {
                    }

                }

            });
        }
    }

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

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onProtectedBlockFade(BlockFadeEvent e) {
        Block b = e.getBlock();
        Material type = b.getType();
        String k = this.key(b);
        boolean changed = false;
        if (type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.POWDER_SNOW) {
            if (type == Material.ICE || type == Material.FROSTED_ICE) {
                changed = this.protectedIce.remove(k);
            }
        } else {
            changed = this.protectedSnow.remove(k);
            this.paintedSnow.remove(k);
            changed = this.canopySnow.remove(k) || changed;
        }

        if (changed) {
            this.markProtectionDirty();
        }

    }

    private boolean isColdAround(World w, Location center, int rad) {
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for(int i = 0; i < 16; ++i) {
            int x = center.getBlockX() + r.nextInt(-rad, rad + 1);
            int z = center.getBlockZ() + r.nextInt(-rad, rad + 1);
            if (this.isColdAt(w, x, z)) {
                return true;
            }
        }

        return false;
    }

    private boolean isColdAt(World w, int x, int z) {
        try {
            return w.getTemperature(x, z) <= 0.15;
        } catch (Throwable var7) {
            Biome b = w.getBiome(x, w.getHighestBlockYAt(x, z), z);
            String name = b.name();
            return name.contains("SNOW") || name.contains("FROZEN") || name.contains("GROVE") || name.contains("TAIGA");
        }
    }

    private void markSnow(Block b) {
        this.paintedSnow.add(this.key(b));
    }

    void trackCanopySnow(Block b) {
        String stored = this.key(b);
        this.paintedSnow.add(stored);
        if (this.canopySnow.add(stored)) {
            this.markProtectionDirty();
        }

    }

    private void markIce(Block b) {
        this.paintedIce.add(this.key(b));
    }

    private String key(Block b) {
        String var10000 = b.getWorld().getName();
        return var10000 + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private void clearSnowyBelow(Block snowBlock) {
        Block below = snowBlock.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) {
            BlockData data = below.getBlockData();
            if (data instanceof Snowable) {
                Snowable snowData = (Snowable)data;
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

    private void clearAllPainted() {
        for(String k : new HashSet<>(this.paintedSnow)) {
            if (!this.protectedSnow.contains(k)) {
                String[] s = k.split(";");
                World w = Bukkit.getWorld(s[0]);
                if (w != null) {
                    int x = Integer.parseInt(s[1]);
                    int y = Integer.parseInt(s[2]);
                    int z = Integer.parseInt(s[3]);
                    Block b = w.getBlockAt(x, y, z);
                    if ((b.getType() == Material.SNOW || b.getType() == Material.SNOW_BLOCK) && WinterWorldGuardHelper.canModify(b)) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }

        this.paintedSnow.clear();
        if (!this.canopySnow.isEmpty()) {
            this.canopySnow.clear();
            this.markProtectionDirty();
        }

        for(String k : new HashSet<>(this.paintedIce)) {
            if (!this.protectedIce.contains(k)) {
                String[] s = k.split(";");
                World w = Bukkit.getWorld(s[0]);
                if (w != null) {
                    int x = Integer.parseInt(s[1]);
                    int y = Integer.parseInt(s[2]);
                    int z = Integer.parseInt(s[3]);
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.ICE && WinterWorldGuardHelper.canModify(b)) {
                        b.setType(Material.WATER, false);
                    }
                }
            }
        }

        this.paintedIce.clear();
    }

    private boolean isStrongStructureHintMaterial(Material type) {
        return type == Material.DIRT_PATH || type.name().contains("DOOR") || type.name().contains("BED") || type.name().contains("CHEST") || type.name().contains("BARREL") || type.name().contains("FURNACE") || type.name().contains("CRAFTING_TABLE");
    }

    static {
        Method m = null;

        try {
            m = World.class.getMethod("getTemperature", Integer.TYPE, Integer.TYPE, Integer.TYPE);
        } catch (Throwable var2) {
        }

        WORLD_GET_TEMP_XYZ = m;
        STRUCTURE_FACES = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    }

    private static record ChunkRef(UUID worldId, int chunkX, int chunkZ) {
        private ChunkRef {
        }
    }

    private static record StartupSnapshot(int generation, UUID worldId, int chunkX, int chunkZ, int minY, int maxY, ChunkSnapshot snapshot) {
        private StartupSnapshot {
        }
    }

    private static record BlockRef(int generation, UUID worldId, int x, int y, int z) {
        private BlockRef {
        }
    }

    private static record SeasonalMeltTarget(UUID worldId, int x, int y, int z) {
        private SeasonalMeltTarget {
        }
    }

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
