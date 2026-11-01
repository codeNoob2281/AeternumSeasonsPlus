package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    public void reloadFromConfig() {
        FileConfiguration y = this.plugin.cfg.climate;
        this.chunksPerTick = Math.max(2, y.getInt("autumn_soil.attempts_per_tick", 4));
        this.radiusChunks = Math.max(2, y.getInt("autumn_soil.radius_chunks", 4));
        this.dappledRadiusChunks = Math.max(this.radiusChunks, y.getInt("autumn_soil.dappled_radius_chunks", 6));
        this.leafChancePerBlock = y.getDouble("autumn_soil.leaf_chance_per_block", (double)1.0F);
        if (this.leafChancePerBlock < (double)0.0F) {
            this.leafChancePerBlock = (double)0.0F;
        }

        if (this.leafChancePerBlock > (double)1.0F) {
            this.leafChancePerBlock = (double)1.0F;
        }

        List<String> list = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
        Set<String> s = new HashSet();

        for(String w : list) {
            if (w != null) {
                String name = w.trim();
                if (!name.isEmpty()) {
                    s.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }

        this.disabledFxWorlds = s;
        this.resolvePaletteMode();
        this.sweepCache.clear();
    }

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

    private List<Material> resolveMaterials(String... names) {
        List<Material> resolved = new ArrayList(names.length);

        for(String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                resolved.add(material);
            }
        }

        return List.copyOf(resolved);
    }

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
            int stage = (int)Math.round(paintFactor * (double)100.0F);
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

            if (paintFactor <= (double)0.0F) {
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

                for(Player p : Bukkit.getOnlinePlayers()) {
                    World w = p.getWorld();
                    if (w.getEnvironment() == Environment.NORMAL && !this.isFxDisabled(w) && !this.seasons.isPermanentWinterWorld(w)) {
                        eligiblePlayers.add(p);
                        sweeps.put(p.getUniqueId(), this.buildChunkSweep(p));
                    }
                }

                if (!eligiblePlayers.isEmpty()) {
                    Set<UUID> onlineEligibleIds = new HashSet();

                    for(Player player : eligiblePlayers) {
                        onlineEligibleIds.add(player.getUniqueId());
                    }

                    this.chunkSweepCursor.keySet().retainAll(onlineEligibleIds);
                    this.sweepCache.keySet().retainAll(onlineEligibleIds);
                    int playerCount = eligiblePlayers.size();
                    int startPlayer = Math.floorMod(this.playerSweepCursor, playerCount);

                    while(budget > 0 && this.hasWorkBudget() && this.pendingTree == null) {
                        boolean processedAny = false;

                        for(int i = 0; i < playerCount && budget > 0 && this.hasWorkBudget() && this.pendingTree == null; ++i) {
                            Player player = (Player)eligiblePlayers.get((startPlayer + i) % playerCount);
                            List<Offset> sweep = (List)sweeps.get(player.getUniqueId());
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

    private List<Offset> buildChunkSweep(Player player) {
        long now = System.currentTimeMillis();
        CachedSweep cached = (CachedSweep)this.sweepCache.get(player.getUniqueId());
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
            offsets.add(new Offset(0, 0, 0, (double)1.0F));

            for(int dx = -radius; dx <= radius; ++dx) {
                for(int dz = -radius; dz <= radius; ++dz) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    if (dist != 0) {
                        Vector dir = (new Vector(dx, 0, dz)).normalize();
                        offsets.add(new Offset(dx, dz, dist, look.dot(dir)));
                    }
                }
            }

            offsets.sort(Comparator.comparingInt((Offset o) -> o.dist).thenComparingDouble((Offset o) -> -o.forwardScore));
            this.sweepCache.put(player.getUniqueId(), new CachedSweep(now + 1000L, offsets));
            return offsets;
        }
    }

    private boolean processNextLoadedChunk(Player player, List<Offset> sweep, boolean highDetail, double paintFactor) {
        if (sweep != null && !sweep.isEmpty()) {
            Location loc = player.getLocation();
            World world = player.getWorld();
            int playerChunkX = loc.getBlockX() >> 4;
            int playerChunkZ = loc.getBlockZ() >> 4;
            UUID playerId = player.getUniqueId();
            int cursor = Math.floorMod((Integer)this.chunkSweepCursor.getOrDefault(playerId, 0), sweep.size());

            for(int checked = 0; checked < sweep.size(); ++checked) {
                Offset offset = (Offset)sweep.get(cursor);
                cursor = (cursor + 1) % sweep.size();
                int chunkX = playerChunkX + offset.dx;
                int chunkZ = playerChunkZ + offset.dz;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    String scanKey = String.valueOf(world.getUID()) + ":" + chunkX + ":" + chunkZ;
                    if ((Long)this.nextScan.getOrDefault(scanKey, 0L) <= System.currentTimeMillis()) {
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
        Optional<Material> cached = (Optional)this.originalGuessCache.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        } else {
            Material result = this.guessOriginalUncached(leaf);
            this.originalGuessCache.put(cacheKey, Optional.ofNullable(result));
            return result;
        }
    }

    private Material guessOriginalUncached(Block leaf) {
        World w = leaf.getWorld();
        Biome biome = leaf.getBiome();
        int radius = 6;
        int lx = leaf.getX();
        int ly = leaf.getY();
        int lz = leaf.getZ();
        int spruce = 0;
        int birch = 0;
        int cherry = 0;
        int acacia = 0;
        int poplar = 0;

        for(int x = lx - radius; x <= lx + radius; ++x) {
            for(int y = ly - radius; y <= ly + radius; ++y) {
                for(int z = lz - radius; z <= lz + radius; ++z) {
                    if (y >= w.getMinHeight() && y < w.getMaxHeight() && w.isChunkLoaded(x >> 4, z >> 4)) {
                        Material t = w.getBlockAt(x, y, z).getType();
                        if (t == Material.SPRUCE_LOG || t == Material.SPRUCE_WOOD || t == Material.STRIPPED_SPRUCE_LOG || t == Material.STRIPPED_SPRUCE_WOOD) {
                            ++spruce;
                        }

                        if (t == Material.BIRCH_LOG || t == Material.BIRCH_WOOD || t == Material.STRIPPED_BIRCH_LOG || t == Material.STRIPPED_BIRCH_WOOD) {
                            ++birch;
                        }

                        if (t == Material.CHERRY_LOG || t == Material.CHERRY_WOOD || t == Material.STRIPPED_CHERRY_LOG || t == Material.STRIPPED_CHERRY_WOOD) {
                            ++cherry;
                        }

                        if (t == Material.ACACIA_LOG || t == Material.ACACIA_WOOD || t == Material.STRIPPED_ACACIA_LOG || t == Material.STRIPPED_ACACIA_WOOD) {
                            ++acacia;
                        }

                        if (this.isPoplarWood(t)) {
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

    private void processChunk(World w, int cx, int cz, boolean highDetail, double paintFactor) {
        String scanKey = String.valueOf(w.getUID()) + ":" + cx + ":" + cz;
        int cursor = (Integer)this.scanPositions.getOrDefault(scanKey, 0);
        int depth = 48;
        int total = 12288;
        int[] tops = (int[])this.scanTops.computeIfAbsent(scanKey, (ignored) -> {
            int[] values = new int[256];
            Arrays.fill(values, Integer.MIN_VALUE);
            return values;
        });
        int slice = 256;

        while(cursor < total && slice-- > 0 && this.hasWorkBudget() && this.pendingTree == null) {
            int column = scanColumn(cursor);
            int wx = (cx << 4) + column / 16;
            int wz = (cz << 4) + column % 16;
            if (tops[column] == Integer.MIN_VALUE) {
                tops[column] = Math.min(w.getMaxHeight() - 1, w.getHighestBlockYAt(wx, wz));
            }

            int y = tops[column] - cursor / 256;
            ++cursor;
            --this.remainingBlocks;
            if (y >= w.getMinHeight()) {
                Block block = w.getBlockAt(wx, y, wz);
                Material type = block.getType();
                if (this.leafTypes.contains(type) || this.isAutumnPaintMaterial(type)) {
                    BlockData var20 = block.getBlockData();
                    if (var20 instanceof Leaves) {
                        Leaves leaves = (Leaves)var20;
                        if (!leaves.isPersistent()) {
                            String leafKey = this.key(w, wx, y, wz);
                            Material original = (Material)this.paintedLeaves.get(leafKey);
                            if (paintFactor > (double)0.0F) {
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

                                if (paintFactor <= (double)0.0F && original != null) {
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
            return (double)0.0F;
        } else {
            return day >= 3 ? (double)1.0F : (day == 2 ? 0.65 : 0.35);
        }
    }

    static int scanColumn(int cursor) {
        return Integer.reverse(cursor & 255) >>> 24;
    }

    static boolean eligibleForStage(long seed, int x, int y, int z, double factor) {
        long hash = seed ^ (long)x * 341873128712L ^ (long)z * 132897987541L ^ (long)y * 42317861L;
        hash = (hash ^ hash >>> 30) * -4658895280553007687L;
        hash = (hash ^ hash >>> 27) * -7723592293110705685L;
        hash ^= hash >>> 31;
        return (double)(hash >>> 11) * (double)1.110223E-16F < factor;
    }

    private boolean takeTreeBudget() {
        if (!this.hasWorkBudget()) {
            return false;
        } else {
            --this.remainingBlocks;
            return true;
        }
    }

    private void restoreTree() {
        if (!this.paintedTrees.isEmpty()) {
            PaintedTree tree = (PaintedTree)this.paintedTrees.removeFirst();
            this.commitTree(tree.world(), tree.original(), tree.leaves(), tree.original(), true);
        } else {
            if (this.pendingTree == null) {
                if (this.restoreCursor == null || !this.restoreCursor.hasNext()) {
                    this.restoreCursor = this.paintedLeaves.entrySet().iterator();
                }

                while(this.restoreCursor.hasNext() && this.takeTreeBudget()) {
                    Map.Entry<String, Material> entry = (Map.Entry)this.restoreCursor.next();
                    String[] p = ((String)entry.getKey()).split(":");
                    if (p.length == 4) {
                        World world = Bukkit.getWorld(UUID.fromString(p[0]));
                        if (world != null) {
                            int x = Integer.parseInt(p[1]);
                            int y = Integer.parseInt(p[2]);
                            int z = Integer.parseInt(p[3]);
                            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                                Block block = world.getBlockAt(x, y, z);
                                if (!this.isAutumnPaintMaterial(block.getType())) {
                                    this.paintedLeaves.remove(entry.getKey());
                                } else {
                                    if (this.leafTypes.contains(entry.getValue())) {
                                        this.pendingTree = new AutumnTreePlan(block, (Material)entry.getValue(), this::originalLeafType);
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

    private void finishPendingTree(boolean restore) {
        AutumnTreePlan plan = this.pendingTree;
        plan.advance(this::takeTreeBudget);
        if (plan.finished()) {
            this.pendingTree = null;
            if (!plan.complete()) {
                if (restore && plan.world.isChunkLoaded(plan.seed.x() >> 4, plan.seed.z() >> 4)) {
                    this.restoreOriginalLeafForDecay(plan.world.getBlockAt(plan.seed.x(), plan.seed.y(), plan.seed.z()));
                }

            } else if (!restore && !eligibleForStage(plan.world.getSeed(), plan.anchor().x(), 0, plan.anchor().z(), (double)this.previousStage / (double)100.0F * this.leafChancePerBlock)) {
                for(AutumnTreePlan.Pos pos : plan.leaves()) {
                    this.validatedLeaves.add(this.key(plan.world, pos.x(), pos.y(), pos.z()));
                }

            } else {
                Material target = restore ? plan.original : (this.paletteMode == AutumnSoilPainter.PaletteMode.LEGACY_ACACIA ? Material.ACACIA_LEAVES : (Material)this.dappledPalette.get(AutumnTreePlan.paletteIndex(plan.world.getSeed(), plan.anchor(), this.dappledPalette.size())));
                this.commitTree(plan.world, plan.original, plan.leaves(), target, restore);
            }
        }
    }

    private void commitTree(World world, Material original, Collection<AutumnTreePlan.Pos> positions, Material target, boolean restore) {
        for(AutumnTreePlan.Pos pos : positions) {
            if (!restore && !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                return;
            }
        }

        record Change(Block block, String key, Leaves data) {
            Change {
            }
        }

        List<Change> changes = new ArrayList();
        Leaves template = (Leaves)target.createBlockData();

        for(AutumnTreePlan.Pos pos : positions) {
            if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                String leafKey = this.key(world, pos.x(), pos.y(), pos.z());
                if (this.originalLeafType(block) == original) {
                    BlockData var13 = block.getBlockData();
                    if (var13 instanceof Leaves) {
                        Leaves old = (Leaves)var13;
                        if (!old.isPersistent()) {
                            if (restore) {
                                if (!this.paintedLeaves.containsKey(leafKey)) {
                                    continue;
                                }
                            } else if (this.validatedLeaves.contains(leafKey)) {
                                continue;
                            }

                            Leaves data = (Leaves)template.clone();
                            data.setDistance(old.getDistance());
                            data.setPersistent(false);
                            changes.add(new Change(block, leafKey, data));
                        }
                    }
                }
            }
        }

        for(Change change : changes) {
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

    private void paintLeaf(Block b, Material originalType) {
        Material paintedType = Material.ACACIA_LEAVES;
        String k = this.key(b.getWorld(), b.getX(), b.getY(), b.getZ());
        this.paintedLeaves.putIfAbsent(k, originalType);
        if (b.getType() != paintedType) {
            BlockData oldData = b.getBlockData();
            int distance = 1;
            if (oldData instanceof Leaves) {
                Leaves leavesOld = (Leaves)oldData;
                distance = leavesOld.getDistance();
            }

            Leaves newLeaves = (Leaves)paintedType.createBlockData();
            newLeaves.setDistance(distance);
            newLeaves.setPersistent(false);
            b.setBlockData(newLeaves, false);
        }
    }

    private Material originalLeafType(Block block) {
        Material type = block.getType();
        if (this.leafTypes.contains(type)) {
            return type;
        } else {
            return !this.isAutumnPaintMaterial(type) ? null : (Material)this.paintedLeaves.get(this.key(block.getWorld(), block.getX(), block.getY(), block.getZ()));
        }
    }

    public boolean restoreOriginalLeafForDecay(Block b) {
        if (b == null) {
            return false;
        } else if (!this.isAutumnPaintMaterial(b.getType())) {
            return false;
        } else {
            World w = b.getWorld();
            if (w == null) {
                return false;
            } else {
                String k = this.key(w, b.getX(), b.getY(), b.getZ());
                Material original = (Material)this.paintedLeaves.remove(k);
                if (original != null && original != Material.ACACIA_LEAVES) {
                    BlockData currentData = b.getBlockData();
                    int distance = 1;
                    boolean persistent = false;
                    if (currentData instanceof Leaves) {
                        Leaves currentLeaves = (Leaves)currentData;
                        distance = currentLeaves.getDistance();
                        persistent = currentLeaves.isPersistent();
                    }

                    BlockData backData = original.createBlockData();
                    if (backData instanceof Leaves) {
                        Leaves backLeaves = (Leaves)backData;
                        backLeaves.setDistance(distance);
                        backLeaves.setPersistent(persistent);
                        b.setBlockData(backLeaves, false);
                    } else {
                        b.setType(original, false);
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
    public void onPaintedLeafBreak(BlockBreakEvent event) {
        this.restoreOriginalLeafForDecay(event.getBlock());
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onPaintedLeafDecay(LeavesDecayEvent event) {
        this.restoreOriginalLeafForDecay(event.getBlock());
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onPaintedLeavesBlockExplosion(BlockExplodeEvent event) {
        for(Block block : event.blockList()) {
            this.restoreOriginalLeafForDecay(block);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onPaintedLeavesEntityExplosion(EntityExplodeEvent event) {
        for(Block block : event.blockList()) {
            this.restoreOriginalLeafForDecay(block);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onSaplingSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack st = item.getItemStack();
        if (st.getType() == Material.ACACIA_SAPLING || st.getType() == this.poplarSapling) {
            Location loc = item.getLocation();
            World w = loc.getWorld();
            if (w != null) {
                Block leafBlock = this.findNearbyPaintedLeaf(w, loc);
                Material original = null;
                if (leafBlock != null) {
                    String k = this.key(w, leafBlock.getX(), leafBlock.getY(), leafBlock.getZ());
                    original = (Material)this.paintedLeaves.get(k);
                    if (original == null) {
                        original = this.guessOriginalFromWorld(leafBlock);
                    }

                    if (original == null) {
                        original = this.guessOriginalFromNearbyLogItems(item);
                    }
                } else {
                    original = this.guessOriginalFromWorld(w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                    if (original == null) {
                        original = this.guessOriginalFromNearbyLogItems(item);
                    }
                }

                if (original != null) {
                    Material correct = this.saplingFor(original);
                    if (correct != null && correct != st.getType()) {
                        ItemStack newStack = new ItemStack(correct, st.getAmount());
                        newStack.setItemMeta(st.getItemMeta());
                        item.setItemStack(newStack);
                    }
                }
            }
        }
    }

    private Material guessOriginalFromNearbyLogItems(Item saplingItem) {
        Location loc = saplingItem.getLocation();
        World w = loc.getWorld();
        if (w == null) {
            return null;
        } else {
            double r = (double)6.0F;
            int spruce = 0;
            int birch = 0;
            int cherry = 0;

            for(Entity e : w.getNearbyEntities(loc, r, r, r)) {
                if (e instanceof Item) {
                    Item it = (Item)e;
                    Material t = it.getItemStack().getType();
                    if (t == Material.SPRUCE_LOG || t == Material.SPRUCE_WOOD || t == Material.STRIPPED_SPRUCE_LOG || t == Material.STRIPPED_SPRUCE_WOOD) {
                        ++spruce;
                    }

                    if (t == Material.BIRCH_LOG || t == Material.BIRCH_WOOD || t == Material.STRIPPED_BIRCH_LOG || t == Material.STRIPPED_BIRCH_WOOD) {
                        ++birch;
                    }

                    if (t == Material.CHERRY_LOG || t == Material.CHERRY_WOOD || t == Material.STRIPPED_CHERRY_LOG || t == Material.STRIPPED_CHERRY_WOOD) {
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

    private Block findNearbyPaintedLeaf(World w, Location loc) {
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        int rXZ = 2;
        int rY = 3;

        for(int dy = -rY; dy <= rY; ++dy) {
            for(int dx = -rXZ; dx <= rXZ; ++dx) {
                for(int dz = -rXZ; dz <= rXZ; ++dz) {
                    Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (this.isAutumnPaintMaterial(b.getType())) {
                        return b;
                    }
                }
            }
        }

        return null;
    }

    private void revertSomeLeaves(int maxBlocks) {
        if (maxBlocks > 0) {
            if (this.restoreCursor == null || !this.restoreCursor.hasNext()) {
                this.restoreCursor = this.paintedLeaves.entrySet().iterator();
            }

            Iterator<Map.Entry<String, Material>> it = this.restoreCursor;

            while(it.hasNext() && maxBlocks-- > 0 && this.hasWorkBudget()) {
                --this.remainingBlocks;
                Map.Entry<String, Material> entry = (Map.Entry)it.next();
                String key = (String)entry.getKey();
                Material original = (Material)entry.getValue();
                String[] parts = key.split(":");
                if (parts.length != 4) {
                    it.remove();
                } else {
                    UUID worldId;
                    try {
                        worldId = UUID.fromString(parts[0]);
                    } catch (IllegalArgumentException var18) {
                        it.remove();
                        continue;
                    }

                    World w = Bukkit.getWorld(worldId);
                    if (w == null) {
                        it.remove();
                    } else {
                        int x;
                        int y;
                        int z;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                            z = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException var17) {
                            it.remove();
                            continue;
                        }

                        if (w.isChunkLoaded(x >> 4, z >> 4)) {
                            Block b = w.getBlockAt(x, y, z);
                            if (!this.isAutumnPaintMaterial(b.getType())) {
                                it.remove();
                            } else {
                                BlockData currentData = b.getBlockData();
                                int distance = 1;
                                if (currentData instanceof Leaves) {
                                    Leaves leavesCurrent = (Leaves)currentData;
                                    distance = leavesCurrent.getDistance();
                                }

                                BlockData backData = original.createBlockData();
                                if (backData instanceof Leaves) {
                                    Leaves backLeaves = (Leaves)backData;
                                    backLeaves.setDistance(distance);
                                    backLeaves.setPersistent(false);
                                    b.setBlockData(backLeaves, false);
                                } else {
                                    b.setType(original, false);
                                }

                                it.remove();
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
            return name.equals("POPLAR_LOG") || name.equals("POPLAR_WOOD") || name.equals("STRIPPED_POPLAR_LOG") || name.equals("STRIPPED_POPLAR_WOOD");
        }
    }

    private Material saplingFor(Material originalLeaves) {
        Material var10000;
        switch (originalLeaves) {
            case SPRUCE_LEAVES -> var10000 = Material.SPRUCE_SAPLING;
            case BIRCH_LEAVES -> var10000 = Material.BIRCH_SAPLING;
            case OAK_LEAVES -> var10000 = Material.OAK_SAPLING;
            case DARK_OAK_LEAVES -> var10000 = Material.DARK_OAK_SAPLING;
            case JUNGLE_LEAVES -> var10000 = Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> var10000 = Material.ACACIA_SAPLING;
            case CHERRY_LEAVES -> var10000 = Material.CHERRY_SAPLING;
            default -> var10000 = null;
        }

        return var10000;
    }

    private String key(World w, int x, int y, int z) {
        return String.valueOf(w.getUID()) + ":" + x + ":" + y + ":" + z;
    }

    private int computeDayInSeason(CalendarState st) {
        try {
            try {
                Method m = st.getClass().getMethod("getDayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return this.clampDayInSeason(n.intValue());
                }
            } catch (NoSuchMethodException var10) {
                Method m = st.getClass().getMethod("dayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return this.clampDayInSeason(n.intValue());
                }
            }
        } catch (Exception var11) {
        }

        try {
            for(Field f : st.getClass().getDeclaredFields()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.contains("dayinseason")) {
                    f.setAccessible(true);
                    Object v = f.get(st);
                    if (v instanceof Number) {
                        Number n = (Number)v;
                        return this.clampDayInSeason(n.intValue());
                    }
                }
            }
        } catch (Exception var9) {
        }

        Integer dayGlobal = this.tryGetIntField(st, "dayOfYear", "day_of_year", "day");
        if (dayGlobal != null) {
            int d = (dayGlobal - 1) % 28 + 1;
            return this.clampDayInSeason(d);
        } else {
            return 1;
        }
    }

    private boolean isFxDisabled(World w) {
        return w != null && this.disabledFxWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    private Integer tryGetIntField(CalendarState st, String... candidates) {
        try {
            for(String raw : candidates) {
                String name = raw;
                String var10000 = raw.substring(0, 1).toUpperCase(Locale.ROOT);
                String getter = "get" + var10000 + raw.substring(1);

                try {
                    Method m = st.getClass().getMethod(getter);
                    Object v = m.invoke(st);
                    if (v instanceof Number n) {
                        return n.intValue();
                    }
                } catch (NoSuchMethodException var15) {
                }

                try {
                    Method m2 = st.getClass().getMethod(name);
                    Object v2 = m2.invoke(st);
                    if (v2 instanceof Number n2) {
                        return n2.intValue();
                    }
                } catch (NoSuchMethodException var14) {
                }
            }

            for(Field f : st.getClass().getDeclaredFields()) {
                String fn = f.getName();

                for(String cand : candidates) {
                    if (fn.equalsIgnoreCase(cand)) {
                        f.setAccessible(true);
                        Object v = f.get(st);
                        if (v instanceof Number) {
                            Number n = (Number)v;
                            return n.intValue();
                        }
                    }
                }
            }
        } catch (Exception var16) {
        }

        return null;
    }

    private int clampDayInSeason(int d) {
        int max = Math.max(1, this.seasons.getDaysPerSeason());
        if (d < 1) {
            d = 1;
        }

        if (d > max) {
            d = max;
        }

        return d;
    }

    private double computePreAutumnFactor(int dayInSeason) {
        if (dayInSeason < 26) {
            return (double)0.0F;
        } else {
            int step = dayInSeason - 26;
            double factor = (double)(step + 1) / (double)3.0F;
            if (factor < (double)0.0F) {
                factor = (double)0.0F;
            }

            if (factor > (double)1.0F) {
                factor = (double)1.0F;
            }

            return factor;
        }
    }

    public static AutumnSoilPainter instanceOrNull() {
        return INSTANCE;
    }

    private static enum PaletteMode {
        DISABLED,
        LEGACY_ACACIA,
        DAPPLED_POPLAR;

        private PaletteMode() {
        }

        // $FF: synthetic method
        private static PaletteMode[] $values() {
            return new PaletteMode[]{DISABLED, LEGACY_ACACIA, DAPPLED_POPLAR};
        }
    }

    private static record CachedSweep(long expires, List<Offset> offsets) {
        private CachedSweep {
        }
    }

    private static record PaintedTree(World world, Material original, List<AutumnTreePlan.Pos> leaves) {
        private PaintedTree {
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
}
