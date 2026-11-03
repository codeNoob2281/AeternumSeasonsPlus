package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 季节植被控制器。
 *
 * <p>本类是季节性植物效果的核心：按配置的规则（{@link FloraRule}）在季节变化时，于玩家周围的区块中
 * 清除应消失的植被、并按概率恢复到应出现的植被；同时监听方块的放置、破坏、蔓延、催熟、生长与玩家交互，
 * 阻止在“非当前季节允许”或“不允许的生物群系”中产生或扩散这些植被。</p>
 *
 * <p>为避免卡顿，工作以 tick 预算（每 tick 的方块数/区块数）分片执行，并对玩家放置与本插件放置的方块
 * 用区块持久化数据打标记（{@code flora_player} / {@code flora_plugin}），既支持保护玩家方块，也支持
 * 区分自然/插件来源以决定是否清理。区域权限统一委托 {@link WinterWorldGuardHelper} 判定。</p>
 */
public final class SeasonalFloraController implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private boolean enabled;
    private int tickPeriod;
    private int innerRadiusChunksCfg;
    private int outerRadiusChunksCfg;
    private int budgetPerTick;
    private int maxChunksPerTick;
    private boolean protectPlayerPlaced;
    private int surfaceScanDepth;
    private boolean allowInView;
    private long scanCursor;
    private int playerCursor;
    /**
     * 每个区块已扫描到的列游标（用于分片清理，避免每次从头扫描）。
     */
    private final Map<Long, Integer> purgeColumnCursors = new HashMap();
    /**
     * 每个世界最近一次清理时所处的季节。
     */
    private final Map<UUID, Season> lastCleanupSeason = new HashMap();
    /**
     * 每个世界的“加速清理”截止时间戳。
     */
    private final Map<UUID, Long> cleanupBoostUntil = new HashMap();
    private int normalTicksRemaining;
    private long cleanupDeadlineNs = Long.MAX_VALUE;
    private static final long CLEANUP_BOOST_MS = 30000L;
    private BukkitTask task;
    /**
     * 按 id 索引的植被规则。
     */
    private final Map<String, FloraRule> rules = new LinkedHashMap();
    /**
     * 按方块材质索引的植被规则（一个材质可对应多条规则）。
     */
    private final Map<Material, List<FloraRule>> rulesByMaterial = new EnumMap(Material.class);
    /**
     * 玩家放置方块的标记缓存：区块键 -> 方块键集合。
     */
    private final Map<Long, Set<Long>> playerPlaced = new ConcurrentHashMap();
    /**
     * 本插件放置方块的标记缓存：区块键 -> 方块键集合。
     */
    private final Map<Long, Set<Long>> pluginPlaced = new ConcurrentHashMap();
    private Set<String> disabledSeasonFxWorlds = Set.of();
    private static final int OFFSETS_STEP = 1;
    private static final int SAMPLES_PER_CHUNK = 28;
    private static final Object HM_MOTION_NO_LEAVES;
    private static final Method M_GET_HIGHEST_Y_HM;

    public SeasonalFloraController(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.reloadFromConfig();
    }

    /**
     * 注册事件监听并启动周期任务。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.enabled) {
            this.normalTicksRemaining = 0;
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 1L, (long) Math.min(5, this.tickPeriod));
        }
    }

    /**
     * 注销事件监听、取消任务并清空所有缓存。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        HandlerList.unregisterAll(this);
        this.rules.clear();
        this.rulesByMaterial.clear();
        this.playerPlaced.clear();
        this.pluginPlaced.clear();
        this.purgeColumnCursors.clear();
        this.lastCleanupSeason.clear();
        this.cleanupBoostUntil.clear();
    }

    /**
     * 从配置重新读取参数并重建植被规则表。
     */
    public void reloadFromConfig() {
        this.enabled = this.plugin.cfg.climate.getBoolean("seasonal_flora.enabled", true);
        this.tickPeriod = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.tick_period_ticks", 10));
        this.innerRadiusChunksCfg = Math.max(0, this.plugin.cfg.climate.getInt("seasonal_flora.inner_radius_chunks", 0));
        this.outerRadiusChunksCfg = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.outer_radius_chunks", 8));
        this.budgetPerTick = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.budget_blocks_per_tick", 120));
        this.maxChunksPerTick = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.max_chunks_per_tick", 8));
        this.protectPlayerPlaced = this.plugin.cfg.climate.getBoolean("seasonal_flora.protect_player_placed", true);
        this.allowInView = this.plugin.cfg.climate.getBoolean("seasonal_flora.allow_in_view", true);
        this.surfaceScanDepth = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.surface_scan_depth", 8));
        List<String> disabledList = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
        this.disabledSeasonFxWorlds = (disabledList == null ? List.<String>of() : disabledList).stream().filter(Objects::nonNull).map((name) -> name.trim().toLowerCase(Locale.ROOT)).filter((name) -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.rules.clear();
        this.rulesByMaterial.clear();
        ConfigurationSection rulesSection = this.plugin.cfg.climate.getConfigurationSection("seasonal_flora.rules");
        if (rulesSection == null) {
            this.plugin.getLogger().info("[SeasonalFlora] No rules found at seasonal_flora.rules");
        } else {
            int loaded = 0;
            int purgeOnly = 0;

            for (String id : rulesSection.getKeys(false)) {
                ConfigurationSection ruleSection = rulesSection.getConfigurationSection(id);
                if (ruleSection != null) {
                    FloraRule rule = SeasonalFloraController.FloraRule.fromConfig(id, ruleSection, this.plugin);
                    if (rule.enabled || rule.purgeWhenDisabled) {
                        if (rule.blocks.isEmpty()) {
                            this.plugin.getLogger().warning("[SeasonalFlora] rule '" + id + "' has no blocks, skipped.");
                        } else {
                            this.rules.put(id, rule);
                            ++loaded;
                            if (!rule.enabled && rule.purgeWhenDisabled) {
                                ++purgeOnly;
                            }

                            for (Material material : rule.blocks) {
                                ((List) this.rulesByMaterial.computeIfAbsent(material, (key) -> new ArrayList())).add(rule);
                            }
                        }
                    }
                }
            }

            this.plugin.getLogger().info("[SeasonalFlora] Loaded " + loaded + " flora rules (" + purgeOnly + " purge-only).");
        }
    }

    /**
     * 判断该世界是否启用季节特效（未列入 disabled_season_fx）。
     */
    private boolean isWorldFxEnabled(World w) {
        if (w == null) {
            return false;
        } else {
            return !this.disabledSeasonFxWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 监听方块放置：为受规则管理的植被记录“玩家放置”标记，避免被季节清理误删。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent e) {
        if (this.enabled && this.protectPlayerPlaced) {
            if (this.isWorldFxEnabled(e.getBlock().getWorld())) {
                Material type = e.getBlockPlaced().getType();
                if (this.rulesByMaterial.containsKey(type)) {
                    Block block = e.getBlockPlaced();
                    this.markPlayerPlaced(block);
                    if (this.isDoublePlant(type)) {
                        Block up = block.getRelative(BlockFace.UP);
                        if (up.getType() == type) {
                            this.markPlayerPlaced(up);
                        }
                    }

                }
            }
        }
    }

    /**
     * 监听方块蔓延：若目标植被当前季节禁止蔓延或生物群系不允许，则取消蔓延。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBlockSpread(BlockSpreadEvent e) {
        if (this.enabled) {
            if (this.isWorldFxEnabled(e.getBlock().getWorld())) {
                Material newType = e.getNewState().getType();
                List<FloraRule> list = (List) this.rulesByMaterial.get(newType);
                if (list != null) {
                    Location newLocation = e.getNewState().getLocation();
                    Season season = this.seasons.getStateCopy(newLocation.getWorld()).season;
                    Biome newBiome = newLocation.getWorld().getBiome(newLocation.getBlockX(), newLocation.getBlockY(), newLocation.getBlockZ());

                    for (FloraRule rule : list) {
                        if (rule.enabled && (!rule.spreadSeasons.contains(season) || !rule.isBiomeAllowed(newBiome))) {
                            e.setCancelled(true);
                            return;
                        }
                    }

                }
            }
        }
    }

    /**
     * 监听方块破坏：清除对应的“玩家放置”标记（双格植物同时处理上下两格）。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBlockBreak(BlockBreakEvent e) {
        if (this.enabled && this.protectPlayerPlaced) {
            Material type = e.getBlock().getType();
            if (this.rulesByMaterial.containsKey(type)) {
                Block block = e.getBlock();
                this.unmarkPlayerPlaced(block);
                if (this.isDoublePlant(type)) {
                    Block up = block.getRelative(BlockFace.UP);
                    if (up.getType() == type) {
                        this.unmarkPlayerPlaced(up);
                    }

                    Block down = block.getRelative(BlockFace.DOWN);
                    if (down.getType() == type) {
                        this.unmarkPlayerPlaced(down);
                    }
                }

            }
        }
    }

    /**
     * 监听骨粉催熟：取消不允许的植被生长，并标记由玩家催生出的方块。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBlockFertilize(BlockFertilizeEvent e) {
        if (this.enabled) {
            if (this.isWorldFxEnabled(e.getBlock().getWorld())) {
                for (BlockState blockState : e.getBlocks()) {
                    List<FloraRule> list = (List) this.rulesByMaterial.get(blockState.getType());
                    if (list != null) {
                        Location location = blockState.getLocation();
                        Season season = this.seasons.getStateCopy(location.getWorld()).season;
                        Biome biome = location.getWorld().getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());

                        for (FloraRule rule : list) {
                            if (rule.enabled && (!rule.spreadSeasons.contains(season) || !rule.isBiomeAllowed(biome))) {
                                e.setCancelled(true);
                                return;
                            }
                        }
                    }
                }

                if (this.protectPlayerPlaced && e.getPlayer() != null) {
                    for (BlockState blockState : e.getBlocks()) {
                        Block placed = blockState.getBlock();
                        if (this.rulesByMaterial.containsKey(placed.getType())) {
                            this.markPlayerPlaced(placed);
                            if (this.isDoublePlant(placed.getType())) {
                                Block up = placed.getRelative(BlockFace.UP);
                                if (up.getType() == placed.getType()) {
                                    this.markPlayerPlaced(up);
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * 监听方块生长：取消不允许的植被生长。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBlockGrow(BlockGrowEvent e) {
        if (this.enabled) {
            if (this.isWorldFxEnabled(e.getBlock().getWorld())) {
                Material type = e.getNewState().getType();
                List<FloraRule> list = (List) this.rulesByMaterial.get(type);
                if (list != null) {
                    Location location = e.getBlock().getLocation();
                    Season season = this.seasons.getStateCopy(location.getWorld()).season;
                    Biome biome = location.getWorld().getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());

                    for (FloraRule rule : list) {
                        if (rule.enabled && (!rule.spreadSeasons.contains(season) || !rule.isBiomeAllowed(biome))) {
                            e.setCancelled(true);
                            return;
                        }
                    }

                }
            }
        }
    }

    /**
     * 监听玩家右键骨粉：对当前季节禁止的植被取消使用骨粉。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (this.enabled) {
            if (this.isWorldFxEnabled(e.getPlayer().getWorld())) {
                if (e.getHand() == EquipmentSlot.HAND) {
                    if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                        ItemStack item = e.getItem();
                        if (item != null && item.getType() == Material.BONE_MEAL) {
                            Block block = e.getClickedBlock();
                            if (block != null) {
                                List<FloraRule> list = (List) this.rulesByMaterial.get(block.getType());
                                if (list != null) {
                                    Season season = this.seasons.getStateCopy(block.getWorld()).season;

                                    for (FloraRule rule : list) {
                                        if (rule.enabled && !rule.spreadSeasons.contains(season)) {
                                            e.setCancelled(true);
                                            return;
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
     * 周期任务入口：在预算范围内轮转玩家与区块，处理植被的清理与恢复。
     */
    @Override
    public void run() {
        if (this.enabled && !this.rules.isEmpty()) {
            this.normalTicksRemaining -= Math.min(5, this.tickPeriod);
            boolean normalPass = this.normalTicksRemaining <= 0;
            if (normalPass) {
                this.normalTicksRemaining += this.tickPeriod;
            }

            this.cleanupDeadlineNs = normalPass ? Long.MAX_VALUE : System.nanoTime() + 1000000L;
            int budget = this.budgetPerTick;
            if (budget > 0) {
                Set<Long> processedThisTick = new HashSet();
                List<Player> players = new ArrayList(Bukkit.getOnlinePlayers());
                if (!players.isEmpty()) {
                    Collections.rotate(players, -Math.floorMod(this.playerCursor++, players.size()));
                    long cursor = (long) (this.scanCursor++);

                    for (Player player : players) {
                        if (budget <= 0 || System.nanoTime() >= this.cleanupDeadlineNs || processedThisTick.size() >= this.maxChunksPerTick) {
                            break;
                        }

                        World world = player.getWorld();
                        if (this.isWorldFxEnabled(world) && world.getEnvironment() == Environment.NORMAL) {
                            CalendarState state = this.seasons.getStateCopy(world);
                            Season season = state.season;
                            boolean cleanupOnly = this.isCleanupBoost(world.getUID(), season, System.currentTimeMillis());
                            if (normalPass || cleanupOnly) {
                                if (cleanupOnly && this.cleanupDeadlineNs == Long.MAX_VALUE) {
                                    this.cleanupDeadlineNs = System.nanoTime() + 1000000L;
                                }

                                int view = Bukkit.getViewDistance();
                                int outer = Math.min(Math.max(this.outerRadiusChunksCfg, 1), view);
                                int inner = Math.min(this.innerRadiusChunksCfg, Math.max(0, outer - 1));
                                Location location = player.getLocation();
                                int playerChunkX = location.getBlockX() >> 4;
                                int playerChunkZ = location.getBlockZ() >> 4;
                                int width = outer * 2 + 1;
                                int count = width * width;

                                for (int offset = 0; offset < count && budget > 0 && System.nanoTime() < this.cleanupDeadlineNs && processedThisTick.size() < this.maxChunksPerTick; ++offset) {
                                    int index = Math.floorMod(cursor * (long) this.maxChunksPerTick + (long) offset, count);
                                    int dx = index / width - outer;
                                    int dz = index % width - outer;
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) >= inner) {
                                        int cx = playerChunkX + dx;
                                        int cz = playerChunkZ + dz;
                                        if (world.isChunkLoaded(cx, cz)) {
                                            long chunkKey = this.chunkKey(world, cx, cz);
                                            if (processedThisTick.add(chunkKey)) {
                                                Chunk chunk = world.getChunkAt(cx, cz);
                                                budget = this.processChunk(chunk, season, budget, cleanupOnly);
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

    /**
     * 当世界季节发生变化时触发一段时间的加速清理，并返回当前是否处于加速窗口。
     */
    boolean isCleanupBoost(UUID world, Season season, long now) {
        Season previous = (Season) this.lastCleanupSeason.put(world, season);
        if (previous != season) {
            this.cleanupBoostUntil.put(world, now + 30000L);
        }

        return now < (Long) this.cleanupBoostUntil.getOrDefault(world, 0L);
    }

    /**
     * 处理单个区块：按预算清理该季节应消失的植被，并尝试在合适位置恢复植被。
     *
     * @return 剩余预算
     */
    private int processChunk(Chunk ch, Season season, int budget, boolean cleanupOnly) {
        if (budget <= 0) {
            return 0;
        } else {
            World world = ch.getWorld();
            int baseX = ch.getX() << 4;
            int baseZ = ch.getZ() << 4;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            long cleanupChunkKey = this.chunkKey(world, ch.getX(), ch.getZ());
            int cleanupCursor = (Integer) this.purgeColumnCursors.getOrDefault(cleanupChunkKey, 0);
            int samples = cleanupOnly ? 56 : 28;

            for (int i = 0; i < samples && budget > 0 && System.nanoTime() < this.cleanupDeadlineNs; ++i) {
                int column = cleanupCursor++ & 255;
                int x = baseX + column / 16;
                int z = baseZ + column % 16;
                int surfaceY = this.getSurfaceY(world, x, z);
                Block block = this.findPurgeCandidate(world, x, surfaceY, z, this.surfaceScanDepth);
                if (block != null) {
                    Material type = block.getType();
                    List<FloraRule> list = (List) this.rulesByMaterial.get(type);
                    if (list != null) {
                        for (FloraRule rule : list) {
                            boolean purgeSeason = rule.enabled && rule.removeSeasons.contains(season);
                            boolean purgeDisabled = !rule.enabled && rule.purgeWhenDisabled;
                            if ((purgeSeason || purgeDisabled) && !this.isProtectedByPlayer(block) && !this.isBambooLike(type) && this.mayChangeFlora(block, true)) {
                                boolean placedByPlugin = this.isPlacedByPlugin(block);
                                boolean canPurge = rule.purgePluginPlaced && placedByPlugin || rule.purgeNatural && !placedByPlugin;
                                if (canPurge) {
                                    Material replaceWith = rule.replaceWith != null ? rule.replaceWith : Material.AIR;
                                    int removed = this.purgeBlockRespectingShape(block, replaceWith);
                                    if (removed > 0) {
                                        budget -= removed;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (this.purgeColumnCursors.size() > 4096) {
                this.purgeColumnCursors.clear();
            }

            this.purgeColumnCursors.put(cleanupChunkKey, cleanupCursor & 255);
            if (budget > 0 && !cleanupOnly && System.nanoTime() < this.cleanupDeadlineNs) {
                for (FloraRule rule : this.rules.values()) {
                    if (budget <= 0) {
                        break;
                    }

                    if (rule.enabled && rule.restoreSeasons.contains(season) && !(rnd.nextDouble() > rule.restoreChance)) {
                        if (rule.maxPerChunk > 0) {
                            int existing = this.countRuleBlocksInChunk(ch, rule.blocks, rule.maxPerChunk);
                            if (existing >= rule.maxPerChunk) {
                                continue;
                            }
                        }

                        for (int tries = 0; tries < rule.restoreTriesPerChunk && budget > 0; ++tries) {
                            int x = baseX + rnd.nextInt(16);
                            int z = baseZ + rnd.nextInt(16);
                            int y = this.getSurfaceY(world, x, z);
                            Block groundCandidate = null;

                            for (int scanY = y; scanY >= Math.max(world.getMinHeight(), y - this.surfaceScanDepth); --scanY) {
                                Block candidate = world.getBlockAt(x, scanY, z);
                                if (candidate.isLiquid()) {
                                    break;
                                }

                                if (candidate.getType().isSolid() && !candidate.getType().name().endsWith("_LEAVES")) {
                                    groundCandidate = candidate;
                                    break;
                                }
                            }

                            if (groundCandidate != null) {
                                Block placeAt = groundCandidate.getRelative(BlockFace.UP);
                                if (placeAt.getType().isAir() && !this.isProtectedByPlayer(placeAt) && this.mayChangeFlora(placeAt, false)) {
                                    Biome biome = world.getBiome(x, placeAt.getY(), z);
                                    if (rule.isBiomeAllowed(biome)) {
                                        int light = placeAt.getLightLevel();
                                        if ((rule.blocks.contains(Material.RED_MUSHROOM) || rule.blocks.contains(Material.BROWN_MUSHROOM)) && world.getTime() >= 13000L && world.getTime() < 23000L) {
                                            light = Math.max(placeAt.getLightFromBlocks(), Math.max(0, placeAt.getLightFromSky() - 11));
                                        }

                                        if (light >= rule.minLight && light <= rule.maxLight) {
                                            Block ground = placeAt.getRelative(BlockFace.DOWN);
                                            if ((!rule.requireSolidGround || ground.getType().isSolid() && (rule.groundBlocks.isEmpty() || rule.groundBlocks.contains(ground.getType()))) && (!rule.forbidCanopy || this.isOpenSky(placeAt, rule.canopyCheckHeight)) && (rule.minDistanceBlocks <= 0 || !this.hasNearbyRuleBlock(placeAt, rule.blocks, rule.minDistanceBlocks))) {
                                                boolean placed = this.placeRuleBlock(rule, placeAt, rnd);
                                                if (placed) {
                                                    --budget;
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

                return budget;
            } else {
                return budget;
            }
        }
    }

    /**
     * 统计区块内指定材质方块数量，达到上限即提前返回。
     */
    private int countRuleBlocksInChunk(Chunk ch, List<Material> matrices, int cap) {
        World world = ch.getWorld();
        int baseX = ch.getX() << 4;
        int baseZ = ch.getZ() << 4;
        int count = 0;

        for (int dx = 0; dx < 16; ++dx) {
            for (int dz = 0; dz < 16; ++dz) {
                int x = baseX + dx;
                int z = baseZ + dz;
                Block block = this.findRuleBlockInColumn(world, x, z, matrices);
                if (block != null && matrices.contains(block.getType())) {
                    ++count;
                    if (count >= cap) {
                        return count;
                    }
                }
            }
        }

        return count;
    }

    /**
     * 判断是否允许修改该方块：受 WorldGuard 区域限制，且（当不允许改动视野内方块时）避免玩家视线内的改动。
     */
    private boolean mayChangeFlora(Block block, boolean removing) {
        if (!WinterWorldGuardHelper.canModify(block)) {
            return false;
        } else if (this.allowInView) {
            return true;
        } else {
            Location center = block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);

            for (Player viewer : block.getWorld().getPlayers()) {
                Vector delta = center.toVector().subtract(viewer.getEyeLocation().toVector());
                double distance = delta.lengthSquared();
                if (distance < (double) 64.0F) {
                    return false;
                }

                if (distance < (double) 4096.0F && viewer.getEyeLocation().getDirection().dot(delta.normalize()) > 0.15) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * 判断中心方块周围半径内是否存在指定材质之一。
     */
    private boolean hasNearbyRuleBlock(Block center, List<Material> matrices, int radius) {
        World world = center.getWorld();
        int centerX = center.getX();
        int centerY = center.getY();
        int centerZ = center.getZ();
        int effectiveRadius = Math.min(radius, 8);

        for (int dx = -effectiveRadius; dx <= effectiveRadius; ++dx) {
            for (int dz = -effectiveRadius; dz <= effectiveRadius; ++dz) {
                if (dx * dx + dz * dz <= effectiveRadius * effectiveRadius) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                        if (matrices.contains(block.getType())) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 依据规则在指定位置放置植被方块（含可可豆、双格植物的特殊处理）。
     */
    private boolean placeRuleBlock(FloraRule rule, Block placeAt, ThreadLocalRandom rnd) {
        Material toPlace = rule.blocks.size() == 1 ? (Material) rule.blocks.get(0) : (Material) rule.blocks.get(rnd.nextInt(rule.blocks.size()));
        if (rule.requiresAttachment && toPlace == Material.COCOA) {
            boolean ok = this.placeCocoa(rule, placeAt, rnd);
            if (ok) {
                this.markPluginPlaced(placeAt);
            }

            return ok;
        } else if (rule.doublePlant && this.isDoublePlant(toPlace)) {
            Block up = placeAt.getRelative(BlockFace.UP);
            if (!up.getType().isAir()) {
                return false;
            } else {
                this.placeDoublePlant(placeAt, toPlace);
                this.markPluginPlaced(placeAt);
                this.markPluginPlaced(up);
                return true;
            }
        } else {
            placeAt.setType(toPlace, false);
            this.markPluginPlaced(placeAt);
            return true;
        }
    }

    /**
     * 在可附着面上放置可可豆。
     */
    private boolean placeCocoa(FloraRule rule, Block placeAt, ThreadLocalRandom rnd) {
        List<BlockFace> faces = new ArrayList(List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST));
        Collections.shuffle(faces, rnd);

        for (BlockFace face : faces) {
            Block attached = placeAt.getRelative(face);
            if (rule.attachBlocks.isEmpty() || rule.attachBlocks.contains(attached.getType())) {
                placeAt.setType(Material.COCOA, false);
                BlockData blockData = placeAt.getBlockData();
                if (blockData instanceof Cocoa) {
                    Cocoa cocoa = (Cocoa) blockData;
                    cocoa.setFacing(face.getOppositeFace());
                    cocoa.setAge(0);
                    placeAt.setBlockData(cocoa, false);
                }

                return true;
            }
        }

        return false;
    }

    /**
     * 放置双格植物并正确设置上下半部分。
     */
    private void placeDoublePlant(Block lower, Material mat) {
        lower.setType(mat, false);
        Block upper = lower.getRelative(BlockFace.UP);
        upper.setType(mat, false);
        BlockData lowerData = lower.getBlockData();
        BlockData upperData = upper.getBlockData();
        if (lowerData instanceof Bisected lowerBisected) {
            if (upperData instanceof Bisected upperBisected) {
                lowerBisected.setHalf(Half.BOTTOM);
                upperBisected.setHalf(Half.TOP);
                lower.setBlockData(lowerBisected, false);
                upper.setBlockData(upperBisected, false);
            }
        }

    }

    /**
     * 判断从指定方块向上给定高度内是否均为空气/透明方块（即天空可见）。
     */
    private boolean isOpenSky(Block placeAt, int height) {
        Block block = placeAt;

        for (int i = 0; i < height; ++i) {
            block = block.getRelative(BlockFace.UP);
            Material type = block.getType();
            if (!type.isAir() && !type.isTransparent()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判断材质是否为双格植物（向日葵、玫瑰丛、丁香、牡丹）。
     */
    private boolean isDoublePlant(Material m) {
        String name = m.name();
        return "SUNFLOWER".equals(name) || "ROSE_BUSH".equals(name) || "LILAC".equals(name) || "PEONY".equals(name);
    }

    /**
     * 判断材质是否为竹子或竹笋。
     */
    private boolean isBambooLike(Material m) {
        return m == Material.BAMBOO || m == Material.BAMBOO_SAPLING;
    }

    /**
     * 检查竹子所在整列（向上 48 格内）是否存在任意标记。
     */
    private boolean hasAnyMarkInBambooColumn(World w, int x, int y, int z, Set<Long> marks) {
        if (marks != null && !marks.isEmpty()) {
            int DOWN_CAP = 32;
            int UP_CAP = 48;
            int baseY = y;

            for (int i = 0; i < 32; ++i) {
                int ny = y - i;
                if (ny < w.getMinHeight()) {
                    break;
                }

                Material type = w.getBlockAt(x, ny, z).getType();
                if (!this.isBambooLike(type)) {
                    break;
                }

                baseY = ny;
            }

            for (int i = 0; i < 48; ++i) {
                int ny = baseY + i;
                if (ny >= w.getMaxHeight()) {
                    break;
                }

                Material type = w.getBlockAt(x, ny, z).getType();
                if (!this.isBambooLike(type)) {
                    break;
                }

                long blockKey = this.blockKey(w, x, ny, z);
                if (marks.contains(blockKey)) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    /**
     * 清理整列竹子，返回实际改变的方块数量。
     */
    private int purgeBambooColumn(Block any, Material replaceWith) {
        World world = any.getWorld();
        int x = any.getX();
        int z = any.getZ();
        int y = any.getY();
        if (!this.isBambooLike(any.getType())) {
            return 0;
        } else {
            int DOWN_CAP = 32;
            int UP_CAP = 48;
            int baseY = y;

            for (int i = 0; i < 32; ++i) {
                int ny = y - i;
                if (ny < world.getMinHeight()) {
                    break;
                }

                Material type = world.getBlockAt(x, ny, z).getType();
                if (!this.isBambooLike(type)) {
                    break;
                }

                baseY = ny;
            }

            int changed = 0;

            for (int i = 0; i < 48; ++i) {
                int ny = baseY + i;
                if (ny >= world.getMaxHeight()) {
                    break;
                }

                Block currentBlock = world.getBlockAt(x, ny, z);
                if (!this.isBambooLike(currentBlock.getType())) {
                    break;
                }

                currentBlock.setType(replaceWith, false);
                this.unmarkPluginPlaced(currentBlock);
                this.unmarkPlayerPlaced(currentBlock);
                ++changed;
            }

            return changed;
        }
    }

    /**
     * 从地表向上下一段范围内寻找需要清理的植被方块。
     */
    private Block findPurgeCandidate(World w, int x, int surfaceY, int z, int depth) {
        int minY = Math.max(w.getMinHeight(), surfaceY - depth);
        int startY = Math.min(w.getMaxHeight() - 1, surfaceY + 2);

        for (int y = startY; y >= minY; --y) {
            Block block = w.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type != Material.AIR && type != Material.SNOW && type != Material.SNOW_BLOCK) {
                if (this.rulesByMaterial.containsKey(type)) {
                    return block;
                }

                if (type.isSolid()) {
                    Block up = block.getRelative(BlockFace.UP);
                    if (this.rulesByMaterial.containsKey(up.getType())) {
                        return up;
                    }
                    break;
                }
            }
        }

        return null;
    }

    /**
     * 在指定列中寻找符合给定材质的植被方块。
     */
    private Block findRuleBlockInColumn(World w, int x, int z, List<Material> mats) {
        int surfaceY = this.getSurfaceYCompat(w, x, z);
        int depth = Math.max(1, this.plugin.cfg.climate.getInt("seasonal_flora.surface_scan_depth", 8));
        int minY = Math.max(w.getMinHeight(), surfaceY - depth);
        int startY = Math.min(w.getMaxHeight() - 1, surfaceY + 2);

        for (int y = startY; y >= minY; --y) {
            Block block = w.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type != Material.AIR && type != Material.SNOW && type != Material.SNOW_BLOCK) {
                if (mats.contains(type)) {
                    return block;
                }

                if (type.isSolid()) {
                    Block up = block.getRelative(BlockFace.UP);
                    if (mats.contains(up.getType())) {
                        return up;
                    }
                    break;
                }
            }
        }

        return null;
    }

    /**
     * 获取列的最高方块 Y（优先使用 MOTION_BLOCKING_NO_LEAVES，回退到普通高度）。
     */
    private int getSurfaceYCompat(World w, int x, int z) {
        try {
            return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (Throwable ignored) {
            return w.getHighestBlockYAt(x, z);
        }
    }

    /**
     * 判断该方块是否被玩家放置标记保护。
     */
    private boolean isProtectedByPlayer(Block b) {
        if (!this.protectPlayerPlaced) {
            return false;
        } else {
            World world = b.getWorld();
            this.chunkKey(world, b.getX() >> 4, b.getZ() >> 4);
            Set<Long> marks = this.persistedMarks(b, this.playerPlaced, "flora_player");
            if (marks != null && !marks.isEmpty()) {
                long blockKey = this.blockKey(world, b.getX(), b.getY(), b.getZ());
                if (marks.contains(blockKey)) {
                    return true;
                } else {
                    return this.isBambooLike(b.getType()) && this.hasAnyMarkInBambooColumn(world, b.getX(), b.getY(), b.getZ(), marks);
                }
            } else {
                return false;
            }
        }
    }

    /**
     * 判断该方块是否被本插件放置标记记录。
     */
    private boolean isPlacedByPlugin(Block b) {
        World world = b.getWorld();
        this.chunkKey(world, b.getX() >> 4, b.getZ() >> 4);
        Set<Long> marks = this.persistedMarks(b, this.pluginPlaced, "flora_plugin");
        if (marks != null && !marks.isEmpty()) {
            long blockKey = this.blockKey(world, b.getX(), b.getY(), b.getZ());
            if (marks.contains(blockKey)) {
                return true;
            } else {
                return this.isBambooLike(b.getType()) && this.hasAnyMarkInBambooColumn(world, b.getX(), b.getY(), b.getZ(), marks);
            }
        } else {
            return false;
        }
    }

    /**
     * 记录方块为插件放置并持久化。
     */
    private void markPluginPlaced(Block b) {
        this.chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long blockKey = this.blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        Set<Long> marks = this.persistedMarks(b, this.pluginPlaced, "flora_plugin");
        marks.add(blockKey);
        this.saveMarks(b, marks, "flora_plugin");
    }

    /**
     * 移除方块的插件放置标记并持久化。
     */
    private void unmarkPluginPlaced(Block b) {
        long chunkKey = this.chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long blockKey = this.blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        Set<Long> marks = this.persistedMarks(b, this.pluginPlaced, "flora_plugin");
        if (marks != null) {
            marks.remove(blockKey);
            this.saveMarks(b, marks, "flora_plugin");
            if (marks.isEmpty()) {
                this.pluginPlaced.remove(chunkKey);
            }
        }

    }

    /**
     * 清理方块并正确处理双格/竹子等连体结构，返回改变的方块数量。
     */
    private int purgeBlockRespectingShape(Block b, Material replaceWith) {
        Material type = b.getType();
        if (this.isBambooLike(type)) {
            return this.purgeBambooColumn(b, replaceWith);
        } else {
            int changed = 0;
            if (this.isDoublePlant(type)) {
                Block up = b.getRelative(BlockFace.UP);
                Block down = b.getRelative(BlockFace.DOWN);
                if (up.getType() == type) {
                    up.setType(replaceWith, false);
                    this.unmarkPluginPlaced(up);
                    this.unmarkPlayerPlaced(up);
                    ++changed;
                }

                if (down.getType() == type) {
                    down.setType(replaceWith, false);
                    this.unmarkPluginPlaced(down);
                    this.unmarkPlayerPlaced(down);
                    ++changed;
                }

                b.setType(replaceWith, false);
                this.unmarkPluginPlaced(b);
                this.unmarkPlayerPlaced(b);
                ++changed;
                return changed;
            } else {
                b.setType(replaceWith, false);
                this.unmarkPluginPlaced(b);
                this.unmarkPlayerPlaced(b);
                return 1;
            }
        }
    }

    /**
     * 记录方块为玩家放置并持久化。
     */
    private void markPlayerPlaced(Block b) {
        this.chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long blockKey = this.blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        Set<Long> marks = this.persistedMarks(b, this.playerPlaced, "flora_player");
        marks.add(blockKey);
        this.saveMarks(b, marks, "flora_player");
    }

    /**
     * 移除方块的玩家放置标记并持久化。
     */
    private void unmarkPlayerPlaced(Block b) {
        long chunkKey = this.chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long blockKey = this.blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        Set<Long> marks = this.persistedMarks(b, this.playerPlaced, "flora_player");
        if (marks != null) {
            marks.remove(blockKey);
            this.saveMarks(b, marks, "flora_player");
            if (marks.isEmpty()) {
                this.playerPlaced.remove(chunkKey);
            }
        }

    }

    /**
     * 在 MONITOR 阶段补记竹子向上蔓延产生的“玩家放置”保护。
     */
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onBlockSpreadMonitor(BlockSpreadEvent e) {
        if (this.enabled && this.protectPlayerPlaced) {
            Material newType = e.getNewState().getType();
            if (this.isBambooLike(newType)) {
                Block source = e.getSource();
                Block target = e.getBlock();
                if (source != null) {
                    if (source.getX() == target.getX() && source.getZ() == target.getZ()) {
                        if (target.getY() > source.getY()) {
                            if (this.isProtectedByPlayer(source)) {
                                this.markPlayerPlaced(target);
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 读取并缓存指定区块的方块标记（从区块持久化数据加载）。
     */
    private Set<Long> persistedMarks(Block block, Map<Long, Set<Long>> cache, String id) {
        return (Set) cache.computeIfAbsent(this.chunkKey(block.getWorld(), block.getX() >> 4, block.getZ() >> 4), (ignored) -> {
            Set<Long> marks = ConcurrentHashMap.newKeySet();
            long[] stored = (long[]) block.getChunk().getPersistentDataContainer().get(new NamespacedKey(this.plugin, id), PersistentDataType.LONG_ARRAY);
            if (stored != null) {
                for (long key : stored) {
                    marks.add(key);
                }
            }

            return marks;
        });
    }

    /**
     * 将方块标记写回区块持久化数据。
     */
    private void saveMarks(Block block, Set<Long> marks, String id) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(this.plugin, id);
        if (marks.isEmpty()) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.LONG_ARRAY, marks.stream().mapToLong(Long::longValue).toArray());
        }

    }

    /**
     * 监听区块卸载：清理该区块相关的内存缓存。
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        long key = this.chunkKey(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        this.playerPlaced.remove(key);
        this.pluginPlaced.remove(key);
        this.purgeColumnCursors.remove(key);
    }

    /**
     * 计算区块的全局唯一键（世界 UUID 混合坐标）。
     */
    private long chunkKey(World w, int cx, int cz) {
        long packed = ((long) cx & 4294967295L) << 32 | (long) cz & 4294967295L;
        long worldHash = w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits();
        return packed ^ worldHash;
    }

    /**
     * 计算方块在其世界内的唯一键（坐标压缩 + 世界 UUID 混合）。
     */
    private long blockKey(World w, int x, int y, int z) {
        long wx = (long) x & 67108863L;
        long wz = (long) z & 67108863L;
        long wy = (long) y + 2048L & 4095L;
        long packed = wx << 38 | wz << 12 | wy;
        long worldHash = w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits();
        return packed ^ worldHash;
    }

    /**
     * 获取列的地表 Y（反射优先使用 MOTION_BLOCKING_NO_LEAVES）。
     */
    private int getSurfaceY(World w, int x, int z) {
        if (M_GET_HIGHEST_Y_HM != null && HM_MOTION_NO_LEAVES != null) {
            try {
                return (Integer) M_GET_HIGHEST_Y_HM.invoke(w, x, z, HM_MOTION_NO_LEAVES);
            } catch (Throwable ignored) {
            }
        }

        return w.getHighestBlockYAt(x, z);
    }

    static {
        Object heightMap = null;
        Method method = null;

        try {
            Class<?> heightMapClass = Class.forName("org.bukkit.HeightMap");
            heightMap = Enum.valueOf((Class<? extends Enum>) heightMapClass, "MOTION_BLOCKING_NO_LEAVES");
            method = World.class.getMethod("getHighestBlockYAt", Integer.TYPE, Integer.TYPE, heightMapClass);
        } catch (Throwable ignored) {
        }

        HM_MOTION_NO_LEAVES = heightMap;
        M_GET_HIGHEST_Y_HM = method;
    }

    /**
     * 单条季节植被规则，描述哪些方块在哪些季节出现/消失、允许的生物群系、光照与放置条件等。
     */
    private static final class FloraRule {
        final String id;
        final boolean enabled;
        final boolean purgeWhenDisabled;
        final List<Material> blocks;
        final EnumSet<Season> spreadSeasons;
        final EnumSet<Season> removeSeasons;
        final boolean purgeNatural;
        final boolean purgePluginPlaced;
        final Material replaceWith;
        final EnumSet<Season> restoreSeasons;
        final double restoreChance;
        final int restoreTriesPerChunk;
        final Set<Biome> biomes;
        final int minLight;
        final int maxLight;
        final boolean requireSolidGround;
        final Set<Material> groundBlocks;
        final boolean forbidCanopy;
        final int canopyCheckHeight;
        final boolean doublePlant;
        final boolean requiresAttachment;
        final Set<Material> attachBlocks;
        final int maxPerChunk;
        final int minDistanceBlocks;

        FloraRule(String id, boolean enabled, boolean purgeWhenDisabled, List<Material> blocks, EnumSet<Season> spreadSeasons, EnumSet<Season> removeSeasons, boolean purgeNatural, boolean purgePluginPlaced, Material replaceWith, EnumSet<Season> restoreSeasons, double restoreChance, int restoreTriesPerChunk, Set<Biome> biomes, int minLight, int maxLight, boolean requireSolidGround, Set<Material> groundBlocks, boolean forbidCanopy, int canopyCheckHeight, boolean doublePlant, boolean requiresAttachment, Set<Material> attachBlocks, int maxPerChunk, int minDistanceBlocks) {
            super();
            this.id = id;
            this.enabled = enabled;
            this.purgeWhenDisabled = purgeWhenDisabled;
            this.blocks = blocks;
            this.spreadSeasons = spreadSeasons;
            this.removeSeasons = removeSeasons;
            this.purgeNatural = purgeNatural;
            this.purgePluginPlaced = purgePluginPlaced;
            this.replaceWith = replaceWith;
            this.restoreSeasons = restoreSeasons;
            this.restoreChance = restoreChance;
            this.restoreTriesPerChunk = restoreTriesPerChunk;
            this.biomes = biomes;
            this.minLight = minLight;
            this.maxLight = maxLight;
            this.requireSolidGround = requireSolidGround;
            this.groundBlocks = groundBlocks;
            this.forbidCanopy = forbidCanopy;
            this.canopyCheckHeight = canopyCheckHeight;
            this.doublePlant = doublePlant;
            this.requiresAttachment = requiresAttachment;
            this.attachBlocks = attachBlocks;
            this.maxPerChunk = maxPerChunk;
            this.minDistanceBlocks = minDistanceBlocks;
        }

        /**
         * 判断该生物群系是否在规则允许范围内（空集合表示不限制）。
         */
        boolean isBiomeAllowed(Biome b) {
            return this.biomes == null || this.biomes.isEmpty() || this.biomes.contains(b);
        }

        /**
         * 从配置节解析一条植被规则。
         */
        static FloraRule fromConfig(String id, ConfigurationSection sec, AeternumSeasonsPlugin plugin) {
            boolean enabled = sec.getBoolean("enabled", true);
            boolean purgeWhenDisabled = sec.getBoolean("purge_when_disabled", false);
            List<Material> blocks = new ArrayList();

            for (String s : sec.getStringList("blocks")) {
                Material material = parseMaterial(s);
                if (material != null) {
                    blocks.add(material);
                }
            }

            EnumSet<Season> spread = parseSeasons(sec.getStringList("spread_seasons"), EnumSet.allOf(Season.class));
            EnumSet<Season> remove = parseSeasons(sec.getStringList("remove_seasons"), EnumSet.noneOf(Season.class));
            boolean purge = sec.getBoolean("purge_natural", false);
            boolean purgePluginPlaced = sec.getBoolean("purge_plugin_placed", true);
            Material replaceWith = parseMaterial(sec.getString("replace_with", "AIR"));
            EnumSet<Season> restore = parseSeasons(sec.getStringList("restore_seasons"), EnumSet.noneOf(Season.class));
            double chance = clamp01(sec.getDouble("restore_chance", (double) 0.0F));
            int restoreTries = Math.max(1, sec.getInt("restore_tries_per_chunk", 6));
            Set<Biome> biomes = new HashSet();

            for (String s : sec.getStringList("biomes")) {
                Biome biome = parseBiome(s);
                if (biome != null) {
                    biomes.add(biome);
                }
            }

            if (!sec.getBoolean("strict_biomes", false) && ("small_flowers".equals(id) || "tall_flowers".equals(id) || "mushrooms".equals(id))) {
                for (String name : List.of("PLAINS", "SUNFLOWER_PLAINS", "FOREST", "FLOWER_FOREST", "BIRCH_FOREST", "OLD_GROWTH_BIRCH_FOREST", "DARK_FOREST", "MEADOW", "TAIGA", "OLD_GROWTH_PINE_TAIGA", "OLD_GROWTH_SPRUCE_TAIGA", "JUNGLE", "SPARSE_JUNGLE", "BAMBOO_JUNGLE", "SAVANNA", "SAVANNA_PLATEAU", "WINDSWEPT_SAVANNA", "DAPPLED_FOREST", "MUSHROOM_FIELDS")) {
                    Biome natural = parseBiome(name);
                    if (natural != null) {
                        biomes.add(natural);
                    }
                }
            }

            int minLight = Math.max(0, sec.getInt("min_light", 0));
            int maxLight = Math.min(15, sec.getInt("max_light", 15));
            boolean requireSolidGround = sec.getBoolean("require_solid_ground", true);
            Set<Material> groundBlocks = new HashSet();

            for (String s : sec.getStringList("ground_blocks")) {
                Material material = parseMaterial(s);
                if (material != null) {
                    groundBlocks.add(material);
                }
            }

            boolean forbidCanopy = sec.getBoolean("forbid_canopy", false);
            int canopyCheckHeight = Math.max(1, sec.getInt("canopy_check_height", 3));
            boolean doublePlant = sec.getBoolean("double_plant", false);
            boolean requiresAttachment = sec.getBoolean("requires_attachment", false);
            Set<Material> attachBlocks = new HashSet();

            for (String s : sec.getStringList("attach_blocks")) {
                Material material = parseMaterial(s);
                if (material != null) {
                    attachBlocks.add(material);
                }
            }

            int maxPerChunk = Math.max(0, sec.getInt("max_per_chunk", 0));
            int minDistanceBlocks = Math.max(0, sec.getInt("min_distance_blocks", 0));
            if (blocks.isEmpty()) {
                plugin.getLogger().warning("[SeasonalFlora] rule '" + id + "' has invalid blocks.");
            }

            return new FloraRule(id, enabled, purgeWhenDisabled, blocks, spread, remove, purge, purgePluginPlaced, replaceWith, restore, chance, restoreTries, biomes, minLight, maxLight, requireSolidGround, groundBlocks, forbidCanopy, canopyCheckHeight, doublePlant, requiresAttachment, attachBlocks, maxPerChunk, minDistanceBlocks);
        }

        /**
         * 解析材质名称，失败返回 null。
         */
        private static Material parseMaterial(String s) {
            if (s == null) {
                return null;
            } else {
                try {
                    return Material.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }

        /**
         * 解析生物群系名称，失败返回 null。
         */
        private static Biome parseBiome(String s) {
            if (s == null) {
                return null;
            } else {
                try {
                    return Biome.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }

        /**
         * 解析季节列表，空列表时使用默认值。
         */
        private static EnumSet<Season> parseSeasons(List<String> list, EnumSet<Season> def) {
            if (list != null && !list.isEmpty()) {
                EnumSet<Season> set = EnumSet.noneOf(Season.class);

                for (String s : list) {
                    try {
                        set.add(Season.valueOf(s.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                return set.isEmpty() ? def : set;
            } else {
                return def;
            }
        }

        /**
         * 将数值限制在 [0,1] 区间。
         */
        private static double clamp01(double value) {
            if (value < (double) 0.0F) {
                return (double) 0.0F;
            } else {
                return value > (double) 1.0F ? (double) 1.0F : value;
            }
        }
    }
}
