package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public final class WitherLooseEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDur;
    private int maxDur;
    private double baseChance;
    private int minTargets;
    private int maxTargets;
    private boolean onlySurvivalAdventure;
    private int spawnDelaySeconds;
    private int countdownIntervalSeconds;
    private int spawnMinDistance;
    private int spawnMaxDistance;
    private double witherMaxHealth;
    private boolean witherGlowing;
    private int idleKillSeconds;
    private int idleCheckSeconds;
    private double extraLootChanceScrap;
    private int extraLootScrapMin;
    private int extraLootScrapMax;
    private double extraLootChanceFireProtBook;
    private double extraLootChanceGhastTear;
    private final List<UUID> targets = new ArrayList();
    private BukkitTask countdownTask;
    private BukkitTask spawnTask;
    private UUID activeWitherId = null;
    private long lastDamageAtMs = 0L;
    private BukkitTask idleTask = null;
    private final NamespacedKey witherMarkKey;

    public WitherLooseEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.witherMarkKey = new NamespacedKey(plugin, "event_wither_loose");
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.wither_loose.enabled", true);
        this.minDur = y.getInt("events.wither_loose.min_duration_days", 1);
        this.maxDur = y.getInt("events.wither_loose.max_duration_days", 1);
        this.baseChance = y.getDouble("events.wither_loose.base_chance_per_day", 0.06);
        this.minTargets = y.getInt("events.wither_loose.targets.min_targets", 1);
        this.maxTargets = y.getInt("events.wither_loose.targets.max_targets", 3);
        this.onlySurvivalAdventure = y.getBoolean("events.wither_loose.targets.only_survival_adventure", true);
        this.spawnDelaySeconds = y.getInt("events.wither_loose.spawn_delay_seconds", 60);
        this.countdownIntervalSeconds = y.getInt("events.wither_loose.countdown_interval_seconds", 20);
        this.spawnMinDistance = y.getInt("events.wither_loose.spawn.min_distance", 18);
        this.spawnMaxDistance = y.getInt("events.wither_loose.spawn.max_distance", 28);
        this.witherMaxHealth = y.getDouble("events.wither_loose.wither.max_health", (double)180.0F);
        this.witherGlowing = y.getBoolean("events.wither_loose.wither.glowing", true);
        this.idleKillSeconds = y.getInt("events.wither_loose.wither.idle_kill_seconds", 180);
        this.idleCheckSeconds = y.getInt("events.wither_loose.wither.idle_check_seconds", 10);
        this.extraLootChanceScrap = y.getDouble("events.wither_loose.loot.netherite_scrap_chance", 0.3);
        this.extraLootScrapMin = y.getInt("events.wither_loose.loot.netherite_scrap_min", 1);
        this.extraLootScrapMax = y.getInt("events.wither_loose.loot.netherite_scrap_max", 2);
        this.extraLootChanceFireProtBook = y.getDouble("events.wither_loose.loot.fire_prot_book_chance", 0.15);
        this.extraLootChanceGhastTear = y.getDouble("events.wither_loose.loot.ghast_tear_chance", (double)0.25F);
    }

    public String getId() {
        return "wither_loose";
    }

    public String getDisplayName() {
        return "Wither Loose";
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
            int eligible = this.getEligibleNetherPlayers().size();
            if (eligible < Math.max(1, this.minTargets)) {
                return false;
            } else {
                return Math.random() < this.baseChance;
            }
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        this.stopTasks();
        List<Player> eligible = this.getEligibleNetherPlayers();
        if (!eligible.isEmpty()) {
            int want = this.clamp(ThreadLocalRandom.current().nextInt(this.minTargets, this.maxTargets + 1), 1, eligible.size());
            Collections.shuffle(eligible, ThreadLocalRandom.current());
            this.targets.clear();

            for(int i = 0; i < want; ++i) {
                this.targets.add(((Player)eligible.get(i)).getUniqueId());
            }

            this.forEachTarget((p) -> p.sendTitle(this.plugin.lang.tr(p, "event.wither_loose.title"), this.plugin.lang.tr(p, "event.wither_loose.subtitle"), 20, 80, 40));
            this.announceSeconds(this.spawnDelaySeconds);
            final int interval = Math.max(5, this.countdownIntervalSeconds);
            this.countdownTask = Bukkit.getScheduler().runTaskTimer(this.plugin, new Runnable() {
                int left;

                {
                    this.left = WitherLooseEvent.this.spawnDelaySeconds;
                }

                public void run() {
                    this.left -= interval;
                    if (this.left <= 0) {
                        if (WitherLooseEvent.this.countdownTask != null) {
                            WitherLooseEvent.this.countdownTask.cancel();
                        }

                        WitherLooseEvent.this.countdownTask = null;
                    } else {
                        WitherLooseEvent.this.announceSeconds(this.left);
                    }
                }
            }, 20L * (long)interval, 20L * (long)interval);
            this.spawnTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                this.spawnTask = null;
                this.spawnWitherNearTargets();
            }, 20L * (long)Math.max(1, this.spawnDelaySeconds));
        }
    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.stopTasks();
        this.targets.clear();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER) {
                p.sendMessage(this.plugin.lang.tr(p, "event.wither_loose.end"));
            }
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
    }

    @EventHandler
    public void onWitherDamage(EntityDamageEvent e) {
        Entity var3 = e.getEntity();
        if (var3 instanceof Wither w) {
            if (w.getWorld().getEnvironment() == Environment.NETHER) {
                PersistentDataContainer pdc = w.getPersistentDataContainer();
                if (pdc.has(this.witherMarkKey, PersistentDataType.BYTE)) {
                    if (!e.isCancelled()) {
                        if (!(e.getFinalDamage() <= (double)0.0F)) {
                            this.lastDamageAtMs = System.currentTimeMillis();
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onWitherDeath(EntityDeathEvent e) {
        LivingEntity var3 = e.getEntity();
        if (var3 instanceof Wither w) {
            if (w.getWorld().getEnvironment() == Environment.NETHER) {
                PersistentDataContainer pdc = w.getPersistentDataContainer();
                if (pdc.has(this.witherMarkKey, PersistentDataType.BYTE)) {
                    this.activeWitherId = null;
                    this.lastDamageAtMs = 0L;
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    if (rnd.nextDouble() < this.extraLootChanceScrap) {
                        int amt = this.randomBetween(this.extraLootScrapMin, this.extraLootScrapMax, rnd);
                        if (amt > 0) {
                            e.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, amt));
                        }
                    }

                    if (rnd.nextDouble() < this.extraLootChanceGhastTear) {
                        e.getDrops().add(new ItemStack(Material.GHAST_TEAR, 1));
                    }

                    if (rnd.nextDouble() < this.extraLootChanceFireProtBook) {
                        e.getDrops().add(this.makeFireProtBook(4));
                    }

                }
            }
        }
    }

    private void spawnWitherNearTargets() {
        Player chosen = this.pickActiveTargetInNether();
        if (chosen != null) {
            Location spawn = this.findSafeSpawnNear(chosen, this.spawnMinDistance, this.spawnMaxDistance, 40);
            if (spawn == null) {
                spawn = chosen.getLocation().clone().add((double)4.0F, (double)2.0F, (double)4.0F);
            }

            World w = spawn.getWorld();
            if (w != null && w.getEnvironment() == Environment.NETHER) {
                Wither wither = (Wither)w.spawnEntity(spawn, EntityType.WITHER);
                wither.getPersistentDataContainer().set(this.witherMarkKey, PersistentDataType.BYTE, (byte)1);
                AttributeInstance maxHp = wither.getAttribute(Attribute.MAX_HEALTH);
                if (maxHp != null) {
                    maxHp.setBaseValue(this.witherMaxHealth);
                }

                wither.setHealth(Math.min(this.witherMaxHealth, wither.getMaxHealth()));
                wither.setGlowing(this.witherGlowing);
                wither.setCustomName(this.plugin.lang.trServer("event.wither_loose.wither_name"));
                wither.setCustomNameVisible(true);
                this.activeWitherId = wither.getUniqueId();
                this.lastDamageAtMs = System.currentTimeMillis();
                this.startIdleTaskIfNeeded();
                this.forEachTarget((p) -> {
                    p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0F, 0.8F);
                    p.sendTitle(this.plugin.lang.tr(p, "event.wither_loose.title"), this.plugin.lang.tr(p, "event.wither_loose.survive"), 10, 60, 20);
                });
                w.spawnParticle(Particle.SMOKE, spawn.clone().add((double)0.0F, (double)1.5F, (double)0.0F), 24, 0.8, 0.7, 0.8, 0.01);
                w.spawnParticle(Particle.LAVA, spawn.clone().add((double)0.0F, 1.2, (double)0.0F), 10, 0.6, 0.4, 0.6, 0.01);
            }
        }
    }

    private void startIdleTaskIfNeeded() {
        if (this.idleTask == null) {
            long periodTicks = 20L * (long)Math.max(2, this.idleCheckSeconds);
            this.idleTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                if (this.activeWitherId != null) {
                    Wither found = null;
                    Entity ent = Bukkit.getEntity(this.activeWitherId);
                    if (ent instanceof Wither) {
                        Wither ww = (Wither)ent;
                        if (ww.getWorld().getEnvironment() == Environment.NETHER && !ww.isDead()) {
                            found = ww;
                        }
                    }

                    if (found == null) {
                        this.activeWitherId = null;
                        this.lastDamageAtMs = 0L;
                    } else {
                        long now = System.currentTimeMillis();
                        if (this.lastDamageAtMs <= 0L) {
                            this.lastDamageAtMs = now;
                        }

                        if (now - this.lastDamageAtMs >= (long)this.idleKillSeconds * 1000L) {
                            found.remove();
                            this.activeWitherId = null;
                            this.lastDamageAtMs = 0L;
                            this.forEachTarget((p) -> p.sendMessage(this.plugin.lang.tr(p, "event.wither_loose.idle_killed")));
                        }

                    }
                }
            }, periodTicks, periodTicks);
        }
    }

    private void announceSeconds(int seconds) {
        this.forEachTarget((p) -> {
            String var10000;
            switch (seconds) {
                case 20 -> var10000 = "event.wither_loose.countdown_20";
                case 40 -> var10000 = "event.wither_loose.countdown_40";
                case 60 -> var10000 = "event.wither_loose.countdown_60";
                default -> var10000 = "event.wither_loose.countdown_generic";
            }

            String key = var10000;
            String msg = this.plugin.lang.trf(p, key, Map.of("seconds", String.valueOf(seconds)));
            p.sendMessage(msg);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.6F);
        });
    }

    private void stopTasks() {
        if (this.countdownTask != null) {
            this.countdownTask.cancel();
        }

        if (this.spawnTask != null) {
            this.spawnTask.cancel();
        }

        if (this.idleTask != null) {
            this.idleTask.cancel();
        }

        this.countdownTask = null;
        this.spawnTask = null;
        this.idleTask = null;
        this.activeWitherId = null;
        this.lastDamageAtMs = 0L;
    }

    private void forEachTarget(Consumer<Player> action) {
        for(UUID id : this.targets) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().getEnvironment() == Environment.NETHER) {
                action.accept(p);
            }
        }

    }

    private List<Player> getEligibleNetherPlayers() {
        List<Player> out = new ArrayList();

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == Environment.NETHER && p.getGameMode() != GameMode.SPECTATOR && (!this.onlySurvivalAdventure || p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                out.add(p);
            }
        }

        return out;
    }

    private Player pickActiveTargetInNether() {
        List<Player> alive = new ArrayList();

        for(UUID id : this.targets) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().getEnvironment() == Environment.NETHER && p.getGameMode() != GameMode.SPECTATOR) {
                alive.add(p);
            }
        }

        if (alive.isEmpty()) {
            return null;
        } else {
            return (Player)alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
        }
    }

    private Location findSafeSpawnNear(Player target, int minDist, int maxDist, int attempts) {
        World w = target.getWorld();
        if (w == null) {
            return null;
        } else {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            Location base = target.getLocation();
            int minY = Math.max(w.getMinHeight() + 5, 10);
            int maxY = Math.min(w.getMaxHeight() - 5, base.getBlockY() + 10);

            for(int i = 0; i < attempts; ++i) {
                int dx = rnd.nextInt(-maxDist, maxDist + 1);
                int dz = rnd.nextInt(-maxDist, maxDist + 1);
                if (dx * dx + dz * dz >= minDist * minDist) {
                    int x = base.getBlockX() + dx;
                    int z = base.getBlockZ() + dz;
                    int y = this.clamp(base.getBlockY() + rnd.nextInt(-6, 7), minY, maxY);

                    for(int dy = -4; dy <= 4; ++dy) {
                        int yy = this.clamp(y + dy, minY, w.getMaxHeight() - 5);
                        Block feet = w.getBlockAt(x, yy, z);
                        Block head = w.getBlockAt(x, yy + 1, z);
                        Block floor = w.getBlockAt(x, yy - 1, z);
                        if (feet.isPassable() && head.isPassable()) {
                            Material fm = floor.getType();
                            if (floor.getType().isSolid() && fm != Material.LAVA && fm != Material.MAGMA_BLOCK && fm != Material.FIRE && fm != Material.SOUL_FIRE) {
                                Location loc = new Location(w, (double)x + (double)0.5F, (double)yy, (double)z + (double)0.5F);
                                if (!(loc.distanceSquared(base) < (double)(minDist * minDist))) {
                                    return loc;
                                }
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    private ItemStack makeFireProtBook(int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
        if (meta != null) {
            meta.addStoredEnchant(Enchantment.FIRE_PROTECTION, level, true);
            book.setItemMeta(meta);
        }

        return book;
    }

    private int randomBetween(int min, int max, ThreadLocalRandom rnd) {
        if (max < min) {
            return min;
        } else {
            return min == max ? min : rnd.nextInt(min, max + 1);
        }
    }

    private int clamp(int v, int a, int b) {
        return Math.max(a, Math.min(b, v));
    }
}
