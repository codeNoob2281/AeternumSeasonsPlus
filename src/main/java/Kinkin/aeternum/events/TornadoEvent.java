package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.world.WinterWorldGuardHelper;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 龙卷风事件（Tornado）。
 *
 * <p>触发后会在随机一名主世界玩家附近生成一个会缓慢游走、追逐玩家的龙卷风，
 * 持续 5~10 分钟（可配置）。龙卷风激活期间：
 * <ul>
 *     <li>向附近观察者渲染螺旋状烟雾/粒子；</li>
 *     <li>把半径内的实体向中心拉扯、旋转、上抛，并对生物造成持续伤害；</li>
 *     <li>按概率破坏命中的可破坏植物（不破坏受保护方块）。</li>
 * </ul>
 * 生成点需避开世界边界与受保护区域。配置来自 {@link YamlEvents} 读取的
 * {@code events.yml}。</p>
 */
public final class TornadoEvent implements SeasonalEvent {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private int minDurDays;
    private int maxDurDays;
    /**
     * 龙卷风最短持续时间（分钟）。
     */
    private int minDurMinutes;
    /**
     * 龙卷风最长持续时间（分钟）。
     */
    private int maxDurMinutes;
    /**
     * 每天触发事件的基础概率。
     */
    private double baseChance;
    /**
     * 每 tick 对生物造成的伤害。
     */
    private double damagePerTick;
    /**
     * 破坏植物的概率。
     */
    private double cropBreakChance;
    /**
     * 龙卷风作用半径（格）。
     */
    private double radius;
    /**
     * 龙卷风作用高度（格）。
     */
    private double height;
    /**
     * 距玩家最近的生成距离。
     */
    private double minSpawnDistance;
    /**
     * 距玩家最远的生成距离。
     */
    private double maxSpawnDistance;
    /**
     * 水平向心拉力系数。
     */
    private double pullHorizontal;
    /**
     * 切向旋转力系数。
     */
    private double pullSwirl;
    /**
     * 垂直上抛力系数。
     */
    private double pullVertical;
    /**
     * 龙卷风游走速度系数。
     */
    private double driftSpeed;
    private final Map<World, Location> centers = new HashMap();
    private final Map<World, Long> endTicks = new HashMap();
    private final Map<World, Vector> driftDirs = new HashMap();
    private Set<String> allowedWorlds = Collections.emptySet();
    private Set<String> disabledWorlds = Collections.emptySet();
    /**
     * 当前锁定的目标玩家 UUID。
     */
    private UUID activeTarget;
    /**
     * 向观察者显示粒子/标题的距离（格）。
     */
    private int viewerDistanceBlocks = 96;
    /**
     * 是否只对生存/冒险模式玩家生效。
     */
    private boolean onlySurvivalAdventure = true;
    private static final Set<Material> BREAKABLE_PLANTS;

    public TornadoEvent(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reloadFromConfig();
    }

