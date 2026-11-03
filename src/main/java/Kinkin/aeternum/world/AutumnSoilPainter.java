package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秋季落叶/土壤染色器（AutumnSoilPainter）。
 *
 * <p>作为周期任务运行，在秋季把玩家附近的自然树叶（云杉/白桦/樱花等）逐步“染”成秋季配色
 * （DAPPLED_POPLAR 调色板或旧版金合欢色），并记录每片被染色树叶的原始材质以便日后还原。
 * 工作按预算分片执行（时间片 + 方块数上限），避免卡顿；随季节阶段推进渐变、入冬前逐渐还原。
 * 同时监听方块破坏、树叶凋零、爆炸与树苗掉落等事件，在树叶消失时还原原始类型、修正树苗种类。</p>
 *
 * <p>依赖 {@link SeasonService}（季节/天数）、{@link BiomeSpoofAdapter}（决定配色方案）、
 * {@link AutumnTreePlan}（分步扫描整棵树）、{@link AutumnLeafStore}（持久化原始材质）。</p>
 */
public final class AutumnSoilPainter implements Runnable, Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final BiomeSpoofAdapter biomeSpoof;
    private static volatile AutumnSoilPainter INSTANCE;
    private BukkitTask task;
    private final Random random = new Random();
    private int chunksPerTick;
    private int radiusChunks;
    private int dappledRadiusChunks;
    private double leafChancePerBlock;
    private final Set<Material> leafTypes;
    private final Map<String, Material> paintedLeaves;
    private PaletteMode paletteMode;
    private List<Material> dappledPalette;
    private Material poplarSapling;
    private final Map<UUID, Integer> chunkSweepCursor;
    private final Map<UUID, CachedSweep> sweepCache;
    private int playerSweepCursor;
    private long workDeadline;
    private int remainingBlocks;
    private final Map<String, Integer> scanPositions;
    private final Map<String, int[]> scanTops;
    private final Map<String, Long> nextScan;
    private final Map<String, Optional<Material>> originalGuessCache;
    private Iterator<Map.Entry<String, Material>> restoreCursor;
    private boolean restoring;
    private int guessesRemaining;
    private AutumnTreePlan pendingTree;
    private final Set<String> validatedLeaves;
    private int previousStage;
    private final ArrayDeque<PaintedTree> paintedTrees;
    private static final int DAYS_PER_SEASON = 28;
    private static final int PRE_AUTUMN_START_DAY = 26;
    private static final int PRE_AUTUMN_RAMP_DAYS = 3;
    private boolean gridFlip;
    private Set<String> disabledFxWorlds;

    public AutumnSoilPainter(AeternumSeasonsPlugin plugin, SeasonService seasons, BiomeSpoofAdapter biomeSpoof) {
        super();
        this.leafTypes = EnumSet.of(Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES);
        this.paintedLeaves = new ConcurrentHashMap();
        this.paletteMode = AutumnSoilPainter.PaletteMode.DISABLED;
        this.dappledPalette = List.of();
        this.chunkSweepCursor = new HashMap();
        this.sweepCache = new HashMap();
        this.scanPositions = new HashMap();
        this.scanTops = new HashMap();
        this.nextScan = new HashMap();
        this.originalGuessCache = new HashMap();
        this.validatedLeaves = new HashSet();
        this.previousStage = -1;
        this.paintedTrees = new ArrayDeque();
        this.gridFlip = false;
        this.disabledFxWorlds = Collections.emptySet();
        this.plugin = plugin;
        this.seasons = seasons;
        this.biomeSpoof = biomeSpoof;
        this.reloadFromConfig();
        this.paintedLeaves.putAll(AutumnLeafStore.load(plugin));
    }

    /**
     * 从气候配置与主配置重新读取染色参数、禁用世界列表，并重新解析配色方案。
     */
    public void reloadFromConfig() {
        FileConfiguration climateConfig = this.plugin.cfg.climate;
        this.chunksPerTick = Math.max(2, climateConfig.getInt("autumn_soil.attempts_per_tick", 4));
        this.radiusChunks = Math.max(2, climateConfig.getInt("autumn_soil.radius_chunks", 4));
        this.dappledRadiusChunks = Math.max(this.radiusChunks, climateConfig.getInt("autumn_soil.dappled_radius_chunks", 6));
        this.leafChancePerBlock = climateConfig.getDouble("autumn_soil.leaf_chance_per_block", (double) 1.0F);
        if (this.leafChancePerBlock < (double) 0.0F) {
            this.leafChancePerBlock = (double) 0.0F;
        }

        if (this.leafChancePerBlock > (double) 1.0F) {
            this.leafChancePerBlock = (double) 1.0F;
        }

        List<String> list = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
        Set<String> disabledWorlds = new HashSet();

        for (String worldName : list) {
            if (worldName != null) {
                String name = worldName.trim();
                if (!name.isEmpty()) {
                    disabledWorlds.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }

        this.disabledFxWorlds = disabledWorlds;
        this.resolvePaletteMode();
        this.sweepCache.clear();
    }

    /**
     * 依据 {@link BiomeSpoofAdapter} 的秋季群系类型，决定使用哪套树叶调色板（或禁用）。
     */
    private void resolvePaletteMode() {
        this.dappledPalette = this.resolveMaterials("RED_POPLAR_LEAVES", "ORANGE_POPLAR_LEAVES", "YELLOW_POPLAR_LEAVES");
        this.poplarSapling = Material.matchMaterial("POPLAR_SAPLING");
        if (this.biomeSpoof != null && this.biomeSpoof.usesSavannaPlateauForAutumn()) {
            this.paletteMode = AutumnSoilPainter.PaletteMode.LEGACY_ACACIA;
        } else if (this.biomeSpoof != null && this.biomeSpoof.usesDappledForestForAutumn()) {
            if (this.dappledPalette.size() == 3 && this.poplarSapling != null) {
                this.paletteMode = AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR;
            } else {
                this.paletteMode = AutumnSoilPainter.PaletteMode.DISABLED;
                this.plugin.getLogger().warning("[AutumnSoil] Dappled Forest detected, but the 26.3 Poplar materials are unavailable.");
            }

        } else {
            this.paletteMode = AutumnSoilPainter.PaletteMode.DISABLED;
        }
    }

    /**
     * 按名称批量解析材质，忽略无法识别的项，返回不可变列表。
     */
    private List<Material> resolveMaterials(String... names) {
        List<Material> resolved = new ArrayList(names.length);

        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                resolved.add(material);
            }
        }

        return List.copyOf(resolved);
    }

    /**
     * 注册周期任务与事件监听（仅当配置启用且配色方案可用时）。
     */
    public void register() {
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.plugin.cfg.climate.getBoolean("autumn_soil.enabled", false)) {
            if (this.paletteMode == AutumnSoilPainter.PaletteMode.DISABLED) {
                this.plugin.getLogger().info("[AutumnSoil] Leaf painter disabled because the configured autumn biome has no managed palette.");
            } else {
                INSTANCE = this;
                this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 1L, 1L);
                Bukkit.getPluginManager().registerEvents(this, this.plugin);
                this.plugin.getLogger().info(this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR ? "[AutumnSoil] Using gradual day 1-3 red/orange/yellow Poplar foliage." : "[AutumnSoil] Using the legacy Acacia leaf palette.");
            }
        }
    }

    /**
     * 取消任务、注销事件监听、清理状态并把已记录的原始树叶材质持久化。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.chunkSweepCursor.clear();
        this.sweepCache.clear();
        this.pendingTree = null;
        this.paintedTrees.clear();
        this.playerSweepCursor = 0;
        AutumnLeafStore.save(this.plugin, this.paintedLeaves);
        if (INSTANCE == this) {
            INSTANCE = null;
        }

    }

    /**
     * 周期任务主体：按当前季节阶段推进染色/还原，每次仅消耗有限预算。
     */
    @Override
    public void run() {
        this.workDeadline = System.nanoTime() + 1000000L;
        this.remainingBlocks = 1024;
        this.guessesRemaining = 1;
        CalendarState st = this.seasons.getStateCopy();
        this.gridFlip = !this.gridFlip;
        if (this.originalGuessCache.size() > 4096) {
            this.originalGuessCache.clear();
        }

        int budget = this.chunksPerTick;
        if (budget > 0) {
            Season season = st.season;
            int dayInSeason = this.computeDayInSeason(st);
            double paintFactor = autumnFactor(this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR, season, dayInSeason);
            int stage = (int) Math.round(paintFactor * (double) 100.0F);
            if (stage != this.previousStage) {
                this.pendingTree = null;
                this.validatedLeaves.clear();
                this.scanPositions.clear();
                this.scanTops.clear();
                this.nextScan.clear();
                this.chunkSweepCursor.clear();
                this.restoreCursor = null;
                if (stage > 0 && this.previousStage <= 0) {
                    this.paintedTrees.clear();
                }

                this.previousStage = stage;
            }

            if (paintFactor <= (double) 0.0F) {
                if (!this.restoring) {
                    this.scanPositions.clear();
                    this.nextScan.clear();
                    this.restoring = true;
                }

                if (this.pendingTree != null || !this.paintedTrees.isEmpty() || !this.paintedLeaves.isEmpty()) {
                    this.restoreTree();
                    return;
                }

                if (this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR) {
                    return;
                }

                this.revertSomeLeaves(1024);
            } else if (this.restoring) {
                this.scanPositions.clear();
                this.nextScan.clear();
                this.originalGuessCache.clear();
                this.restoring = false;
            }

            boolean matureAutumn = season == Season.AUTUMN && (this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR || dayInSeason >= 3);
            if (this.pendingTree != null) {
                this.finishPendingTree(false);
            } else {
                List<Player> eligiblePlayers = new ArrayList();
                Map<UUID, List<Offset>> sweeps = new HashMap();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    World world = player.getWorld();
                    if (world.getEnvironment() == Environment.NORMAL && !this.isFxDisabled(world) && !this.seasons.isPermanentWinterWorld(world)) {
                        eligiblePlayers.add(player);
                        sweeps.put(player.getUniqueId(), this.buildChunkSweep(player));
                    }
                }

                if (!eligiblePlayers.isEmpty()) {
                    Set<UUID> onlineEligibleIds = new HashSet();

                    for (Player player : eligiblePlayers) {
                        onlineEligibleIds.add(player.getUniqueId());
                    }

                    this.chunkSweepCursor.keySet().retainAll(onlineEligibleIds);
                    this.sweepCache.keySet().retainAll(onlineEligibleIds);
                    int playerCount = eligiblePlayers.size();
                    int startPlayer = Math.floorMod(this.playerSweepCursor, playerCount);

                    while (budget > 0 && this.hasWorkBudget() && this.pendingTree == null) {
                        boolean processedAny = false;

                        for (int i = 0; i < playerCount && budget > 0 && this.hasWorkBudget() && this.pendingTree == null; ++i) {
                            Player player = (Player) eligiblePlayers.get((startPlayer + i) % playerCount);
                            List<Offset> sweep = (List) sweeps.get(player.getUniqueId());
                            if (this.processNextLoadedChunk(player, sweep, matureAutumn, paintFactor)) {
                                --budget;
                                processedAny = true;
                            }
                        }

                        if (!processedAny) {
                            break;
                        }
                    }

                    this.playerSweepCursor = (startPlayer + 1) % playerCount;
                }
            }
        }
    }

    /**
     * 构建某玩家周围待扫描的区块偏移列表（按距离与朝向评分排序，并做 1 秒缓存）。
     */
    private List<Offset> buildChunkSweep(Player player) {
        long now = System.currentTimeMillis();
        CachedSweep cached = (CachedSweep) this.sweepCache.get(player.getUniqueId());
        if (cached != null && cached.expires() > now) {
            return cached.offsets();
        } else {
            Location loc = player.getLocation();
            Vector look = loc.getDirection().clone();
            look.setY(0);
            if (look.lengthSquared() < 1.0E-4) {
                look = new Vector(0, 0, 1);
            } else {
                look.normalize();
            }

            int radius = this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR ? this.dappledRadiusChunks : this.radiusChunks;
            List<Offset> offsets = new ArrayList((radius * 2 + 1) * (radius * 2 + 1));
            offsets.add(new Offset(0, 0, 0, (double) 1.0F));

            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    if (dist != 0) {
                        Vector dir = (new Vector(dx, 0, dz)).normalize();
                        offsets.add(new Offset(dx, dz, dist, look.dot(dir)));
                    }
                }
            }

            offsets.sort(Comparator.comparingInt((Offset offset) -> offset.dist).thenComparingDouble((Offset offset) -> -offset.forwardScore));
            this.sweepCache.put(player.getUniqueId(), new CachedSweep(now + 1000L, offsets));
            return offsets;
        }
    }

    /**
     * 处理玩家扫描列表中的下一个已加载区块；返回是否真正处理了一个区块。
     */
    private boolean processNextLoadedChunk(Player player, List<Offset> sweep, boolean highDetail, double paintFactor) {
        if (sweep != null && !sweep.isEmpty()) {
            Location loc = player.getLocation();
            World world = player.getWorld();
            int playerChunkX = loc.getBlockX() >> 4;
            int playerChunkZ = loc.getBlockZ() >> 4;
            UUID playerId = player.getUniqueId();
            int cursor = Math.floorMod((Integer) this.chunkSweepCursor.getOrDefault(playerId, 0), sweep.size());

            for (int checked = 0; checked < sweep.size(); ++checked) {
                Offset offset = (Offset) sweep.get(cursor);
                cursor = (cursor + 1) % sweep.size();
                int chunkX = playerChunkX + offset.dx;
                int chunkZ = playerChunkZ + offset.dz;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    String scanKey = world.getUID() + ":" + chunkX + ":" + chunkZ;
                    if ((Long) this.nextScan.getOrDefault(scanKey, 0L) <= System.currentTimeMillis()) {
                        this.processChunk(world, chunkX, chunkZ, highDetail, paintFactor);
                        this.chunkSweepCursor.put(playerId, cursor);
                        return true;
                    }
                }
            }

            this.chunkSweepCursor.put(playerId, cursor);
            return false;
        } else {
            return false;
        }
    }

    private Material guessOriginalFromWorld(Block leaf) {
        String cacheKey = this.key(leaf.getWorld(), leaf.getX(), leaf.getY(), leaf.getZ());
        Optional<Material> cached = this.originalGuessCache.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        } else {
            Material result = this.guessOriginalUncached(leaf);
            this.originalGuessCache.put(cacheKey, Optional.ofNullable(result));
            return result;
        }
    }

    /**
     * 在树叶周围 6 格范围内统计各类原木，据此推断其原始树叶材质；无法判断时返回 null。
     */
    private Material guessOriginalUncached(Block leaf) {
        World world = leaf.getWorld();
        Biome biome = leaf.getBiome();
        int radius = 6;
        int leafX = leaf.getX();
        int leafY = leaf.getY();
        int leafZ = leaf.getZ();
        int spruce = 0;
        int birch = 0;
        int cherry = 0;
        int acacia = 0;
        int poplar = 0;

        for (int x = leafX - radius; x <= leafX + radius; ++x) {
            for (int y = leafY - radius; y <= leafY + radius; ++y) {
                for (int z = leafZ - radius; z <= leafZ + radius; ++z) {
                    if (y >= world.getMinHeight() && y < world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4)) {
                        Material type = world.getBlockAt(x, y, z).getType();
                        if (type == Material.SPRUCE_LOG || type == Material.SPRUCE_WOOD || type == Material.STRIPPED_SPRUCE_LOG || type == Material.STRIPPED_SPRUCE_WOOD) {
                            ++spruce;
                        }

                        if (type == Material.BIRCH_LOG || type == Material.BIRCH_WOOD || type == Material.STRIPPED_BIRCH_LOG || type == Material.STRIPPED_BIRCH_WOOD) {
                            ++birch;
                        }

                        if (type == Material.CHERRY_LOG || type == Material.CHERRY_WOOD || type == Material.STRIPPED_CHERRY_LOG || type == Material.STRIPPED_CHERRY_WOOD) {
                            ++cherry;
                        }

                        if (type == Material.ACACIA_LOG || type == Material.ACACIA_WOOD || type == Material.STRIPPED_ACACIA_LOG || type == Material.STRIPPED_ACACIA_WOOD) {
                            ++acacia;
                        }

                        if (this.isPoplarWood(type)) {
                            ++poplar;
                        }
                    }
                }
            }
        }

        if (spruce == 0 && birch == 0 && cherry == 0) {
            return null;
        } else if (poplar <= 0 && acacia <= 0) {
            if (biome == Biome.CHERRY_GROVE && cherry > 0) {
                return Material.CHERRY_LEAVES;
            } else if (cherry >= birch && cherry >= spruce) {
                return Material.CHERRY_LEAVES;
            } else if (spruce >= birch) {
                return Material.SPRUCE_LEAVES;
            } else {
                return Material.BIRCH_LEAVES;
            }
        } else {
            return null;
        }
    }

    private boolean isTaigaBiome(Biome biome) {
        return biome == Biome.TAIGA || biome == Biome.SNOWY_TAIGA || biome == Biome.OLD_GROWTH_SPRUCE_TAIGA || biome == Biome.OLD_GROWTH_PINE_TAIGA;
    }

    private boolean isBirchBiome(Biome biome) {
        return biome == Biome.BIRCH_FOREST || biome == Biome.OLD_GROWTH_BIRCH_FOREST;
    }

    /**
     * 在给定区块内按预算扫描方块，按阶段对树叶进行染色或还原。
     */
    private void processChunk(World world, int cx, int cz, boolean highDetail, double paintFactor) {
        String scanKey = world.getUID() + ":" + cx + ":" + cz;
        int cursor = (Integer) this.scanPositions.getOrDefault(scanKey, 0);
        int depth = 48;
        int total = 12288;
        int[] tops = (int[]) this.scanTops.computeIfAbsent(scanKey, (ignoredKey) -> {
            int[] values = new int[256];
            Arrays.fill(values, Integer.MIN_VALUE);
            return values;
        });
        int slice = 256;

        while (cursor < total && slice-- > 0 && this.hasWorkBudget() && this.pendingTree == null) {
            int column = scanColumn(cursor);
            int wx = (cx << 4) + column / 16;
            int wz = (cz << 4) + column % 16;
            if (tops[column] == Integer.MIN_VALUE) {
                tops[column] = Math.min(world.getMaxHeight() - 1, world.getHighestBlockYAt(wx, wz));
            }

            int y = tops[column] - cursor / 256;
            ++cursor;
            --this.remainingBlocks;
            if (y >= world.getMinHeight()) {
                Block block = world.getBlockAt(wx, y, wz);
                Material type = block.getType();
                if (this.leafTypes.contains(type) || this.isAutumnPaintMaterial(type)) {
                    BlockData blockData = block.getBlockData();
                    if (blockData instanceof Leaves) {
                        Leaves leavesData = (Leaves) blockData;
                        if (!leavesData.isPersistent()) {
                            String leafKey = this.key(world, wx, y, wz);
                            Material original = (Material) this.paintedLeaves.get(leafKey);
                            if (paintFactor > (double) 0.0F) {
                                if (!this.validatedLeaves.contains(leafKey)) {
                                    if (original == null && this.leafTypes.contains(type)) {
                                        original = type;
                                    }

                                    if (this.leafTypes.contains(original)) {
                                        this.pendingTree = new AutumnTreePlan(block, original, this::originalLeafType);
                                    }
                                }
                            } else {
                                if (original == null && this.isAutumnPaintMaterial(type)) {
                                    if (!this.originalGuessCache.containsKey(leafKey)) {
                                        if (this.guessesRemaining <= 0) {
                                            --cursor;
                                            break;
                                        }

                                        --this.guessesRemaining;
                                    }

                                    original = this.guessOriginalFromWorld(block);
                                    if (original == null) {
                                        continue;
                                    }

                                    this.paintedLeaves.putIfAbsent(leafKey, original);
                                }

                                if (paintFactor <= (double) 0.0F && original != null) {
                                    this.restoreOriginalLeafForDecay(block);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cursor >= total) {
            this.scanPositions.remove(scanKey);
            this.scanTops.remove(scanKey);
            if (this.nextScan.size() > 4096) {
                this.nextScan.clear();
            }

            this.nextScan.put(scanKey, System.currentTimeMillis() + 30000L);
        } else {
            if (this.scanPositions.size() > 512) {
                this.scanPositions.clear();
                this.scanTops.clear();
            }

            this.scanPositions.put(scanKey, cursor);
        }

    }

    private boolean hasWorkBudget() {
        return this.remainingBlocks > 0 && System.nanoTime() < this.workDeadline;
    }

    static double autumnFactor(boolean dappled, Season season, int day) {
        if (season != Season.AUTUMN) {
            return (double) 0.0F;
        } else {
            return day >= 3 ? (double) 1.0F : (day == 2 ? 0.65 : 0.35);
        }
    }

    static int scanColumn(int cursor) {
        return Integer.reverse(cursor & 255) >>> 24;
    }

    static boolean eligibleForStage(long seed, int x, int y, int z, double factor) {
        long hash = seed ^ (long) x * 341873128712L ^ (long) z * 132897987541L ^ (long) y * 42317861L;
        hash = (hash ^ hash >>> 30) * -4658895280553007687L;
        hash = (hash ^ hash >>> 27) * -7723592293110705685L;
        hash ^= hash >>> 31;
        return (double) (hash >>> 11) * (double) 1.110223E-16F < factor;
    }

    private boolean takeTreeBudget() {
        if (!this.hasWorkBudget()) {
            return false;
        } else {
            --this.remainingBlocks;
            return true;
        }
    }

    /**
     * 分步还原已染色的树/树叶：优先还原整棵树，否则按记录逐条回退。
     */
    private void restoreTree() {
        if (!this.paintedTrees.isEmpty()) {
            PaintedTree tree = (PaintedTree) this.paintedTrees.removeFirst();
            this.commitTree(tree.world(), tree.original(), tree.leaves(), tree.original(), true);
        } else {
            if (this.pendingTree == null) {
                if (this.restoreCursor == null || !this.restoreCursor.hasNext()) {
                    this.restoreCursor = this.paintedLeaves.entrySet().iterator();
                }

                while (this.restoreCursor.hasNext() && this.takeTreeBudget()) {
                    Map.Entry<String, Material> entry = (Map.Entry) this.restoreCursor.next();
                    String[] parts = ((String) entry.getKey()).split(":");
                    if (parts.length == 4) {
                        World world = Bukkit.getWorld(UUID.fromString(parts[0]));
                        if (world != null) {
                            int x = Integer.parseInt(parts[1]);
                            int y = Integer.parseInt(parts[2]);
                            int z = Integer.parseInt(parts[3]);
                            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                                Block block = world.getBlockAt(x, y, z);
                                if (!this.isAutumnPaintMaterial(block.getType())) {
                                    this.paintedLeaves.remove(entry.getKey());
                                } else {
                                    if (this.leafTypes.contains(entry.getValue())) {
                                        this.pendingTree = new AutumnTreePlan(block, (Material) entry.getValue(), this::originalLeafType);
                                        break;
                                    }

                                    this.restoreOriginalLeafForDecay(block);
                                }
                            }
                        }
                    }
                }
            }

            if (this.pendingTree != null) {
                this.finishPendingTree(true);
            }

        }
    }

    /**
     * 推进当前待处理的树计划；完成后依据是否还原与阶段判定执行提交或记录验证。
     */
    private void finishPendingTree(boolean restore) {
        AutumnTreePlan plan = this.pendingTree;
        plan.advance(this::takeTreeBudget);
        if (plan.finished()) {
            this.pendingTree = null;
            if (!plan.complete()) {
                if (restore && plan.world.isChunkLoaded(plan.seed.x() >> 4, plan.seed.z() >> 4)) {
                    this.restoreOriginalLeafForDecay(plan.world.getBlockAt(plan.seed.x(), plan.seed.y(), plan.seed.z()));
                }

            } else if (!restore && !eligibleForStage(plan.world.getSeed(), plan.anchor().x(), 0, plan.anchor().z(), (double) this.previousStage / (double) 100.0F * this.leafChancePerBlock)) {
                for (AutumnTreePlan.Pos pos : plan.leaves()) {
                    this.validatedLeaves.add(this.key(plan.world, pos.x(), pos.y(), pos.z()));
                }

            } else {
                Material target = restore ? plan.original : (this.paletteMode == AutumnSoilPainter.PaletteMode.LEGACY_ACACIA ? Material.ACACIA_LEAVES : (Material) this.dappledPalette.get(AutumnTreePlan.paletteIndex(plan.world.getSeed(), plan.anchor(), this.dappledPalette.size())));
                this.commitTree(plan.world, plan.original, plan.leaves(), target, restore);
            }
        }
    }

    /**
     * 对一棵树的全部坐标执行染色（或还原），记录/移除原始材质映射。
     */
    private void commitTree(World world, Material original, Collection<AutumnTreePlan.Pos> positions, Material target, boolean restore) {
        for (AutumnTreePlan.Pos pos : positions) {
            if (!restore && !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                return;
            }
        }

        /** 用于暂存单个方块的叶片数据变更（目标方块、唯一键、原始 Leaves 数据）。 */
        record Change(Block block, String key, Leaves data) {
            Change {
            }
        }

        List<Change> changes = new ArrayList();
        Leaves template = (Leaves) target.createBlockData();

        for (AutumnTreePlan.Pos pos : positions) {
            if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                String leafKey = this.key(world, pos.x(), pos.y(), pos.z());
                if (this.originalLeafType(block) == original) {
                    BlockData currentData = block.getBlockData();
                    if (currentData instanceof Leaves) {
                        Leaves old = (Leaves) currentData;
                        if (!old.isPersistent()) {
                            if (restore) {
                                if (!this.paintedLeaves.containsKey(leafKey)) {
                                    continue;
                                }
                            } else if (this.validatedLeaves.contains(leafKey)) {
                                continue;
                            }

                            Leaves data = (Leaves) template.clone();
                            data.setDistance(old.getDistance());
                            data.setPersistent(false);
                            changes.add(new Change(block, leafKey, data));
                        }
                    }
                }
            }
        }

        for (Change change : changes) {
            if (!restore) {
                this.paintedLeaves.putIfAbsent(change.key(), original);
            }

            if (change.block().getType() != target) {
                change.block().setBlockData(change.data(), false);
            }

            if (restore) {
                this.paintedLeaves.remove(change.key());
            } else {
                this.validatedLeaves.add(change.key());
            }
        }

        if (!restore && !changes.isEmpty()) {
            this.paintedTrees.addLast(new PaintedTree(world, original, List.copyOf(positions)));
        }

    }

    /**
     * 将单个方块染成金合欢树叶并记录其原始材质（保留原距离值）。
     */
    private void paintLeaf(Block block, Material originalType) {
        Material paintedType = Material.ACACIA_LEAVES;
        String leafKey = this.key(block.getWorld(), block.getX(), block.getY(), block.getZ());
        this.paintedLeaves.putIfAbsent(leafKey, originalType);
        if (block.getType() != paintedType) {
            BlockData oldData = block.getBlockData();
            int distance = 1;
            if (oldData instanceof Leaves) {
                Leaves leavesOld = (Leaves) oldData;
                distance = leavesOld.getDistance();
            }

            Leaves newLeaves = (Leaves) paintedType.createBlockData();
            newLeaves.setDistance(distance);
            newLeaves.setPersistent(false);
            block.setBlockData(newLeaves, false);
        }
    }

    private Material originalLeafType(Block block) {
        Material type = block.getType();
        if (this.leafTypes.contains(type)) {
            return type;
        } else {
            return !this.isAutumnPaintMaterial(type) ? null : (Material) this.paintedLeaves.get(this.key(block.getWorld(), block.getX(), block.getY(), block.getZ()));
        }
    }

    /**
     * 在被染色的树叶消失（破坏/凋零/爆炸）前，把它还原为原始材质，避免掉落错误树苗。
     */
    public boolean restoreOriginalLeafForDecay(Block block) {
        if (block == null) {
            return false;
        } else if (!this.isAutumnPaintMaterial(block.getType())) {
            return false;
        } else {
            World world = block.getWorld();
            if (world == null) {
                return false;
            } else {
                String leafKey = this.key(world, block.getX(), block.getY(), block.getZ());
                Material original = (Material) this.paintedLeaves.remove(leafKey);
                if (original != null && original != Material.ACACIA_LEAVES) {
                    BlockData currentData = block.getBlockData();
                    int distance = 1;
                    boolean persistent = false;
                    if (currentData instanceof Leaves) {
                        Leaves currentLeaves = (Leaves) currentData;
                        distance = currentLeaves.getDistance();
                        persistent = currentLeaves.isPersistent();
                    }

                    BlockData backData = original.createBlockData();
                    if (backData instanceof Leaves) {
                        Leaves backLeaves = (Leaves) backData;
                        backLeaves.setDistance(distance);
                        backLeaves.setPersistent(persistent);
                        block.setBlockData(backLeaves, false);
                    } else {
                        block.setType(original, false);
                    }

                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    /** 监听到被染色树叶被破坏前，先还原为原始类型。 */
    public void onPaintedLeafBreak(BlockBreakEvent event) {
        this.restoreOriginalLeafForDecay(event.getBlock());
    }

    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    /** 监听到被染色树叶自然凋零前，先还原为原始类型。 */
    public void onPaintedLeafDecay(LeavesDecayEvent event) {
        this.restoreOriginalLeafForDecay(event.getBlock());
    }

    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    /** 方块爆炸时，先还原爆炸范围内被染色的树叶。 */
    public void onPaintedLeavesBlockExplosion(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            this.restoreOriginalLeafForDecay(block);
        }

    }

    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    /** 实体爆炸时，先还原爆炸范围内被染色的树叶。 */
    public void onPaintedLeavesEntityExplosion(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            this.restoreOriginalLeafForDecay(block);
        }

    }

    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    /** 监听树苗掉落：若落在被染色树叶附近，把金合欢/白杨树苗纠正为对应原树种的树苗。 */
    public void onSaplingSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack itemStack = item.getItemStack();
        if (itemStack.getType() == Material.ACACIA_SAPLING || itemStack.getType() == this.poplarSapling) {
            Location loc = item.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                Block leafBlock = this.findNearbyPaintedLeaf(world, loc);
                Material original = null;
                if (leafBlock != null) {
                    String leafKey = this.key(world, leafBlock.getX(), leafBlock.getY(), leafBlock.getZ());
                    original = (Material) this.paintedLeaves.get(leafKey);
                    if (original == null) {
                        original = this.guessOriginalFromWorld(leafBlock);
                    }

                    if (original == null) {
                        original = this.guessOriginalFromNearbyLogItems(item);
                    }
                } else {
                    original = this.guessOriginalFromWorld(world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                    if (original == null) {
                        original = this.guessOriginalFromNearbyLogItems(item);
                    }
                }

                if (original != null) {
                    Material correct = this.saplingFor(original);
                    if (correct != null && correct != itemStack.getType()) {
                        ItemStack newStack = new ItemStack(correct, itemStack.getAmount());
                        newStack.setItemMeta(itemStack.getItemMeta());
                        item.setItemStack(newStack);
                    }
                }
            }
        }
    }

    /**
     * 通过附近 6 格内的原木掉落物推断原始树种；无法判断时返回 null。
     */
    private Material guessOriginalFromNearbyLogItems(Item saplingItem) {
        Location loc = saplingItem.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return null;
        } else {
            double radius = (double) 6.0F;
            int spruce = 0;
            int birch = 0;
            int cherry = 0;

            for (Entity entity : world.getNearbyEntities(loc, radius, radius, radius)) {
                if (entity instanceof Item) {
                    Item item = (Item) entity;
                    Material type = item.getItemStack().getType();
                    if (type == Material.SPRUCE_LOG || type == Material.SPRUCE_WOOD || type == Material.STRIPPED_SPRUCE_LOG || type == Material.STRIPPED_SPRUCE_WOOD) {
                        ++spruce;
                    }

                    if (type == Material.BIRCH_LOG || type == Material.BIRCH_WOOD || type == Material.STRIPPED_BIRCH_LOG || type == Material.STRIPPED_BIRCH_WOOD) {
                        ++birch;
                    }

                    if (type == Material.CHERRY_LOG || type == Material.CHERRY_WOOD || type == Material.STRIPPED_CHERRY_LOG || type == Material.STRIPPED_CHERRY_WOOD) {
                        ++cherry;
                    }
                }
            }

            if (spruce == 0 && birch == 0 && cherry == 0) {
                return null;
            } else if (spruce >= birch && spruce >= cherry) {
                return Material.SPRUCE_LEAVES;
            } else if (birch >= spruce && birch >= cherry) {
                return Material.BIRCH_LEAVES;
            } else {
                return Material.CHERRY_LEAVES;
            }
        }
    }

    /**
     * 在给定位置附近（水平 2 格、垂直 3 格）寻找最近的被染色树叶方块。
     */
    private Block findNearbyPaintedLeaf(World world, Location loc) {
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();
        int radiusXZ = 2;
        int radiusY = 3;

        for (int dy = -radiusY; dy <= radiusY; ++dy) {
            for (int dx = -radiusXZ; dx <= radiusXZ; ++dx) {
                for (int dz = -radiusXZ; dz <= radiusXZ; ++dz) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    if (this.isAutumnPaintMaterial(block.getType())) {
                        return block;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 在没有整棵树可还原时，按记录逐条还原最多 {@code maxBlocks} 片树叶。
     */
    private void revertSomeLeaves(int maxBlocks) {
        if (maxBlocks > 0) {
            if (this.restoreCursor == null || !this.restoreCursor.hasNext()) {
                this.restoreCursor = this.paintedLeaves.entrySet().iterator();
            }

            Iterator<Map.Entry<String, Material>> iterator = this.restoreCursor;

            while (iterator.hasNext() && maxBlocks-- > 0 && this.hasWorkBudget()) {
                --this.remainingBlocks;
                Map.Entry<String, Material> entry = (Map.Entry) iterator.next();
                String key = (String) entry.getKey();
                Material original = (Material) entry.getValue();
                String[] parts = key.split(":");
                if (parts.length != 4) {
                    iterator.remove();
                } else {
                    UUID worldId;
                    try {
                        worldId = UUID.fromString(parts[0]);
                    } catch (IllegalArgumentException invalidWorldId) {
                        iterator.remove();
                        continue;
                    }

                    World world = Bukkit.getWorld(worldId);
                    if (world == null) {
                        iterator.remove();
                    } else {
                        int x;
                        int y;
                        int z;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                            z = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException invalidCoordinate) {
                            iterator.remove();
                            continue;
                        }

                        if (world.isChunkLoaded(x >> 4, z >> 4)) {
                            Block block = world.getBlockAt(x, y, z);
                            if (!this.isAutumnPaintMaterial(block.getType())) {
                                iterator.remove();
                            } else {
                                BlockData currentData = block.getBlockData();
                                int distance = 1;
                                if (currentData instanceof Leaves) {
                                    Leaves leavesCurrent = (Leaves) currentData;
                                    distance = leavesCurrent.getDistance();
                                }

                                BlockData backData = original.createBlockData();
                                if (backData instanceof Leaves) {
                                    Leaves backLeaves = (Leaves) backData;
                                    backLeaves.setDistance(distance);
                                    backLeaves.setPersistent(false);
                                    block.setBlockData(backLeaves, false);
                                } else {
                                    block.setType(original, false);
                                }

                                iterator.remove();
                            }
                        }
                    }
                }
            }

        }
    }

    private boolean isAutumnPaintMaterial(Material material) {
        return material == Material.ACACIA_LEAVES || this.paletteMode == AutumnSoilPainter.PaletteMode.DAPPLED_POPLAR && this.dappledPalette.contains(material);
    }

    private boolean isPoplarWood(Material material) {
        if (material == null) {
            return false;
        } else {
            String name = material.name();
            return "POPLAR_LOG".equals(name) || "POPLAR_WOOD".equals(name) || "STRIPPED_POPLAR_LOG".equals(name) || "STRIPPED_POPLAR_WOOD".equals(name);
        }
    }

    /**
     * 根据原始树叶材质返回对应的树苗材质（未知类型返回 null）。
     */
    private Material saplingFor(Material originalLeaves) {
        Material sapling;
        switch (originalLeaves) {
            case SPRUCE_LEAVES -> sapling = Material.SPRUCE_SAPLING;
            case BIRCH_LEAVES -> sapling = Material.BIRCH_SAPLING;
            case OAK_LEAVES -> sapling = Material.OAK_SAPLING;
            case DARK_OAK_LEAVES -> sapling = Material.DARK_OAK_SAPLING;
            case JUNGLE_LEAVES -> sapling = Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> sapling = Material.ACACIA_SAPLING;
            case CHERRY_LEAVES -> sapling = Material.CHERRY_SAPLING;
            default -> sapling = null;
        }

        return sapling;
    }

    /**
     * 生成用于记录树叶位置/原始材质的键：{@code 世界UUID:x:y:z}。
     */
    private String key(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ":" + y + ":" + z;
    }

    /**
     * 通过反射从日历状态中推算“季节内第几天”，兼容多种字段/方法命名。
     */
    private int computeDayInSeason(CalendarState st) {
        try {
            try {
                Method m = st.getClass().getMethod("getDayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return this.clampDayInSeason(n.intValue());
                }
            } catch (NoSuchMethodException noGetDayInSeasonMethod) {
                Method m = st.getClass().getMethod("dayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return this.clampDayInSeason(n.intValue());
                }
            }
        } catch (Exception dayLookupError) {
        }

        try {
            for (Field field : st.getClass().getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("dayinseason")) {
                    field.setAccessible(true);
                    Object v = field.get(st);
                    if (v instanceof Number) {
                        Number n = (Number) v;
                        return this.clampDayInSeason(n.intValue());
                    }
                }
            }
        } catch (Exception fieldScanError) {
        }

        Integer dayGlobal = this.tryGetIntField(st, "dayOfYear", "day_of_year", "day");
        if (dayGlobal != null) {
            int day = (dayGlobal - 1) % 28 + 1;
            return this.clampDayInSeason(day);
        } else {
            return 1;
        }
    }

    /**
     * 判断该世界是否已在“禁用季节特效世界”列表中。
     */
    private boolean isFxDisabled(World world) {
        return world != null && this.disabledFxWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * 通过反射尝试读取指定名称的整型字段（兼容 getter/直接方法/字段三种形式），失败返回 null。
     */
    private Integer tryGetIntField(CalendarState st, String... candidates) {
        try {
            for (String raw : candidates) {
                String name = raw;
                String firstCharUpper = raw.substring(0, 1).toUpperCase(Locale.ROOT);
                String getter = "get" + firstCharUpper + raw.substring(1);

                try {
                    Method m = st.getClass().getMethod(getter);
                    Object v = m.invoke(st);
                    if (v instanceof Number n) {
                        return n.intValue();
                    }
                } catch (NoSuchMethodException noGetterMethod) {
                }

                try {
                    Method m2 = st.getClass().getMethod(name);
                    Object v2 = m2.invoke(st);
                    if (v2 instanceof Number n2) {
                        return n2.intValue();
                    }
                } catch (NoSuchMethodException noFieldMethod) {
                }
            }

            for (Field field : st.getClass().getDeclaredFields()) {
                String fn = field.getName();

                for (String cand : candidates) {
                    if (fn.equalsIgnoreCase(cand)) {
                        field.setAccessible(true);
                        Object v = field.get(st);
                        if (v instanceof Number) {
                            Number n = (Number) v;
                            return n.intValue();
                        }
                    }
                }
            }
        } catch (Exception error) {
        }

        return null;
    }

    private int clampDayInSeason(int day) {
        int max = Math.max(1, this.seasons.getDaysPerSeason());
        if (day < 1) {
            day = 1;
        }

        if (day > max) {
            day = max;
        }

        return day;
    }

    private double computePreAutumnFactor(int dayInSeason) {
        if (dayInSeason < 26) {
            return (double) 0.0F;
        } else {
            int step = dayInSeason - 26;
            double factor = (double) (step + 1) / (double) 3.0F;
            if (factor < (double) 0.0F) {
                factor = (double) 0.0F;
            }

            if (factor > (double) 1.0F) {
                factor = (double) 1.0F;
            }

            return factor;
        }
    }

    public static AutumnSoilPainter instanceOrNull() {
        return INSTANCE;
    }

    /**
     * 树叶配色方案：禁用、旧版金合欢、Dappled 白杨三选一。
     */
    private enum PaletteMode {
        DISABLED,
        LEGACY_ACACIA,
        DAPPLED_POPLAR;

        PaletteMode() {
        }

        // $FF: synthetic method
        private static PaletteMode[] $values() {
            return new PaletteMode[]{DISABLED, LEGACY_ACACIA, DAPPLED_POPLAR};
        }
    }

    /**
     * 玩家区块扫描偏移列表的缓存，带过期时间戳。
     */
    private record CachedSweep(long expires, List<Offset> offsets) {
        private CachedSweep {
        }
    }

    /**
     * 已染色且待还原的树：记录世界、原始材质与其全部树叶坐标。
     */
    private record PaintedTree(World world, Material original, List<AutumnTreePlan.Pos> leaves) {
        private PaintedTree {
        }
    }

    /**
     * 单个区块偏移：记录相对偏移、切比雪夫距离与朝向评分，用于排序扫描顺序。
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
}
