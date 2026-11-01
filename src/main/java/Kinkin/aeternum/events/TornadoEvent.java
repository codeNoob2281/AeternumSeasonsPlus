package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.world.WinterWorldGuardHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class TornadoEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDurDays;
    private int maxDurDays;
    private int minDurMinutes;
    private int maxDurMinutes;
    private double baseChance;
    private double damagePerTick;
    private double cropBreakChance;
    private double radius;
    private double height;
    private double minSpawnDistance;
    private double maxSpawnDistance;
    private double pullHorizontal;
    private double pullSwirl;
    private double pullVertical;
    private double driftSpeed;
    private final Map<World, Location> centers = new HashMap();
    private final Map<World, Long> endTicks = new HashMap();
    private final Map<World, Vector> driftDirs = new HashMap();
    private Set<String> allowedWorlds = Collections.emptySet();
    private Set<String> disabledWorlds = Collections.emptySet();
    private UUID activeTarget;
    private int viewerDistanceBlocks = 96;
    private boolean onlySurvivalAdventure = true;
    private static final Set<Material> BREAKABLE_PLANTS;

    public TornadoEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    private void reloadFromConfig() {
        FileConfiguration y = YamlEvents.get(this.plugin);
        this.enabled = y.getBoolean("events.tornado.enabled", true);
        this.minSpawnDistance = y.getDouble("events.tornado.min_spawn_distance", (double)40.0F);
        this.maxSpawnDistance = y.getDouble("events.tornado.max_spawn_distance", (double)90.0F);
        this.minDurDays = y.getInt("events.tornado.min_duration_days", 1);
        this.maxDurDays = y.getInt("events.tornado.max_duration_days", 1);
        this.minDurMinutes = y.getInt("events.tornado.min_duration_minutes", 5);
        this.maxDurMinutes = y.getInt("events.tornado.max_duration_minutes", 10);
        this.baseChance = y.getDouble("events.tornado.base_chance_per_day", 0.05);
        this.damagePerTick = y.getDouble("events.tornado.damage_per_tick", (double)1.0F);
        this.cropBreakChance = y.getDouble("events.tornado.crop_break_chance", 0.3);
        this.radius = y.getDouble("events.tornado.radius_blocks", (double)10.0F);
        this.height = y.getDouble("events.tornado.height_blocks", (double)24.0F);
        this.pullHorizontal = y.getDouble("events.tornado.pull_horizontal", 0.22);
        this.pullSwirl = y.getDouble("events.tornado.pull_swirl", 0.14);
        this.pullVertical = y.getDouble("events.tornado.pull_vertical", 0.35);
        this.driftSpeed = y.getDouble("events.tornado.drift_speed", y.getDouble("events.tornado.chase_factor", 0.18));
        this.viewerDistanceBlocks = y.getInt("events.tornado.targets.viewer_distance", 96);
        this.onlySurvivalAdventure = y.getBoolean("events.tornado.targets.only_survival_adventure", true);
        List<String> allowList = y.getStringList("events.tornado.allowed_worlds");
        List<String> blockList = y.getStringList("events.tornado.disabled_worlds");
        if (!allowList.isEmpty()) {
            Set<String> tmp = new HashSet();

            for(String s : allowList) {
                if (s != null && !s.isBlank()) {
                    tmp.add(s.toLowerCase(Locale.ROOT));
                }
            }

            this.allowedWorlds = tmp;
        } else {
            this.allowedWorlds = Collections.emptySet();
        }

        if (!blockList.isEmpty()) {
            Set<String> tmp = new HashSet();

            for(String s : blockList) {
                if (s != null && !s.isBlank()) {
                    tmp.add(s.toLowerCase(Locale.ROOT));
                }
            }

            this.disabledWorlds = tmp;
        } else {
            this.disabledWorlds = Collections.emptySet();
        }

    }

    private Player pickSingleTarget(EventContext ctx, ThreadLocalRandom r) {
        List<Player> eligible = new ArrayList();

        for(World w : ctx.overworlds()) {
            if (this.isWorldAllowed(w)) {
                for(Player p : w.getPlayers()) {
                    if (p != null && !this.isCitizensNPC(p) && p.getGameMode() != GameMode.SPECTATOR) {
                        if (this.onlySurvivalAdventure) {
                            GameMode gm = p.getGameMode();
                            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) {
                                continue;
                            }
                        }

                        eligible.add(p);
                    }
                }
            }
        }

        if (eligible.isEmpty()) {
            return null;
        } else {
            return (Player)eligible.get(r.nextInt(eligible.size()));
        }
    }

    private Location pickSpawnNearPlayer(Player anchor, ThreadLocalRandom r) {
        if (anchor == null) {
            return null;
        } else {
            World w = anchor.getWorld();
            if (w == null) {
                return null;
            } else {
                Location base = anchor.getLocation();
                double minD = Math.max((double)0.0F, this.minSpawnDistance);
                double maxD = Math.max(minD + (double)8.0F, this.maxSpawnDistance);

                for(int attempt = 0; attempt < 12; ++attempt) {
                    double dist = minD + r.nextDouble(maxD - minD);
                    double angle = r.nextDouble((double)0.0F, (Math.PI * 2D));
                    double dx = Math.cos(angle) * dist;
                    double dz = Math.sin(angle) * dist;
                    double x = base.getX() + dx;
                    double z = base.getZ() + dz;
                    WorldBorder border = w.getWorldBorder();
                    if (border != null) {
                        Location bc = border.getCenter();
                        double maxRadius = border.getSize() / (double)2.0F - (double)16.0F;
                        double vx = x - bc.getX();
                        double vz = z - bc.getZ();
                        double len = Math.sqrt(vx * vx + vz * vz);
                        if (len > maxRadius && len > 1.0E-4) {
                            double scale = maxRadius / len;
                            vx *= scale;
                            vz *= scale;
                            x = bc.getX() + vx;
                            z = bc.getZ() + vz;
                        }
                    }

                    int bx = (int)Math.floor(x);
                    int bz = (int)Math.floor(z);
                    int y = w.getHighestBlockYAt(bx, bz) + 1;
                    Location loc = new Location(w, x, (double)y, z);
                    if (!this.isProtected(loc)) {
                        return loc;
                    }
                }

                return null;
            }
        }
    }

    public String getId() {
        return "tornado";
    }

    public String getDisplayName() {
        return "Tornado";
    }

    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    public int getMinDurationDays() {
        return this.minDurDays;
    }

    public int getMaxDurationDays() {
        return this.maxDurDays;
    }

    public boolean canStartToday(CalendarState st, EventContext ctx) {
        if (!this.enabled) {
            return false;
        } else {
            return Math.random() < this.baseChance;
        }
    }

    public void onStart(CalendarState st, EventContext ctx) {
        this.centers.clear();
        this.endTicks.clear();
        this.driftDirs.clear();
        this.activeTarget = null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Player target = this.pickSingleTarget(ctx, r);
        if (target != null) {
            this.activeTarget = target.getUniqueId();
            World w = target.getWorld();
            if (!this.isWorldAllowed(w)) {
                this.activeTarget = null;
            } else {
                Location center = this.pickSpawnNearPlayer(target, r);
                if (center == null) {
                    this.activeTarget = null;
                } else {
                    this.centers.put(w, center);
                    int minM = Math.max(1, this.minDurMinutes);
                    int maxM = Math.max(minM, this.maxDurMinutes);
                    int chosenMin = minM == maxM ? minM : r.nextInt(minM, maxM + 1);
                    long lifetimeTicks = (long)chosenMin * 60L * 20L;
                    this.endTicks.put(w, w.getFullTime() + lifetimeTicks);
                    double ang = r.nextDouble((double)0.0F, (Math.PI * 2D));
                    this.driftDirs.put(w, (new Vector(Math.cos(ang), (double)0.0F, Math.sin(ang))).multiply(this.driftSpeed));
                    this.sendStartTitleToViewers(w, center);
                }
            }
        }
    }

    private void sendStartTitleToViewers(World w, Location center) {
        if (w != null && center != null) {
            int vd = Math.max(16, this.viewerDistanceBlocks);
            double vd2 = (double)vd * (double)vd;

            for(Player p : w.getPlayers()) {
                if (p != null && !this.isCitizensNPC(p) && !(p.getLocation().distanceSquared(center) > vd2)) {
                    String title = this.plugin.lang.tr(p, "event.tornado.title");
                    String sub = this.plugin.lang.tr(p, "event.tornado.subtitle");
                    p.sendTitle(title, sub, 20, 80, 40);
                }
            }

        }
    }

    public void onEnd(CalendarState st, EventContext ctx) {
        this.centers.clear();
        this.endTicks.clear();
        this.driftDirs.clear();
        this.activeTarget = null;

        for(Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(this.plugin.lang.tr(p, "event.tornado.end"));
        }

    }

    public void onDayTick(CalendarState st, EventContext ctx) {
    }

    public void onTick(CalendarState st, EventContext ctx) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (this.centers.isEmpty()) {
            Player target = this.activeTarget != null ? Bukkit.getPlayer(this.activeTarget) : null;
            if (target == null) {
                target = this.pickSingleTarget(ctx, r);
                if (target == null) {
                    return;
                }

                this.activeTarget = target.getUniqueId();
            }

            World w = target.getWorld();
            if (this.isWorldAllowed(w)) {
                Location spawn = this.pickSpawnNearPlayer(target, r);
                if (spawn != null) {
                    this.centers.put(w, spawn);
                    int minM = Math.max(1, this.minDurMinutes);
                    int maxM = Math.max(minM, this.maxDurMinutes);
                    int chosenMin = minM == maxM ? minM : r.nextInt(minM, maxM + 1);
                    long lifetimeTicks = (long)chosenMin * 60L * 20L;
                    this.endTicks.put(w, w.getFullTime() + lifetimeTicks);
                    double angle = r.nextDouble((double)0.0F, (Math.PI * 2D));
                    this.driftDirs.put(w, (new Vector(Math.cos(angle), (double)0.0F, Math.sin(angle))).multiply(this.driftSpeed));
                    this.sendStartTitleToViewers(w, spawn);
                }
            }
        } else {
            for(World w : new ArrayList<>(this.centers.keySet())) {
                if (w != null && this.isWorldAllowed(w)) {
                    Long endAt = (Long)this.endTicks.get(w);
                    if (endAt != null && w.getFullTime() >= endAt) {
                        this.centers.remove(w);
                        this.driftDirs.remove(w);
                        this.endTicks.remove(w);
                    } else {
                        Location center = (Location)this.centers.get(w);
                        if (center != null) {
                            Vector dir = (Vector)this.driftDirs.computeIfAbsent(w, (ww) -> {
                                double angle = ThreadLocalRandom.current().nextDouble((double)0.0F, (Math.PI * 2D));
                                return (new Vector(Math.cos(angle), (double)0.0F, Math.sin(angle))).multiply(this.driftSpeed);
                            });
                            double jitterAngle = (r.nextDouble() - (double)0.5F) * 0.1;
                            double cos = Math.cos(jitterAngle);
                            double sin = Math.sin(jitterAngle);
                            Vector rotated = new Vector(dir.getX() * cos - dir.getZ() * sin, (double)0.0F, dir.getX() * sin + dir.getZ() * cos);
                            center.add(rotated);
                            Player target = this.getClosestPlayer(w, center, (double)160.0F);
                            if (target != null) {
                                Vector to = target.getLocation().toVector().subtract(center.toVector());
                                to.setY(0);
                                double dist = to.length();
                                if (dist > this.radius * (double)0.5F) {
                                    to.normalize().multiply(0.2);
                                    center.add(to);
                                }
                            }

                            center.setY((double)(w.getHighestBlockYAt(center) + 1));
                            this.centers.put(w, center);
                            this.renderAndAffect(w, center);
                        }
                    }
                } else {
                    this.centers.remove(w);
                    this.endTicks.remove(w);
                    this.driftDirs.remove(w);
                }
            }

        }
    }

    private Player getClosestPlayer(World w, Location from, double maxDist) {
        Player closest = null;
        double best = maxDist * maxDist;

        for(Player p : w.getPlayers()) {
            if (!this.isCitizensNPC(p) && p.getGameMode() != GameMode.SPECTATOR) {
                double d2 = p.getLocation().distanceSquared(from);
                if (d2 < best) {
                    best = d2;
                    closest = p;
                }
            }
        }

        return closest;
    }

    private void renderAndAffect(World w, Location center) {
        double r = this.radius;
        double h = this.height;
        double baseY = center.getY();
        long time = w.getGameTime();
        int vd = Math.max(16, this.viewerDistanceBlocks);
        double vd2 = (double)vd * (double)vd;
        List<Player> viewers = new ArrayList();

        for(Player p : w.getPlayers()) {
            if (p != null && !this.isCitizensNPC(p) && p.getLocation().distanceSquared(center) <= vd2) {
                viewers.add(p);
            }
        }

        if (!viewers.isEmpty()) {
            for(double y = (double)0.0F; y <= h; y += 0.3) {
                double heightFactor = y / h;
                double scalePart = 0.4 + heightFactor * 0.8;
                double angle1 = (double)time / (double)4.0F + y * 0.6;
                double angle2 = angle1 + Math.PI;
                double currentRadius = r * scalePart;
                double x1 = center.getX() + Math.cos(angle1) * currentRadius;
                double z1 = center.getZ() + Math.sin(angle1) * currentRadius;
                double x2 = center.getX() + Math.cos(angle2) * currentRadius * 0.9;
                double z2 = center.getZ() + Math.sin(angle2) * currentRadius * 0.9;

                for(Player p : viewers) {
                    p.spawnParticle(Particle.CLOUD, x1, baseY + y, z1, 10, 0.35, 0.7, 0.35, 0.02);
                    p.spawnParticle(Particle.CLOUD, x2, baseY + y, z2, 6, 0.3, 0.6, 0.3, 0.02);
                    p.spawnParticle(Particle.LARGE_SMOKE, x2, baseY + y, z2, 2, (double)0.25F, (double)0.5F, (double)0.25F, 0.01);
                }
            }
        }

        for(Entity e : w.getNearbyEntities(center, r, h, r)) {
            if (!this.isCitizensNPC(e) && !this.isProtected(e.getLocation())) {
                boolean isPlayer = e instanceof Player;
                boolean isLiving = e instanceof LivingEntity;
                if (isPlayer) {
                    Player p = (Player)e;
                    if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE || this.isUnderCover(p.getLocation())) {
                        continue;
                    }
                } else if (isLiving && this.isUnderCover(e.getLocation())) {
                    continue;
                }

                Location el = e.getLocation();
                Vector fromCenter = el.toVector().subtract(center.toVector());
                Vector horizontal = new Vector(fromCenter.getX(), (double)0.0F, fromCenter.getZ());
                double dist = horizontal.length();
                if (dist < 0.1) {
                    dist = 0.1;
                }

                Vector inward = horizontal.clone().multiply(-1);
                if (inward.lengthSquared() > 1.0E-4) {
                    inward.normalize();
                }

                Vector tangential = new Vector(-horizontal.getZ(), (double)0.0F, horizontal.getX());
                if (tangential.lengthSquared() > 1.0E-4) {
                    tangential.normalize();
                }

                double distFactor = (double)1.0F - Math.min((double)1.0F, dist / r);
                double scale = (double)1.0F;
                if (isPlayer) {
                    if (Math.random() > 0.8) {
                        continue;
                    }

                    scale = 1.1;
                }

                Vector vel = e.getVelocity();
                vel.add(inward.multiply(this.pullHorizontal * (0.4 + distFactor * 1.2) * scale));
                vel.add(tangential.multiply(this.pullSwirl * (0.6 + distFactor) * scale));
                double extraUp = this.pullVertical * ((double)0.5F + distFactor) * scale;
                vel.setY(Math.min(vel.getY() + extraUp, 1.2));
                e.setVelocity(vel);
                if (e instanceof LivingEntity) {
                    LivingEntity le = (LivingEntity)e;
                    if (le.getNoDamageTicks() == 0) {
                        le.damage(this.damagePerTick);
                    }
                }
            }
        }

        if (Math.random() < this.cropBreakChance) {
            int dx = (int)Math.round((Math.random() - (double)0.5F) * r * (double)2.0F);
            int dz = (int)Math.round((Math.random() - (double)0.5F) * r * (double)2.0F);
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = w.getHighestBlockYAt(x, z);
            Block b = w.getBlockAt(x, y, z);
            if (!WinterWorldGuardHelper.canModify(b)) {
                return;
            }

            if (WinterWorldGuardHelper.canModify(b) && this.isBreakablePlant(b.getType())) {
                Block target = b;
                BlockData lower = b.getBlockData();
                if (lower instanceof Bisected) {
                    Bisected bisected = (Bisected)lower;
                    if (bisected.getHalf() == Half.TOP) {
                        Block below = b.getRelative(0, -1, 0);
                        if (below.getType() == b.getType()) {
                            target = below;
                        }
                    }
                }

                target.breakNaturally();
            }
        }

    }

    private boolean isWorldAllowed(World w) {
        if (w == null) {
            return false;
        } else if (w.getEnvironment() != Environment.NORMAL) {
            return false;
        } else {
            String name = w.getName().toLowerCase(Locale.ROOT);
            if (!this.disabledWorlds.isEmpty() && this.disabledWorlds.contains(name)) {
                return false;
            } else {
                return this.allowedWorlds.isEmpty() || this.allowedWorlds.contains(name);
            }
        }
    }

    private boolean isBreakablePlant(Material mat) {
        return BREAKABLE_PLANTS.contains(mat);
    }

    private boolean isUnderCover(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            int surfaceY = w.getHighestBlockYAt(x, z);
            return (double)surfaceY > loc.getY() + (double)1.0F;
        }
    }

    private boolean isProtected(Location loc) {
        if (loc != null && loc.getWorld() != null) {
            return !WinterWorldGuardHelper.canModify(loc.getBlock());
        } else {
            return false;
        }
    }

    private boolean isCitizensNPC(Entity entity) {
        return entity != null && entity.hasMetadata("NPC");
    }

    static {
        BREAKABLE_PLANTS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM, Material.SWEET_BERRY_BUSH, Material.NETHER_WART, Material.COCOA, Material.SUGAR_CANE, Material.BAMBOO, Material.SHORT_GRASS, Material.SEAGRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY);
    }
}