    /**
     * 从 events.yml 重新读取本事件的配置项。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.tornado.enabled", true);
        this.minSpawnDistance = config.getDouble("events.tornado.min_spawn_distance", (double) 40.0F);
        this.maxSpawnDistance = config.getDouble("events.tornado.max_spawn_distance", (double) 90.0F);
        this.minDurDays = config.getInt("events.tornado.min_duration_days", 1);
        this.maxDurDays = config.getInt("events.tornado.max_duration_days", 1);
        this.minDurMinutes = config.getInt("events.tornado.min_duration_minutes", 5);
        this.maxDurMinutes = config.getInt("events.tornado.max_duration_minutes", 10);
        this.baseChance = config.getDouble("events.tornado.base_chance_per_day", 0.05);
        this.damagePerTick = config.getDouble("events.tornado.damage_per_tick", (double) 1.0F);
        this.cropBreakChance = config.getDouble("events.tornado.crop_break_chance", 0.3);
        this.radius = config.getDouble("events.tornado.radius_blocks", (double) 10.0F);
        this.height = config.getDouble("events.tornado.height_blocks", (double) 24.0F);
        this.pullHorizontal = config.getDouble("events.tornado.pull_horizontal", 0.22);
        this.pullSwirl = config.getDouble("events.tornado.pull_swirl", 0.14);
        this.pullVertical = config.getDouble("events.tornado.pull_vertical", 0.35);
        this.driftSpeed = config.getDouble("events.tornado.drift_speed", config.getDouble("events.tornado.chase_factor", 0.18));
        this.viewerDistanceBlocks = config.getInt("events.tornado.targets.viewer_distance", 96);
        this.onlySurvivalAdventure = config.getBoolean("events.tornado.targets.only_survival_adventure", true);
        List<String> allowList = config.getStringList("events.tornado.allowed_worlds");
        List<String> blockList = config.getStringList("events.tornado.disabled_worlds");
        if (!allowList.isEmpty()) {
            Set<String> normalized = new HashSet();

            for (String worldName : allowList) {
                if (worldName != null && !worldName.isBlank()) {
                    normalized.add(worldName.toLowerCase(Locale.ROOT));
                }
            }

            this.allowedWorlds = normalized;
        } else {
            this.allowedWorlds = Collections.emptySet();
        }

        if (!blockList.isEmpty()) {
            Set<String> normalized = new HashSet();

            for (String worldName : blockList) {
                if (worldName != null && !worldName.isBlank()) {
                    normalized.add(worldName.toLowerCase(Locale.ROOT));
                }
            }

            this.disabledWorlds = normalized;
        } else {
            this.disabledWorlds = Collections.emptySet();
        }

    }

    /**
     * 从所有允许的主世界中随机挑选一名合格玩家作为目标。
     */
    private Player pickSingleTarget(EventContext context, ThreadLocalRandom random) {
        List<Player> eligible = new ArrayList();

        for (World world : context.overworlds()) {
            if (this.isWorldAllowed(world)) {
                for (Player player : world.getPlayers()) {
                    if (player != null && !this.isCitizensNPC(player) && player.getGameMode() != GameMode.SPECTATOR) {
                        if (this.onlySurvivalAdventure) {
                            GameMode gameMode = player.getGameMode();
                            if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.ADVENTURE) {
                                continue;
                            }
                        }

                        eligible.add(player);
                    }
                }
            }
        }

