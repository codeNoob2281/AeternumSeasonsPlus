package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;

/**
 * 恶魂惊扰事件（Ghast Alert）。
 *
 * <p>当玩家身处下界时，有一定概率触发本事件。事件激活期间：
 * <ul>
 *     <li>下界自然生成（含区块生成）的恶魂有一定概率被“标记”——获得自定义名称、发光效果；</li>
 *     <li>被标记的恶魂还有概率在其附近额外召唤一只恶魂；</li>
 *     <li>玩家击杀被标记的恶魂时，有概率额外掉落恶魂之泪。</li>
 * </ul>
 * 为避免刷屏，同一区块内的额外召唤受冷却时间与附近恶魂数量上限约束。</p>
 */
public final class GhastAlertEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /** 事件最短持续天数。 */
    private int minDur;
    /** 事件最长持续天数。 */
    private int maxDur;
    /** 每天触发事件的基础概率。 */
    private double baseChance;
    /** 自然生成的恶魂被“标记”的概率。 */
    private double markedGhastChance;
    /** 击杀被标记恶魂时额外掉落恶魂之泪的概率。 */
    private double extraTearChance;
    /** 被标记恶魂额外召唤同伴的概率。 */
    private double extraSpawnChance;
    /** 同一区块额外召唤的冷却时间（毫秒）。 */
    private long chunkCooldownMs;
    /** 附近恶魂数量上限，达到后不再额外召唤。 */
    private int maxNearbyGhasts;
    /** 统计附近恶魂时的搜索半径。 */
    private double nearbyRadius;
    /** 记录每个区块最近一次额外召唤的时间戳，键为“世界UUID:x:z”。 */
    private final Map<String, Long> chunkLastExtra = new HashMap();

    public GhastAlertEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /** 从 events.yml 重新读取本事件的配置项。 */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.ghast_alert.enabled", true);
        this.minDur = config.getInt("events.ghast_alert.min_duration_days", 1);
        this.maxDur = config.getInt("events.ghast_alert.max_duration_days", 1);
        this.baseChance = config.getDouble("events.ghast_alert.base_chance_per_day", 0.08);
        this.markedGhastChance = config.getDouble("events.ghast_alert.marked_ghast_chance", (double)0.25F);
        this.extraTearChance = config.getDouble("events.ghast_alert.extra_tear_chance", 0.3);
        this.extraSpawnChance = config.getDouble("events.ghast_alert.extra_spawn_chance", 0.8);
        this.chunkCooldownMs = config.getLong("events.ghast_alert.chunk_cooldown_ms", 15000L);
        this.maxNearbyGhasts = config.getInt("events.ghast_alert.max_nearby_ghasts", 6);
        this.nearbyRadius = config.getDouble("events.ghast_alert.nearby_radius", (double)48.0F);
    }

    public String getId() {
        return "ghast_alert";
    }

    public String getDisplayName() {
        return "Ghast Alert";
    }

    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    public int getMinDurationDays() {
        return this.minDur;
    }

    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /** 仅当有玩家位于下界、且通过基础概率判定时才允许开始。 */
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((player) -> player.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    /** 事件开始时清空区块冷却记录，并向所有身处下界的玩家发送标题提示。 */
    public void onStart(CalendarState state, EventContext context) {
        this.chunkLastExtra.clear();

        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.ghast_alert.title"), this.plugin.lang.tr(player, "event.ghast_alert.subtitle"), 20, 80, 40);
            }
        }

    }

    /** 事件结束时清空区块冷却记录，并通知所有身处下界的玩家事件已结束。 */
    public void onEnd(CalendarState state, EventContext context) {
        this.chunkLastExtra.clear();

        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.ghast_alert.end"));
            }
        }

    }

    public void onDayTick(CalendarState state, EventContext context) {
    }

    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听生物生成事件：在下界自然生成恶魂时，按概率将其标记，并可能额外召唤一只恶魂。
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Ghast ghast) {
            if (ghast.getWorld().getEnvironment() == Environment.NETHER) {
                CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
                if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN) {
                    if (Math.random() < this.markedGhastChance) {
                        String displayName = this.plugin.lang.trServer("event.ghast_alert.ghast_name");
                        ghast.setCustomName(displayName);
                        ghast.setCustomNameVisible(true);
                        ghast.setGlowing(true);
                    }

                    if (!(Math.random() > this.extraSpawnChance)) {
                        World world = ghast.getWorld();
                        Chunk chunk = ghast.getLocation().getChunk();
                        String worldId = String.valueOf(world.getUID());
                        String chunkKey = worldId + ":" + chunk.getX() + ":" + chunk.getZ();
                        long now = System.currentTimeMillis();
                        long lastExtraAt = (Long)this.chunkLastExtra.getOrDefault(chunkKey, 0L);
                        if (now - lastExtraAt >= this.chunkCooldownMs) {
                            int nearbyGhastCount = 0;

                            for(Entity nearbyEntity : ghast.getNearbyEntities(this.nearbyRadius, this.nearbyRadius, this.nearbyRadius)) {
                                if (nearbyEntity instanceof Ghast) {
                                    ++nearbyGhastCount;
                                    if (nearbyGhastCount >= this.maxNearbyGhasts) {
                                        return;
                                    }
                                }
                            }

                            this.chunkLastExtra.put(chunkKey, now);
                            Location baseLocation = ghast.getLocation();
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            double offsetX = random.nextDouble((double)-18.0F, (double)18.0F);
                            double offsetZ = random.nextDouble((double)-18.0F, (double)18.0F);
                            double offsetY = random.nextDouble((double)-6.0F, (double)8.0F);
                            Location spawnLocation = baseLocation.clone().add(offsetX, offsetY, offsetZ);
                            if (!spawnLocation.getChunk().isLoaded()) {
                                spawnLocation.getChunk().load();
                            }

                            world.spawn(spawnLocation, Ghast.class, (spawnedGhast) -> {
                                spawnedGhast.getWorld().spawnParticle(Particle.SMOKE, spawnedGhast.getLocation().add((double)0.0F, (double)1.5F, (double)0.0F), 10, 0.6, (double)0.5F, 0.6, 0.01);
                                if (Math.random() < this.markedGhastChance * 0.6) {
                                    String displayName = this.plugin.lang.trServer("event.ghast_alert.ghast_name");
                                    spawnedGhast.setCustomName(displayName);
                                    spawnedGhast.setCustomNameVisible(true);
                                    spawnedGhast.setGlowing(true);
                                }

                            });
                        }
                    }
                }
            }
        }
    }

    /**
     * 监听恶魂死亡：若被玩家击杀，有概率额外掉落恶魂之泪并播放烟雾粒子效果。
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Ghast ghast) {
            if (ghast.getWorld().getEnvironment() == Environment.NETHER) {
                if (ghast.getKiller() != null) {
                    if (Math.random() < this.extraTearChance) {
                        event.getDrops().add(new ItemStack(Material.GHAST_TEAR, 1));
                    }

                    ghast.getWorld().spawnParticle(Particle.SMOKE, ghast.getLocation().add((double)0.0F, (double)2.0F, (double)0.0F), 14, 0.7, 0.6, 0.7, 0.01);
                }
            }
        }
    }
}
