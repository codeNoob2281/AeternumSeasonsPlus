package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 凋灵骷髅群潮事件（Wither Skeleton Swarm）。
 *
 * <p>当下界有玩家且通过概率判定时触发。事件激活期间：
 * <ul>
 *     <li>下界自然生成的凋灵骷髅有概率被“强化”（加速、双倍血量与攻击、发光、改名）；</li>
 *     <li>非自定义生成的凋灵骷髅有概率在附近额外召唤同伴（受区块冷却限制）；</li>
 *     <li>玩家击杀凋灵骷髅时有概率获得额外战利品，另有小概率额外掉落凋灵骷髅头。</li>
 * </ul>
 * 配置来自 {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class WitherSkeletonSwarmEvent implements SeasonalEvent {
    private static final String TAG_BUFFED = "asevent_wss_buffed";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    /**
     * 事件最短持续天数。
     */
    private int minDur;
    /**
     * 事件最长持续天数。
     */
    private int maxDur;
    /**
     * 每天触发事件的基础概率。
     */
    private double baseChance;
    /**
     * 自然生成的凋灵骷髅被强化的概率。
     */
    private double buffChance;
    /**
     * 额外召唤同伴的概率。
     */
    private double extraSpawnChance;
    /**
     * 同一区块额外召唤的冷却时间（毫秒）。
     */
    private long chunkCooldownMs;
    /**
     * 击杀时获得额外战利品的概率。
     */
    private double extraLootChance;
    /**
     * 击杀时额外掉落凋灵骷髅头的概率。
     */
    private double extraSkullChance;
    /**
     * 记录每个区块最近一次额外召唤的时间戳，键为“世界UUID:x:z”。
     */
    private final Map<String, Long> chunkLastExtra = new HashMap();
    /**
     * 事件是否正在运行。
     */
    private volatile boolean running = false;

    public WitherSkeletonSwarmEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.wither_skeleton_swarm.enabled", true);
        this.minDur = config.getInt("events.wither_skeleton_swarm.min_duration_days", 1);
        this.maxDur = config.getInt("events.wither_skeleton_swarm.max_duration_days", 2);
        this.baseChance = config.getDouble("events.wither_skeleton_swarm.base_chance_per_day", 0.06);
        this.buffChance = config.getDouble("events.wither_skeleton_swarm.buff_chance", (double) 0.75F);
        this.extraSpawnChance = config.getDouble("events.wither_skeleton_swarm.extra_spawn_chance", 0.18);
        this.chunkCooldownMs = config.getLong("events.wither_skeleton_swarm.chunk_cooldown_ms", 20000L);
        this.extraLootChance = config.getDouble("events.wither_skeleton_swarm.extra_loot_chance", 0.35);
        this.extraSkullChance = config.getDouble("events.wither_skeleton_swarm.extra_skull_chance", 0.02);
    }

    @Override
    public String getId() {
        return "wither_skeleton_swarm";
    }

    @Override
    public String getDisplayName() {
        return this.plugin.lang.trOr(null, "event.wither_skeleton_swarm.display_name", "&8Wither Skeleton Swarm");
    }

    @Override
    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    @Override
    public int getMinDurationDays() {
        return this.minDur;
    }

    @Override
    public int getMaxDurationDays() {
        return this.maxDur;
    }

    /**
     * 仅当有玩家位于下界、且通过基础概率判定时才允许开始。
     */
    @Override
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

    /**
     * 事件开始时标记运行状态、清空区块冷却，并向身处下界的玩家发送标题。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.running = true;
        this.chunkLastExtra.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendTitle(this.plugin.lang.tr(player, "event.wither_skeleton_swarm.title"), this.plugin.lang.tr(player, "event.wither_skeleton_swarm.subtitle"), 20, 80, 40);
            }
        }

    }

    /**
     * 事件结束时清除运行状态与区块冷却，并通知身处下界的玩家。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.running = false;
        this.chunkLastExtra.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == Environment.NETHER) {
                player.sendMessage(this.plugin.lang.tr(player, "event.wither_skeleton_swarm.end"));
            }
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    @Override
    public void onTick(CalendarState state, EventContext context) {
    }

    /**
     * 监听生物生成事件：在下界生成凋灵骷髅时，按概率强化它，并可能额外召唤同伴。
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (this.running) {
            LivingEntity entity = event.getEntity();
            if (entity instanceof WitherSkeleton) {
                WitherSkeleton skeleton = (WitherSkeleton) entity;
                if (skeleton.getWorld().getEnvironment() == Environment.NETHER) {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    if (random.nextDouble() < this.buffChance && !skeleton.getScoreboardTags().contains("asevent_wss_buffed")) {
                        skeleton.addScoreboardTag("asevent_wss_buffed");
                        skeleton.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6000, 0, true, false, true));
                        AttributeInstance maxHealth = skeleton.getAttribute(Attribute.MAX_HEALTH);
                        if (maxHealth != null) {
                            double baseValue = maxHealth.getBaseValue();
                            double newMax = baseValue * (double) 2.0F;
                            maxHealth.setBaseValue(newMax);
                            skeleton.setHealth(Math.min(newMax, skeleton.getHealth() > (double) 0.0F ? newMax : newMax));
                        }

                        AttributeInstance attackDamage = skeleton.getAttribute(Attribute.ATTACK_DAMAGE);
                        if (attackDamage != null) {
                            double baseValue = attackDamage.getBaseValue();
                            attackDamage.setBaseValue(baseValue * (double) 2.0F);
                        }

                        skeleton.setGlowing(true);
                        skeleton.setCustomName("Empowered Wither Skeleton");
                        skeleton.setCustomNameVisible(true);
                    }

                    if (event.getSpawnReason() != SpawnReason.CUSTOM) {
                        if (random.nextDouble() < this.extraSpawnChance) {
                            String key = this.chunkKey(skeleton.getWorld(), skeleton.getLocation().getChunk());
                            long now = System.currentTimeMillis();
                            long last = (Long) this.chunkLastExtra.getOrDefault(key, 0L);
                            if (now - last < this.chunkCooldownMs) {
                                return;
                            }

                            this.chunkLastExtra.put(key, now);
                            Location baseLocation = skeleton.getLocation();
                            Location spawnLocation = baseLocation.clone().add(random.nextDouble((double) -6.0F, (double) 6.0F), (double) 0.0F, random.nextDouble((double) -6.0F, (double) 6.0F));
                            if (!spawnLocation.getBlock().isPassable()) {
                                spawnLocation = baseLocation;
                            }

                            skeleton.getWorld().spawnEntity(spawnLocation, EntityType.WITHER_SKELETON);
                        }

                    }
                }
            }
        }
    }

    /**
     * 监听凋灵骷髅死亡：被玩家击杀时，有概率追加额外战利品或凋灵骷髅头。
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (this.running) {
            LivingEntity entity = event.getEntity();
            if (entity instanceof WitherSkeleton) {
                WitherSkeleton skeleton = (WitherSkeleton) entity;
                if (skeleton.getWorld().getEnvironment() == Environment.NETHER) {
                    if (skeleton.getKiller() != null) {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        if (random.nextDouble() < this.extraLootChance) {
                            int roll = random.nextInt(100);
                            if (roll < 35) {
                                event.getDrops().add(new ItemStack(Material.COAL, 3));
                            } else if (roll < 65) {
                                event.getDrops().add(new ItemStack(Material.BONE, 4));
                            } else if (roll < 85) {
                                event.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 10));
                            } else if (roll < 95) {
                                event.getDrops().add(new ItemStack(Material.BLAZE_POWDER, 2));
                            } else {
                                event.getDrops().add(new ItemStack(Material.DIAMOND, 1));
                            }
                        }

                        if (random.nextDouble() < this.extraSkullChance) {
                            event.getDrops().add(new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
                        }

                    }
                }
            }
        }
    }

    /**
     * 生成区块冷却键，格式为“世界UUID:x:z”。
     */
    private String chunkKey(World world, Chunk chunk) {
        String worldId = String.valueOf(world.getUID());
        return worldId + ":" + chunk.getX() + ":" + chunk.getZ();
    }
}
