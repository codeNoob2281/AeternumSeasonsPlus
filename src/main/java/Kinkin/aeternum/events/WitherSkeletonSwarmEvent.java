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
import org.bukkit.World;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class WitherSkeletonSwarmEvent implements SeasonalEvent {
    private static final String TAG_BUFFED = "asevent_wss_buffed";
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double buffChance;
    private double extraSpawnChance;
    private long chunkCooldownMs;
    private double extraLootChance;
    private double extraSkullChance;
    private final Map<String, Long> chunkLastExtra = new HashMap();
    private volatile boolean running = false;

    public WitherSkeletonSwarmEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.wither_skeleton_swarm.enabled", true);
        this.minDur = y.getInt("events.wither_skeleton_swarm.min_duration_days", 1);
        this.maxDur = y.getInt("events.wither_skeleton_swarm.max_duration_days", 2);
        this.baseChance = y.getDouble("events.wither_skeleton_swarm.base_chance_per_day", 0.06);
        this.buffChance = y.getDouble("events.wither_skeleton_swarm.buff_chance", (double)0.75F);
        this.extraSpawnChance = y.getDouble("events.wither_skeleton_swarm.extra_spawn_chance", 0.18);
        this.chunkCooldownMs = y.getLong("events.wither_skeleton_swarm.chunk_cooldown_ms", 20000L);
        this.extraLootChance = y.getDouble("events.wither_skeleton_swarm.extra_loot_chance", 0.35);
        this.extraSkullChance = y.getDouble("events.wither_skeleton_swarm.extra_skull_chance", 0.02);
    }

    public String getId() {
        return "wither_skeleton_swarm";
    }

    public String getDisplayName() {
        return "Wither Skeleton Swarm";
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

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else {
            boolean someoneInNether = Bukkit.getOnlinePlayers().stream().anyMatch((p) -> p.getWorld().getEnvironment() == Environment.NETHER);
            if (!someoneInNether) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        this.running = true;
        this.chunkLastExtra.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendTitle(this.plugin.lang.tr(p, "event.wither_skeleton_swarm.title"), this.plugin.lang.tr(p, "event.wither_skeleton_swarm.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.running = false;
        this.chunkLastExtra.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.wither_skeleton_swarm.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (this.running) {
            LivingEntity var3 = e.getEntity();
            if (var3 instanceof WitherSkeleton) {
                WitherSkeleton ws = (WitherSkeleton)var3;
                if (ws.getWorld().getEnvironment() == Environment.NETHER) {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    if (rnd.nextDouble() < this.buffChance && !ws.getScoreboardTags().contains("asevent_wss_buffed")) {
                        ws.addScoreboardTag("asevent_wss_buffed");
                        ws.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6000, 0, true, false, true));
                        AttributeInstance maxHp = ws.getAttribute(Attribute.MAX_HEALTH);
                        if (maxHp != null) {
                            double base = maxHp.getBaseValue();
                            double newMax = base * (double)2.0F;
                            maxHp.setBaseValue(newMax);
                            ws.setHealth(Math.min(newMax, ws.getHealth() > (double)0.0F ? newMax : newMax));
                        }

                        AttributeInstance dmg = ws.getAttribute(Attribute.ATTACK_DAMAGE);
                        if (dmg != null) {
                            double base = dmg.getBaseValue();
                            dmg.setBaseValue(base * (double)2.0F);
                        }

                        ws.setGlowing(true);
                        ws.setCustomName("Empowered Wither Skeleton");
                        ws.setCustomNameVisible(true);
                    }

                    if (e.getSpawnReason() != SpawnReason.CUSTOM) {
                        if (rnd.nextDouble() < this.extraSpawnChance) {
                            String key = this.chunkKey(ws.getWorld(), ws.getLocation().getChunk());
                            long now = System.currentTimeMillis();
                            long last = (Long)this.chunkLastExtra.getOrDefault(key, 0L);
                            if (now - last < this.chunkCooldownMs) {
                                return;
                            }

                            this.chunkLastExtra.put(key, now);
                            Location base = ws.getLocation();
                            Location loc = base.clone().add(rnd.nextDouble((double)-6.0F, (double)6.0F), (double)0.0F, rnd.nextDouble((double)-6.0F, (double)6.0F));
                            if (!loc.getBlock().isPassable()) {
                                loc = base;
                            }

                            ws.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);
                        }

                    }
                }
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (this.running) {
            LivingEntity var3 = e.getEntity();
            if (var3 instanceof WitherSkeleton) {
                WitherSkeleton ws = (WitherSkeleton)var3;
                if (ws.getWorld().getEnvironment() == Environment.NETHER) {
                    if (ws.getKiller() != null) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        if (rnd.nextDouble() < this.extraLootChance) {
                            int roll = rnd.nextInt(100);
                            if (roll < 35) {
                                e.getDrops().add(new ItemStack(Material.COAL, 3));
                            } else if (roll < 65) {
                                e.getDrops().add(new ItemStack(Material.BONE, 4));
                            } else if (roll < 85) {
                                e.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 10));
                            } else if (roll < 95) {
                                e.getDrops().add(new ItemStack(Material.BLAZE_POWDER, 2));
                            } else {
                                e.getDrops().add(new ItemStack(Material.DIAMOND, 1));
                            }
                        }

                        if (rnd.nextDouble() < this.extraSkullChance) {
                            e.getDrops().add(new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
                        }

                    }
                }
            }
        }
    }

    private String chunkKey(World w, Chunk c) {
        String var10000 = String.valueOf(w.getUID());
        return var10000 + ":" + c.getX() + ":" + c.getZ();
    }
}
