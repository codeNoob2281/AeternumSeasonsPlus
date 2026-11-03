package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Illusioner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 幻术师生成替换监听器。
 *
 * <p>在特定结构附近把部分灾厄村民（掠夺者/卫道士/唤魔者）替换为幻术师：
 * <ul>
 *     <li>林地府邸内自然生成的卫道士/唤魔者，按配置概率替换为幻术师；</li>
 *     <li>掠夺者前哨站附近自然/巡逻/袭击生成的掠夺者，按较低概率替换为幻术师。</li>
 * </ul>
 * 为避免频繁调用开销较大的结构定位（locateNearestStructure），本类按“世界 + 结构类型 + 区块”缓存结果，
 * 并用令牌桶限制每秒的结构搜索次数；另有全局生成冷却与幻术师死亡掉落（不死图腾）逻辑。</p>
 */
public final class IllusionerSpawnListener implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 林地府邸内生成的灾厄村民被替换为幻术师的概率。
     */
    private double mansionReplacementChance;
    /**
     * 前哨站/村庄附近生成的掠夺者被替换为幻术师的概率。
     */
    private double outpostReplacementChance;
    /**
     * 结构搜索的区块半径。
     */
    private int structureSearchRadiusChunks;
    /**
     * 幻术师全局生成冷却（毫秒）。
     */
    private long globalSpawnCooldownMs;
    /**
     * 每秒允许的最大结构搜索次数。
     */
    private int maxStructureSearchesPerSecond;
    /**
     * 命中结构时的缓存有效期（毫秒）。
     */
    private long cacheTtlPositiveMs;
    /**
     * 未命中结构时的缓存有效期（毫秒）。
     */
    private long cacheTtlNegativeMs;
    /**
     * 允许触发掠夺者替换的生成原因。
     */
    private EnumSet<CreatureSpawnEvent.SpawnReason> allowedPillagerReasons;
    /**
     * 允许触发府邸替换的生成原因。
     */
    private EnumSet<CreatureSpawnEvent.SpawnReason> allowedMansionReasons;
    /**
     * 结构判定缓存：缓存键 -> (区块键 -> 缓存结果)。
     */
    private final Map<CacheKey, ConcurrentHashMap<Long, CacheEntry>> chunkCache = new ConcurrentHashMap();
    private volatile int tokens;
    private volatile long tokenSecond;
    /**
     * 上一次成功生成幻术师的时间戳（全局共享）。
     */
    private static volatile long lastIllusionerSpawnTime = 0L;
    private boolean debug;

    public IllusionerSpawnListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
        this.resetRateLimiter();
    }

    /**
     * 从 illusioner_spawning 配置节重新读取全部参数。
     */
    public void reloadFromConfig() {
        ConfigurationSection sec = this.plugin.cfg.climate.getConfigurationSection("illusioner_spawning");
        if (sec == null) {
            this.enabled = false;
        } else {
            this.enabled = sec.getBoolean("enabled", false);
            this.mansionReplacementChance = sec.getDouble("mansion_replacement_chance", 0.1);
            this.outpostReplacementChance = sec.contains("outpost_replacement_chance") ? sec.getDouble("outpost_replacement_chance", 0.005) : sec.getDouble("village_spawn_chance", 0.005);
            this.structureSearchRadiusChunks = sec.contains("structure_check_radius_chunks") ? sec.getInt("structure_check_radius_chunks", 4) : sec.getInt("village_check_radius", 4);
            this.globalSpawnCooldownMs = sec.getLong("global_spawn_cooldown_ms", 2500L);
            this.maxStructureSearchesPerSecond = sec.getInt("max_structure_searches_per_second", 2);
            this.cacheTtlPositiveMs = sec.getLong("cache_ttl_positive_ms", 600000L);
            this.cacheTtlNegativeMs = sec.getLong("cache_ttl_negative_ms", 120000L);
            this.debug = sec.getBoolean("debug", false);
            this.allowedPillagerReasons = EnumSet.of(SpawnReason.NATURAL, SpawnReason.PATROL, SpawnReason.RAID);
            this.allowedMansionReasons = EnumSet.of(SpawnReason.NATURAL, SpawnReason.CHUNK_GEN);
            this.plugin.getLogger().info("[IllusionerSpawn] Enabled=" + this.enabled + ", MansionChance=" + this.mansionReplacementChance + ", OutpostChance=" + this.outpostReplacementChance + ", RadiusChunks=" + this.structureSearchRadiusChunks + ", CooldownMs=" + this.globalSpawnCooldownMs + ", SearchesPerSec=" + this.maxStructureSearchesPerSecond + ", CachePosMs=" + this.cacheTtlPositiveMs + ", CacheNegMs=" + this.cacheTtlNegativeMs + ", Debug=" + this.debug);
        }
    }

    /**
     * 重置令牌桶到当前秒并补满令牌。
     */
    private void resetRateLimiter() {
        this.tokenSecond = System.currentTimeMillis() / 1000L;
        this.tokens = this.maxStructureSearchesPerSecond;
    }

    /**
     * 监听生物生成事件：对符合条件的灾厄村民按概率与结构判定替换为幻术师。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (this.enabled) {
            Location loc = e.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
                EntityType type = e.getEntityType();
                if (type != EntityType.VINDICATOR && type != EntityType.EVOKER) {
                    if (type == EntityType.PILLAGER) {
                        if (!this.allowedPillagerReasons.contains(reason)) {
                            return;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastIllusionerSpawnTime < this.globalSpawnCooldownMs) {
                            return;
                        }

                        if (ThreadLocalRandom.current().nextDouble() >= this.outpostReplacementChance) {
                            return;
                        }

                        if (this.isInStructureCached(loc, StructureType.PILLAGER_OUTPOST, this.structureSearchRadiusChunks)) {
                            e.setCancelled(true);
                            this.spawnIllusioner(loc, reason);
                            lastIllusionerSpawnTime = now;
                        }
                    }

                } else if (this.allowedMansionReasons.contains(reason)) {
                    if (!(ThreadLocalRandom.current().nextDouble() >= this.mansionReplacementChance)) {
                        if (this.isInStructureCached(loc, StructureType.WOODLAND_MANSION, this.structureSearchRadiusChunks)) {
                            e.setCancelled(true);
                            this.spawnIllusioner(loc, reason);
                        }

                    }
                }
            }
        }
    }

    /**
     * 在指定位置以相同生成原因生成一只幻术师，并处理调试日志与异常。
     */
    private void spawnIllusioner(Location loc, CreatureSpawnEvent.SpawnReason reason) {
        World world = loc.getWorld();
        if (world != null) {
            try {
                Illusioner illusioner = (Illusioner) world.spawn(loc, Illusioner.class, reason, false, (Consumer) null);
                if (this.debug) {
                    Logger logger = this.plugin.getLogger();
                    int blockX = loc.getBlockX();
                    logger.info("[IllusionerSpawn] Illusioner generado en " + blockX + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " (Razón: " + reason.name() + "), ent=" + illusioner.getUniqueId());
                }
            } catch (Throwable ex) {
                Logger logger = this.plugin.getLogger();
                String errorType = ex.getClass().getSimpleName();
                logger.warning("[IllusionerSpawn] Falló al generar Illusioner: " + errorType + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
            }

        }
    }

    /**
     * 带缓存地判断位置是否位于指定结构附近；使用令牌桶限制实际结构搜索频率。
     */
    private boolean isInStructureCached(Location loc, StructureType structureType, int radiusChunks) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            Chunk chunk = loc.getChunk();
            long chunkKey = packChunkKey(chunk.getX(), chunk.getZ());
            CacheKey key = new CacheKey(world.getUID(), structureType);
            final ConcurrentHashMap<Long, CacheEntry> cacheMap = (ConcurrentHashMap) this.chunkCache.computeIfAbsent(key, (cacheKeyObj) -> new ConcurrentHashMap());
            long now = System.currentTimeMillis();
            CacheEntry cached = (CacheEntry) cacheMap.get(chunkKey);
            if (cached != null && cached.expiresAtMs > now) {
                return cached.inStructure;
            } else if (!this.tryConsumeToken(now)) {
                if (this.debug) {
                    this.plugin.getLogger().info("[IllusionerSpawn] RateLimit bloqueó locateNearestStructure (" + structureType + ")");
                }

                cacheMap.put(chunkKey, new CacheEntry(false, now + 10000L));
                return false;
            } else {
                boolean result = this.isNearStructureSlow(loc, structureType, radiusChunks);
                long ttl = result ? this.cacheTtlPositiveMs : this.cacheTtlNegativeMs;
                cacheMap.put(chunkKey, new CacheEntry(result, now + ttl));
                if (cacheMap.size() > 20000) {
                    (new BukkitRunnable() {
                        @Override
                        public void run() {
                            long currentTime = System.currentTimeMillis();
                            cacheMap.entrySet().removeIf((entry) -> ((CacheEntry) entry.getValue()).expiresAtMs <= currentTime);
                        }
                    }).runTask(this.plugin);
                }

                return result;
            }
        }
    }

    /**
     * 实际调用 locateNearestStructure 判断位置是否在结构半径内。
     */
    private boolean isNearStructureSlow(Location loc, StructureType structureType, int radiusChunks) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        } else {
            try {
                Location structureLocation = world.locateNearestStructure(loc, structureType, radiusChunks, false);
                if (structureLocation == null) {
                    return false;
                } else {
                    double maxDistBlocks = (double) radiusChunks * (double) 16.0F;
                    return structureLocation.getWorld().equals(world) && structureLocation.distanceSquared(loc) <= maxDistBlocks * maxDistBlocks;
                }
            } catch (Throwable ex) {
                if (this.debug) {
                    Logger logger = this.plugin.getLogger();
                    String structureName = String.valueOf(structureType);
                    logger.warning("[IllusionerSpawn] locateNearestStructure falló (" + structureName + "): " + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
                }

                return false;
            }
        }
    }

    /**
     * 令牌桶限流：每秒补充一次令牌，成功消费返回 true。
     */
    private boolean tryConsumeToken(long nowMs) {
        long currentSecond = nowMs / 1000L;
        if (currentSecond != this.tokenSecond) {
            this.tokenSecond = currentSecond;
            this.tokens = Math.max(0, this.maxStructureSearchesPerSecond);
        }

        if (this.tokens <= 0) {
            return false;
        } else {
            --this.tokens;
            return true;
        }
    }

    /**
     * 将区块坐标打包为单个 long 键。
     */
    private static long packChunkKey(int cx, int cz) {
        return (long) cx << 32 ^ (long) cz & 4294967295L;
    }

    /**
     * 监听幻术师死亡：清空默认掉落并掉落 1~2 个不死图腾。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntityType() == EntityType.ILLUSIONER) {
            e.getDrops().clear();
            int totemCount = ThreadLocalRandom.current().nextInt(2) + 1;
            e.getDrops().add(new ItemStack(Material.TOTEM_OF_UNDYING, totemCount));
        }

    }

    /**
     * 结构缓存键：世界 UUID + 结构类型。
     */
    private record CacheKey(UUID worldId, StructureType type) {
        private CacheKey {
        }
    }

    /**
     * 单个区块的结构判定缓存条目。
     */
    private static final class CacheEntry {
        final boolean inStructure;
        final long expiresAtMs;

        CacheEntry(boolean inStructure, long expiresAtMs) {
            super();
            this.inStructure = inStructure;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
