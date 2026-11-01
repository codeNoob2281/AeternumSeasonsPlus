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

public final class GhastAlertEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private double markedGhastChance;
    private double extraTearChance;
    private double extraSpawnChance;
    private long chunkCooldownMs;
    private int maxNearbyGhasts;
    private double nearbyRadius;
    private final Map<String, Long> chunkLastExtra = new HashMap();

    public GhastAlertEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.ghast_alert.enabled", true);
        this.minDur = y.getInt("events.ghast_alert.min_duration_days", 1);
        this.maxDur = y.getInt("events.ghast_alert.max_duration_days", 1);
        this.baseChance = y.getDouble("events.ghast_alert.base_chance_per_day", 0.08);
        this.markedGhastChance = y.getDouble("events.ghast_alert.marked_ghast_chance", (double)0.25F);
        this.extraTearChance = y.getDouble("events.ghast_alert.extra_tear_chance", 0.3);
        this.extraSpawnChance = y.getDouble("events.ghast_alert.extra_spawn_chance", 0.8);
        this.chunkCooldownMs = y.getLong("events.ghast_alert.chunk_cooldown_ms", 15000L);
        this.maxNearbyGhasts = y.getInt("events.ghast_alert.max_nearby_ghasts", 6);
        this.nearbyRadius = y.getDouble("events.ghast_alert.nearby_radius", (double)48.0F);
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
        this.chunkLastExtra.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendTitle(this.plugin.lang.tr(p, "event.ghast_alert.title"), this.plugin.lang.tr(p, "event.ghast_alert.subtitle"), 20, 80, 40);
            }
        }

    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.chunkLastExtra.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.ghast_alert.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Ghast g) {
            if (g.getWorld().getEnvironment() == Environment.NETHER) {
                CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
                if (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN) {
                    if (Math.random() < this.markedGhastChance) {
                        String name = this.plugin.lang.trServer("event.ghast_alert.ghast_name");
                        g.setCustomName(name);
                        g.setCustomNameVisible(true);
                        g.setGlowing(true);
                    }

                    if (!(Math.random() > this.extraSpawnChance)) {
                        World w = g.getWorld();
                        Chunk c = g.getLocation().getChunk();
                        String var10000 = String.valueOf(w.getUID());
                        String key = var10000 + ":" + c.getX() + ":" + c.getZ();
                        long now = System.currentTimeMillis();
                        long last = (Long)this.chunkLastExtra.getOrDefault(key, 0L);
                        if (now - last >= this.chunkCooldownMs) {
                            int nearby = 0;

                            for(Entity ent : g.getNearbyEntities(this.nearbyRadius, this.nearbyRadius, this.nearbyRadius)) {
                                if (ent instanceof Ghast) {
                                    ++nearby;
                                    if (nearby >= this.maxNearbyGhasts) {
                                        return;
                                    }
                                }
                            }

                            this.chunkLastExtra.put(key, now);
                            Location base = g.getLocation();
                            ThreadLocalRandom rnd = ThreadLocalRandom.current();
                            double dx = rnd.nextDouble((double)-18.0F, (double)18.0F);
                            double dz = rnd.nextDouble((double)-18.0F, (double)18.0F);
                            double dy = rnd.nextDouble((double)-6.0F, (double)8.0F);
                            Location loc = base.clone().add(dx, dy, dz);
                            if (!loc.getChunk().isLoaded()) {
                                loc.getChunk().load();
                            }

                            w.spawn(loc, Ghast.class, (gh) -> {
                                gh.getWorld().spawnParticle(Particle.SMOKE, gh.getLocation().add((double)0.0F, (double)1.5F, (double)0.0F), 10, 0.6, (double)0.5F, 0.6, 0.01);
                                if (Math.random() < this.markedGhastChance * 0.6) {
                                    String name = this.plugin.lang.trServer("event.ghast_alert.ghast_name");
                                    gh.setCustomName(name);
                                    gh.setCustomNameVisible(true);
                                    gh.setGlowing(true);
                                }

                            });
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Ghast g) {
            if (g.getWorld().getEnvironment() == Environment.NETHER) {
                if (g.getKiller() != null) {
                    if (Math.random() < this.extraTearChance) {
                        e.getDrops().add(new ItemStack(Material.GHAST_TEAR, 1));
                    }

                    g.getWorld().spawnParticle(Particle.SMOKE, g.getLocation().add((double)0.0F, (double)2.0F, (double)0.0F), 14, 0.7, 0.6, 0.7, 0.01);
                }
            }
        }
    }
}
