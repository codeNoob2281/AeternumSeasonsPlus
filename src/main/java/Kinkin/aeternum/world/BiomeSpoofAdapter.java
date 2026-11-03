package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.*;
import kinkin.aeternum.util.PlayerArrivalSync;
import org.bukkit.*;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 生物群系伪装适配器（BiomeSpoofAdapter）。
 *
 * <p>核心职责：在玩家周围一定半径的区块内，把服务端真实生物群系“伪装”为当前季节对应的视觉群系
 * （春季花林、夏季平原、秋季白杨/热带草原、冬季雪原，海洋另有季节配色），并在季节切换或停用时
 * 还原为原始群系。为避免破坏生态与生成，它还包含：</p>
 * <ul>
 *     <li><b>原始备份</b>：应用前用 {@link BiomeBackupStore} 记录原始群系网格，供还原使用；</li>
 *     <li><b>生态保护</b>：跳过被排除/受保护群系（河流、沼泽水色等），并据原始冷/暖属性决定是否跳过；</li>
 *     <li><b>生成守卫</b>：拦截依赖水或受群系限制的自然生物生成；</li>
 *     <li><b>客户端刷新</b>：通过 {@link BiomePacketSender} 发送群系数据包或降级 {@code refreshChunk}，
 *     并用“轻推”假方块强制客户端重算；</li>
 *     <li><b>预算调度</b>：以信用额度与时间片把工作分摊到每 tick。</li>
 * </ul>
 * 依赖 {@link SeasonService}/{@link CalendarState} 提供季节与日历状态。</p>
 */
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

    /**
     * 玩家到达/切换后重新同步其扫描范围：清理过期待处理区块并把新视野区块入队。
     */
    private void resyncPlayer(Player player) {
        this.playerScanStates.remove(player.getUniqueId());
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            World world = player.getWorld();
            CalendarChannel channel = this.seasons.resolveChannel(world);
            if (world.getEnvironment() == Environment.NORMAL && !this.plugin.isWorldDisabled(world) && channel != null && this.seasons.isChannelEnabled(channel)) {
                this.pendingChunks.removeIf((work) -> {
                    World queuedWorld = Bukkit.getWorld(work.worldId());
                    if (queuedWorld != null && this.hasClientViewer(queuedWorld, work.chunkX(), work.chunkZ())) {
                        return false;
                    } else {
                        this.queuedChunks.remove(work);
                        return true;
                    }
                });
                Location location = player.getLocation();
                int cx = location.getBlockX() >> 4;
                int cz = location.getBlockZ() >> 4;
                List<Offset> offsets = this.offsetsForRadius(Math.max(this.radiusChunksCfg, Bukkit.getViewDistance() + 1));

                for (int i = offsets.size() - 1; i >= 0; --i) {
                    Offset offset = (Offset) offsets.get(i);
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

    /**
     * 返回当前活动实例（未启用时为 null）。
     */
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
        this.preTransitionRateMultiplier = (double) 1.0F;
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

    /**
     * 从气候配置重新读取伪装开关、季节目标群系、海洋设置、生成守卫与客户端刷新参数。
     */
    private void reloadFromConfig() {
        this.seasonTarget.clear();
        this.oceanTarget.clear();
        boolean enabled = this.plugin.cfg.climate.getBoolean("biome_spoof.enabled", true);
        if (!enabled) {
            this.mode = BiomeSpoofAdapter.Mode.OFF;
        } else {
            String modeName = this.plugin.cfg.climate.getString("biome_spoof.mode", "GLOBAL_RING");
            if ("OFF".equalsIgnoreCase(modeName)) {
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

    /**
     * 读取配置中的一组群系名并写入目标集合，无法识别的项告警后忽略。
     */
    private void readBiomeSet(String path, Set<Biome> destination) {
        for (String raw : this.plugin.cfg.climate.getStringList(path)) {
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

    /**
     * 读取秋季目标群系；配置为 AUTO 时优先解析 {@code dappled_forest}，否则回退热带草原。
     */
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
            return key == null ? null : (Biome) Registry.BIOME.get(key);
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

    /**
     * 当前实际使用的秋季目标群系。
     */
    public Biome getEffectiveAutumnBiome() {
        return (Biome) this.seasonTarget.get(Season.AUTUMN);
    }

    /**
     * 秋季是否使用热带草原（旧版方案）作为目标群系。
     */
    public boolean usesSavannaPlateauForAutumn() {
        return isBiome(this.getEffectiveAutumnBiome(), "savanna_plateau");
    }

    /**
     * 秋季是否使用白杨林作为目标群系。
     */
    public boolean usesDappledForestForAutumn() {
        return isBiome(this.getEffectiveAutumnBiome(), "dappled_forest");
    }

    /**
     * 注册事件监听、玩家到达同步与主循环任务。
     */
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

    /**
     * 注销任务与监听、还原全部伪装并清理状态。
     */
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
        this.chunkCredits = (double) 0.0F;
        this.excludedCache.clear();
        if (INSTANCE == this) {
            INSTANCE = null;
        }

    }

    /**
     * 生成守卫：拦截在伪装区块中出现的、依赖水或受群系限制的自然生成生物，避免生态错乱。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            if (this.spawnGuardEnabled) {
                CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
                if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN) {
                    Location loc = event.getLocation();
                    World world = loc.getWorld();
                    if (world != null) {
                        if (this.spawnGuardOnlySpoofedChunks) {
                            long chunkKey = this.key(world, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                            if (!this.spoofed.contains(chunkKey)) {
                                return;
                            }
                        }

                        EntityType type = event.getEntityType();
                        if (CAPPED_AQUATIC_MOBS.contains(type) && this.exceedsAquaticCap(loc, type)) {
                            event.setCancelled(true);
                        } else if (WATER_REQUIRED_MOBS.contains(type)) {
                            if (!this.isInWaterLikeBlock(loc)) {
                                event.setCancelled(true);
                            }

                        } else {
                            if (type == EntityType.DROWNED) {
                                if (this.isInWaterLikeBlock(loc)) {
                                    return;
                                }

                                int nearWaterRadius = this.spawnGuardNearWaterRadius;
                                if (nearWaterRadius > 0 && this.hasWaterNearby(loc, nearWaterRadius)) {
                                    return;
                                }

                                event.setCancelled(true);
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 判断位置本身或其正下方是否为水/含水方块。
     */
    private boolean isInWaterLikeBlock(Location loc) {
        Block block = loc.getBlock();
        if (this.isWaterLike(block)) {
            return true;
        } else {
            Block below = block.getRelative(0, -1, 0);
            return this.isWaterLike(below);
        }
    }

    /**
     * 判断方块是否为水、气泡柱或含水（waterlogged）方块。
     */
    private boolean isWaterLike(Block block) {
        Material type = block.getType();
        if (type != Material.WATER && type != Material.BUBBLE_COLUMN) {
            BlockData data = block.getBlockData();
            return data instanceof Waterlogged && ((Waterlogged) data).isWaterlogged();
        } else {
            return true;
        }
    }

    /**
     * 在给定水平半径内查找附近是否存在水/含水方块（含下一层）。
     */
    private boolean hasWaterNearby(Location loc, int radius) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            int baseX = loc.getBlockX();
            int baseY = loc.getBlockY();
            int baseZ = loc.getBlockZ();

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (dx != 0 || dz != 0) {
                        Block block = world.getBlockAt(baseX + dx, baseY, baseZ + dz);
                        if (this.isWaterLike(block)) {
                            return true;
                        }

                        Block below = world.getBlockAt(baseX + dx, baseY - 1, baseZ + dz);
                        if (this.isWaterLike(below)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    /**
     * 判断指定位置附近同类水生生物数量是否已达上限。
     */
    private boolean exceedsAquaticCap(Location loc, EntityType spawningType) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            int max = this.maxAquaticAllowed(spawningType);
            if (max <= 0) {
                return false;
            } else {
                int count = 0;

                for (Entity entity : world.getNearbyEntities(loc, (double) 32.0F, (double) 16.0F, (double) 32.0F)) {
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

    /**
     * 返回某类水生生物在范围内允许的最大数量（海豚 6，其余 48）。
     */
    private int maxAquaticAllowed(EntityType type) {
        return type == EntityType.DOLPHIN ? 6 : 48;
    }

    /**
     * 区块加载时清空该区块的缓存与伪装标记，若位于活动玩家范围则重新入队处理。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkWork work = new ChunkWork(event.getChunk().getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        this.backupLookupsPending.remove(work);
        this.backupLookupsCompleted.remove(work);
        long key = this.key(event.getChunk());
        this.spoofed.remove(key);
        this.modifiedBiomeCells.remove(key);
        this.lastApplied.remove(key);
        this.familyCache.remove(key);
        this.warmChunks.remove(key);
        COLD_CHUNKS.remove(key);
        this.excludedCache.remove(key);
        if (this.mode != BiomeSpoofAdapter.Mode.OFF && this.isInsideAnyActivePlayerArea(event.getChunk())) {
            this.enqueueChunk(event.getChunk().getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        }

    }

    /**
     * 区块卸载时移除相关队列/缓存，并在需要时先还原该区块群系。
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        long key = this.key(chunk);
        ChunkWork work = new ChunkWork(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        this.queuedChunks.remove(work);
        this.queuedClientRefreshes.remove(work);
        this.backupLookupsPending.remove(work);
        this.backupLookupsCompleted.remove(work);
        if (this.spoofed.remove(key)) {
            this.revertChunk(chunk, false);
        }

        this.backups.remove(key);
        this.modifiedBiomeCells.remove(key);
        this.warmChunks.remove(key);
        COLD_CHUNKS.remove(key);
        NudgeTarget unloaded = new NudgeTarget(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());

        for (ArrayDeque<NudgeTarget> queue : this.nudgeQueue.values()) {
            queue.remove(unloaded);
        }

        this.excludedCache.remove(key);
    }

    /**
     * 季节切换时进入过渡窗口（提高处理预算）并清空玩家扫描/客户端刷新队列。
     */
    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent event) {
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

    /**
     * 优化版主循环：按信用额度与时间片消费待处理区块，并刷新客户端。
     */
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

            double multiplier = System.currentTimeMillis() < this.seasonTransitionUntil ? (double) 3.0F : this.preTransitionRateMultiplier;
            double ratePerTick = Math.max((double) 1.0F, (double) this.budgetPerTick * multiplier);
            double creditCap = Math.max((double) 1.0F, ratePerTick * (double) 2.0F);
            this.chunkCredits = Math.min(creditCap, this.chunkCredits + ratePerTick);
            int allowance = (int) Math.floor(this.chunkCredits);
            if (allowance > 0 && !this.pendingChunks.isEmpty()) {
                long deadline = System.nanoTime() + 2000000L;
                int processed = 0;

                while (processed < allowance && !this.pendingChunks.isEmpty()) {
                    ChunkWork work = (ChunkWork) this.pendingChunks.pollFirst();
                    if (this.queuedChunks.remove(work)) {
                        this.processQueuedChunk(work);
                        this.chunkCredits = Math.max((double) 0.0F, this.chunkCredits - (double) 1.0F);
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

    /**
     * 根据所有在线玩家位置，把他们视野范围内、需要更新的区块入队（增量环状补充）。
     */
    private void enqueuePlayerAreas() {
        long cooldownCutoff = System.currentTimeMillis() - 3000L;
        this.nudgeLast.entrySet().removeIf((entry) -> (Long) entry.getValue() < cooldownCutoff);
        Set<String> disabled = new HashSet();

        for (String worldName : this.plugin.getConfig().getStringList("worlds.disabled_season_fx")) {
            if (worldName != null) {
                disabled.add(worldName.toLowerCase(Locale.ROOT));
            }
        }

        this.disabledWorldNames = Set.copyOf(disabled);
        Set<UUID> online = new HashSet();
        double maxPreTransition = (double) 0.0F;
        int radius = Math.max(this.radiusChunksCfg, Bukkit.getViewDistance() + 1);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            World world = player.getWorld();
            if (world.getEnvironment() == Environment.NORMAL && !this.isWorldDisabled(world)) {
                CalendarChannel channel = this.seasons.resolveChannel(world);
                if (channel != null && this.seasons.isChannelEnabled(channel)) {
                    boolean permanentWinter = this.seasons.isPermanentWinterWorld(world);
                    CalendarState state = this.seasons.getStateCopy(world);
                    int dayInPeriod = this.computeDayInPeriod(channel, state);
                    double preFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, state, dayInPeriod) : (double) 0.0F;
                    maxPreTransition = Math.max(maxPreTransition, preFactor);
                    Location location = player.getLocation();
                    int centerX = location.getBlockX() >> 4;
                    int centerZ = location.getBlockZ() >> 4;
                    long visualStamp = this.visualStamp(state) ^ Double.doubleToLongBits(preFactor);
                    PlayerScanState previous = (PlayerScanState) this.playerScanStates.get(playerId);
                    PlayerScanState current = new PlayerScanState(world.getUID(), centerX, centerZ, radius, visualStamp);
                    if (!current.equals(previous)) {
                        boolean enqueueOnlyNewRing = previous != null && previous.worldId().equals(current.worldId()) && previous.radius() == current.radius() && previous.visualStamp() == current.visualStamp();

                        for (Offset offset : this.offsetsForRadius(radius)) {
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
        this.preTransitionRateMultiplier = (double) 1.0F + maxPreTransition * (double) 2.0F;
    }

    /**
     * 取（并缓存）给定半径的区块偏移列表，按距离与平方距离排序。
     */
    private List<Offset> offsetsForRadius(int radius) {
        return (List) this.offsetsByRadius.computeIfAbsent(radius, (radiusKey) -> {
            List<Offset> offsets = new ArrayList((radiusKey * 2 + 1) * (radiusKey * 2 + 1));

            for (int dx = -radiusKey; dx <= radiusKey; ++dx) {
                for (int dz = -radiusKey; dz <= radiusKey; ++dz) {
                    int distance = Math.max(Math.abs(dx), Math.abs(dz));
                    offsets.add(new Offset(dx, dz, distance, (double) 0.0F));
                }
            }

            offsets.sort(Comparator.comparingInt((Offset offset) -> offset.dist).thenComparingInt((Offset offset) -> offset.dx * offset.dx + offset.dz * offset.dz));
            return List.copyOf(offsets);
        });
    }

    private long visualStamp(CalendarState state) {
        long stamp = (long) state.year << 32 ^ (long) state.day << 16;
        stamp ^= (long) state.season.ordinal() << 8;
        if (state.monthsEnabled) {
            stamp ^= (long) state.monthIndex + 1L;
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

            while (batch.size() < allowance && examined++ < Math.max(64, allowance * 4) && !this.clientRefreshQueue.isEmpty()) {
                ChunkWork work = (ChunkWork) this.clientRefreshQueue.pollFirst();
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

                    for (int i = 0; i < batch.size(); ++i) {
                        ChunkWork work = (ChunkWork) batch.get(i);
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

                    for (int i = batch.size() - 1; i >= firstUnsent; --i) {
                        this.clientRefreshQueue.addFirst((ChunkWork) batch.get(i));
                    }

                }
            }
        }
    }

    /**
     * 按世界分组批量发送群系数据包；任一世界发送失败即返回 false 以便降级。
     */
    private boolean sendBiomePacketBatch(List<ChunkWork> batch) {
        Map<UUID, List<Chunk>> chunksByWorld = new LinkedHashMap();

        for (ChunkWork work : batch) {
            World world = Bukkit.getWorld(work.worldId());
            if (world != null && world.isChunkLoaded(work.chunkX(), work.chunkZ())) {
                ((List) chunksByWorld.computeIfAbsent(work.worldId(), (ignoredKey) -> new ArrayList())).add(world.getChunkAt(work.chunkX(), work.chunkZ()));
            }
        }

        for (Map.Entry<UUID, List<Chunk>> entry : chunksByWorld.entrySet()) {
            World world = Bukkit.getWorld((UUID) entry.getKey());
            if (world != null && !this.biomePacketSender.send(world, (List) entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    private boolean hasClientViewer(World world, int chunkX, int chunkZ) {
        int view = Bukkit.getViewDistance() + 1;

        for (Player player : world.getPlayers()) {
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

        for (Player player : chunk.getWorld().getPlayers()) {
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

    /**
     * 处理单个待处理区块：确保原始备份已加载，判定是否跳过/排除，然后选择目标群系并应用。
     */
    private void processQueuedChunk(ChunkWork work) {
        World world = Bukkit.getWorld(work.worldId());
        if (world != null && world.getEnvironment() == Environment.NORMAL && !this.isWorldDisabled(world) && world.isChunkLoaded(work.chunkX(), work.chunkZ())) {
            CalendarChannel channel = this.seasons.resolveChannel(world);
            if (channel != null && this.seasons.isChannelEnabled(channel)) {
                boolean permanentWinter = this.seasons.isPermanentWinterWorld(world);
                CalendarState state = this.seasons.getStateCopy(world);
                Season season = state.season;
                int dayInPeriod = this.computeDayInPeriod(channel, state);
                double preTransitionFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, state, dayInPeriod) : (double) 0.0F;
                Season nextSeason = permanentWinter ? Season.WINTER : this.resolveNextVisualSeason(channel, state);
                Biome currentTarget = (Biome) this.seasonTarget.getOrDefault(season, Biome.PLAINS);
                Biome nextTarget = (Biome) this.seasonTarget.getOrDefault(nextSeason, currentTarget);
                Biome currentOceanTarget = (Biome) this.oceanTarget.getOrDefault(season, Biome.OCEAN);
                Biome nextOceanTarget = (Biome) this.oceanTarget.getOrDefault(nextSeason, currentOceanTarget);
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
                        Family family = (Family) this.familyCache.computeIfAbsent(chunkKey, (ignoredKey) -> this.classifyOriginalFamily(chunk));
                        Biome target = this.chooseTargetBiomeForChunk(chunkKey, family, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, chunk, season, nextSeason);
                        if (!this.shouldSkipSpoofForChunk(chunk, season)) {
                            AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(chunkKey);
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
                            AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(chunkKey);
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

    /**
     * 确保某区块的原始群系备份已加载：未加载时异步读取磁盘，成功后再入队重试。
     */
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

    /**
     * {@link Runnable} 入口：委托给优化版主循环。
     */
    @Override
    public void run() {
        this.runOptimized();
    }

    /** 旧版主循环（逐玩家逐区块直接应用），保留作为备用/参考实现。 */
    /**
     * 旧版主循环（逐玩家逐区块直接应用），保留作为备用/参考实现。
     */
    private void runLegacy() {
        if (this.mode != BiomeSpoofAdapter.Mode.OFF) {
            long now = System.currentTimeMillis();
            int baseBudget = this.budgetPerTick;
            int budget = baseBudget;
            if (baseBudget > 0) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (budget <= 0) {
                        break;
                    }

                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL) {
                        List<String> disabled = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
                        if (disabled == null || !disabled.stream().anyMatch((worldName) -> worldName != null && worldName.equalsIgnoreCase(world.getName()))) {
                            CalendarChannel channel = this.seasons.resolveChannel(world);
                            if (channel != null && this.seasons.isChannelEnabled(channel)) {
                                boolean permanentWinter = this.seasons.isPermanentWinterWorld(world);
                                CalendarState state = this.seasons.getStateCopy(world);
                                Season season = state.season;
                                int dayInPeriod = this.computeDayInPeriod(channel, state);
                                double preTransitionFactor = this.preTransitionEnabled && !permanentWinter ? this.computePreTransitionFactor(channel, state, dayInPeriod) : (double) 0.0F;
                                Season nextVisualSeason = permanentWinter ? Season.WINTER : this.resolveNextVisualSeason(channel, state);
                                Biome currentTarget = (Biome) this.seasonTarget.getOrDefault(season, Biome.PLAINS);
                                Biome nextTarget = (Biome) this.seasonTarget.getOrDefault(nextVisualSeason, currentTarget);
                                Biome currentOceanTarget = (Biome) this.oceanTarget.getOrDefault(season, Biome.OCEAN);
                                Biome nextOceanTarget = (Biome) this.oceanTarget.getOrDefault(nextVisualSeason, currentOceanTarget);
                                int effectiveBudget = baseBudget;
                                if (now < this.seasonTransitionUntil) {
                                    effectiveBudget = Math.max(1, baseBudget * 3);
                                } else if (preTransitionFactor > (double) 0.0F) {
                                    double extra = (double) 1.0F + preTransitionFactor * (double) 2.0F;
                                    effectiveBudget = (int) Math.max(1L, Math.round((double) baseBudget * extra));
                                }

                                budget = Math.min(budget, effectiveBudget);
                                int view = Bukkit.getViewDistance();
                                int radius = Math.max(this.radiusChunksCfg, view + 1);
                                Location loc = player.getLocation();
                                int pcx = loc.getBlockX() >> 4;
                                int pcz = loc.getBlockZ() >> 4;
                                Vector look = loc.getDirection().clone();
                                look.setY(0);
                                if (look.lengthSquared() < 1.0E-4) {
                                    look = new Vector(0, 0, 1);
                                } else {
                                    look.normalize();
                                }

                                if (world.isChunkLoaded(pcx, pcz) && budget > 0) {
                                    Chunk center = world.getChunkAt(pcx, pcz);
                                    long ck = this.key(center);
                                    boolean centerExcluded = this.isChunkExcludedByConfig(center);
                                    if (centerExcluded) {
                                        if (this.spoofed.remove(ck)) {
                                            this.revertChunk(center);
                                        }

                                        this.lastApplied.remove(ck);
                                        this.familyCache.remove(ck);
                                    } else {
                                        Family fam = (Family) this.familyCache.computeIfAbsent(ck, (ignoredKey) -> this.classifyOriginalFamily(center));
                                        Biome chunkTarget = this.chooseTargetBiomeForChunk(ck, fam, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, center, season, nextVisualSeason);
                                        if (this.shouldSkipSpoofForChunk(center, season)) {
                                            AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(ck);
                                            boolean alreadyValidated = last != null && last.matches(chunkTarget, season);
                                            this.spoofed.remove(ck);
                                            if (!alreadyValidated) {
                                                this.revertChunk(center);
                                                this.lastApplied.put(ck, new AppliedBiomeState(chunkTarget, season));
                                            }

                                            this.familyCache.remove(ck);
                                        } else {
                                            AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(ck);
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
                                                    this.nudgeViewers(world, pcx, pcz);
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

                                for (int dx = -radius; dx <= radius; ++dx) {
                                    for (int dz = -radius; dz <= radius; ++dz) {
                                        if (dx != 0 || dz != 0) {
                                            int dist = Math.max(Math.abs(dx), Math.abs(dz));
                                            Vector dir = new Vector(dx, 0, dz);
                                            double forwardScore;
                                            if (dir.lengthSquared() < 1.0E-4) {
                                                forwardScore = (double) 0.0F;
                                            } else {
                                                dir.normalize();
                                                forwardScore = look.dot(dir);
                                            }

                                            offsets.add(new Offset(dx, dz, dist, forwardScore));
                                        }
                                    }
                                }

                                offsets.sort(Comparator.comparingInt((Offset offset) -> offset.dist).thenComparingDouble((Offset offset) -> -offset.forwardScore));

                                for (Offset off : offsets) {
                                    if (budget <= 0) {
                                        break;
                                    }

                                    int cx = pcx + off.dx;
                                    int cz = pcz + off.dz;
                                    if (world.isChunkLoaded(cx, cz)) {
                                        Chunk ch = world.getChunkAt(cx, cz);
                                        long k = this.key(ch);
                                        if (this.isChunkExcludedByConfig(ch)) {
                                            if (this.spoofed.remove(k)) {
                                                this.revertChunk(ch);
                                            }

                                            this.lastApplied.remove(k);
                                            this.familyCache.remove(k);
                                        } else {
                                            Family fam = (Family) this.familyCache.computeIfAbsent(k, (ignoredKey) -> this.classifyOriginalFamily(ch));
                                            Biome chunkTarget = this.chooseTargetBiomeForChunk(k, fam, currentTarget, nextTarget, currentOceanTarget, nextOceanTarget, preTransitionFactor, ch, season, nextVisualSeason);
                                            if (!this.shouldSkipSpoofForChunk(ch, season)) {
                                                AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(k);
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
                                                            this.nudgeViewers(world, cx, cz);
                                                            --budget;
                                                        }
                                                    }
                                                }
                                            } else {
                                                AppliedBiomeState last = (AppliedBiomeState) this.lastApplied.get(k);
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

    /**
     * 判断某区块是否因全部采样群系都受保护/被排除而应整体跳过伪装（带缓存）。
     */
    private boolean isChunkExcludedByConfig(Chunk chunk) {
        if (this.excludedBiomes.isEmpty()) {
            return false;
        } else {
            long key = this.key(chunk);
            Boolean cached = (Boolean) this.excludedCache.get(key);
            if (cached != null) {
                return cached;
            } else {
                boolean result = true;
                boolean foundBiome = false;
                Biome[] old = (Biome[]) this.backups.get(key);
                if (old != null && old.length > 0) {
                    for (Biome biome : old) {
                        if (biome != null) {
                            foundBiome = true;
                            if (!this.shouldProtectBiomeFromSpoof(biome)) {
                                result = false;
                                break;
                            }
                        }
                    }

                    result = foundBiome && result;
                    this.excludedCache.put(key, result);
                    return result;
                } else {
                    World world = chunk.getWorld();
                    int baseX = chunk.getX() << 4;
                    int baseZ = chunk.getZ() << 4;
                    int minY = world.getMinHeight();
                    int maxY = world.getMaxHeight();

                    for (int x = 0; x < 16; x += 8) {
                        for (int z = 0; z < 16; z += 8) {
                            for (int y = minY; y < maxY; y += 32) {
                                Biome biome = world.getBiome(baseX + x, y, baseZ + z);
                                if (biome != null) {
                                    foundBiome = true;
                                    if (!this.shouldProtectBiomeFromSpoof(biome)) {
                                        result = false;
                                    }
                                }
                            }
                        }
                    }

                    result = foundBiome && result;
                    this.excludedCache.put(key, result);
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
            return "RIVER".equals(name) || "FROZEN_RIVER".equals(name);
        }
    }

    private boolean isWaterColorSensitiveBiome(Biome biome) {
        String name = biome.name();
        return "SWAMP".equals(name) || "MANGROVE_SWAMP".equals(name) || "RIVER".equals(name) || "FROZEN_RIVER".equals(name);
    }

    /**
     * 读取原始海洋群系的代表值（优先取备份中的海洋群系，否则回退第一个/代表群系）。
     */
    private Biome getRepresentativeOriginalOceanBiome(Chunk ch) {
        long key = this.key(ch);
        Biome[] old = (Biome[]) this.backups.get(key);
        if (old != null && old.length > 0) {
            for (Biome biome : old) {
                if (this.isOceanBiome(biome)) {
                    return biome;
                }
            }

            return old[0];
        } else {
            return this.getRepresentativeOriginalBiome(ch);
        }
    }

    /**
     * 选择给定季节的目标群系（无配置时保留原始群系）。
     */
    private Biome chooseTargetFor(Season season, Biome original) {
        Biome global = (Biome) this.seasonTarget.get(season);
        return global == null ? original : global;
    }

    /**
     * 判断指定区块当前所有采样点是否都已是目标群系。
     */
    private boolean isChunkAtTarget(Chunk ch, Biome target) {
        World world = ch.getWorld();
        int baseX = ch.getX() << 4;
        int baseZ = ch.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int x = 0; x < 16; x += 8) {
            for (int z = 0; z < 16; z += 8) {
                for (int y = minY; y < maxY; y += 32) {
                    if (world.getBiome(baseX + x, y, baseZ + z) != target) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * 判断群系是否属于寒冷类（雪/冰/冻洋/山峰等），樱花林除外。
     */
    private boolean isColdBiome(Biome biome) {
        if (biome == Biome.CHERRY_GROVE) {
            return false;
        } else {
            String name = biome.name();
            return name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE") || "GROVE".equals(name) || name.contains("SNOWY_TAIGA") || name.contains("PEAK") || name.contains("MOUNTAIN");
        }
    }

    /**
     * 判断群系是否为海洋类。
     */
    private boolean isOceanBiome(Biome biome) {
        return biome.name().contains("OCEAN");
    }

    /**
     * 判断群系是否为深海类。
     */
    private boolean isDeepOcean(Biome biome) {
        return biome.name().contains("DEEP_") && this.isOceanBiome(biome);
    }

    /**
     * 判断群系是否为海滩/海岸类。
     */
    private boolean isShoreBiome(Biome biome) {
        String name = biome.name();
        return name.contains("BEACH") || name.contains("SHORE");
    }

    /**
     * 判断区块原始群系家族（陆地/海洋），并顺带缓存其冷暖属性。
     */
    private Family classifyOriginalFamily(Chunk ch) {
        long key = this.key(ch);
        Biome[] old = (Biome[]) this.backups.get(key);
        if (old != null && old.length > 0) {
            this.cacheOriginalTemperature(key, Arrays.asList(old));
            return this.classifyFamilyFromSamples(Arrays.asList(old));
        } else {
            World world = ch.getWorld();
            int baseX = ch.getX() << 4;
            int baseZ = ch.getZ() << 4;
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            List<Biome> samples = new ArrayList(32);

            for (int x = 0; x < 16; x += 8) {
                for (int z = 0; z < 16; z += 8) {
                    for (int y = minY; y < maxY; y += 32) {
                        samples.add(world.getBiome(baseX + x, y, baseZ + z));
                    }
                }
            }

            this.cacheOriginalTemperature(key, samples);
            return this.classifyFamilyFromSamples(samples);
        }
    }

    private void cacheOriginalTemperature(long chunkKey, Iterable<Biome> samples) {
        for (Biome biome : samples) {
            if (this.isColdBiome(biome)) {
                COLD_CHUNKS.add(chunkKey);
                this.warmChunks.remove(chunkKey);
                return;
            }
        }

        this.warmChunks.add(chunkKey);
    }

    /**
     * 根据采样群系统计海洋/海岸/陆地比例，判定该区块的家族。
     */
    private Family classifyFamilyFromSamples(Iterable<Biome> samples) {
        int oceanCount = 0;
        int shoreCount = 0;
        int landCount = 0;

        for (Biome biome : samples) {
            if (biome != null) {
                if (this.oceansEnabled && this.isOceanBiome(biome)) {
                    ++oceanCount;
                } else if (this.oceansEnabled && this.oceansAffectShores && this.isShoreBiome(biome)) {
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

    /**
     * 读取区块原始群系的代表值（备份第一个，或首次采样的最低层群系）。
     */
    private Biome getRepresentativeOriginalBiome(Chunk ch) {
        long key = this.key(ch);
        Biome[] old = (Biome[]) this.backups.get(key);
        if (old != null && old.length > 0) {
            return old[0];
        } else {
            World world = ch.getWorld();
            int baseX = ch.getX() << 4;
            int baseZ = ch.getZ() << 4;
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();

            for (int x = 0; x < 16; x += 8) {
                for (int z = 0; z < 16; z += 8) {
                    if (minY < maxY) {
                        return world.getBiome(baseX + x, minY, baseZ + z);
                    }
                }
            }

            return Biome.OCEAN;
        }
    }

    /**
     * 按原始海洋是否为深海，对目标海洋群系做深/浅变体适配。
     */
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
                    } catch (IllegalArgumentException deepNotFound) {
                        return baseTarget;
                    }
                }
            } else if (baseName.startsWith("DEEP_")) {
                String shallow = baseName.substring("DEEP_".length());

                try {
                    return Biome.valueOf(shallow);
                } catch (IllegalArgumentException shallowNotFound) {
                    return baseTarget;
                }
            } else {
                return baseTarget;
            }
        }
    }

    /**
     * 综合家族、季节目标与过渡因子，为区块选出最终要伪装成的目标群系。
     */
    private Biome chooseTargetBiomeForChunk(long chunkKey, Family family, Biome currentLandTarget, Biome nextLandTarget, Biome currentOceanTarget, Biome nextOceanTarget, double preTransitionFactor, Chunk ch, Season currentSeason, Season nextSeason) {
        if (family == BiomeSpoofAdapter.Family.OCEAN && this.oceansEnabled) {
            Biome origOcean = this.getRepresentativeOriginalOceanBiome(ch);
            if (currentSeason == Season.WINTER && this.oceansWinterForceSnow) {
                Biome safeWinterTarget = this.isOceanBiome(this.oceansWinterForceSnowBiome) ? this.oceansWinterForceSnowBiome : (Biome) this.oceanTarget.getOrDefault(Season.WINTER, Biome.FROZEN_OCEAN);
                return this.isOceanBiome(origOcean) ? this.applyOceanVariant(safeWinterTarget, origOcean) : safeWinterTarget;
            } else {
                double oceanPreTransitionFactor = this.preTransitionAffectOceans ? preTransitionFactor : (double) 0.0F;
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

    /**
     * 采样区块原始群系（首次时记录备份），并把每个采样点替换为目标群系，返回应用结果。
     */
    private BiomeApplyResult captureAndApply(Chunk ch, Biome target, Season season) {
        try {
            World world = ch.getWorld();
            int baseX = ch.getX() << 4;
            int baseZ = ch.getZ() << 4;
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int seaLevel = world.getSeaLevel();
            long key = this.key(ch);
            Biome[] existing = (Biome[]) this.backups.get(key);
            List<Biome> prevs = existing == null ? new ArrayList() : null;
            boolean anyChange = false;
            BitSet modifiedCells = new BitSet();
            int sampleIndex = 0;

            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    int wx = baseX + x;
                    int wz = baseZ + z;
                    boolean waterColumn = this.protectWaterColorBiomes && this.isWaterSurfaceColumn(world, wx, wz);

                    for (int y = minY; y < maxY; y += 4) {
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

                            Biome current = world.getBiome(wx, y, wz);
                            if (current != desired) {
                                world.setBiome(wx, y, wz, desired);
                                anyChange = true;
                            }
                        } else {
                            Biome current = world.getBiome(wx, y, wz);
                            prevs.add(current);
                            int gridIndex = sampleIndex++;
                            Biome desired = this.resolveDesiredBiome(current, target, season, waterColumn, y, seaLevel);
                            if (desired != current) {
                                modifiedCells.set(gridIndex);
                            }

                            if (current != desired) {
                                anyChange = true;
                                world.setBiome(wx, y, wz, desired);
                            }
                        }
                    }
                }
            }

            if (prevs != null) {
                boolean cold = false;
                boolean fullyExcluded = true;
                boolean foundBiome = false;

                for (Biome biome : prevs) {
                    if (biome != null) {
                        foundBiome = true;
                        if (this.isColdBiome(biome)) {
                            cold = true;
                        }

                        if (!this.shouldProtectBiomeFromSpoof(biome)) {
                            fullyExcluded = false;
                        }
                    }
                }

                if (cold) {
                    COLD_CHUNKS.add(key);
                    this.warmChunks.remove(key);
                } else {
                    this.warmChunks.add(key);
                }

                this.excludedCache.put(key, foundBiome && fullyExcluded);
                this.familyCache.put(key, this.classifyFamilyFromSamples(prevs));
            }

            if (prevs != null) {
                Biome[] arr = (Biome[]) prevs.toArray(new Biome[0]);
                if (this.diskBackupEnabled) {
                    this.diskBackups.saveFirstTouch(ch, arr, 4, 4);
                }

                return new BiomeApplyResult(arr, modifiedCells, anyChange, true);
            } else {
                return new BiomeApplyResult((Biome[]) null, modifiedCells, anyChange, true);
            }
        } catch (Throwable failure) {
            Logger logger = this.plugin.getLogger();
            int chunkXValue = ch.getX();
            logger.warning("[BiomeSpoof] spoof error " + chunkXValue + "," + ch.getZ() + ": " + failure.getMessage());
            return new BiomeApplyResult((Biome[]) null, (BitSet) null, false, false);
        }
    }

    /**
     * 记录区块被修改过的采样点集合（拷贝后保存）。
     */
    private void rememberModifiedCells(long chunkKey, BiomeApplyResult result) {
        BitSet modifiedCells = result.modifiedCells();
        if (modifiedCells != null) {
            this.modifiedBiomeCells.put(chunkKey, (BitSet) modifiedCells.clone());
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

        while (true) {
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

    /**
     * 按备份把区块还原为原始群系（默认立即刷新客户端）。
     */
    private void revertChunk(Chunk ch) {
        this.revertChunk(ch, true);
    }

    /**
     * 按备份把区块还原为原始群系；仅还原被标记修改过的采样点（无记录时全部还原），返回是否有改动。
     *
     * @param refreshClientImmediately 是否立即刷新客户端
     */
    private boolean revertChunk(Chunk ch, boolean refreshClientImmediately) {
        long k = this.key(ch);
        Biome[] old = (Biome[]) this.backups.get(k);
        if (old == null) {
            this.modifiedBiomeCells.remove(k);
            this.lastApplied.remove(k);
            this.familyCache.remove(k);
            return false;
        } else {
            BitSet modifiedCells = (BitSet) this.modifiedBiomeCells.get(k);
            boolean restoreAll = modifiedCells == null;
            boolean changed = false;
            boolean completed = false;

            try {
                World world = ch.getWorld();
                int bx = ch.getX() << 4;
                int bz = ch.getZ() << 4;
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();
                int i = 0;

                cellRestoreLoop:
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        for (int y = minY; y < maxY; y += 4) {
                            if (i >= old.length) {
                                break cellRestoreLoop;
                            }

                            Biome original = old[i];
                            if (restoreAll || modifiedCells.get(i)) {
                                world.setBiome(bx + x, y, bz + z, original);
                                changed = true;
                            }

                            ++i;
                        }
                    }
                }

                completed = true;
                if (changed && refreshClientImmediately) {
                    world.refreshChunk(ch.getX(), ch.getZ());
                    if (this.clientRefreshNudge) {
                        this.nudgeViewers(world, ch.getX(), ch.getZ());
                    }
                }
            } catch (Throwable failure) {
                Logger logger = this.plugin.getLogger();
                int chunkXValue = ch.getX();
                logger.warning("[BiomeSpoof] revert error " + chunkXValue + "," + ch.getZ() + ": " + failure.getMessage());
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

    /**
     * 计算世界与区块坐标组合成的稳定键（混入世界 UUID 高低位）。
     */
    private long key(World world, int cx, int cz) {
        long k = ((long) cx & 4294967295L) << 32 | (long) cz & 4294967295L;
        return k ^ world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits();
    }

    /**
     * 查询某区块是否被标记为“天然寒冷”（供积雪渲染使用）。
     */
    public static boolean isChunkNaturallySnowy(World world, int cx, int cz) {
        long k = ((long) cx & 4294967295L) << 32 | (long) cz & 4294967295L;
        k ^= world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits();
        return COLD_CHUNKS.contains(k);
    }

    /**
     * 还原所有世界中已伪装的区块并清空全部缓存。
     */
    private void revertAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk ch : world.getLoadedChunks()) {
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

    /**
     * 判断某区块在当前季节是否应跳过伪装（例如非冬季但原始为天然雪区）。
     */
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
                World world = ch.getWorld();
                int baseX = ch.getX() << 4;
                int baseZ = ch.getZ() << 4;
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();

                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        for (int y = minY; y < maxY; y += 32) {
                            Biome biome = world.getBiome(baseX + x, y, baseZ + z);
                            if (this.isColdBiome(biome)) {
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

    /**
     * 对能“看到”该区块的玩家安排一次轻推刷新。
     */
    private void nudgeViewers(World world, int cx, int cz) {
        for (Player viewer : world.getPlayers()) {
            int vcx = viewer.getLocation().getBlockX() >> 4;
            int vcz = viewer.getLocation().getBlockZ() >> 4;
            int view = Bukkit.getViewDistance() + 1;
            if (Math.abs(vcx - cx) <= view && Math.abs(vcz - cz) <= view) {
                this.enqueueNudge(viewer, world, cx, cz);
            }
        }

    }

    /**
     * 为一个玩家入队一次轻推目标（带 3 秒冷却去重）。
     */
    private void enqueueNudge(Player player, World world, int cx, int cz) {
        NudgeTarget target = new NudgeTarget(world.getUID(), cx, cz);
        NudgeCooldownKey cooldownKey = new NudgeCooldownKey(player.getUniqueId(), target);
        long now = System.currentTimeMillis();
        Long last = (Long) this.nudgeLast.get(cooldownKey);
        if (last == null || now - last >= 3000L) {
            this.nudgeLast.put(cooldownKey, now);
            ((ArrayDeque) this.nudgeQueue.computeIfAbsent(player.getUniqueId(), (ignoredKey) -> new ArrayDeque())).add(target);
        }
    }

    /**
     * 每 tick 发送有限数量的“假方块 + 真方块”轻推，强制客户端重算目标区块外观。
     */
    private void flushNudges() {
        int sent = 0;
        Iterator<Map.Entry<UUID, ArrayDeque<NudgeTarget>>> iterator = this.nudgeQueue.entrySet().iterator();

        while (iterator.hasNext() && sent < 8) {
            Map.Entry<UUID, ArrayDeque<NudgeTarget>> entry = (Map.Entry) iterator.next();
            UUID playerId = (UUID) entry.getKey();
            ArrayDeque<NudgeTarget> queue = (ArrayDeque) entry.getValue();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                if (queue.isEmpty()) {
                    iterator.remove();
                } else {
                    NudgeTarget target = (NudgeTarget) queue.poll();
                    ++sent;
                    World world = Bukkit.getWorld(target.worldId());
                    if (world != null && player.getWorld() == world && world.isChunkLoaded(target.chunkX(), target.chunkZ())) {
                        int minY = world.getMinHeight();
                        Location loc = new Location(world, (double) (target.chunkX() << 4), (double) minY, (double) (target.chunkZ() << 4));
                        BlockData fake = NUDGE_FAKE.createBlockData();
                        BlockData real = world.getBlockAt(loc).getBlockData();
                        player.sendBlockChange(loc, fake);
                        Bukkit.getScheduler().runTask(this.plugin, () -> {
                            if (player.isOnline()) {
                                player.sendBlockChange(loc, real);
                            }

                        });
                    }
                }
            } else {
                iterator.remove();
            }
        }

    }

    /**
     * 计算当前在该日历周期内是第几天（受月份长度约束）。
     */
    private int computeDayInPeriod(CalendarChannel channel, CalendarState st) {
        int day = st != null ? st.day : 1;
        int max;
        if (st != null && st.monthsEnabled && st.daysInMonth > 0) {
            max = st.daysInMonth;
        } else if (channel != null) {
            max = Math.max(1, this.seasons.getCurrentPeriodLength(channel));
        } else {
            max = 1;
        }

        if (day < 1) {
            day = 1;
        }

        if (day > max) {
            day = max;
        }

        return day;
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
            return (double) 0.0F;
        } else {
            int step = dayInPeriod - start;
            double fraction = (double) 0.0F;
            if (channel != null) {
                List<World> worlds = this.seasons.getWorlds(channel);
                if (!worlds.isEmpty()) {
                    long time = Math.floorMod(((World) worlds.get(0)).getTime(), 24000L);
                    fraction = Math.floor((double) time / (double) 1500.0F) / (double) 16.0F;
                }
            }

            double factor = ((double) step + fraction) / (double) window;
            if (factor < (double) 0.0F) {
                factor = (double) 0.0F;
            }

            if (factor > (double) 1.0F) {
                factor = (double) 1.0F;
            }

            return factor;
        }
    }

    /**
     * 返回给定季节的下一个季节（循环）。
     */
    private Season nextSeason(Season season) {
        Season result;
        switch (season) {
            case SPRING -> result = Season.SUMMER;
            case SUMMER -> result = Season.AUTUMN;
            case AUTUMN -> result = Season.WINTER;
            case WINTER -> result = Season.SPRING;
            default -> throw new MatchException((String) null, (Throwable) null);
        }

        return result;
    }

    /**
     * 依据备份网格近似查询某坐标的原始群系（无备份时返回当前群系）。
     */
    public Biome getOriginalBiomeApprox(World world, int x, int y, int z) {
        Chunk ch = world.getChunkAt(x >> 4, z >> 4);
        Biome[] old = (Biome[]) this.backups.get(this.key(ch));
        if (old != null && old.length != 0) {
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int clampedY = Math.max(minY, Math.min(maxY - 1, y));
            int xCount = 4;
            int zCount = 4;
            int yCount = (maxY - minY) / 4;
            int lx = (x & 15) / 4;
            int lz = (z & 15) / 4;
            int ly = (clampedY - minY) / 4;
            int idx = (lx * zCount + lz) * yCount + ly;
            return idx >= 0 && idx < old.length ? old[idx] : old[0];
        } else {
            return world.getBiome(x, y, z);
        }
    }

    /**
     * 同 {@link #getOriginalBiomeApprox}，但无备份时返回 null。
     */
    public Biome getOriginalBiomeApproxOrNull(World world, int x, int y, int z) {
        Chunk ch = world.getChunkAt(x >> 4, z >> 4);
        Biome[] old = (Biome[]) this.backups.get(this.key(ch));
        if (old != null && old.length != 0) {
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int clampedY = Math.max(minY, Math.min(maxY - 1, y));
            int xCount = 4;
            int zCount = 4;
            int yCount = (maxY - minY) / 4;
            int lx = (x & 15) / 4;
            int lz = (z & 15) / 4;
            int ly = (clampedY - minY) / 4;
            int idx = (lx * zCount + lz) * yCount + ly;
            return idx >= 0 && idx < old.length ? old[idx] : old[0];
        } else {
            return null;
        }
    }

    private Biome chooseTargetBiomeForChunk(long chunkKey, Biome currentTarget, Biome nextTarget, double preTransitionFactor) {
        if (nextTarget != null && currentTarget != nextTarget && !(preTransitionFactor <= (double) 0.0F)) {
            if (preTransitionFactor >= (double) 1.0F) {
                return nextTarget;
            } else {
                long hash = chunkKey * 1103515245L + 12345L;
                hash ^= hash >>> 16;
                int bucket = (int) (hash & 65535L);
                double threshold = preTransitionFactor * (double) 65536.0F;
                return (double) bucket < threshold ? nextTarget : currentTarget;
            }
        } else {
            return currentTarget;
        }
    }

    /**
     * 通过命令开关伪装：启用时启动主循环任务，停用时取消任务并清空队列。
     */
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
            this.chunkCredits = (double) 0.0F;
            this.clientRefreshTickCounter = 0;
        }

    }

    /**
     * 解析下一个可视化季节：优先按日历月份顺序，无法解析时回退为自然下一季。
     */
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

                    for (int i = 0; i < order.size(); ++i) {
                        String id = (String) order.get(i);
                        if (id != null && id.equalsIgnoreCase(st.monthId)) {
                            idx = i;
                            break;
                        }
                    }

                    if (idx < 0) {
                        return this.nextSeason(st.season);
                    } else {
                        String nextMonthId = (String) order.get((idx + 1) % order.size());
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
                                } catch (IllegalArgumentException invalidSeason) {
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

    /**
     * 当前是否处于伪装启用状态。
     */
    public boolean isEnabled() {
        return this.mode != BiomeSpoofAdapter.Mode.OFF;
    }

    /**
     * 是否启用磁盘备份。
     */
    public boolean isDiskBackupEnabled() {
        return this.diskBackupEnabled;
    }

    /**
     * 设置是否启用磁盘备份。
     */
    public void setDiskBackupEnabled(boolean enabled) {
        this.diskBackupEnabled = enabled;
    }

    /**
     * 返回底层生物群系备份存储。
     */
    public BiomeBackupStore getDiskBackups() {
        return this.diskBackups;
    }

    static {
        WATER_REQUIRED_MOBS = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.AXOLOTL, EntityType.TADPOLE);
        CAPPED_AQUATIC_MOBS = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH, EntityType.DOLPHIN);
        COLD_CHUNKS = ConcurrentHashMap.newKeySet();
        NUDGE_FAKE = Material.BARRIER;
    }

    /**
     * 伪装工作模式：GLOBAL_RING 全局环状启用，OFF 关闭。
     */
    public enum Mode {
        GLOBAL_RING,
        OFF;

        Mode() {
        }

        // $FF: synthetic method
        private static Mode[] $values() {
            return new Mode[]{GLOBAL_RING, OFF};
        }
    }

    /**
     * 区块偏移：记录相对偏移、切比雪夫距离与朝向评分。
     */
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

    /**
     * 待处理的区块工作项：世界 UUID 与区块坐标。
     */
    private record ChunkWork(UUID worldId, int chunkX, int chunkZ) {
        private ChunkWork {
        }
    }

    /**
     * 某区块最近一次应用的目标群系与季节，用于判断是否需要重新应用。
     */
    private record AppliedBiomeState(Biome target, Season season) {
        private AppliedBiomeState {
        }

        /**
         * 判断当前状态是否与期望的目标/季节一致。
         */
        private boolean matches(Biome expectedTarget, Season expectedSeason) {
            return this.target == expectedTarget && this.season == expectedSeason;
        }
    }

    /**
     * 应用一次伪装的结果：原始网格、修改过的采样点、是否有改动、是否成功。
     */
    private record BiomeApplyResult(Biome[] original, BitSet modifiedCells, boolean changed, boolean successful) {
        private BiomeApplyResult {
        }
    }

    /**
     * 玩家扫描状态：所在区块中心、半径与视觉戳，用于增量补充。
     */
    private record PlayerScanState(UUID worldId, int chunkX, int chunkZ, int radius, long visualStamp) {
        private PlayerScanState {
        }
    }

    /**
     * 轻推目标：世界 UUID 与区块坐标。
     */
    private record NudgeTarget(UUID worldId, int chunkX, int chunkZ) {
        private NudgeTarget {
        }
    }

    /**
     * 轻推冷却键：玩家 UUID 与目标区块。
     */
    private record NudgeCooldownKey(UUID playerId, NudgeTarget target) {
        private NudgeCooldownKey {
        }
    }

    /**
     * 区块原始群系家族：陆地或海洋。
     */
    private enum Family {
        LAND,
        OCEAN;

        Family() {
        }

        // $FF: synthetic method
        private static Family[] $values() {
            return new Family[]{LAND, OCEAN};
        }
    }
}
