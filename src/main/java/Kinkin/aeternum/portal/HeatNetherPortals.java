package Kinkin.aeternum.portal;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 炽热领域（aeternum_heat）下界传送门的连接管理器。
 *
 * <p>本监听器区分“炽热门”（下界疣块框）与“原版门”（黑曜石框），仅接管炽热门：
 * <ul>
 *     <li>用打火石/火焰弹点燃下界疣块框传送门，或用投射物命中门内触发；</li>
 *     <li>在玩家/实体穿越炽热门时，取消原版传送并按已保存链接或新建门计算目标；</li>
 *     <li>将双向链接持久化到 heat_nether_links.yml，并在传送后展示领域标题。</li>
 * </ul>
 * 传送门以 {@link PortalKey}（世界名 + 坐标 + 朝向 + 种类）标识，依赖
 * {@link PortalBuildProtection} 选择安全建造点。</p>
 */
public final class HeatNetherPortals implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final File portalDataFile;
    private final Map<PortalKey, PortalKey> portalConnections = new ConcurrentHashMap();
    private boolean registered;

    /** 创建管理器并从数据文件加载已保存的传送门链接。 */
    public HeatNetherPortals(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.portalDataFile = new File(plugin.getDataFolder(), "heat_nether_links.yml");
        this.loadPortalLinks();
    }

    /** 幂等地将本监听器注册到插件的事件总线。 */
    public void register() {
        if (!this.registered) {
            this.registered = true;
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
        }
    }

    /** 注销事件监听并保存当前传送门链接。 */
    public void unregister() {
        this.registered = false;
        HandlerList.unregisterAll(this);
        this.savePortalLinks();
    }

    /** 读取 heat_nether_links.yml，将成对出现的单向炽热门记录恢复为双向链接。 */
    private void loadPortalLinks() {
        this.portalConnections.clear();
        if (this.portalDataFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(this.portalDataFile);
            Map<PortalKey, PortalKey> loaded = new HashMap();

            for(String key : config.getKeys(false)) {
                String destRaw = config.getString(key);
                if (destRaw != null && !destRaw.isBlank()) {
                    PortalKey source = this.parsePortalKey(key);
                    PortalKey dest = this.parsePortalKey(destRaw);
                    if (source != null && dest != null && source.kind == HeatNetherPortals.PortalKind.HEAT && dest.kind == HeatNetherPortals.PortalKind.HEAT && !source.equals(dest)) {
                        loaded.put(source, dest);
                    }
                }
            }

            for(Map.Entry<PortalKey, PortalKey> entry : loaded.entrySet()) {
                PortalKey source = (PortalKey)entry.getKey();
                PortalKey destination = (PortalKey)entry.getValue();
                PortalKey reverse = (PortalKey)loaded.get(destination);
                if ((reverse == null || reverse.equals(source)) && !this.portalConnections.containsKey(source) && !this.portalConnections.containsKey(destination)) {
                    this.portalConnections.put(source, destination);
                    this.portalConnections.put(destination, source);
                }
            }

            this.plugin.getLogger().info("[HeatNetherPortals] Cargados " + this.portalConnections.size() + " enlaces exactos.");
        }
    }

    /** 将当前所有双向链接写回 heat_nether_links.yml。 */
    private void savePortalLinks() {
        FileConfiguration config = new YamlConfiguration();

        for(Map.Entry<PortalKey, PortalKey> entry : this.portalConnections.entrySet()) {
            config.set(this.formatPortalKey((PortalKey)entry.getKey()), this.formatPortalKey((PortalKey)entry.getValue()));
        }

        try {
            config.save(this.portalDataFile);
        } catch (IOException e) {
            this.plugin.getLogger().severe("[HeatNetherPortals] No se pudo guardar heat_nether_links.yml: " + e.getMessage());
        }

    }

    /** 将传送门键序列化为“世界|X|Y|Z|朝向|种类”格式。 */
    private String formatPortalKey(PortalKey key) {
        String worldName = key.worldName;
        return worldName + "|" + key.x + "|" + key.y + "|" + key.z + "|" + key.axis.name() + "|" + key.kind.name();
    }

    /**
     * 解析“世界|X|Y|Z|朝向[|种类]”格式的字符串。
     * 旧格式缺少种类字段时，会根据锚点方块与框架材质推断门种类。
     */
    private @Nullable PortalKey parsePortalKey(String raw) {
        String[] parts = raw.split("\\|");
        if (parts.length != 5 && parts.length != 6) {
            return null;
        } else {
            try {
                String worldName = parts[0];
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                Axis axis = Axis.valueOf(parts[4]);
                PortalKind kind;
                if (parts.length == 6) {
                    kind = HeatNetherPortals.PortalKind.valueOf(parts[5]);
                } else {
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        Block anchor = world.getBlockAt(x, y, z);
                        if (anchor.getType() == Material.NETHER_PORTAL) {
                            PortalBounds bounds = this.getPortalBounds(anchor);
                            kind = bounds != null ? this.getPortalKind(bounds, world) : (worldName.equalsIgnoreCase("aeternum_heat") ? HeatNetherPortals.PortalKind.HEAT : HeatNetherPortals.PortalKind.VANILLA);
                        } else {
                            kind = worldName.equalsIgnoreCase("aeternum_heat") ? HeatNetherPortals.PortalKind.HEAT : HeatNetherPortals.PortalKind.VANILLA;
                        }
                    } else {
                        kind = worldName.equalsIgnoreCase("aeternum_heat") ? HeatNetherPortals.PortalKind.HEAT : HeatNetherPortals.PortalKind.VANILLA;
                    }
                }

                return new PortalKey(worldName, x, y, z, axis, kind);
            } catch (Exception exception) {
                return null;
            }
        }
    }

    /** 先解除两端原有链接，再建立新的双向链接并立即保存。 */
    private void registerPortalLink(PortalKey source, PortalKey destination) {
        this.unlinkPortalEndpoint(source);
        this.unlinkPortalEndpoint(destination);
        this.portalConnections.put(source, destination);
        this.portalConnections.put(destination, source);
        this.savePortalLinks();
    }

    /** 解除指定端点的链接并立即保存。 */
    private void removePortalLink(PortalKey endpoint) {
        this.unlinkPortalEndpoint(endpoint);
        this.savePortalLinks();
    }

    /** 解除单个端点的双向映射：若对端确实指回自己，则一并移除对端记录。 */
    private void unlinkPortalEndpoint(PortalKey endpoint) {
        PortalKey other = (PortalKey)this.portalConnections.remove(endpoint);
        if (other != null && endpoint.equals(this.portalConnections.get(other))) {
            this.portalConnections.remove(other);
        }

    }

    /**
     * 右键交互处理：手持打火石或火焰弹右键方块时，若目标空中格可构成炽热门形状，
     * 则取消原版点火并自行点亮。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getClickedBlock() != null) {
                Material item = event.getItem() != null ? event.getItem().getType() : Material.AIR;
                if (item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE) {
                    Block clicked = event.getClickedBlock();
                    BlockFace face = event.getBlockFace();
                    Block inside = clicked.getRelative(face);
                    if (inside.getType().isAir()) {
                        HeatPortalShape shape = HeatNetherPortals.HeatPortalShape.detect(inside);
                        if (shape != null) {
                            event.setCancelled(true);
                            this.lightPortal(shape);
                        }
                    }
                }
            }
        }
    }

    /**
     * 投射物命中处理：玩家投掷的投射物击中方块后，检测命中面另一侧是否构成炽热门，是则点亮。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player) {
            Block hit = event.getHitBlock();
            if (hit != null) {
                BlockFace face = event.getHitBlockFace();
                Block inside = face != null ? hit.getRelative(face) : hit;
                HeatPortalShape shape = HeatNetherPortals.HeatPortalShape.detect(inside);
                if (shape != null) {
                    this.lightPortal(shape);
                }
            }
        }
    }

    /** 将传送门内部区域填充为下界传送门方块，并播放音效与粒子。 */
    private void lightPortal(HeatPortalShape shape) {
        World world = shape.world;
        Orientable data = (Orientable)Material.NETHER_PORTAL.createBlockData();
        data.setAxis(shape.axis);

        for(int x = 0; x < shape.width; ++x) {
            for(int y = 0; y < shape.height; ++y) {
                Block block = shape.getInteriorBlock(x, y);
                block.setBlockData(data, false);
            }
        }

        world.playSound(shape.getCenter(), Sound.BLOCK_PORTAL_TRAVEL, 1.0F, 1.0F);
        world.spawnParticle(Particle.PORTAL, shape.getCenter(), 60, (double)0.5F, (double)1.0F, (double)0.5F, 0.1);
    }

    /** 统计门框四周材质，若存在下界疣块则判定为“炽热门”，否则为原版门。 */
    private PortalKind getPortalKind(PortalBounds bounds, World world) {
        int wart = 0;
        int obsidian = 0;
        if (bounds.axis == Axis.X) {
            int z = bounds.minZ;

            for(int y = bounds.minY; y <= bounds.maxY; ++y) {
                Material left = world.getBlockAt(bounds.minX - 1, y, z).getType();
                Material right = world.getBlockAt(bounds.maxX + 1, y, z).getType();
                if (left == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (left == Material.OBSIDIAN) {
                    ++obsidian;
                }

                if (right == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (right == Material.OBSIDIAN) {
                    ++obsidian;
                }
            }

            for(int x = bounds.minX; x <= bounds.maxX; ++x) {
                Material bottom = world.getBlockAt(x, bounds.minY - 1, z).getType();
                Material top = world.getBlockAt(x, bounds.maxY + 1, z).getType();
                if (bottom == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (bottom == Material.OBSIDIAN) {
                    ++obsidian;
                }

                if (top == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (top == Material.OBSIDIAN) {
                    ++obsidian;
                }
            }
        } else {
            int x = bounds.minX;

            for(int y = bounds.minY; y <= bounds.maxY; ++y) {
                Material north = world.getBlockAt(x, y, bounds.minZ - 1).getType();
                Material south = world.getBlockAt(x, y, bounds.maxZ + 1).getType();
                if (north == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (north == Material.OBSIDIAN) {
                    ++obsidian;
                }

                if (south == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (south == Material.OBSIDIAN) {
                    ++obsidian;
                }
            }

            for(int z = bounds.minZ; z <= bounds.maxZ; ++z) {
                Material bottom = world.getBlockAt(x, bounds.minY - 1, z).getType();
                Material top = world.getBlockAt(x, bounds.maxY + 1, z).getType();
                if (bottom == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (bottom == Material.OBSIDIAN) {
                    ++obsidian;
                }

                if (top == Material.NETHER_WART_BLOCK) {
                    ++wart;
                } else if (top == Material.OBSIDIAN) {
                    ++obsidian;
                }
            }
        }

        return wart > 0 ? HeatNetherPortals.PortalKind.HEAT : HeatNetherPortals.PortalKind.VANILLA;
    }

    /**
     * 玩家传送门事件处理：若出发点为炽热门，则取消原版传送，
     * 解析目标门并以原朝向传送，随后延时展示领域标题。
     */
    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.HIGHEST
    )
    public void onPortal(PlayerPortalEvent event) {
        if (this.isHeatPortalAt(event.getFrom())) {
            event.setCancelled(true);
            event.setCanCreatePortal(false);
            PortalTarget target = this.resolvePortalTarget(event.getFrom());
            if (target != null) {
                World fromWorld = event.getFrom().getWorld();
                Location to = target.teleportLocation.clone();
                to.setYaw(event.getFrom().getYaw());
                to.setPitch(event.getFrom().getPitch());
                event.setTo(to);
                event.setCancelled(false);
                event.setCanCreatePortal(false);
                this.scheduleRealmTitle(event.getPlayer(), fromWorld, to.getWorld());
            }
        }
    }

    /** 实体传送门事件处理：若出发点为炽热门，则改为传送至解析出的目标门位置。 */
    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.HIGHEST
    )
    public void onEntityPortal(EntityPortalEvent event) {
        if (this.isHeatPortalAt(event.getFrom())) {
            event.setCancelled(true);
            PortalTarget target = this.resolvePortalTarget(event.getFrom());
            if (target != null) {
                event.setTo(target.teleportLocation.clone());
                event.setCancelled(false);
            }
        }
    }

    /** 传送后延迟一 tick，确认玩家已进入目标世界再展示领域标题。 */
    private void scheduleRealmTitle(Player player, World from, World to) {
        if (player != null && from != null && to != null) {
            boolean enteringHeat = to.getName().equalsIgnoreCase("aeternum_heat");
            boolean leavingHeat = from.getName().equalsIgnoreCase("aeternum_heat");
            if (enteringHeat || leavingHeat) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    if (player.isOnline()) {
                        if (player.getWorld().getUID().equals(to.getUID())) {
                            this.showRealmTitle(player, from, to);
                        }
                    }
                }, 1L);
            }
        }
    }

    /** 根据出发/目标世界展示“进入炽热领域”或“返回”的标题与副标题。 */
    private void showRealmTitle(Player player, World from, World to) {
        String title;
        String subtitle;
        if (to.getName().equalsIgnoreCase("aeternum_heat")) {
            String heatName = this.plugin.lang.tr(player, "realm.heat_overworld");
            title = this.plugin.lang.trf(player, "realm.heat_title", Map.of("name", heatName));
            subtitle = this.plugin.lang.tr(player, "realm.heat_subtitle");
        } else {
            if (!from.getName().equalsIgnoreCase("aeternum_heat")) {
                return;
            }

            String heatName = this.plugin.lang.tr(player, "realm.heat_overworld");
            subtitle = this.plugin.lang.trf(player, "realm.returned_from", Map.of("name", heatName));
            if (to.getName().equalsIgnoreCase(this.plugin.getMainOverworldName())) {
                title = this.plugin.lang.tr(player, "realm.overworld_title");
            } else if (to.getName().equalsIgnoreCase("aeternum_frost")) {
                String frostName = this.plugin.lang.tr(player, "realm.frost_overworld");
                title = this.plugin.lang.trf(player, "realm.frost_title", Map.of("name", frostName));
            } else {
                title = "&a" + to.getName();
            }
        }

        title = ChatColor.translateAlternateColorCodes('&', title);
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
        player.sendTitle(title, subtitle, 10, 60, 10);
    }

    /** 判断给定位置附近是否存在炽热门。 */
    private boolean isHeatPortalAt(Location from) {
        Block portalBlock = this.findPortalBlock(from);
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            PortalBounds bounds = this.getPortalBounds(portalBlock);
            return bounds != null && this.getPortalKind(bounds, portalBlock.getWorld()) == HeatNetherPortals.PortalKind.HEAT;
        } else {
            return false;
        }
    }

    /** 解析炽热门的传送目标：优先复用已保存链接，否则就近复用或新建炽热门。 */
    private @Nullable PortalTarget resolvePortalTarget(Location from) {
        Block portalBlock = this.findPortalBlock(from);
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            PortalBounds sourceBounds = this.getPortalBounds(portalBlock);
            if (sourceBounds == null) {
                return null;
            } else {
                PortalKind sourceKind = this.getPortalKind(sourceBounds, portalBlock.getWorld());
                if (sourceKind != HeatNetherPortals.PortalKind.HEAT) {
                    return null;
                } else {
                    TeleportDecision decision = this.resolveTeleport(portalBlock, sourceBounds, from);
                    if (decision == null) {
                        return null;
                    } else {
                        PortalKey sourceKey = sourceBounds.toKey(portalBlock.getWorld().getName(), sourceKind);
                        PortalTarget linked = this.findLinkedPortal(sourceKey, decision.toWorld, decision.portalKind);
                        if (linked != null) {
                            return linked;
                        } else {
                            PortalTarget target = this.findOrCreatePortal(sourceKey, decision.toWorld, decision.targetX, decision.targetY, decision.targetZ, decision.axis, decision.portalKind);
                            if (target != null) {
                                this.registerPortalLink(sourceKey, target.key);
                                return target;
                            } else {
                                return null;
                            }
                        }
                    }
                }
            }
        } else {
            return null;
        }
    }

    /** 查找已保存且仍然有效（世界、门种类与归一化键一致）的链接目标；失效则清除链接。 */
    private @Nullable PortalTarget findLinkedPortal(PortalKey sourceKey, World expectedWorld, PortalKind expectedKind) {
        PortalKey destKey = (PortalKey)this.portalConnections.get(sourceKey);
        if (destKey == null) {
            return null;
        } else {
            World world = Bukkit.getWorld(destKey.worldName);
            if (world != null && world.getName().equalsIgnoreCase(expectedWorld.getName())) {
                Block anchor = world.getBlockAt(destKey.x, destKey.y, destKey.z);
                if (anchor.getType() != Material.NETHER_PORTAL) {
                    this.removePortalLink(sourceKey);
                    return null;
                } else {
                    PortalBounds bounds = this.getPortalBounds(anchor);
                    if (bounds == null) {
                        this.removePortalLink(sourceKey);
                        return null;
                    } else {
                        PortalKind actualKind = this.getPortalKind(bounds, world);
                        if (actualKind == expectedKind && destKey.kind == expectedKind) {
                            PortalKey normalized = bounds.toKey(world.getName(), actualKind);
                            if (!normalized.equals(destKey)) {
                                this.registerPortalLink(sourceKey, normalized);
                            }

                            return new PortalTarget(bounds.getTeleportLocation(world), normalized);
                        } else {
                            this.removePortalLink(sourceKey);
                            return null;
                        }
                    }
                }
            } else {
                this.removePortalLink(sourceKey);
                return null;
            }
        }
    }

    /**
     * 依据出发世界决定传送方向：非炽热世界一律前往炽热世界；
     * 从炽热世界离开时则回到其已链接的自然世界。
     */
    private @Nullable TeleportDecision resolveTeleport(Block portalBlock, PortalBounds bounds, Location from) {
        World fromWorld = portalBlock.getWorld();
        String fromName = fromWorld.getName();
        Axis axis = this.getPortalAxis(portalBlock);
        World mainWorld = this.plugin.getMainOverworld();
        World heatWorld = Bukkit.getWorld("aeternum_heat");
        if (mainWorld == null) {
            return null;
        } else {
            this.getPortalKind(bounds, fromWorld);
            if (!fromName.equalsIgnoreCase("aeternum_heat")) {
                return heatWorld == null ? null : new TeleportDecision(heatWorld, from.getX(), from.getY(), from.getZ(), axis, HeatNetherPortals.PortalKind.HEAT);
            } else if (fromName.equalsIgnoreCase("aeternum_heat")) {
                PortalKey sourceKey = bounds.toKey(fromWorld.getName(), HeatNetherPortals.PortalKind.HEAT);
                PortalKey linkedKey = (PortalKey)this.portalConnections.get(sourceKey);
                World returnWorld = linkedKey != null ? Bukkit.getWorld(linkedKey.worldName) : null;
                return returnWorld != null && !returnWorld.getName().equalsIgnoreCase("aeternum_heat") ? new TeleportDecision(returnWorld, from.getX(), from.getY(), from.getZ(), axis, HeatNetherPortals.PortalKind.HEAT) : null;
            } else {
                return null;
            }
        }
    }

    /** 读取传送门方块朝向；读取失败时回退为 X 轴。 */
    private Axis getPortalAxis(Block portalBlock) {
        try {
            if (portalBlock.getBlockData() instanceof Orientable) {
                return ((Orientable)portalBlock.getBlockData()).getAxis();
            }
        } catch (Exception exception) {
        }

        return Axis.X;
    }

    /** 在给定位置周围半径 2 的立方体内查找最近的传送门方块（优先按门中心距离）。 */
    private @Nullable Block findPortalBlock(Location from) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        } else {
            Block best = null;
            double bestScore = Double.MAX_VALUE;
            int bx = from.getBlockX();
            int by = from.getBlockY();
            int bz = from.getBlockZ();

            for(int dx = -2; dx <= 2; ++dx) {
                for(int dy = -2; dy <= 2; ++dy) {
                    for(int dz = -2; dz <= 2; ++dz) {
                        Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        if (block.getType() == Material.NETHER_PORTAL) {
                            PortalBounds bounds = this.getPortalBounds(block);
                            if (bounds != null && bounds.contains(from)) {
                                double score = bounds.distanceSquaredToCenter(from);
                                if (score < bestScore) {
                                    bestScore = score;
                                    best = block;
                                }
                            }
                        }
                    }
                }
            }

            if (best != null) {
                return best;
            } else {
                for(int dx = -2; dx <= 2; ++dx) {
                    for(int dy = -2; dy <= 2; ++dy) {
                        for(int dz = -2; dz <= 2; ++dz) {
                            Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                            if (block.getType() == Material.NETHER_PORTAL) {
                                Location c = block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
                                double score = c.distanceSquared(from);
                                if (score < bestScore) {
                                    bestScore = score;
                                    best = block;
                                }
                            }
                        }
                    }
                }

                return best;
            }
        }
    }

    /** 计算传送门方块所在门的包围盒（逐轴扩张以覆盖整座门的范围）。 */
    private @Nullable PortalBounds getPortalBounds(Block portalBlock) {
        if (portalBlock.getType() != Material.NETHER_PORTAL) {
            return null;
        } else {
            Axis axis = this.getPortalAxis(portalBlock);
            World world = portalBlock.getWorld();
            int minY = portalBlock.getY();

            int maxY;
            for(maxY = portalBlock.getY(); minY > world.getMinHeight() && world.getBlockAt(portalBlock.getX(), minY - 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minY) {
            }

            while(maxY < world.getMaxHeight() - 1 && world.getBlockAt(portalBlock.getX(), maxY + 1, portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                ++maxY;
            }

            if (axis == Axis.X) {
                int minX = portalBlock.getX();

                int maxX;
                for(maxX = portalBlock.getX(); world.getBlockAt(minX - 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL; --minX) {
                }

                while(world.getBlockAt(maxX + 1, portalBlock.getY(), portalBlock.getZ()).getType() == Material.NETHER_PORTAL) {
                    ++maxX;
                }

                return new PortalBounds(axis, minX, maxX, minY, maxY, portalBlock.getZ(), portalBlock.getZ());
            } else {
                int minZ = portalBlock.getZ();

                int maxZ;
                for(maxZ = portalBlock.getZ(); world.getBlockAt(portalBlock.getX(), portalBlock.getY(), minZ - 1).getType() == Material.NETHER_PORTAL; --minZ) {
                }

                while(world.getBlockAt(portalBlock.getX(), portalBlock.getY(), maxZ + 1).getType() == Material.NETHER_PORTAL) {
                    ++maxZ;
                }

                return new PortalBounds(axis, portalBlock.getX(), portalBlock.getX(), minY, maxY, minZ, maxZ);
            }
        }
    }

    /** 在目标世界就近复用可用炽热门，否则在安全点新建一座炽热门并返回其传送目标。 */
    private @Nullable PortalTarget findOrCreatePortal(PortalKey sourceKey, World world, double x, double y, double z, Axis axis, PortalKind portalKind) {
        if (portalKind != HeatNetherPortals.PortalKind.HEAT) {
            return null;
        } else {
            PortalTarget reusable = this.findReusableNearbyPortal(sourceKey, world, x, y, z, 32, portalKind);
            if (reusable != null) {
                return reusable;
            } else {
                int bx = (int)Math.round(x);
                int bz = (int)Math.round(z);
                boolean followSurface = world.getEnvironment() != Environment.NETHER;
                int preferredBottomY = followSurface ? world.getHighestBlockYAt(bx, bz) + 1 : this.clampNetherY(world, (int)Math.round(y)) + 1;
                PortalBuildProtection.Site site = PortalBuildProtection.findSafeSite(world, bx, preferredBottomY, bz, axis, followSurface);
                if (site == null) {
                    this.plugin.getLogger().warning("[HeatNetherPortals] No safe build site near " + world.getName() + " " + bx + "," + preferredBottomY + "," + bz);
                    return null;
                } else {
                    Material frameMaterial = Material.NETHER_WART_BLOCK;
                    HeatPortalShape shape = HeatNetherPortals.HeatPortalShape.buildAt(world, site.centerX(), site.bottomY(), site.centerZ(), axis, frameMaterial);
                    this.lightPortal(shape);
                    Block anchor = shape.getInteriorBlock(0, 0);
                    PortalBounds bounds = this.getPortalBounds(anchor);
                    return bounds == null ? new PortalTarget(shape.getSpawnLocation(), new PortalKey(world.getName(), anchor.getX(), anchor.getY(), anchor.getZ(), axis, portalKind)) : new PortalTarget(bounds.getTeleportLocation(world), bounds.toKey(world.getName(), portalKind));
                }
            }
        }
    }

    /** 在给定半径内查找种类匹配且可被当前来源占用的最近炽热门。 */
    private @Nullable PortalTarget findReusableNearbyPortal(PortalKey sourceKey, World world, double x, double y, double z, int radius, PortalKind expectedKind) {
        int bx = (int)Math.round(x);
        int by = (int)Math.round(y);
        int bz = (int)Math.round(z);
        PortalTarget best = null;
        double bestDist = Double.MAX_VALUE;

        for(int ix = bx - radius; ix <= bx + radius; ++ix) {
            for(int iz = bz - radius; iz <= bz + radius; ++iz) {
                int minY = Math.max(world.getMinHeight(), by - radius);
                int maxY = Math.min(world.getMaxHeight() - 1, by + radius);

                for(int iy = minY; iy <= maxY; ++iy) {
                    Block block = world.getBlockAt(ix, iy, iz);
                    if (block.getType() == Material.NETHER_PORTAL) {
                        PortalBounds bounds = this.getPortalBounds(block);
                        if (bounds != null) {
                            PortalKind actualKind = this.getPortalKind(bounds, world);
                            if (actualKind == expectedKind) {
                                PortalKey candidateKey = bounds.toKey(world.getName(), actualKind);
                                if (this.isPortalAvailableForSource(sourceKey, candidateKey, expectedKind)) {
                                    Location tp = bounds.getTeleportLocation(world);
                                    double dx = tp.getX() - x;
                                    double dy = tp.getY() - y;
                                    double dz = tp.getZ() - z;
                                    double distSq = dx * dx + dy * dy + dz * dz;
                                    if (distSq < bestDist) {
                                        bestDist = distSq;
                                        best = new PortalTarget(tp, candidateKey);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /** 判断候选门是否可被来源门复用：未被占用、已被来源占用或原链接已失效时视为可用。 */
    private boolean isPortalAvailableForSource(PortalKey sourceKey, PortalKey candidateKey, PortalKind expectedKind) {
        if (candidateKey.kind != expectedKind) {
            return false;
        } else {
            PortalKey owner = (PortalKey)this.portalConnections.get(candidateKey);
            if (owner == null) {
                return true;
            } else if (owner.equals(sourceKey)) {
                return true;
            } else if (!this.isPortalKeyAlive(candidateKey, expectedKind)) {
                this.removePortalLink(candidateKey);
                return true;
            } else if (!this.isPortalKeyAlive(owner, (PortalKind)null)) {
                this.removePortalLink(candidateKey);
                return true;
            } else {
                PortalKey reverse = (PortalKey)this.portalConnections.get(owner);
                if (reverse != null && reverse.equals(candidateKey)) {
                    return false;
                } else {
                    this.removePortalLink(candidateKey);
                    return true;
                }
            }
        }
    }

    /** 校验传送门键对应的门是否仍有效（世界存在、为下界传送门、归一化一致，且种类可选匹配）。 */
    private boolean isPortalKeyAlive(PortalKey key, @Nullable PortalKind expectedKind) {
        World world = Bukkit.getWorld(key.worldName);
        if (world == null) {
            return false;
        } else {
            Block anchor = world.getBlockAt(key.x, key.y, key.z);
            if (anchor.getType() != Material.NETHER_PORTAL) {
                return false;
            } else {
                PortalBounds bounds = this.getPortalBounds(anchor);
                if (bounds == null) {
                    return false;
                } else {
                    PortalKind actualKind = this.getPortalKind(bounds, world);
                    PortalKey normalized = bounds.toKey(world.getName(), actualKind);
                    if (!normalized.equals(key)) {
                        return false;
                    } else {
                        return expectedKind == null || actualKind == expectedKind;
                    }
                }
            }
        }
    }

    /** 将下界目标高度限制在合理范围内（避开基岩层与建筑高度上限）。 */
    private int clampNetherY(World world, int preferredY) {
        int min = world.getMinHeight() + 8;
        int max = world.getMaxHeight() - 16;
        return Math.max(min, Math.min(max, preferredY));
    }

    /** 传送门种类：原版（黑曜石框）或炽热（下界疣块框）。 */
    private static enum PortalKind {
        VANILLA(false),
        HEAT(true);

        private final boolean custom;

        private PortalKind(boolean custom) {
            this.custom = custom;
        }

        public boolean isCustom() {
            return this.custom;
        }

        // $FF: synthetic method
        private static PortalKind[] $values() {
            return new PortalKind[]{VANILLA, HEAT};
        }
    }

    /** 传送目标：推荐传送坐标与其对应的传送门键。 */
    private static final class PortalTarget {
        final Location teleportLocation;
        final PortalKey key;

        private PortalTarget(Location teleportLocation, PortalKey key) {
            super();
            this.teleportLocation = teleportLocation;
            this.key = key;
        }
    }

    /** 传送决策：目标世界、目标坐标、朝向与门种类。 */
    private static final class TeleportDecision {
        final World toWorld;
        final double targetX;
        final double targetY;
        final double targetZ;
        final Axis axis;
        final PortalKind portalKind;

        private TeleportDecision(World toWorld, double targetX, double targetY, double targetZ, Axis axis, PortalKind portalKind) {
            super();
            this.toWorld = toWorld;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.axis = axis;
            this.portalKind = portalKind;
        }
    }

    /** 传送门键：以世界名、锚点坐标、朝向与种类唯一标识一座传送门。 */
    private static final class PortalKey {
        final String worldName;
        final int x;
        final int y;
        final int z;
        final Axis axis;
        final PortalKind kind;

        private PortalKey(String worldName, int x, int y, int z, Axis axis, PortalKind kind) {
            super();
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.axis = axis;
            this.kind = kind;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof PortalKey)) {
                return false;
            } else {
                PortalKey other = (PortalKey)obj;
                return this.x == other.x && this.y == other.y && this.z == other.z && this.axis == other.axis && this.kind == other.kind && Objects.equals(this.worldName, other.worldName);
            }
        }

        public int hashCode() {
            return Objects.hash(new Object[]{this.worldName, this.x, this.y, this.z, this.axis, this.kind});
        }
    }

    /** 传送门的轴对齐包围盒，提供包含判定、中心距离与传送点计算。 */
    private static final class PortalBounds {
        private final Axis axis;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private PortalBounds(Axis axis, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            super();
            this.axis = axis;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        private boolean contains(Location loc) {
            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            if (!(y < (double)this.minY) && !(y >= (double)(this.maxY + 1))) {
                if (this.axis == Axis.X) {
                    return x >= (double)this.minX && x < (double)(this.maxX + 1) && Math.abs(z - ((double)this.minZ + (double)0.5F)) <= (double)0.75F;
                } else {
                    return z >= (double)this.minZ && z < (double)(this.maxZ + 1) && Math.abs(x - ((double)this.minX + (double)0.5F)) <= (double)0.75F;
                }
            } else {
                return false;
            }
        }

        private double distanceSquaredToCenter(Location loc) {
            double cx = (double)(this.minX + this.maxX + 1) / (double)2.0F;
            double cy = (double)(this.minY + this.maxY + 1) / (double)2.0F;
            double cz = (double)(this.minZ + this.maxZ + 1) / (double)2.0F;
            double dx = loc.getX() - cx;
            double dy = loc.getY() - cy;
            double dz = loc.getZ() - cz;
            return dx * dx + dy * dy + dz * dz;
        }

        private Location getTeleportLocation(World world) {
            double x;
            double z;
            if (this.axis == Axis.X) {
                x = (double)(this.minX + this.maxX + 1) / (double)2.0F;
                z = (double)this.minZ + (double)0.5F;
            } else {
                x = (double)this.minX + (double)0.5F;
                z = (double)(this.minZ + this.maxZ + 1) / (double)2.0F;
            }

            double y = (double)this.minY + 0.1;
            return new Location(world, x, y, z);
        }

        private PortalKey toKey(String worldName, PortalKind kind) {
            return new PortalKey(worldName, this.minX, this.minY, this.minZ, this.axis, kind);
        }
    }

    /** 炽热传送门的几何描述（底部锚点、宽高、朝向与框材质），提供建造、检测与取点工具。 */
    private static final class HeatPortalShape {
        final World world;
        final int x0;
        final int y0;
        final int z0;
        final int width;
        final int height;
        final Axis axis;
        final Material frameMaterial;

        private HeatPortalShape(World world, int x0, int y0, int z0, int width, int height, Axis axis, Material frameMaterial) {
            super();
            this.world = world;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.width = width;
            this.height = height;
            this.axis = axis;
            this.frameMaterial = frameMaterial;
        }

        /** 在指定中心与底部高度处以给定框材质建造一座炽热门，返回其几何描述。 */
        static HeatPortalShape buildAt(World world, int centerX, int bottomY, int centerZ, Axis axis, Material frameMaterial) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int x0 = centerX - 1;
                int z0 = centerZ;
                int y0 = bottomY;

                for(int x = -1; x <= width; ++x) {
                    for(int y = -1; y <= height; ++y) {
                        Block block = world.getBlockAt(x0 + x, y0 + y, z0);
                        boolean frame = x == -1 || x == width || y == -1 || y == height;
                        block.setType(frame ? frameMaterial : Material.AIR, false);
                    }
                }

                return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.X, frameMaterial);
            } else {
                int z0 = centerZ - 1;
                int x0 = centerX;
                int y0 = bottomY;

                for(int z = -1; z <= width; ++z) {
                    for(int y = -1; y <= height; ++y) {
                        Block block = world.getBlockAt(x0, y0 + y, z0 + z);
                        boolean frame = z == -1 || z == width || y == -1 || y == height;
                        block.setType(frame ? frameMaterial : Material.AIR, false);
                    }
                }

                return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.Z, frameMaterial);
            }
        }

        /** 以内部某格为起点，分别按 X、Z 两种朝向探测是否构成炽热门。 */
        static @Nullable HeatPortalShape detect(Block inside) {
            if (!inside.getType().isAir() && inside.getType() != Material.FIRE) {
                return null;
            } else {
                World world = inside.getWorld();
                int cx = inside.getX();
                int cy = inside.getY();
                int cz = inside.getZ();
                HeatPortalShape shape = detectWithOrientation(world, cx, cy, cz, Axis.X);
                return shape != null ? shape : detectWithOrientation(world, cx, cy, cz, Axis.Z);
            }
        }

        /** 以给定朝向尝试匹配炽热门框，命中则返回门对象。 */
        private static @Nullable HeatPortalShape detectWithOrientation(World world, int cx, int cy, int cz, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int z0 = cz;

                for(int x0 = cx - 1; x0 <= cx; ++x0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cx >= x0 && cx < x0 + width && cy >= y0 && cy < y0 + height && isValidFrameX(world, x0, y0, z0, width, height, Material.NETHER_WART_BLOCK)) {
                            return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.X, Material.NETHER_WART_BLOCK);
                        }
                    }
                }
            } else {
                int x0 = cx;

                for(int z0 = cz - 1; z0 <= cz; ++z0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cz >= z0 && cz < z0 + width && cy >= y0 && cy < y0 + height && isValidFrameZ(world, x0, y0, z0, width, height, Material.NETHER_WART_BLOCK)) {
                            return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.Z, Material.NETHER_WART_BLOCK);
                        }
                    }
                }
            }

            return null;
        }

        /** 校验 X 轴朝向的门是否完全由给定框材质与空/火内部组成。 */
        private static boolean isValidFrameX(World world, int x0, int y0, int z0, int width, int height, Material frameMaterial) {
            for(int x = -1; x <= width; ++x) {
                for(int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0 + x, y0 + y, z0);
                    boolean frame = x == -1 || x == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != frameMaterial) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.FIRE) {
                        return false;
                    }
                }
            }

            return true;
        }

        /** 校验 Z 轴朝向的门是否完全由给定框材质与空/火内部组成。 */
        private static boolean isValidFrameZ(World world, int x0, int y0, int z0, int width, int height, Material frameMaterial) {
            for(int z = -1; z <= width; ++z) {
                for(int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0, y0 + y, z0 + z);
                    boolean frame = z == -1 || z == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != frameMaterial) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.FIRE) {
                        return false;
                    }
                }
            }

            return true;
        }

        /** 取得门内部第 (dx, dy) 格对应的方块。 */
        Block getInteriorBlock(int dx, int dy) {
            return this.axis == Axis.X ? this.world.getBlockAt(this.x0 + dx, this.y0 + dy, this.z0) : this.world.getBlockAt(this.x0, this.y0 + dy, this.z0 + dx);
        }

        /** 计算门中心的近似世界坐标。 */
        Location getCenter() {
            double cx;
            double cz;
            if (this.axis == Axis.X) {
                cx = (double)this.x0 + (double)this.width / (double)2.0F + (double)0.5F;
                cz = (double)this.z0 + (double)0.5F;
            } else {
                cx = (double)this.x0 + (double)0.5F;
                cz = (double)this.z0 + (double)this.width / (double)2.0F + (double)0.5F;
            }

            double cy = (double)this.y0 + (double)1.0F;
            return new Location(this.world, cx, cy, cz);
        }

        /** 返回略高于底部的出生位置。 */
        Location getSpawnLocation() {
            Location c = this.getCenter().clone();
            c.setY((double)this.y0 + 0.1);
            return c;
        }
    }
}
