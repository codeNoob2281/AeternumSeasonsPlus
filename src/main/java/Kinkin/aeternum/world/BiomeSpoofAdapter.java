package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarChannel;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import Kinkin.aeternum.util.PlayerArrivalSync;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class BiomeSpoofAdapter implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final BiomePacketSender biomePacketSender;
    private Mode mode;
    private int radiusChunksCfg;
    private int budgetPerTick;
    private static volatile BiomeSpoofAdapter INSTANCE;
    private boolean revertOnSeasonChange;
    private final EnumMap<Season, Biome> seasonTarget = new EnumMap(Season.class);
    private final Set<Biome> excludedBiomes = new HashSet();
    private final Set<Biome> protectedEcologyBiomes = new HashSet();
    private final Map<Long, Boolean> excludedCache = new ConcurrentHashMap();
    private boolean respectNaturallySnowyBiomes;
    private boolean protectWaterColorBiomes;
    private boolean oceansEnabled;
    private boolean oceansAffectShores;
    private boolean oceansKeepDeepVariants;
    private boolean oceansWinterForceSnow;
    private Biome oceansWinterForceSnowBiome;
    private final EnumMap<Season, Biome> oceanTarget;
    private boolean spawnGuardEnabled;
    private boolean spawnGuardOnlySpoofedChunks;
    private int spawnGuardNearWaterRadius;
    private static final EnumSet<EntityType> WATER_REQUIRED_MOBS;
    private static final EnumSet<EntityType> CAPPED_AQUATIC_MOBS;
    private static final int AQUATIC_CAP_RADIUS_XZ = 32;
    private static final int AQUATIC_CAP_RADIUS_Y = 16;
    private static final int MAX_FISH_PER_AREA = 48;
    private static final int MAX_DOLPHINS_PER_AREA = 6;
    private final Map<Long, AppliedBiomeState> lastApplied;
    private final Map<Long, Family> familyCache;
    private BukkitTask task;
    private PlayerArrivalSync arrivalSync;
    private final Deque<ChunkWork> pendingChunks;
    private final Set<ChunkWork> queuedChunks;
    private final Set<ChunkWork> backupLookupsPending;
    private final Set<ChunkWork> backupLookupsCompleted;
    private final Deque<ChunkWork> clientRefreshQueue;
    private final Set<ChunkWork> queuedClientRefreshes;
    private final Map<UUID, PlayerScanState> playerScanStates;
    private final Map<Integer, List<Offset>> offsetsByRadius;
    private double chunkCredits;
    private int optimizedTickCounter;
    private int clientRefreshTickCounter;
    private double preTransitionRateMultiplier;
    private Set<String> disabledWorldNames;
    private int clientRefreshIntervalTicks;
    private int clientRefreshChunksPerInterval;
    private int clientFullRefreshChunksPerInterval;
    private boolean clientUseBiomePackets;
    private boolean clientRefreshNudge;
    private static final long SPOOF_WORK_BUDGET_NANOS = 2000000L;
    private static final int LEGACY_SCHEDULE_PERIOD_TICKS = 10;
    private final Map<Long, Biome[]> backups;
    private final Map<Long, BitSet> modifiedBiomeCells;
    private final Set<Long> spoofed;
    private static final Set<Long> COLD_CHUNKS;
    private final Set<Long> warmChunks;
    private static final int NUDGES_PER_TICK = 8;
    private static final long NUDGE_COOLDOWN_MS = 3000L;
    private static final Material NUDGE_FAKE;
    private static final int STEP_XZ = 4;
    private static final int STEP_Y = 4;
    private final Map<UUID, ArrayDeque<NudgeTarget>> nudgeQueue;
    private final Map<NudgeCooldownKey, Long> nudgeLast;
    private static final long TRANSITION_WINDOW_MS = 5000L;
    private static final int TRANSITION_BUDGET_MULTIPLIER = 3;
    private volatile long seasonTransitionUntil;
    private static final int PRE_TRANSITION_DAYS = 3;
    private boolean preTransitionEnabled;
    private boolean preTransitionAffectOceans;
    private final BiomeBackupStore diskBackups;
    private volatile boolean diskBackupEnabled;

    private void resyncPlayer(Player player) {
        this.playerScanStates.remove(player.getUniqueId());
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            World world = player.getWorld();
            CalendarChannel channel = this.seasons.resolveChannel(world);
            if (world.getEnvironment() == Environment.NORMAL && !this.plugin.isWorldDisabled(world) && channel != null && this.seasons.isChannelEnabled(channel)) {
                this.pendingChunks.removeIf((workx) -> {
                    World queuedWorld = Bukkit.getWorld(workx.worldId());
                    if (queuedWorld != null && this.hasClientViewer(queuedWorld, workx.chunkX(), workx.chunkZ())) {
                        return false;
                    } else {
                        this.queuedChunks.remove(workx);
                        return true;
                    }
                });
                Location location = player.getLocation();
                int cx = location.getBlockX() >> 4;
                int cz = location.getBlockZ() >> 4;
                List<Offset> offsets = this.offsetsForRadius(Math.max(this.radiusChunksCfg, Bukkit.getViewDistance() + 1));

                for(int i = offsets.size() - 1; i >= 0; --i) {
                    Offset offset = (Offset)offsets.get(i);
                    int x = cx + offset.dx;
                    int z = cz + offset.dz;
                    if (world.isChunkLoaded(x, z)) {
                        ChunkWork work = new ChunkWork(world.getUID(), x, z);
                        this.pendingChunks.remove(work);
                        this.queuedChunks.add(work);
                        this.pendingChunks.addFirst(work);
                        this.clientRefreshQueue.remove(work);
                        this.queuedClientRefreshes.add(work);
                        this.clientRefreshQueue.addFirst(work);
                    }
                }

            }
        }
    }

    public static BiomeSpoofAdapter instanceOrNull() {
        return INSTANCE;
    }

    public BiomeSpoofAdapter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.oceansWinterForceSnowBiome = Biome.SNOWY_PLAINS;
        this.oceanTarget = new EnumMap(Season.class);
        this.lastApplied = new ConcurrentHashMap();
        this.familyCache = new ConcurrentHashMap();
        this.pendingChunks = new ArrayDeque();
        this.queuedChunks = new HashSet();
        this.backupLookupsPending = new HashSet();
        this.backupLookupsCompleted = new HashSet();
        this.clientRefreshQueue = new ArrayDeque();
        this.queuedClientRefreshes = new HashSet();
        this.playerScanStates = new HashMap();
        this.offsetsByRadius = new HashMap();
        this.preTransitionRateMultiplier = (double)1.0F;
        this.disabledWorldNames = Set.of();
        this.clientRefreshIntervalTicks = 1;
        this.clientRefreshChunksPerInterval = 12;
        this.clientFullRefreshChunksPerInterval = 2;
        this.clientUseBiomePackets = true;
        this.backups = new ConcurrentHashMap();
        this.modifiedBiomeCells = new ConcurrentHashMap();
        this.spoofed = ConcurrentHashMap.newKeySet();
        this.warmChunks = new HashSet();
        this.nudgeQueue = new HashMap();
        this.nudgeLast = new HashMap();
        this.seasonTransitionUntil = 0L;
        this.diskBackupEnabled = true;
        this.plugin = plugin;
        this.seasons = seasons;
        this.biomePacketSender = new BiomePacketSender(plugin);
        this.diskBackups = new BiomeBackupStore(plugin);
        this.reloadFromConfig();
        INSTANCE = this;
    }

    private void reloadFromConfig() {
        this.seasonTarget.clear();
        this.oceanTarget.clear();
        boolean enabled = this.plugin.cfg.climate.getBoolean("biome_spoof.enabled", true);
        if (!enabled) {
            this.mode = BiomeSpoofAdapter.Mode.OFF;
        } else {
            String m = this.plugin.cfg.climate.getString("biome_spoof.mode", "GLOBAL_RING");
            if ("OFF".equalsIgnoreCase(m)) {
                this.mode = BiomeSpoofAdapter.Mode.OFF;
            } else {
                this.mode = BiomeSpoofAdapter.Mode.GLOBAL_RING;
            }

            this.radiusChunksCfg = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.radius_chunks", 8));
            this.budgetPerTick = Math.max(2, this.plugin.cfg.climate.getInt("biome_spoof.budget_chunks_per_tick", 16));
            this.clientRefreshIntervalTicks = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.client_refresh.interval_ticks", 1));
            this.clientRefreshChunksPerInterval = Math.max(1, Math.min(64, this.plugin.cfg.climate.getInt("biome_spoof.client_refresh.chunks_per_interval", 12)));
            this.clientFullRefreshChunksPerInterval = Math.max(1, Math.min(4, this.plugin.cfg.climate.getInt("biome_spoof.client_refresh.full_chunk_fallback_chunks_per_interval", 2)));
            this.clientUseBiomePackets = this.plugin.cfg.climate.getBoolean("biome_spoof.client_refresh.use_biome_packets", true);
            this.clientRefreshNudge = this.plugin.cfg.climate.getBoolean("biome_spoof.client_refresh.extra_nudge", false);
            this.revertOnSeasonChange = this.plugin.cfg.climate.getBoolean("biome_spoof.revert_on_non_winter", true);
            this.respectNaturallySnowyBiomes = this.plugin.cfg.climate.getBoolean("real_snow.melt.respect_naturally_snowy_biomes", true);
            this.protectWaterColorBiomes = this.plugin.cfg.climate.getBoolean("biome_spoof.oceans.protect_water_color_biomes", this.plugin.cfg.climate.getBoolean("biome_spoof.protect_water_color_biomes", true));
            this.preTransitionEnabled = this.plugin.cfg.climate.getBoolean("biome_spoof.pre_transition.enabled", true);
            this.preTransitionAffectOceans = this.plugin.cfg.climate.getBoolean("biome_spoof.pre_transition.affect_oceans", false);
            this.diskBackupEnabled = this.plugin.cfg.climate.getBoolean("biome_spoof.disk_backup.enabled", true);
            this.seasonTarget.put(Season.SPRING, this.readBiome("biome_spoof.seasons.SPRING", Biome.FLOWER_FOREST));
            this.seasonTarget.put(Season.SUMMER, this.readBiome("biome_spoof.seasons.SUMMER", Biome.PLAINS));
            this.seasonTarget.put(Season.AUTUMN, this.readAutumnBiome("biome_spoof.seasons.AUTUMN"));
            this.seasonTarget.put(Season.WINTER, this.readBiome("biome_spoof.seasons.WINTER", Biome.SNOWY_PLAINS));
            this.oceansEnabled = this.plugin.cfg.climate.getBoolean("biome_spoof.oceans.enabled", true);
            this.oceansAffectShores = this.plugin.cfg.climate.getBoolean("biome_spoof.oceans.affect_shores", true);
            this.oceansKeepDeepVariants = this.plugin.cfg.climate.getBoolean("biome_spoof.oceans.keep_deep_variants", true);
            this.oceansWinterForceSnow = this.plugin.cfg.climate.getBoolean("biome_spoof.oceans.winter_force_snow", false);
            this.oceansWinterForceSnowBiome = this.readBiome("biome_spoof.oceans.winter_force_snow_biome", Biome.SNOWY_PLAINS);
            this.oceanTarget.put(Season.SPRING, this.readBiome("biome_spoof.oceans.seasons.SPRING", Biome.LUKEWARM_OCEAN));
            this.oceanTarget.put(Season.SUMMER, this.readBiome("biome_spoof.oceans.seasons.SUMMER", Biome.WARM_OCEAN));
            this.oceanTarget.put(Season.AUTUMN, this.readBiome("biome_spoof.oceans.seasons.AUTUMN", Biome.OCEAN));
            this.oceanTarget.put(Season.WINTER, this.readBiome("biome_spoof.oceans.seasons.WINTER", Biome.FROZEN_OCEAN));
            this.spawnGuardEnabled = this.plugin.cfg.climate.getBoolean("biome_spoof.spawn_guard.enabled", false);
            this.spawnGuardOnlySpoofedChunks = this.plugin.cfg.climate.getBoolean("biome_spoof.spawn_guard.only_spoofed_chunks", true);
            this.spawnGuardNearWaterRadius = Math.max(0, this.plugin.cfg.climate.getInt("biome_spoof.spawn_guard.near_water_radius", 2));
            this.excludedBiomes.clear();
            this.protectedEcologyBiomes.clear();
            this.readBiomeSet("biome_spoof.excluded_biomes", this.excludedBiomes);
            if (this.plugin.cfg.climate.getBoolean("biome_spoof.protected_ecology.enabled", true)) {
                this.readBiomeSet("biome_spoof.protected_ecology.biomes", this.protectedEcologyBiomes);
            }

            this.excludedCache.clear();
        }
    }

    private void readBiomeSet(String path, Set<Biome> destination) {
        for(String raw : this.plugin.cfg.climate.getStringList(path)) {
            if (raw != null && !raw.isBlank()) {
                Biome biome = this.findBiome(raw);
                if (biome != null) {
                    destination.add(biome);
                } else {
                    this.plugin.getLogger().warning("[BiomeSpoof] Invalid biome '" + raw + "' at " + path + " (ignored)");
                }
            }
        }

    }

    private Biome readAutumnBiome(String path) {
        String raw = this.plugin.cfg.climate.getString(path, "AUTO");
        if (raw != null && "AUTO".equalsIgnoreCase(raw.trim())) {
            Biome dappledForest = this.findBiome("minecraft:dappled_forest");
            Biome resolved = dappledForest != null ? dappledForest : Biome.SAVANNA_PLATEAU;
            this.plugin.getLogger().info("[BiomeSpoof] AUTUMN=AUTO resolved to " + biomeKey(resolved));
            return resolved;
        } else {
            return this.readBiomeValue(path, raw, Biome.SAVANNA_PLATEAU);
        }
    }

    private Biome readBiome(String path, Biome def) {
        String raw = this.plugin.cfg.climate.getString(path, biomeKey(def));
        return this.readBiomeValue(path, raw, def);
    }

    private Biome readBiomeValue(String path, String raw, Biome def) {
        Biome biome = this.findBiome(raw);
        if (biome != null) {
            return biome;
        } else {
            this.plugin.getLogger().warning("[BiomeSpoof] Invalid biome '" + raw + "' at " + path + ", using " + biomeKey(def));
            return def;
        }
    }

    private Biome findBiome(String raw) {
        if (raw != null && !raw.isBlank()) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            NamespacedKey key = normalized.contains(":") ? NamespacedKey.fromString(normalized) : NamespacedKey.minecraft(normalized);
            return key == null ? null : (Biome)Registry.BIOME.get(key);
        } else {
            return null;
        }
    }

    private static String biomeKey(Biome biome) {
        return biome == null ? "null" : biome.getKey().toString();
    }

    private static boolean isBiome(Biome biome, String minecraftKey) {
        return biome != null && NamespacedKey.minecraft(minecraftKey).equals(biome.getKey());
    }

    public Biome getEffectiveAutumnBiome() {
        return (Biome)this.seasonTarget.get(Season.AUTUMN);
    }

    public boolean usesSavannaPlateauForAutumn() {
        return isBiome(this.getEffectiveAutumnBiome(), "savanna_plateau");
    }

    public boolean usesDappledForestForAutumn() {
        return isBiome(this.getEffectiveAutumnBiome(), "dappled_forest");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.arrivalSync != null) {
            this.arrivalSync.close();
        }

        this.arrivalSync = new PlayerArrivalSync(this.plugin, this::resyncPlayer);
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::runOptimized, 40L, 1L);
    }

    public void unregister() {
        if (this.arrivalSync != null) {
            this.arrivalSync.close();
        }

        this.arrivalSync = null;
        if (this.task != null) {
            this.task.cancel();
        }

        HandlerList.unregisterAll(this);
        this.revertAll();
        this.nudgeQueue.clear();
        this.nudgeLast.clear();
        COLD_CHUNKS.clear();
        this.warmChunks.clear();
        this.lastApplied.clear();
        this.familyCache.clear();
        this.pendingChunks.clear();
        this.queuedChunks.clear();
        this.backupLookupsPending.clear();
        this.backupLookupsCompleted.clear();
        this.clientRefreshQueue.clear();
        this.queuedClientRefreshes.clear();
        this.clientRefreshTickCounter = 0;
        this.playerScanStates.clear();
        this.offsetsByRadius.clear();
        this.chunkCredits = (double)0.0F;
        this.excludedCache.clear();
        if (INSTANCE == this) {
            INSTANCE = null;
        }

    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            if (this.spawnGuardEnabled) {
                CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
                if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN) {
                    Location loc = e.getLocation();
                    World w = loc.getWorld();
                    if (w != null) {
                        if (this.spawnGuardOnlySpoofedChunks) {
                            long ck = this.key(w, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                            if (!this.spoofed.contains(ck)) {
                                return;
                            }
                        }

                        EntityType type = e.getEntityType();
                        if (CAPPED_AQUATIC_MOBS.contains(type) && this.exceedsAquaticCap(loc, type)) {
                            e.setCancelled(true);
                        } else if (WATER_REQUIRED_MOBS.contains(type)) {
                            if (!this.isInWaterLikeBlock(loc)) {
                                e.setCancelled(true);
                            }

                        } else {
                            if (type == EntityType.DROWNED) {
                                if (this.isInWaterLikeBlock(loc)) {
                                    return;
                                }

                                int r = this.spawnGuardNearWaterRadius;
                                if (r > 0 && this.hasWaterNearby(loc, r)) {
                                    return;
                                }

                                e.setCancelled(true);
                            }

                        }
                    }
                }
            }
        }
    }

    private boolean isInWaterLikeBlock(Location loc) {
        Block b = loc.getBlock();
        if (this.isWaterLike(b)) {
            return true;
        } else {
            Block below = b.getRelative(0, -1, 0);
            return this.isWaterLike(below);
        }
    }

    private boolean isWaterLike(Block b) {
        Material t = b.getType();
        if (t != Material.WATER && t != Material.BUBBLE_COLUMN) {
            BlockData data = b.getBlockData();
            return data instanceof Waterlogged ? ((Waterlogged)data).isWaterlogged() : false;
        } else {
            return true;
        }
    }

    private boolean hasWaterNearby(Location loc, int radius) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();

            for(int dx = -radius; dx <= radius; ++dx) {
                for(int dz = -radius; dz <= radius; ++dz) {
                    if (dx != 0 || dz != 0) {
                        Block b = w.getBlockAt(bx + dx, by, bz + dz);
                        if (this.isWaterLike(b)) {
                            return true;
                        }

                        Block below = w.getBlockAt(bx + dx, by - 1, bz + dz);
                        if (this.isWaterLike(below)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    private boolean exceedsAquaticCap(Location loc, EntityType spawningType) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int max = this.maxAquaticAllowed(spawningType);
            if (max <= 0) {
                return false;
            } else {
                int count = 0;

                for(Entity entity : w.getNearbyEntities(loc, (double)32.0F, (double)16.0F, (double)32.0F)) {
                    if (entity.getType() == spawningType) {
                        ++count;
                        if (count >= max) {
                            return true;
                        }
                    }
                }

                return false;
            }
        }
    }

    private int maxAquaticAllowed(EntityType type) {
        return type == EntityType.DOLPHIN ? 6 : 48;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        ChunkWork work = new ChunkWork(e.getChunk().getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
        this.backupLookupsPending.remove(work);
        this.backupLookupsCompleted.remove(work);
        long k = this.key(e.getChunk());
        this.spoofed.remove(k);
        this.modifiedBiomeCells.remove(k);
        this.lastApplied.remove(k);
        this.familyCache.remove(k);
        this.warmChunks.remove(k);
        COLD_CHUNKS.remove(k);
        this.excludedCache.remove(k);
        if (this.mode != BiomeSpoofAdapter.Mode.OFF && this.isInsideAnyActivePlayerArea(e.getChunk())) {
            this.enqueueChunk(e.getChunk().getWorld(), e.getChunk().getX(), e.getChunk().getZ());
        }

    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk ch = e.getChunk();
        long k = this.key(ch);
        ChunkWork work = new ChunkWork(ch.getWorld().getUID(), ch.getX(), ch.getZ());
        this.queuedChunks.remove(work);
        this.queuedClientRefreshes.remove(work);
        this.backupLookupsPending.remove(work);
        this.backupLookupsCompleted.remove(work);
        if (this.spoofed.remove(k)) {
            this.revertChunk(ch, false);
        }

        this.backups.remove(k);
        this.modifiedBiomeCells.remove(k);
        this.warmChunks.remove(k);
        COLD_CHUNKS.remove(k);
        NudgeTarget unloaded = new NudgeTarget(ch.getWorld().getUID(), ch.getX(), ch.getZ());

        for(ArrayDeque<NudgeTarget> queue : this.nudgeQueue.values()) {
            queue.remove(unloaded);
        }

        this.excludedCache.remove(k);
    }

    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent e) {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            this.seasonTransitionUntil = System.currentTimeMillis() + 5000L;
            this.playerScanStates.clear();
            this.clientRefreshQueue.clear();
            this.queuedClientRefreshes.clear();
            this.clientRefreshTickCounter = 0;
            if (this.revertOnSeasonChange) {
            }

        }
    }

    private void runOptimized() {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            ++this.optimizedTickCounter;
            if (this.optimizedTickCounter >= 10) {
                this.optimizedTickCounter = 0;
                this.enqueuePlayerAreas();
                if (this.clientRefreshNudge) {
                    this.flushNudges();
                }
            }

            double multiplier = System.currentTimeMillis() < this.seasonTransitionUntil ? (double)3.0F : this.preTransitionRateMultiplier;
            double ratePerTick = Math.max((double)1.0F, (double)this.budgetPerTick * multiplier);
            double creditCap = Math.max((double)1.0F, ratePerTick * (double)2.0F);
            this.chunkCredits = Math.min(creditCap, this.chunkCredits + ratePerTick);
            int allowance = (int)Math.floor(this.chunkCredits);
            if (allowance > 0 && !this.pendingChunks.isEmpty()) {
                long deadline = System.nanoTime() + 2000000L;
                int processed = 0;

                while(processed < allowance && !this.pendingChunks.isEmpty()) {
                    ChunkWork work = (ChunkWork)this.pendingChunks.pollFirst();
                    if (this.queuedChunks.remove(work)) {
                        this.processQueuedChunk(work);
                        this.chunkCredits = Math.max((double)0.0F, this.chunkCredits - (double)1.0F);
                        ++processed;
                        if (System.nanoTime() >= deadline) {
                            break;
                        }
                    }
                }
            }

            this.flushClientRefreshQueue();
        }
    }

    private void enqueuePlayerAreas() {
        long cooldownCutoff = System.currentTimeMillis() - 3000L;
        this.nudgeLast.entrySet().removeIf((entry) -> (Long)entry.getValue() < cooldownCutoff);
        Set<String> disabled = new HashSet();

        for(String worldName : this.plugin.getConfig().getStringList("worlds.disabled_season_fx")) {
            if (worldName != null) {
                disabled.add(worldName.toLowerCase(Locale.ROOT));
            }
        }

        this.disabledWorldNames = Set.copyOf(disabled);
        Set<UUID> online = new HashSet();
        double maxPreTransition = (double)0.0F;
        int radius = Math.max(this.radiusChunksCfg, Bukkit.getViewDistance() + 1);

        for(Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            World world = player.getWorld();
            if (world.getEnvironment() == Environment.NORMAL && !this.isWorldDisabled(world)) {
                CalendarChannel channel = this.seasons.resolveChannel(world);
                if (channel != null && this.seasons.isChannelEnabled(channel)) {
                    boolean permanentWinter = this.seasons.isPermanentWinterWorld(world);
                    CalendarState state = this.seasons.getStateCopy(world);
                    int dayInPeriod = this.computeDayInPeriod(channel, state);
                    double preFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, state, dayInPeriod) : (double)0.0F;
                    maxPreTransition = Math.max(maxPreTransition, preFactor);
                    Location location = player.getLocation();
                    int centerX = location.getBlockX() >> 4;
                    int centerZ = location.getBlockZ() >> 4;
                    long visualStamp = this.visualStamp(state) ^ Double.doubleToLongBits(preFactor);
                    PlayerScanState previous = (PlayerScanState)this.playerScanStates.get(playerId);
                    PlayerScanState current = new PlayerScanState(world.getUID(), centerX, centerZ, radius, visualStamp);
                    if (!current.equals(previous)) {
                        boolean enqueueOnlyNewRing = previous != null && previous.worldId().equals(current.worldId()) && previous.radius() == current.radius() && previous.visualStamp() == current.visualStamp();

                        for(Offset offset : this.offsetsForRadius(radius)) {
                            int chunkX = centerX + offset.dx;
                            int chunkZ = centerZ + offset.dz;
                            if (!enqueueOnlyNewRing || Math.abs(chunkX - previous.chunkX()) > previous.radius() || Math.abs(chunkZ - previous.chunkZ()) > previous.radius()) {
                                this.enqueueChunk(world, chunkX, chunkZ);
                            }
                        }

                        this.playerScanStates.put(playerId, current);
                    }
                } else {
                    this.playerScanStates.remove(playerId);
                }
            } else {
                this.playerScanStates.remove(playerId);
            }
        }

        this.playerScanStates.keySet().retainAll(online);
        this.preTransitionRateMultiplier = (double)1.0F + maxPreTransition * (double)2.0F;
    }

    private List<Offset> offsetsForRadius(int radius) {
        return (List)this.offsetsByRadius.computeIfAbsent(radius, (value) -> {
            List<Offset> offsets = new ArrayList((value * 2 + 1) * (value * 2 + 1));

            for(int dx = -value; dx <= value; ++dx) {
                for(int dz = -value; dz <= value; ++dz) {
                    int distance = Math.max(Math.abs(dx), Math.abs(dz));
                    offsets.add(new Offset(dx, dz, distance, (double)0.0F));
                }
            }

            offsets.sort(Comparator.comparingInt((Offset offset) -> offset.dist).thenComparingInt((Offset offset) -> offset.dx * offset.dx + offset.dz * offset.dz));
            return List.copyOf(offsets);
        });
    }

    private long visualStamp(CalendarState state) {
        long stamp = (long)state.year << 32 ^ (long)state.day << 16;
        stamp ^= (long)state.season.ordinal() << 8;
        if (state.monthsEnabled) {
            stamp ^= (long)state.monthIndex + 1L;
        }

        return stamp;
    }

    private void enqueueChunk(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            ChunkWork work = new ChunkWork(world.getUID(), chunkX, chunkZ);
            if (this.queuedChunks.add(work)) {
                this.pendingChunks.addLast(work);
            }

        }
    }

    private void enqueueClientRefresh(World world, int chunkX, int chunkZ) {
        ChunkWork work = new ChunkWork(world.getUID(), chunkX, chunkZ);
        if (this.queuedClientRefreshes.add(work)) {
            this.clientRefreshQueue.addLast(work);
        }

    }

    private void flushClientRefreshQueue() {
        ++this.clientRefreshTickCounter;
        if (this.clientRefreshTickCounter >= this.clientRefreshIntervalTicks) {
            this.clientRefreshTickCounter = 0;
            boolean useBiomePackets = this.clientUseBiomePackets && this.biomePacketSender.isSupported();
            int allowance = useBiomePackets ? this.clientRefreshChunksPerInterval : this.clientFullRefreshChunksPerInterval;
            List<ChunkWork> batch = new ArrayList(allowance);
            int examined = 0;

            while(batch.size() < allowance && examined++ < Math.max(64, allowance * 4) && !this.clientRefreshQueue.isEmpty()) {
                ChunkWork work = (ChunkWork)this.clientRefreshQueue.pollFirst();
                if (this.queuedClientRefreshes.contains(work)) {
                    World world = Bukkit.getWorld(work.worldId());
                    if (world != null && world.isChunkLoaded(work.chunkX(), work.chunkZ()) && this.hasClientViewer(world, work.chunkX(), work.chunkZ())) {
                        batch.add(work);
                    } else {
                        this.queuedClientRefreshes.remove(work);
                    }
                }
            }

            if (!batch.isEmpty()) {
                if (useBiomePackets && this.sendBiomePacketBatch(batch)) {
                    this.queuedClientRefreshes.removeAll(batch);
                } else {
                    int refreshed = 0;
                    int firstUnsent = batch.size();

                    for(int i = 0; i < batch.size(); ++i) {
                        ChunkWork work = (ChunkWork)batch.get(i);
                        if (refreshed >= this.clientFullRefreshChunksPerInterval) {
                            firstUnsent = i;
                            break;
                        }

                        World world = Bukkit.getWorld(work.worldId());
                        if (world != null && world.isChunkLoaded(work.chunkX(), work.chunkZ())) {
                            world.refreshChunk(work.chunkX(), work.chunkZ());
                            if (this.clientRefreshNudge) {
                                this.nudgeViewers(world, work.chunkX(), work.chunkZ());
                            }

                            ++refreshed;
                        }

                        this.queuedClientRefreshes.remove(work);
                    }

                    for(int i = batch.size() - 1; i >= firstUnsent; --i) {
                        this.clientRefreshQueue.addFirst((ChunkWork)batch.get(i));
                    }

                }
            }
        }
    }

    private boolean sendBiomePacketBatch(List<ChunkWork> batch) {
        Map<UUID, List<Chunk>> chunksByWorld = new LinkedHashMap();

        for(ChunkWork work : batch) {
            World world = Bukkit.getWorld(work.worldId());
            if (world != null && world.isChunkLoaded(work.chunkX(), work.chunkZ())) {
                ((List)chunksByWorld.computeIfAbsent(work.worldId(), (ignored) -> new ArrayList())).add(world.getChunkAt(work.chunkX(), work.chunkZ()));
            }
        }

        for(Map.Entry<UUID, List<Chunk>> entry : chunksByWorld.entrySet()) {
            World world = Bukkit.getWorld((UUID)entry.getKey());
            if (world != null && !this.biomePacketSender.send(world, (List)entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    private boolean hasClientViewer(World world, int chunkX, int chunkZ) {
        int view = Bukkit.getViewDistance() + 1;

        for(Player player : world.getPlayers()) {
            Location location = player.getLocation();
            int playerChunkX = location.getBlockX() >> 4;
            int playerChunkZ = location.getBlockZ() >> 4;
            if (Math.abs(playerChunkX - chunkX) <= view && Math.abs(playerChunkZ - chunkZ) <= view) {
                return true;
            }
        }

        return false;
    }

    private boolean isInsideAnyActivePlayerArea(Chunk chunk) {
        int radius = Math.max(this.radiusChunksCfg, Bukkit.getViewDistance() + 1);

        for(Player player : chunk.getWorld().getPlayers()) {
            Location location = player.getLocation();
            int centerX = location.getBlockX() >> 4;
            int centerZ = location.getBlockZ() >> 4;
            if (Math.abs(chunk.getX() - centerX) <= radius && Math.abs(chunk.getZ() - centerZ) <= radius) {
                return true;
            }
        }

        return false;
    }

    private boolean isWorldDisabled(World world) {
        return this.disabledWorldNames.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private void processQueuedChunk(ChunkWork work) {
        World world = Bukkit.getWorld(work.worldId());
        if (world != null && world.getEnvironment() == Environment.NORMAL && !this.isWorldDisabled(world) && world.isChunkLoaded(work.chunkX(), work.chunkZ())) {
            CalendarChannel channel = this.seasons.resolveChannel(world);
            if (channel != null && this.seasons.isChannelEnabled(channel)) {
                boolean permanentWinter = this.seasons.isPermanentWinterWorld(world);
                CalendarState state = this.seasons.getStateCopy(world);
                Season season = state.season;
                int dayInPeriod = this.computeDayInPeriod(channel, state);
                double preTransitionFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, state, dayInPeriod) : (double)0.0F;
                Season nextSeason = permanentWinter ? Season.WINTER : this.resolveNextVisualSeason(channel, state);
                Biome currentTarget = (Biome)this.seasonTarget.getOrDefault(season, Biome.PLAINS);
                Biome nextTarget = (Biome)this.seasonTarget.getOrDefault(nextSeason, currentTarget);
                Biome currentOceanTarget = (Biome)this.oceanTarget.getOrDefault(season, Biome.OCEAN);
                Biome nextOceanTarget = (Biome)this.oceanTarget.getOrDefault(nextSeason, currentOceanTarget);
                Chunk chunk = world.getChunkAt(work.chunkX(), work.chunkZ());
                long chunkKey = this.key(chunk);
                if (this.ensureOriginalBackupLoaded(work, world, chunkKey)) {
                    if (this.isChunkExcludedByConfig(chunk)) {
                        if (this.spoofed.remove(chunkKey)) {
                            this.revertChunk(chunk, false);
                            this.enqueueClientRefresh(world, work.chunkX(), work.chunkZ());
                        }

                        this.lastApplied.remove(chunkKey);
                        this.familyCache.remove(chunkKey);
                    } else {
                        Family family = (Family)this.familyCache.computeIfAbsent(chunkKey, (ignored) -> this.classifyOriginalFamily(chunk));
                        Biome target = this.chooseTargetBiomeForChunk(chunkKey, family, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, chunk, season, nextSeason);
                        if (!this.shouldSkipSpoofForChunk(chunk, season)) {
                            AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(chunkKey);
                            if (last == null || !last.matches(target, season)) {
                                BiomeApplyResult result = this.captureAndApply(chunk, target, season);
                                if (!result.successful()) {
                                    this.lastApplied.remove(chunkKey);
                                } else {
                                    if (result.original() != null && !this.backups.containsKey(chunkKey)) {
                                        this.backups.put(chunkKey, result.original());
                                    }

                                    this.rememberModifiedCells(chunkKey, result);
                                    this.spoofed.add(chunkKey);
                                    this.lastApplied.put(chunkKey, new AppliedBiomeState(target, season));
                                    if (result.changed()) {
                                        this.enqueueClientRefresh(world, work.chunkX(), work.chunkZ());
                                    }

                                }
                            }
                        } else {
                            AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(chunkKey);
                            boolean alreadyValidated = last != null && last.matches(target, season);
                            boolean wasSpoofed = this.spoofed.remove(chunkKey);
                            if (!alreadyValidated) {
                                boolean restored = this.revertChunk(chunk, false);
                                this.lastApplied.put(chunkKey, new AppliedBiomeState(target, season));
                                if (restored || wasSpoofed) {
                                    this.enqueueClientRefresh(world, work.chunkX(), work.chunkZ());
                                }
                            } else if (wasSpoofed) {
                                this.enqueueClientRefresh(world, work.chunkX(), work.chunkZ());
                            }

                            this.familyCache.remove(chunkKey);
                        }
                    }
                }
            }
        }
    }

    private boolean ensureOriginalBackupLoaded(ChunkWork work, World world, long chunkKey) {
        if (!this.backups.containsKey(chunkKey) && !this.backupLookupsCompleted.contains(work)) {
            if (!this.backupLookupsPending.add(work)) {
                return false;
            } else {
                this.diskBackups.loadOriginalGridAsync(world, work.chunkX(), work.chunkZ(), 4, 4, (original) -> {
                    if (this.backupLookupsPending.remove(work)) {
                        World loadedWorld = Bukkit.getWorld(work.worldId());
                        if (loadedWorld != null && loadedWorld.isChunkLoaded(work.chunkX(), work.chunkZ())) {
                            if (original != null && original.length > 0) {
                                this.backups.putIfAbsent(chunkKey, original);
                            }

                            this.backupLookupsCompleted.add(work);
                            this.enqueueChunk(loadedWorld, work.chunkX(), work.chunkZ());
                        }
                    }
                });
                return false;
            }
        } else {
            return true;
        }
    }

    public void run() {
        this.runOptimized();
    }

    private void runLegacy() {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            long now = System.currentTimeMillis();
            int baseBudget = this.budgetPerTick;
            int budget = baseBudget;
            if (baseBudget > 0) {
                for(Player p : Bukkit.getOnlinePlayers()) {
                    if (budget <= 0) {
                        break;
                    }

                    World w = p.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL) {
                        List<String> disabled = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
                        if (disabled == null || !disabled.stream().anyMatch((s) -> s != null && s.equalsIgnoreCase(w.getName()))) {
                            CalendarChannel channel = this.seasons.resolveChannel(w);
                            if (channel != null && this.seasons.isChannelEnabled(channel)) {
                                boolean permanentWinter = this.seasons.isPermanentWinterWorld(w);
                                CalendarState st = this.seasons.getStateCopy(w);
                                Season season = st.season;
                                int dayInPeriod = this.computeDayInPeriod(channel, st);
                                double preTransitionFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, st, dayInPeriod) : (double)0.0F;
                                Season nextVisualSeason = permanentWinter ? Season.WINTER : this.resolveNextVisualSeason(channel, st);
                                Biome currentTarget = (Biome)this.seasonTarget.getOrDefault(season, Biome.PLAINS);
                                Biome nextTarget = (Biome)this.seasonTarget.getOrDefault(nextVisualSeason, currentTarget);
                                Biome currentOceanTarget = (Biome)this.oceanTarget.getOrDefault(season, Biome.OCEAN);
                                Biome nextOceanTarget = (Biome)this.oceanTarget.getOrDefault(nextVisualSeason, currentOceanTarget);
                                int effectiveBudget = baseBudget;
                                if (now < this.seasonTransitionUntil) {
                                    effectiveBudget = Math.max(1, baseBudget * 3);
                                } else if (preTransitionFactor > (double)0.0F) {
                                    double extra = (double)1.0F + preTransitionFactor * (double)2.0F;
                                    effectiveBudget = (int)Math.max(1L, Math.round((double)baseBudget * extra));
                                }

                                budget = Math.min(budget, effectiveBudget);
                                int view = Bukkit.getViewDistance();
                                int radius = Math.max(this.radiusChunksCfg, view + 1);
                                Location loc = p.getLocation();
                                int pcx = loc.getBlockX() >> 4;
                                int pcz = loc.getBlockZ() >> 4;
                                Vector look = loc.getDirection().clone();
                                look.setY(0);
                                if (look.lengthSquared() < 1.0E-4) {
                                    look = new Vector(0, 0, 1);
                                } else {
                                    look.normalize();
                                }

                                if (w.isChunkLoaded(pcx, pcz) && budget > 0) {
                                    Chunk center = w.getChunkAt(pcx, pcz);
                                    long ck = this.key(center);
                                    boolean centerExcluded = this.isChunkExcludedByConfig(center);
                                    if (centerExcluded) {
                                        if (this.spoofed.remove(ck)) {
                                            this.revertChunk(center);
                                        }

                                        this.lastApplied.remove(ck);
                                        this.familyCache.remove(ck);
                                    } else {
                                        Family fam = (Family)this.familyCache.computeIfAbsent(ck, (kk) -> this.classifyOriginalFamily(center));
                                        Biome chunkTarget = this.chooseTargetBiomeForChunk(ck, fam, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, center, season, nextVisualSeason);
                                        if (this.shouldSkipSpoofForChunk(center, season)) {
                                            AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(ck);
                                            boolean alreadyValidated = last != null && last.matches(chunkTarget, season);
                                            this.spoofed.remove(ck);
                                            if (!alreadyValidated) {
                                                this.revertChunk(center);
                                                this.lastApplied.put(ck, new AppliedBiomeState(chunkTarget, season));
                                            }

                                            this.familyCache.remove(ck);
                                        } else {
                                            AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(ck);
                                            if (last == null || !last.matches(chunkTarget, season)) {
                                                if (last == null && this.isChunkAtTarget(center, chunkTarget)) {
                                                    this.lastApplied.put(ck, new AppliedBiomeState(chunkTarget, season));
                                                } else {
                                                    BiomeApplyResult apply = this.captureAndApply(center, chunkTarget, season);
                                                    if (!apply.successful()) {
                                                        this.lastApplied.remove(ck);
                                                        continue;
                                                    }

                                                    if (apply.original() != null && !this.backups.containsKey(ck)) {
                                                        this.backups.put(ck, apply.original());
                                                    }

                                                    this.rememberModifiedCells(ck, apply);
                                                    this.spoofed.add(ck);
                                                    this.lastApplied.put(ck, new AppliedBiomeState(chunkTarget, season));
                                                    this.nudgeViewers(w, pcx, pcz);
                                                    --budget;
                                                }
                                            }
                                        }
                                    }
                                }

                                if (budget <= 0) {
                                    break;
                                }

                                List<Offset> offsets = new ArrayList();

                                for(int dx = -radius; dx <= radius; ++dx) {
                                    for(int dz = -radius; dz <= radius; ++dz) {
                                        if (dx != 0 || dz != 0) {
                                            int dist = Math.max(Math.abs(dx), Math.abs(dz));
                                            Vector dir = new Vector(dx, 0, dz);
                                            double forwardScore;
                                            if (dir.lengthSquared() < 1.0E-4) {
                                                forwardScore = (double)0.0F;
                                            } else {
                                                dir.normalize();
                                                forwardScore = look.dot(dir);
                                            }

                                            offsets.add(new Offset(dx, dz, dist, forwardScore));
                                        }
                                    }
                                }

                                offsets.sort(Comparator.comparingInt((Offset o) -> o.dist).thenComparingDouble((Offset o) -> -o.forwardScore));

                                for(Offset off : offsets) {
                                    if (budget <= 0) {
                                        break;
                                    }

                                    int cx = pcx + off.dx;
                                    int cz = pcz + off.dz;
                                    if (w.isChunkLoaded(cx, cz)) {
                                        Chunk ch = w.getChunkAt(cx, cz);
                                        long k = this.key(ch);
                                        if (this.isChunkExcludedByConfig(ch)) {
                                            if (this.spoofed.remove(k)) {
                                                this.revertChunk(ch);
                                            }

                                            this.lastApplied.remove(k);
                                            this.familyCache.remove(k);
                                        } else {
                                            Family fam = (Family)this.familyCache.computeIfAbsent(k, (kk) -> this.classifyOriginalFamily(ch));
                                            Biome chunkTarget = this.chooseTargetBiomeForChunk(k, fam, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, ch, season, nextVisualSeason);
                                            if (!this.shouldSkipSpoofForChunk(ch, season)) {
                                                AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(k);
                                                if (last == null || !last.matches(chunkTarget, season)) {
                                                    if (last == null && this.isChunkAtTarget(ch, chunkTarget)) {
                                                        this.lastApplied.put(k, new AppliedBiomeState(chunkTarget, season));
                                                    } else {
                                                        BiomeApplyResult apply = this.captureAndApply(ch, chunkTarget, season);
                                                        if (!apply.successful()) {
                                                            this.lastApplied.remove(k);
                                                        } else {
                                                            if (apply.original() != null && !this.backups.containsKey(k)) {
                                                                this.backups.put(k, apply.original());
                                                            }

                                                            this.rememberModifiedCells(k, apply);
                                                            this.spoofed.add(k);
                                                            this.lastApplied.put(k, new AppliedBiomeState(chunkTarget, season));
                                                            this.nudgeViewers(w, cx, cz);
                                                            --budget;
                                                        }
                                                    }
                                                }
                                            } else {
                                                AppliedBiomeState last = (AppliedBiomeState)this.lastApplied.get(k);
                                                boolean alreadyValidated = last != null && last.matches(chunkTarget, season);
                                                this.spoofed.remove(k);
                                                if (!alreadyValidated) {
                                                    this.revertChunk(ch);
                                                    this.lastApplied.put(k, new AppliedBiomeState(chunkTarget, season));
                                                }

                                                this.familyCache.remove(k);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                this.flushNudges();
            }
        }
    }

    private boolean isChunkExcludedByConfig(Chunk ch) {
        if (this.excludedBiomes.isEmpty()) {
            return false;
        } else {
            long k = this.key(ch);
            Boolean cached = (Boolean)this.excludedCache.get(k);
            if (cached != null) {
                return cached;
            } else {
                boolean result = true;
                boolean foundBiome = false;
                Biome[] old = (Biome[])this.backups.get(k);
                if (old != null && old.length > 0) {
                    for(Biome b : old) {
                        if (b != null) {
                            foundBiome = true;
                            if (!this.shouldProtectBiomeFromSpoof(b)) {
                                result = false;
                                break;
                            }
                        }
                    }

                    result = foundBiome && result;
                    this.excludedCache.put(k, result);
                    return result;
                } else {
                    World w = ch.getWorld();
                    int bx = ch.getX() << 4;
                    int bz = ch.getZ() << 4;
                    int minY = w.getMinHeight();
                    int maxY = w.getMaxHeight();

                    for(int x = 0; x < 16; x += 8) {
                        for(int z = 0; z < 16; z += 8) {
                            for(int y = minY; y < maxY; y += 32) {
                                Biome b = w.getBiome(bx + x, y, bz + z);
                                if (b != null) {
                                    foundBiome = true;
                                    if (!this.shouldProtectBiomeFromSpoof(b)) {
                                        result = false;
                                    }
                                }
                            }
                        }
                    }

                    result = foundBiome && result;
                    this.excludedCache.put(k, result);
                    return result;
                }
            }
        }
    }

    private boolean shouldProtectBiomeFromSpoof(Biome biome) {
        if (biome == null) {
            return false;
        } else {
            return this.excludedBiomes.contains(biome) || this.protectedEcologyBiomes.contains(biome);
        }
    }

    private boolean isRiverBiome(Biome biome) {
        if (biome == null) {
            return false;
        } else {
            String name = biome.name();
            return name.equals("RIVER") || name.equals("FROZEN_RIVER");
        }
    }

    private boolean isWaterColorSensitiveBiome(Biome biome) {
        String name = biome.name();
        return name.equals("SWAMP") || name.equals("MANGROVE_SWAMP") || name.equals("RIVER") || name.equals("FROZEN_RIVER");
    }

    private Biome getRepresentativeOriginalOceanBiome(Chunk ch) {
        long k = this.key(ch);
        Biome[] old = (Biome[])this.backups.get(k);
        if (old != null && old.length > 0) {
            for(Biome b : old) {
                if (this.isOceanBiome(b)) {
                    return b;
                }
            }

            return old[0];
        } else {
            return this.getRepresentativeOriginalBiome(ch);
        }
    }

    private Biome chooseTargetFor(Season season, Biome original) {
        Biome global = (Biome)this.seasonTarget.get(season);
        return global == null ? original : global;
    }

    private boolean isChunkAtTarget(Chunk ch, Biome target) {
        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        for(int x = 0; x < 16; x += 8) {
            for(int z = 0; z < 16; z += 8) {
                for(int y = minY; y < maxY; y += 32) {
                    if (w.getBiome(bx + x, y, bz + z) != target) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean isColdBiome(Biome biome) {
        if (biome == Biome.CHERRY_GROVE) {
            return false;
        } else {
            String name = biome.name();
            return name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE") || name.equals("GROVE") || name.contains("SNOWY_TAIGA") || name.contains("PEAK") || name.contains("MOUNTAIN");
        }
    }

    private boolean isOceanBiome(Biome b) {
        return b.name().contains("OCEAN");
    }

    private boolean isDeepOcean(Biome b) {
        return b.name().contains("DEEP_") && this.isOceanBiome(b);
    }

    private boolean isShoreBiome(Biome b) {
        String n = b.name();
        return n.contains("BEACH") || n.contains("SHORE");
    }

    private Family classifyOriginalFamily(Chunk ch) {
        long k = this.key(ch);
        Biome[] old = (Biome[])this.backups.get(k);
        if (old != null && old.length > 0) {
            this.cacheOriginalTemperature(k, Arrays.asList(old));
            return this.classifyFamilyFromSamples(Arrays.asList(old));
        } else {
            World w = ch.getWorld();
            int bx = ch.getX() << 4;
            int bz = ch.getZ() << 4;
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();
            List<Biome> samples = new ArrayList(32);

            for(int x = 0; x < 16; x += 8) {
                for(int z = 0; z < 16; z += 8) {
                    for(int y = minY; y < maxY; y += 32) {
                        samples.add(w.getBiome(bx + x, y, bz + z));
                    }
                }
            }

            this.cacheOriginalTemperature(k, samples);
            return this.classifyFamilyFromSamples(samples);
        }
    }

    private void cacheOriginalTemperature(long chunkKey, Iterable<Biome> samples) {
        for(Biome biome : samples) {
            if (this.isColdBiome(biome)) {
                COLD_CHUNKS.add(chunkKey);
                this.warmChunks.remove(chunkKey);
                return;
            }
        }

        this.warmChunks.add(chunkKey);
    }

    private Family classifyFamilyFromSamples(Iterable<Biome> samples) {
        int oceanCount = 0;
        int shoreCount = 0;
        int landCount = 0;

        for(Biome b : samples) {
            if (b != null) {
                if (this.oceansEnabled && this.isOceanBiome(b)) {
                    ++oceanCount;
                } else if (this.oceansEnabled && this.oceansAffectShores && this.isShoreBiome(b)) {
                    ++shoreCount;
                } else {
                    ++landCount;
                }
            }
        }

        int oceanish = oceanCount + shoreCount;
        if (this.oceansEnabled && oceanish > 0) {
            if (oceanCount > 0 && oceanish > landCount) {
                return BiomeSpoofAdapter.Family.OCEAN;
            }

            if (oceanCount >= 2 && oceanish >= landCount) {
                return BiomeSpoofAdapter.Family.OCEAN;
            }
        }

        return BiomeSpoofAdapter.Family.LAND;
    }

    private Biome getRepresentativeOriginalBiome(Chunk ch) {
        long k = this.key(ch);
        Biome[] old = (Biome[])this.backups.get(k);
        if (old != null && old.length > 0) {
            return old[0];
        } else {
            World w = ch.getWorld();
            int bx = ch.getX() << 4;
            int bz = ch.getZ() << 4;
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();

            for(int x = 0; x < 16; x += 8) {
                for(int z = 0; z < 16; z += 8) {
                    if (minY < maxY) {
                        return w.getBiome(bx + x, minY, bz + z);
                    }
                }
            }

            return Biome.OCEAN;
        }
    }

    private Biome applyOceanVariant(Biome baseTarget, Biome originalOcean) {
        if (!this.oceansKeepDeepVariants) {
            return baseTarget;
        } else {
            boolean origDeep = this.isDeepOcean(originalOcean);
            String baseName = baseTarget.name();
            if (origDeep) {
                if (baseName.startsWith("DEEP_")) {
                    return baseTarget;
                } else {
                    try {
                        return Biome.valueOf("DEEP_" + baseName);
                    } catch (IllegalArgumentException var7) {
                        return baseTarget;
                    }
                }
            } else if (baseName.startsWith("DEEP_")) {
                String shallow = baseName.substring("DEEP_".length());

                try {
                    return Biome.valueOf(shallow);
                } catch (IllegalArgumentException var8) {
                    return baseTarget;
                }
            } else {
                return baseTarget;
            }
        }
    }

    private Biome chooseTargetBiomeForChunk(long chunkKey, Family family, Biome currentLandTarget, Biome nextLandTarget, Biome currentOceanTarget, Biome nextOceanTarget, double preTransitionFactor, Chunk ch, Season currentSeason, Season nextSeason) {
        if (family == BiomeSpoofAdapter.Family.OCEAN && this.oceansEnabled) {
            Biome origOcean = this.getRepresentativeOriginalOceanBiome(ch);
            if (currentSeason == Season.WINTER && this.oceansWinterForceSnow) {
                Biome safeWinterTarget = this.isOceanBiome(this.oceansWinterForceSnowBiome) ? this.oceansWinterForceSnowBiome : (Biome)this.oceanTarget.getOrDefault(Season.WINTER, Biome.FROZEN_OCEAN);
                return this.isOceanBiome(origOcean) ? this.applyOceanVariant(safeWinterTarget, origOcean) : safeWinterTarget;
            } else {
                double oceanPreTransitionFactor = this.preTransitionAffectOceans ? preTransitionFactor : (double)0.0F;
                Biome base = this.chooseTargetBiomeForChunk(chunkKey, currentOceanTarget, nextOceanTarget, oceanPreTransitionFactor);
                return this.isOceanBiome(origOcean) ? this.applyOceanVariant(base, origOcean) : base;
            }
        } else {
            Biome orig = this.getRepresentativeOriginalBiome(ch);
            if (currentLandTarget == orig && nextLandTarget == orig) {
                return orig;
            } else {
                return currentLandTarget == nextLandTarget ? currentLandTarget : this.chooseTargetBiomeForChunk(chunkKey, currentLandTarget, nextLandTarget, preTransitionFactor);
            }
        }
    }

    private BiomeApplyResult captureAndApply(Chunk ch, Biome target, Season season) {
        try {
            World w = ch.getWorld();
            int bx = ch.getX() << 4;
            int bz = ch.getZ() << 4;
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();
            int seaLevel = w.getSeaLevel();
            long k = this.key(ch);
            Biome[] existing = (Biome[])this.backups.get(k);
            List<Biome> prevs = existing == null ? new ArrayList() : null;
            boolean anyChange = false;
            BitSet modifiedCells = new BitSet();
            int sampleIndex = 0;

            for(int x = 0; x < 16; x += 4) {
                for(int z = 0; z < 16; z += 4) {
                    int wx = bx + x;
                    int wz = bz + z;
                    boolean waterColumn = this.protectWaterColorBiomes && this.isWaterSurfaceColumn(w, wx, wz);

                    for(int y = minY; y < maxY; y += 4) {
                        if (prevs == null) {
                            if (sampleIndex >= existing.length) {
                                break;
                            }

                            int gridIndex = sampleIndex;
                            Biome original = existing[sampleIndex++];
                            Biome desired = this.resolveDesiredBiome(original, target, season, waterColumn, y, seaLevel);
                            if (desired != original) {
                                modifiedCells.set(gridIndex);
                            }

                            Biome current = w.getBiome(wx, y, wz);
                            if (current != desired) {
                                w.setBiome(wx, y, wz, desired);
                                anyChange = true;
                            }
                        } else {
                            Biome current = w.getBiome(wx, y, wz);
                            prevs.add(current);
                            int gridIndex = sampleIndex++;
                            Biome desired = this.resolveDesiredBiome(current, target, season, waterColumn, y, seaLevel);
                            if (desired != current) {
                                modifiedCells.set(gridIndex);
                            }

                            if (current != desired) {
                                anyChange = true;
                                w.setBiome(wx, y, wz, desired);
                            }
                        }
                    }
                }
            }

            if (prevs != null) {
                boolean cold = false;
                boolean fullyExcluded = true;
                boolean foundBiome = false;

                for(Biome b : prevs) {
                    if (b != null) {
                        foundBiome = true;
                        if (this.isColdBiome(b)) {
                            cold = true;
                        }

                        if (!this.shouldProtectBiomeFromSpoof(b)) {
                            fullyExcluded = false;
                        }
                    }
                }

                if (cold) {
                    COLD_CHUNKS.add(k);
                    this.warmChunks.remove(k);
                } else {
                    this.warmChunks.add(k);
                }

                this.excludedCache.put(k, foundBiome && fullyExcluded);
                this.familyCache.put(k, this.classifyFamilyFromSamples(prevs));
            }

            if (prevs != null) {
                Biome[] arr = (Biome[])prevs.toArray(new Biome[0]);
                if (this.diskBackupEnabled) {
                    this.diskBackups.saveFirstTouch(ch, arr, 4, 4);
                }

                return new BiomeApplyResult(arr, modifiedCells, anyChange, true);
            } else {
                return new BiomeApplyResult((Biome[])null, modifiedCells, anyChange, true);
            }
        } catch (Throwable t) {
            Logger var10000 = this.plugin.getLogger();
            int var10001 = ch.getX();
            var10000.warning("[BiomeSpoof] spoof error " + var10001 + "," + ch.getZ() + ": " + t.getMessage());
            return new BiomeApplyResult((Biome[])null, (BitSet)null, false, false);
        }
    }

    private void rememberModifiedCells(long chunkKey, BiomeApplyResult result) {
        BitSet modifiedCells = result.modifiedCells();
        if (modifiedCells != null) {
            this.modifiedBiomeCells.put(chunkKey, (BitSet)modifiedCells.clone());
        }

    }

    private boolean shouldPreserveWaterColor(Biome original, boolean waterColumn) {
        return this.protectWaterColorBiomes && waterColumn && this.isWaterColorSensitiveBiome(original);
    }

    private Biome resolveDesiredBiome(Biome original, Biome chunkTarget, Season season, boolean waterColumn, int sampleY, int seaLevel) {
        if (original == null) {
            return chunkTarget;
        } else if (this.isRiverBiome(original)) {
            return original;
        } else if (this.shouldProtectBiomeFromSpoof(original)) {
            return original;
        } else if (this.plugin.cfg.climate.getBoolean("real_snow.enabled", true) || season != Season.WINTER && chunkTarget != this.seasonTarget.get(Season.WINTER)) {
            if (season == Season.WINTER) {
                if (this.isShoreBiome(original)) {
                    return Biome.SNOWY_BEACH;
                }

                if (this.isOceanBiome(original)) {
                    if (this.oceansEnabled && this.oceansWinterForceSnow) {
                        return sampleY >= seaLevel ? Biome.SNOWY_PLAINS : Biome.FROZEN_OCEAN;
                    }

                    return original;
                }
            }

            return this.shouldPreserveWaterColor(original, waterColumn) ? original : chunkTarget;
        } else {
            return original;
        }
    }

    private boolean isWaterSurfaceColumn(World world, int x, int z) {
        int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int minY = world.getMinHeight();
        int depth = 0;

        while(true) {
            if (depth < 4 && surfaceY - depth >= minY) {
                Block block = world.getBlockAt(x, surfaceY - depth, z);
                Material type = block.getType();
                if (!this.isWaterLike(block) && type != Material.ICE && type != Material.FROSTED_ICE && type != Material.KELP && type != Material.KELP_PLANT && type != Material.SEAGRASS && type != Material.TALL_SEAGRASS) {
                    if (type.isSolid() && type != Material.LILY_PAD) {
                        return false;
                    }

                    ++depth;
                    continue;
                }

                return true;
            }

            return false;
        }
    }

    private void revertChunk(Chunk ch) {
        this.revertChunk(ch, true);
    }

    private boolean revertChunk(Chunk ch, boolean refreshClientImmediately) {
        long k = this.key(ch);
        Biome[] old = (Biome[])this.backups.get(k);
        if (old == null) {
            this.modifiedBiomeCells.remove(k);
            this.lastApplied.remove(k);
            this.familyCache.remove(k);
            return false;
        } else {
            BitSet modifiedCells = (BitSet)this.modifiedBiomeCells.get(k);
            boolean restoreAll = modifiedCells == null;
            boolean changed = false;
            boolean completed = false;

            try {
                World w = ch.getWorld();
                int bx = ch.getX() << 4;
                int bz = ch.getZ() << 4;
                int minY = w.getMinHeight();
                int maxY = w.getMaxHeight();
                int i = 0;

                label65:
                for(int x = 0; x < 16; x += 4) {
                    for(int z = 0; z < 16; z += 4) {
                        for(int y = minY; y < maxY; y += 4) {
                            if (i >= old.length) {
                                break label65;
                            }

                            Biome original = old[i];
                            if (restoreAll || modifiedCells.get(i)) {
                                w.setBiome(bx + x, y, bz + z, original);
                                changed = true;
                            }

                            ++i;
                        }
                    }
                }

                completed = true;
                if (changed && refreshClientImmediately) {
                    w.refreshChunk(ch.getX(), ch.getZ());
                    if (this.clientRefreshNudge) {
                        this.nudgeViewers(w, ch.getX(), ch.getZ());
                    }
                }
            } catch (Throwable t) {
                Logger var10000 = this.plugin.getLogger();
                int var10001 = ch.getX();
                var10000.warning("[BiomeSpoof] revert error " + var10001 + "," + ch.getZ() + ": " + t.getMessage());
            }

            if (completed) {
                this.modifiedBiomeCells.remove(k);
            }

            this.lastApplied.remove(k);
            this.familyCache.remove(k);
            return changed;
        }
    }

    private long key(Chunk ch) {
        return this.key(ch.getWorld(), ch.getX(), ch.getZ());
    }

    private long key(World w, int cx, int cz) {
        long k = ((long)cx & 4294967295L) << 32 | (long)cz & 4294967295L;
        return k ^ w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits();
    }

    public static boolean isChunkNaturallySnowy(World w, int cx, int cz) {
        long k = ((long)cx & 4294967295L) << 32 | (long)cz & 4294967295L;
        k ^= w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits();
        return COLD_CHUNKS.contains(k);
    }

    private void revertAll() {
        for(World w : Bukkit.getWorlds()) {
            for(Chunk ch : w.getLoadedChunks()) {
                long k = this.key(ch);
                if (this.spoofed.contains(k)) {
                    this.revertChunk(ch);
                }
            }
        }

        this.spoofed.clear();
        this.backups.clear();
        this.modifiedBiomeCells.clear();
        this.nudgeQueue.clear();
        this.nudgeLast.clear();
        this.lastApplied.clear();
        this.familyCache.clear();
        this.excludedCache.clear();
    }

    private boolean shouldSkipSpoofForChunk(Chunk ch, Season currentSeason) {
        if (!this.respectNaturallySnowyBiomes) {
            return false;
        } else if (currentSeason == Season.WINTER) {
            return false;
        } else {
            long k = this.key(ch);
            if (COLD_CHUNKS.contains(k)) {
                return true;
            } else if (this.warmChunks.contains(k)) {
                return false;
            } else if (this.backups.containsKey(k)) {
                return false;
            } else {
                World w = ch.getWorld();
                int bx = ch.getX() << 4;
                int bz = ch.getZ() << 4;
                int minY = w.getMinHeight();
                int maxY = w.getMaxHeight();

                for(int x = 0; x < 16; x += 4) {
                    for(int z = 0; z < 16; z += 4) {
                        for(int y = minY; y < maxY; y += 32) {
                            Biome b = w.getBiome(bx + x, y, bz + z);
                            if (this.isColdBiome(b)) {
                                COLD_CHUNKS.add(k);
                                return true;
                            }
                        }
                    }
                }

                return false;
            }
        }
    }

    private void nudgeViewers(World w, int cx, int cz) {
        for(Player viewer : w.getPlayers()) {
            int vcx = viewer.getLocation().getBlockX() >> 4;
            int vcz = viewer.getLocation().getBlockZ() >> 4;
            int view = Bukkit.getViewDistance() + 1;
            if (Math.abs(vcx - cx) <= view && Math.abs(vcz - cz) <= view) {
                this.enqueueNudge(viewer, w, cx, cz);
            }
        }

    }

    private void enqueueNudge(Player p, World w, int cx, int cz) {
        NudgeTarget target = new NudgeTarget(w.getUID(), cx, cz);
        NudgeCooldownKey cooldownKey = new NudgeCooldownKey(p.getUniqueId(), target);
        long now = System.currentTimeMillis();
        Long last = (Long)this.nudgeLast.get(cooldownKey);
        if (last == null || now - last >= 3000L) {
            this.nudgeLast.put(cooldownKey, now);
            ((ArrayDeque)this.nudgeQueue.computeIfAbsent(p.getUniqueId(), (id) -> new ArrayDeque())).add(target);
        }
    }

    private void flushNudges() {
        int sent = 0;
        Iterator<Map.Entry<UUID, ArrayDeque<NudgeTarget>>> it = this.nudgeQueue.entrySet().iterator();

        while(it.hasNext() && sent < 8) {
            Map.Entry<UUID, ArrayDeque<NudgeTarget>> e = (Map.Entry)it.next();
            UUID pid = (UUID)e.getKey();
            ArrayDeque<NudgeTarget> q = (ArrayDeque)e.getValue();
            Player p = Bukkit.getPlayer(pid);
            if (p != null && p.isOnline()) {
                if (q.isEmpty()) {
                    it.remove();
                } else {
                    NudgeTarget target = (NudgeTarget)q.poll();
                    ++sent;
                    World w = Bukkit.getWorld(target.worldId());
                    if (w != null && p.getWorld() == w && w.isChunkLoaded(target.chunkX(), target.chunkZ())) {
                        int minY = w.getMinHeight();
                        Location loc = new Location(w, (double)(target.chunkX() << 4), (double)minY, (double)(target.chunkZ() << 4));
                        BlockData fake = NUDGE_FAKE.createBlockData();
                        BlockData real = w.getBlockAt(loc).getBlockData();
                        p.sendBlockChange(loc, fake);
                        Bukkit.getScheduler().runTask(this.plugin, () -> {
                            if (p.isOnline()) {
                                p.sendBlockChange(loc, real);
                            }

                        });
                    }
                }
            } else {
                it.remove();
            }
        }

    }

    private int computeDayInPeriod(CalendarChannel channel, CalendarState st) {
        int d = st != null ? st.day : 1;
        int max;
        if (st != null && st.monthsEnabled && st.daysInMonth > 0) {
            max = st.daysInMonth;
        } else if (channel != null) {
            max = Math.max(1, this.seasons.getCurrentPeriodLength(channel));
        } else {
            max = 1;
        }

        if (d < 1) {
            d = 1;
        }

        if (d > max) {
            d = max;
        }

        return d;
    }

    private double computePreTransitionFactor(CalendarChannel channel, CalendarState st, int dayInPeriod) {
        int periodLength;
        if (st != null && st.monthsEnabled && st.daysInMonth > 0) {
            periodLength = st.daysInMonth;
        } else if (channel != null) {
            periodLength = Math.max(1, this.seasons.getCurrentPeriodLength(channel));
        } else {
            periodLength = 1;
        }

        int window = Math.min(3, periodLength);
        int start = periodLength - window + 1;
        if (dayInPeriod < start) {
            return (double)0.0F;
        } else {
            int step = dayInPeriod - start;
            double fraction = (double)0.0F;
            if (channel != null) {
                List<World> worlds = this.seasons.getWorlds(channel);
                if (!worlds.isEmpty()) {
                    long time = Math.floorMod(((World)worlds.get(0)).getTime(), 24000L);
                    fraction = Math.floor((double)time / (double)1500.0F) / (double)16.0F;
                }
            }

            double factor = ((double)step + fraction) / (double)window;
            if (factor < (double)0.0F) {
                factor = (double)0.0F;
            }

            if (factor > (double)1.0F) {
                factor = (double)1.0F;
            }

            return factor;
        }
    }

    private Season nextSeason(Season s) {
        Season var10000;
        switch (s) {
            case SPRING -> var10000 = Season.SUMMER;
            case SUMMER -> var10000 = Season.AUTUMN;
            case AUTUMN -> var10000 = Season.WINTER;
            case WINTER -> var10000 = Season.SPRING;
            default -> throw new MatchException((String)null, (Throwable)null);
        }

        return var10000;
    }

    public Biome getOriginalBiomeApprox(World w, int x, int y, int z) {
        Chunk ch = w.getChunkAt(x >> 4, z >> 4);
        Biome[] old = (Biome[])this.backups.get(this.key(ch));
        if (old != null && old.length != 0) {
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();
            int yy = Math.max(minY, Math.min(maxY - 1, y));
            int xCount = 4;
            int zCount = 4;
            int yCount = (maxY - minY) / 4;
            int lx = (x & 15) / 4;
            int lz = (z & 15) / 4;
            int ly = (yy - minY) / 4;
            int idx = (lx * zCount + lz) * yCount + ly;
            return idx >= 0 && idx < old.length ? old[idx] : old[0];
        } else {
            return w.getBiome(x, y, z);
        }
    }

    public Biome getOriginalBiomeApproxOrNull(World w, int x, int y, int z) {
        Chunk ch = w.getChunkAt(x >> 4, z >> 4);
        Biome[] old = (Biome[])this.backups.get(this.key(ch));
        if (old != null && old.length != 0) {
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();
            int yy = Math.max(minY, Math.min(maxY - 1, y));
            int xCount = 4;
            int zCount = 4;
            int yCount = (maxY - minY) / 4;
            int lx = (x & 15) / 4;
            int lz = (z & 15) / 4;
            int ly = (yy - minY) / 4;
            int idx = (lx * zCount + lz) * yCount + ly;
            return idx >= 0 && idx < old.length ? old[idx] : old[0];
        } else {
            return null;
        }
    }

    private Biome chooseTargetBiomeForChunk(long chunkKey, Biome currentTarget, Biome nextTarget, double preTransitionFactor) {
        if (nextTarget != null && currentTarget != nextTarget && !(preTransitionFactor <= (double)0.0F)) {
            if (preTransitionFactor >= (double)1.0F) {
                return nextTarget;
            } else {
                long h = chunkKey * 1103515245L + 12345L;
                h ^= h >>> 16;
                int bucket = (int)(h & 65535L);
                double threshold = preTransitionFactor * (double)65536.0F;
                return (double)bucket < threshold ? nextTarget : currentTarget;
            }
        } else {
            return currentTarget;
        }
    }

    public synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            this.mode = BiomeSpoofAdapter.Mode.GLOBAL_RING;
            if (this.task == null || this.task.isCancelled()) {
                this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::runOptimized, 40L, 1L);
            }
        } else {
            this.mode = BiomeSpoofAdapter.Mode.OFF;
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }

            this.pendingChunks.clear();
            this.queuedChunks.clear();
            this.backupLookupsPending.clear();
            this.backupLookupsCompleted.clear();
            this.clientRefreshQueue.clear();
            this.queuedClientRefreshes.clear();
            this.playerScanStates.clear();
            this.chunkCredits = (double)0.0F;
            this.clientRefreshTickCounter = 0;
        }

    }

    private Season resolveNextVisualSeason(CalendarChannel channel, CalendarState st) {
        if (st == null) {
            return Season.SPRING;
        } else if (st.monthsEnabled && channel != null && st.monthId != null && !st.monthId.isBlank()) {
            String base = "calendar.channels." + channel.name() + ".months";
            ConfigurationSection monthsSec = this.plugin.cfg.calendar.getConfigurationSection(base);
            if (monthsSec != null && monthsSec.getBoolean("enabled", false)) {
                List<String> order = monthsSec.getStringList("order");
                if (order != null && !order.isEmpty()) {
                    int idx = -1;

                    for(int i = 0; i < order.size(); ++i) {
                        String id = (String)order.get(i);
                        if (id != null && id.equalsIgnoreCase(st.monthId)) {
                            idx = i;
                            break;
                        }
                    }

                    if (idx < 0) {
                        return this.nextSeason(st.season);
                    } else {
                        String nextMonthId = (String)order.get((idx + 1) % order.size());
                        ConfigurationSection defs = monthsSec.getConfigurationSection("definitions");
                        if (defs == null) {
                            return this.nextSeason(st.season);
                        } else {
                            ConfigurationSection nextDef = defs.getConfigurationSection(nextMonthId);
                            if (nextDef == null) {
                                return this.nextSeason(st.season);
                            } else {
                                String raw = nextDef.getString("season", st.season.name());

                                try {
                                    return Season.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                                } catch (IllegalArgumentException var12) {
                                    return st.season;
                                }
                            }
                        }
                    }
                } else {
                    return this.nextSeason(st.season);
                }
            } else {
                return this.nextSeason(st.season);
            }
        } else {
            return this.nextSeason(st.season);
        }
    }

    public boolean isEnabled() {
        return this.mode != BiomeSpoofAdapter.Mode.OFF;
    }

    public boolean isDiskBackupEnabled() {
        return this.diskBackupEnabled;
    }

    public void setDiskBackupEnabled(boolean enabled) {
        this.diskBackupEnabled = enabled;
    }

    public BiomeBackupStore getDiskBackups() {
        return this.diskBackups;
    }

    static {
        WATER_REQUIRED_MOBS = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.AXOLOTL, EntityType.TADPOLE);
        CAPPED_AQUATIC_MOBS = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH, EntityType.DOLPHIN);
        COLD_CHUNKS = ConcurrentHashMap.newKeySet();
        NUDGE_FAKE = Material.BARRIER;
    }

    public static enum Mode {
        GLOBAL_RING,
        OFF;

        private Mode() {
        }

        // $FF: synthetic method
        private static Mode[] $values() {
            return new Mode[]{GLOBAL_RING, OFF};
        }
    }

    private static final class Offset {
        final int dx;
        final int dz;
        final int dist;
        final double forwardScore;

        Offset(int dx, int dz, int dist, double forwardScore) {
            super();
            this.dx = dx;
            this.dz = dz;
            this.dist = dist;
            this.forwardScore = forwardScore;
        }
    }

    private static record ChunkWork(UUID worldId, int chunkX, int chunkZ) {
        private ChunkWork {
        }
    }

    private static record AppliedBiomeState(Biome target, Season season) {
        private AppliedBiomeState {
        }

        private boolean matches(Biome expectedTarget, Season expectedSeason) {
            return this.target == expectedTarget && this.season == expectedSeason;
        }
    }

    private static record BiomeApplyResult(Biome[] original, BitSet modifiedCells, boolean changed, boolean successful) {
        private BiomeApplyResult {
        }
    }

    private static record PlayerScanState(UUID worldId, int chunkX, int chunkZ, int radius, long visualStamp) {
        private PlayerScanState {
        }
    }

    private static record NudgeTarget(UUID worldId, int chunkX, int chunkZ) {
        private NudgeTarget {
        }
    }

    private static record NudgeCooldownKey(UUID playerId, NudgeTarget target) {
        private NudgeCooldownKey {
        }
    }

    private static enum Family {
        LAND,
        OCEAN;

        private Family() {
        }

        // $FF: synthetic method
        private static Family[] $values() {
            return new Family[]{LAND, OCEAN};
        }
    }
}