        if (eligible.isEmpty()) {
            return null;
        } else {
            return (Player) eligible.get(random.nextInt(eligible.size()));
        }
    }

    /**
     * 在目标玩家附近随机寻找一个未被保护、且在世界边界内的生成点。
     */
    private Location pickSpawnNearPlayer(Player anchor, ThreadLocalRandom random) {
        if (anchor == null) {
            return null;
        } else {
            World world = anchor.getWorld();
            if (world == null) {
                return null;
            } else {
                Location baseLocation = anchor.getLocation();
                double minDistance = Math.max((double) 0.0F, this.minSpawnDistance);
                double maxDistance = Math.max(minDistance + (double) 8.0F, this.maxSpawnDistance);

                for (int attempt = 0; attempt < 12; ++attempt) {
                    double distance = minDistance + random.nextDouble(maxDistance - minDistance);
                    double angle = random.nextDouble((double) 0.0F, (Math.PI * 2D));
                    double dx = Math.cos(angle) * distance;
                    double dz = Math.sin(angle) * distance;
                    double x = baseLocation.getX() + dx;
                    double z = baseLocation.getZ() + dz;
                    WorldBorder border = world.getWorldBorder();
                    if (border != null) {
                        Location borderCenter = border.getCenter();
                        double maxRadius = border.getSize() / (double) 2.0F - (double) 16.0F;
                        double vx = x - borderCenter.getX();
                        double vz = z - borderCenter.getZ();
                        double len = Math.sqrt(vx * vx + vz * vz);
                        if (len > maxRadius && len > 1.0E-4) {
                            double scale = maxRadius / len;
                            vx *= scale;
                            vz *= scale;
                            x = borderCenter.getX() + vx;
                            z = borderCenter.getZ() + vz;
                        }
                    }

                    int bx = (int) Math.floor(x);
                    int bz = (int) Math.floor(z);
                    int y = world.getHighestBlockYAt(bx, bz) + 1;
                    Location candidate = new Location(world, x, (double) y, z);
                    if (!this.isProtected(candidate)) {
                        return candidate;
                    }
                }

                return null;
            }
        }
    }

    @Override
    public String getId() {
        return "tornado";
    }

    @Override
    public String getDisplayName() {
        return "Tornado";
    }

    @Override
    public boolean isSeasonAllowed(Season season) {
        return true;
    }

    @Override
    public int getMinDurationDays() {
        return this.minDurDays;
    }

    @Override
    public int getMaxDurationDays() {
        return this.maxDurDays;
    }

    /**
     * 仅当通过基础概率判定时才允许开始。
     */
    @Override
    public boolean canStartToday(CalendarState state, EventContext context) {
        if (!this.enabled) {
            return false;
        } else {
            return Math.random() < this.baseChance;
        }
    }

    /**
     * 事件开始时挑选目标、生成龙卷风中心并初始化其寿命与漂移方向。
     */
    @Override
    public void onStart(CalendarState state, EventContext context) {
        this.centers.clear();
        this.endTicks.clear();
        this.driftDirs.clear();
        this.activeTarget = null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Player target = this.pickSingleTarget(context, random);
        if (target != null) {
            this.activeTarget = target.getUniqueId();
            World world = target.getWorld();
            if (!this.isWorldAllowed(world)) {
                this.activeTarget = null;
            } else {
                Location center = this.pickSpawnNearPlayer(target, random);
                if (center == null) {
                    this.activeTarget = null;
                } else {
                    this.centers.put(world, center);
                    int minMinutes = Math.max(1, this.minDurMinutes);
                    int maxMinutes = Math.max(minMinutes, this.maxDurMinutes);
                    int chosenMinutes = minMinutes == maxMinutes ? minMinutes : random.nextInt(minMinutes, maxMinutes + 1);
                    long lifetimeTicks = (long) chosenMinutes * 60L * 20L;
                    this.endTicks.put(world, world.getFullTime() + lifetimeTicks);
                    double angle = random.nextDouble((double) 0.0F, (Math.PI * 2D));
                    this.driftDirs.put(world, (new Vector(Math.cos(angle), (double) 0.0F, Math.sin(angle))).multiply(this.driftSpeed));
                    this.sendStartTitleToViewers(world, center);
                }
            }
        }
    }

    /**
     * 向龙卷风附近的观察者发送开始标题。
     */
    private void sendStartTitleToViewers(World world, Location center) {
        if (world != null && center != null) {
            int viewerDistance = Math.max(16, this.viewerDistanceBlocks);
            double viewerDistanceSq = (double) viewerDistance * (double) viewerDistance;

            for (Player player : world.getPlayers()) {
                if (player != null && !this.isCitizensNPC(player) && !(player.getLocation().distanceSquared(center) > viewerDistanceSq)) {
                    String title = this.plugin.lang.tr(player, "event.tornado.title");
                    String subtitle = this.plugin.lang.tr(player, "event.tornado.subtitle");
                    player.sendTitle(title, subtitle, 20, 80, 40);
                }
            }

        }
    }

    /**
     * 事件结束时清理所有龙卷风状态并通知所有在线玩家。
     */
    @Override
    public void onEnd(CalendarState state, EventContext context) {
        this.centers.clear();
        this.endTicks.clear();
        this.driftDirs.clear();
        this.activeTarget = null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.plugin.lang.tr(player, "event.tornado.end"));
        }

    }

    @Override
    public void onDayTick(CalendarState state, EventContext context) {
    }

    /**
     * 每个 tick 驱动龙卷风：无中心时尝试生成，否则漂移更新并施加影响。
     */
    @Override
    public void onTick(CalendarState state, EventContext context) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (this.centers.isEmpty()) {
            Player target = this.activeTarget != null ? Bukkit.getPlayer(this.activeTarget) : null;
            if (target == null) {
                target = this.pickSingleTarget(context, random);
                if (target == null) {
                    return;
                }

                this.activeTarget = target.getUniqueId();
            }

            World world = target.getWorld();
            if (this.isWorldAllowed(world)) {
                Location spawn = this.pickSpawnNearPlayer(target, random);
                if (spawn != null) {
                    this.centers.put(world, spawn);
                    int minMinutes = Math.max(1, this.minDurMinutes);
                    int maxMinutes = Math.max(minMinutes, this.maxDurMinutes);
                    int chosenMinutes = minMinutes == maxMinutes ? minMinutes : random.nextInt(minMinutes, maxMinutes + 1);
                    long lifetimeTicks = (long) chosenMinutes * 60L * 20L;
                    this.endTicks.put(world, world.getFullTime() + lifetimeTicks);
                    double angle = random.nextDouble((double) 0.0F, (Math.PI * 2D));
                    this.driftDirs.put(world, (new Vector(Math.cos(angle), (double) 0.0F, Math.sin(angle))).multiply(this.driftSpeed));
                    this.sendStartTitleToViewers(world, spawn);
                }
            }
        } else {
            for (World world : new ArrayList<>(this.centers.keySet())) {
                if (world != null && this.isWorldAllowed(world)) {
                    Long endAt = (Long) this.endTicks.get(world);
                    if (endAt != null && world.getFullTime() >= endAt) {
                        this.centers.remove(world);
                        this.driftDirs.remove(world);
                        this.endTicks.remove(world);
                    } else {
                        Location center = (Location) this.centers.get(world);
                        if (center != null) {
                            Vector dir = (Vector) this.driftDirs.computeIfAbsent(world, (worldKey) -> {
                                double angle = ThreadLocalRandom.current().nextDouble((double) 0.0F, (Math.PI * 2D));
                                return (new Vector(Math.cos(angle), (double) 0.0F, Math.sin(angle))).multiply(this.driftSpeed);
                            });
                            double jitterAngle = (random.nextDouble() - (double) 0.5F) * 0.1;
                            double cos = Math.cos(jitterAngle);
                            double sin = Math.sin(jitterAngle);
                            Vector rotated = new Vector(dir.getX() * cos - dir.getZ() * sin, (double) 0.0F, dir.getX() * sin + dir.getZ() * cos);
                            center.add(rotated);
                            Player target = this.getClosestPlayer(world, center, (double) 160.0F);
                            if (target != null) {
                                Vector toTarget = target.getLocation().toVector().subtract(center.toVector());
                                toTarget.setY(0);
                                double dist = toTarget.length();
                                if (dist > this.radius * (double) 0.5F) {
                                    toTarget.normalize().multiply(0.2);
                                    center.add(toTarget);
                                }
                            }

                            center.setY((double) (world.getHighestBlockYAt(center) + 1));
                            this.centers.put(world, center);
                            this.renderAndAffect(world, center);
                        }
                    }
                } else {
                    this.centers.remove(world);
                    this.endTicks.remove(world);
                    this.driftDirs.remove(world);
                }
            }

        }
    }

    /**
     * 返回距给定位置最近、且未处于旁观模式的合格玩家。
     */
    private Player getClosestPlayer(World world, Location from, double maxDist) {
        Player closest = null;
        double best = maxDist * maxDist;

        for (Player player : world.getPlayers()) {
            if (!this.isCitizensNPC(player) && player.getGameMode() != GameMode.SPECTATOR) {
                double distanceSq = player.getLocation().distanceSquared(from);
                if (distanceSq < best) {
                    best = distanceSq;
                    closest = player;
                }
            }
        }

        return closest;
    }

    /**
     * 向附近观察者渲染龙卷风粒子，并对半径内实体施加拉扯、伤害与植物破坏。
     */
    private void renderAndAffect(World world, Location center) {
        double radius = this.radius;
        double height = this.height;
        double baseY = center.getY();
        long time = world.getGameTime();
        int viewerDistance = Math.max(16, this.viewerDistanceBlocks);
        double viewerDistanceSq = (double) viewerDistance * (double) viewerDistance;
        List<Player> viewers = new ArrayList();

        for (Player player : world.getPlayers()) {
            if (player != null && !this.isCitizensNPC(player) && player.getLocation().distanceSquared(center) <= viewerDistanceSq) {
                viewers.add(player);
            }
        }

        if (!viewers.isEmpty()) {
            for (double y = (double) 0.0F; y <= height; y += 0.3) {
                double heightFactor = y / height;
                double scalePart = 0.4 + heightFactor * 0.8;
                double angle1 = (double) time / (double) 4.0F + y * 0.6;
                double angle2 = angle1 + Math.PI;
                double currentRadius = radius * scalePart;
                double x1 = center.getX() + Math.cos(angle1) * currentRadius;
                double z1 = center.getZ() + Math.sin(angle1) * currentRadius;
                double x2 = center.getX() + Math.cos(angle2) * currentRadius * 0.9;
                double z2 = center.getZ() + Math.sin(angle2) * currentRadius * 0.9;

                for (Player player : viewers) {
                    player.spawnParticle(Particle.CLOUD, x1, baseY + y, z1, 10, 0.35, 0.7, 0.35, 0.02);
                    player.spawnParticle(Particle.CLOUD, x2, baseY + y, z2, 6, 0.3, 0.6, 0.3, 0.02);
                    player.spawnParticle(Particle.LARGE_SMOKE, x2, baseY + y, z2, 2, (double) 0.25F, (double) 0.5F, (double) 0.25F, 0.01);
                }
            }
        }

        for (Entity entity : world.getNearbyEntities(center, radius, height, radius)) {
            if (!this.isCitizensNPC(entity) && !this.isProtected(entity.getLocation())) {
                boolean isPlayer = entity instanceof Player;
                boolean isLiving = entity instanceof LivingEntity;
                if (isPlayer) {
                    Player player = (Player) entity;
                    if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE || this.isUnderCover(player.getLocation())) {
                        continue;
                    }
                } else if (isLiving && this.isUnderCover(entity.getLocation())) {
                    continue;
                }

                Location entityLocation = entity.getLocation();
                Vector fromCenter = entityLocation.toVector().subtract(center.toVector());
                Vector horizontal = new Vector(fromCenter.getX(), (double) 0.0F, fromCenter.getZ());
                double dist = horizontal.length();
                if (dist < 0.1) {
                    dist = 0.1;
                }

                Vector inward = horizontal.clone().multiply(-1);
                if (inward.lengthSquared() > 1.0E-4) {
                    inward.normalize();
                }

                Vector tangential = new Vector(-horizontal.getZ(), (double) 0.0F, horizontal.getX());
                if (tangential.lengthSquared() > 1.0E-4) {
                    tangential.normalize();
                }

                double distFactor = (double) 1.0F - Math.min((double) 1.0F, dist / radius);
                double scale = (double) 1.0F;
                if (isPlayer) {
                    if (Math.random() > 0.8) {
                        continue;
                    }

                    scale = 1.1;
                }

                Vector velocity = entity.getVelocity();
                velocity.add(inward.multiply(this.pullHorizontal * (0.4 + distFactor * 1.2) * scale));
                velocity.add(tangential.multiply(this.pullSwirl * (0.6 + distFactor) * scale));
                double extraUp = this.pullVertical * ((double) 0.5F + distFactor) * scale;
                velocity.setY(Math.min(velocity.getY() + extraUp, 1.2));
                entity.setVelocity(velocity);
                if (entity instanceof LivingEntity) {
                    LivingEntity living = (LivingEntity) entity;
                    if (living.getNoDamageTicks() == 0) {
                        living.damage(this.damagePerTick);
                    }
                }
            }
        }

        if (Math.random() < this.cropBreakChance) {
            int dx = (int) Math.round((Math.random() - (double) 0.5F) * radius * (double) 2.0F);
            int dz = (int) Math.round((Math.random() - (double) 0.5F) * radius * (double) 2.0F);
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);
            Block block = world.getBlockAt(x, y, z);
            if (!WinterWorldGuardHelper.canModify(block)) {
                return;
            }

            if (WinterWorldGuardHelper.canModify(block) && this.isBreakablePlant(block.getType())) {
                Block targetBlock = block;
                BlockData blockData = block.getBlockData();
                if (blockData instanceof Bisected) {
                    Bisected bisected = (Bisected) blockData;
                    if (bisected.getHalf() == Half.TOP) {
                        Block below = block.getRelative(0, -1, 0);
                        if (below.getType() == block.getType()) {
                            targetBlock = below;
                        }
                    }
                }

                targetBlock.breakNaturally();
            }
        }

    }

    /**
     * 判断世界是否为允许的主世界（未在禁用列表且满足允许列表）。
     */
    private boolean isWorldAllowed(World world) {
        if (world == null) {
            return false;
        } else if (world.getEnvironment() != Environment.NORMAL) {
            return false;
        } else {
            String name = world.getName().toLowerCase(Locale.ROOT);
            if (!this.disabledWorlds.isEmpty() && this.disabledWorlds.contains(name)) {
                return false;
            } else {
                return this.allowedWorlds.isEmpty() || this.allowedWorlds.contains(name);
            }
        }
    }

    /**
     * 判断给定方块类型是否属于可被龙卷风破坏的植物。
     */
    private boolean isBreakablePlant(Material material) {
        return BREAKABLE_PLANTS.contains(material);
    }

    /**
     * 判断位置上方是否有遮挡（即处于“室内/掩体”中）。
     */
    private boolean isUnderCover(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        } else {
            int x = location.getBlockX();
            int z = location.getBlockZ();
            int surfaceY = world.getHighestBlockYAt(x, z);
            return (double) surfaceY > location.getY() + (double) 1.0F;
        }
    }

    /**
     * 判断位置是否处于受保护区域（无权限修改）。
     */
    private boolean isProtected(Location location) {
        if (location != null && location.getWorld() != null) {
            return !WinterWorldGuardHelper.canModify(location.getBlock());
        } else {
            return false;
        }
    }

    /**
     * 判断实体是否为 Citizens 插件生成的 NPC。
     */
    private boolean isCitizensNPC(Entity entity) {
        return entity != null && entity.hasMetadata("NPC");
    }

    static {
        BREAKABLE_PLANTS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM, Material.SWEET_BERRY_BUSH, Material.NETHER_WART, Material.COCOA, Material.SUGAR_CANE, Material.BAMBOO, Material.SHORT_GRASS, Material.SEAGRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY);
    }
}
