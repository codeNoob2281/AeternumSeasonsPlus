package kinkin.aeternum.portal;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.frost.FrostBedManager;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 霜原（aeternum_frost）与主世界之间自定义传送门的连接管理器。
 *
 * <p>本监听器负责：
 * <ul>
 *     <li>用打火石/火焰弹点燃由萤石框构成的霜原传送门，并校验门的形状；</li>
 *     <li>用雪球命中门内方块时点亮传送门；</li>
 *     <li>在霜原与主世界之间按已保存的链接进行传送，链接缺失时就近复用或新建安全传送门；</li>
 *     <li>将双向链接持久化到 frost_portal_links.yml。</li>
 * </ul>
 * 传送门以 {@link PortalKey}（世界名 + 坐标 + 朝向）标识，并依赖
 * {@link PortalBuildProtection} 选择安全建造点、{@link FrostBedManager} 管理霜原床位。</p>
 */
public final class FrostOverworldPortals implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private final FrostBedManager bedManager;
    private final File portalDataFile;
    private final Map<PortalKey, PortalKey> portalConnections = new HashMap();

    /**
     * 创建管理器并从数据文件加载已保存的传送门链接。
     */
    public FrostOverworldPortals(AeternumSeasonsPlugin plugin, FrostBedManager bedManager) {
        super();
        this.plugin = plugin;
        this.bedManager = bedManager;
        this.portalDataFile = new File(plugin.getDataFolder(), "frost_portal_links.yml");
        this.loadPortalLinks();
    }

    /**
     * 将本监听器注册到插件的事件总线。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 注销事件监听并保存当前传送门链接。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        this.savePortalLinks();
    }

    /**
     * 读取 frost_portal_links.yml，将成对出现的单向记录恢复为双向链接。
     */
    private void loadPortalLinks() {
        this.portalConnections.clear();
        if (this.portalDataFile.isFile()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(this.portalDataFile);
            Map<PortalKey, PortalKey> loaded = new HashMap();

            for (String rawSource : config.getKeys(false)) {
                PortalKey source = this.parsePortalKey(rawSource);
                PortalKey destination = this.parsePortalKey(config.getString(rawSource));
                if (source != null && destination != null && !source.equals(destination)) {
                    loaded.put(source, destination);
                }
            }

            for (Map.Entry<PortalKey, PortalKey> entry : loaded.entrySet()) {
                PortalKey source = (PortalKey) entry.getKey();
                PortalKey destination = (PortalKey) entry.getValue();
                PortalKey reverse = (PortalKey) loaded.get(destination);
                if ((reverse == null || reverse.equals(source)) && !this.portalConnections.containsKey(source) && !this.portalConnections.containsKey(destination)) {
                    this.linkPortalEndpoints(source, destination);
                }
            }

        }
    }

    /**
     * 将当前所有双向链接写回 frost_portal_links.yml。
     */
    private void savePortalLinks() {
        FileConfiguration config = new YamlConfiguration();

        for (Map.Entry<PortalKey, PortalKey> entry : this.portalConnections.entrySet()) {
            config.set(this.formatPortalKey((PortalKey) entry.getKey()), this.formatPortalKey((PortalKey) entry.getValue()));
        }

        try {
            config.save(this.portalDataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[FrostPortals] Could not save frost_portal_links.yml: " + ex.getMessage());
        }

    }

    /**
     * 将传送门键序列化为“世界|X|Y|Z|朝向”格式。
     */
    private String formatPortalKey(PortalKey key) {
        String worldName = key.worldName;
        return worldName + "|" + key.x + "|" + key.y + "|" + key.z + "|" + key.axis.name();
    }

    /**
     * 解析“世界|X|Y|Z|朝向”格式的字符串，格式非法时返回 null。
     */
    private @Nullable PortalKey parsePortalKey(String raw) {
        if (raw == null) {
            return null;
        } else {
            String[] parts = raw.split("\\|");
            if (parts.length != 5) {
                return null;
            } else {
                try {
                    return new PortalKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Axis.valueOf(parts[4]));
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
        }
    }

    /**
     * 先解除两端原有链接，再建立新的双向链接并立即保存。
     */
    private void registerPortalLink(PortalKey source, PortalKey destination) {
        this.unlinkPortalEndpoint(source);
        this.unlinkPortalEndpoint(destination);
        this.linkPortalEndpoints(source, destination);
        this.savePortalLinks();
    }

    /**
     * 在两个端点之间写入互为镜像的双向映射（不落盘）。
     */
    private void linkPortalEndpoints(PortalKey source, PortalKey destination) {
        this.portalConnections.put(source, destination);
        this.portalConnections.put(destination, source);
    }

    /**
     * 解除指定端点的链接并立即保存。
     */
    private void removePortalLink(PortalKey endpoint) {
        this.unlinkPortalEndpoint(endpoint);
        this.savePortalLinks();
    }

    /**
     * 解除单个端点的双向映射：若对端确实指回自己，则一并移除对端记录。
     */
    private void unlinkPortalEndpoint(PortalKey endpoint) {
        PortalKey other = (PortalKey) this.portalConnections.remove(endpoint);
        if (other != null && endpoint.equals(this.portalConnections.get(other))) {
            this.portalConnections.remove(other);
        }

    }

    /**
     * 右键交互处理：手持打火石或火焰弹右键方块时，若目标位置可构成霜原传送门形状，
     * 则取消原版点火行为（由后续逻辑自行点亮）。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.NORMAL
    )
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getMaterial() == Material.FLINT_AND_STEEL || event.getMaterial() == Material.FIRE_CHARGE) {
                Block clicked = event.getClickedBlock();
                if (clicked == null) {
                    return;
                }

                Block attempt = clicked.getRelative(event.getBlockFace());
                FrostPortalShape shape = FrostOverworldPortals.FrostPortalShape.detect(attempt);
                if (shape != null) {
                    event.setCancelled(true);
                }
            }

        }
    }

    /**
     * 雪球命中处理：雪球击中方块后，检测命中面另一侧是否构成霜原传送门，是则点亮它。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.NORMAL
    )
    public void onSnowballHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile instanceof Snowball) {
            Block hit = event.getHitBlock();
            if (hit != null) {
                BlockFace face = event.getHitBlockFace();
                Block inside = face != null ? hit.getRelative(face) : hit;
                FrostPortalShape shape = FrostOverworldPortals.FrostPortalShape.detect(inside);
                if (shape != null) {
                    this.lightPortal(shape);
                }
            }
        }
    }

    /**
     * 将传送门内部区域填充为下界传送门方块，并播放音效与粒子。
     */
    private void lightPortal(FrostPortalShape shape) {
        World world = shape.world;
        Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
        data.setAxis(shape.axis);

        for (int x = 0; x < shape.width; ++x) {
            for (int y = 0; y < shape.height; ++y) {
                Block block = shape.getInteriorBlock(x, y);
                block.setBlockData(data, false);
            }
        }

        world.playSound(shape.getCenter(), Sound.BLOCK_PORTAL_TRAVEL, 1.0F, 1.0F);
        world.spawnParticle(Particle.PORTAL, shape.getCenter(), 60, (double) 0.5F, (double) 1.0F, (double) 0.5F, 0.1);
    }

    /**
     * 玩家传送门事件处理：识别霜原与主世界间的萤石传送门，
     * 取消原版传送并按已保存链接（或新建）跳转。
     */
    @EventHandler(
            ignoreCancelled = false,
            priority = EventPriority.LOWEST
    )
    public void onPortal(PlayerPortalEvent event) {
        if (this.plugin.shouldLetWorldsHandlePortals() && event.isCancelled()) {
            Player player = event.getPlayer();
            Block portalBlock = this.findPortalBlock(event.getFrom().getBlock());
            if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
                World fromWorld = portalBlock.getWorld();
                String fromName = fromWorld.getName();
                if (("aeternum_frost".equalsIgnoreCase(fromName) || fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) && this.isGlowstonePortal(portalBlock)) {
                    event.setCancelled(true);
                    this.handlePortalTeleport(player, portalBlock, fromWorld, fromName);
                }
            }

        } else {
            Block base = event.getFrom().getBlock();
            Block portalBlock = base;
            if (base.getType() != Material.NETHER_PORTAL) {
                for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    Block rel = base.getRelative(face);
                    if (rel.getType() == Material.NETHER_PORTAL) {
                        portalBlock = rel;
                        break;
                    }
                }
            }

            if (portalBlock.getType() == Material.NETHER_PORTAL) {
                final Player player = event.getPlayer();
                final World fromWorld = portalBlock.getWorld();
                String fromName = fromWorld.getName();
                if ("aeternum_frost".equalsIgnoreCase(fromName) || fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) {
                    if ("aeternum_frost".equalsIgnoreCase(fromName)) {
                        if (this.isGlowstonePortal(portalBlock)) {
                            event.setCancelled(true);
                            event.setCanCreatePortal(false);
                            final World finalTo = this.plugin.getMainOverworld();
                            if (finalTo != null) {
                                Location from = event.getFrom();
                                Location dest = this.resolvePortalDestination(portalBlock, finalTo, from);
                                if (dest == null) {
                                    dest = finalTo.getSpawnLocation();
                                }

                                player.teleport(dest);
                                (new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        FrostOverworldPortals.this.showRealmTitle(player, fromWorld, finalTo);
                                    }
                                }).runTask(this.plugin);
                            }
                        }
                    } else if (this.isGlowstonePortal(portalBlock)) {
                        event.setCancelled(true);
                        event.setCanCreatePortal(false);
                        final World toWorld = Bukkit.getWorld("aeternum_frost");
                        if (toWorld != null) {
                            Location from = event.getFrom();
                            Location dest = this.resolvePortalDestination(portalBlock, toWorld, from);
                            if (dest != null) {
                                player.teleport(dest);
                                (new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        FrostOverworldPortals.this.showRealmTitle(player, fromWorld, toWorld);
                                    }
                                }).runTask(this.plugin);
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 实体传送门事件处理：与玩家传送逻辑类似，为实体计算目标位置，
     * 并在下一 tick 恢复其速度与传送冷却。
     */
    @EventHandler(
            ignoreCancelled = false,
            priority = EventPriority.LOWEST
    )
    public void onEntityPortal(EntityPortalEvent event) {
        Block portalBlock = this.findPortalBlock(event.getFrom().getBlock());
        if (portalBlock.getType() == Material.NETHER_PORTAL) {
            World fromWorld = portalBlock.getWorld();
            String fromName = fromWorld.getName();
            Location dest = null;
            if ("aeternum_frost".equalsIgnoreCase(fromName)) {
                if (!this.isGlowstonePortal(portalBlock)) {
                    return;
                }

                event.setCancelled(true);
                World toWorld = this.plugin.getMainOverworld();
                if (toWorld == null) {
                    return;
                }

                dest = this.resolvePortalDestination(portalBlock, toWorld, event.getFrom());
            } else {
                if (!fromName.equalsIgnoreCase(this.plugin.getMainOverworldName()) || !this.isGlowstonePortal(portalBlock)) {
                    return;
                }

                event.setCancelled(true);
                World toWorld = Bukkit.getWorld("aeternum_frost");
                if (toWorld == null) {
                    return;
                }

                dest = this.resolvePortalDestination(portalBlock, toWorld, event.getFrom());
            }

            if (dest != null) {
                Entity entity = event.getEntity();
                Vector velocity = entity.getVelocity().clone();
                Location to = dest.clone();
                event.setTo(to);
                event.setCancelled(false);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (entity.isValid()) {
                        if (entity.getWorld() == to.getWorld()) {
                            entity.setPortalCooldown(100);
                            entity.setVelocity(velocity);
                        }
                    }
                });
            }
        }
    }

    /**
     * 根据出发/目标世界展示“进入霜原”或“返回主世界”的标题与副标题。
     */
    private void showRealmTitle(Player player, World from, World to) {
        String fromName = from.getName();
        String toName = to.getName();
        String titleKey = null;
        String subtitleKey = null;
        String realmName = null;
        if ("aeternum_frost".equalsIgnoreCase(toName)) {
            realmName = this.plugin.lang.tr(player, "realm.frost_overworld");
            titleKey = "realm.frost_title";
            subtitleKey = "realm.frost_subtitle";
        } else if (toName.equalsIgnoreCase(this.plugin.getMainOverworldName()) && "aeternum_frost".equalsIgnoreCase(fromName)) {
            realmName = this.plugin.lang.tr(player, "realm.frost_overworld");
            titleKey = "realm.overworld_title";
            subtitleKey = "realm.returned_from";
        }

        if (titleKey != null) {
            String title = this.plugin.lang.trf(player, titleKey, Map.of("name", realmName));
            String subtitle;
            if ("realm.returned_from".equals(subtitleKey)) {
                subtitle = this.plugin.lang.trf(player, subtitleKey, Map.of("name", realmName));
            } else {
                subtitle = this.plugin.lang.tr(player, subtitleKey);
            }

            title = ChatColor.translateAlternateColorCodes('&', title);
            subtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
            player.sendTitle(title, subtitle, 10, 60, 10);
        }
    }

    /**
     * 判断传送门方块的六个相邻面中是否存在萤石，用于区分自定义霜原门。
     */
    private boolean isGlowstonePortal(Block portalBlock) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block block = portalBlock.getRelative(face);
            if (block.getType() == Material.GLOWSTONE) {
                return true;
            }
        }

        return false;
    }

    /**
     * 读取传送门方块的朝向；读取失败时回退为 X 轴。
     */
    private Axis getPortalAxis(Block portalBlock) {
        try {
            BlockData blockData = portalBlock.getBlockData();
            if (blockData instanceof Orientable o) {
                return o.getAxis();
            }
        } catch (Exception exception) {
        }

        return Axis.X;
    }

    /**
     * 解析传送目标：优先使用已保存的链接，否则在目标世界就近复用或新建传送门。
     */
    private @Nullable Location resolvePortalDestination(Block sourcePortal, World expectedWorld, Location from) {
        PortalKey sourceKey = this.portalKey(sourcePortal);
        if (sourceKey == null) {
            return null;
        } else {
            Location linked = this.findLinkedPortal(sourceKey, expectedWorld);
            return linked != null ? linked : this.findOrCreatePortal(sourceKey, expectedWorld, from.getX(), from.getY(), from.getZ(), this.getPortalAxis(sourcePortal));
        }
    }

    /**
     * 查找已保存且仍然有效的链接目标；若目标失效则清除该链接。
     */
    private @Nullable Location findLinkedPortal(PortalKey sourceKey, World expectedWorld) {
        PortalKey destinationKey = (PortalKey) this.portalConnections.get(sourceKey);
        if (destinationKey == null) {
            return null;
        } else {
            World world = Bukkit.getWorld(destinationKey.worldName);
            if (world != null && world.getUID().equals(expectedWorld.getUID())) {
                Block anchor = world.getBlockAt(destinationKey.x, destinationKey.y, destinationKey.z);
                PortalKey normalized = this.portalKey(anchor);
                if (normalized != null && normalized.equals(destinationKey) && this.isGlowstonePortal(anchor)) {
                    return this.portalTeleportLocation(destinationKey, world);
                } else {
                    this.removePortalLink(sourceKey);
                    return null;
                }
            } else {
                this.removePortalLink(sourceKey);
                return null;
            }
        }
    }

    /**
     * 在目标世界查找可复用的邻近霜原门，找不到则在安全位置新建一座并建立链接。
     */
    private @Nullable Location findOrCreatePortal(PortalKey sourceKey, World world, double x, double y, double z, Axis axis) {
        PortalTarget existing = this.findNearbyPortal(sourceKey, world, x, y, z, 32);
        if (existing != null) {
            this.registerPortalLink(sourceKey, existing.key);
            return existing.location;
        } else {
            int bx = (int) Math.round(x);
            int bz = (int) Math.round(z);
            int preferredBottomY = world.getHighestBlockYAt(bx, bz) + 1;
            PortalBuildProtection.Site site = PortalBuildProtection.findSafeSite(world, bx, preferredBottomY, bz, axis, true);
            if (site == null) {
                this.plugin.getLogger().warning("[FrostPortals] No safe build site near " + world.getName() + " " + bx + "," + preferredBottomY + "," + bz);
                return null;
            } else {
                FrostPortalShape shape = FrostOverworldPortals.FrostPortalShape.buildAt(world, site.centerX(), site.bottomY(), site.centerZ(), axis);
                this.lightPortal(shape);
                PortalKey targetKey = this.portalKey(shape.getInteriorBlock(0, 0));
                if (targetKey == null) {
                    return null;
                } else {
                    this.registerPortalLink(sourceKey, targetKey);
                    return this.portalTeleportLocation(targetKey, world);
                }
            }
        }
    }

    /**
     * 在给定半径内扫描所有有效的霜原门，返回距离最近的一座。
     */
    private @Nullable PortalTarget findNearbyPortal(PortalKey sourceKey, World world, double x, double y, double z, int radius) {
        int bx = (int) Math.round(x);
        int bz = (int) Math.round(z);
        PortalTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        Set<PortalKey> examined = new HashSet();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int ix = bx - radius; ix <= bx + radius; ++ix) {
            for (int iz = bz - radius; iz <= bz + radius; ++iz) {
                for (int iy = minY; iy <= maxY; ++iy) {
                    Block block = world.getBlockAt(ix, iy, iz);
                    if (block.getType() == Material.NETHER_PORTAL && this.isGlowstonePortal(block)) {
                        PortalKey candidateKey = this.portalKey(block);
                        if (candidateKey != null && examined.add(candidateKey)) {
                            PortalKey owner = (PortalKey) this.portalConnections.get(candidateKey);
                            if (owner != null && !owner.equals(sourceKey)) {
                                if (this.isPortalKeyAlive(candidateKey) && this.isPortalKeyAlive(owner)) {
                                    continue;
                                }

                                this.removePortalLink(candidateKey);
                            }

                            Location location = this.portalTeleportLocation(candidateKey, world);
                            double dx = location.getX() - x;
                            double dz = location.getZ() - z;
                            double distSq = dx * dx + dz * dz;
                            if (distSq < bestDistSq) {
                                bestDistSq = distSq;
                                best = new PortalTarget(candidateKey, location);
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 计算传送门方块所在门的标准化键（底部最低、最左的传送门方块坐标 + 朝向）。
     */
    private @Nullable PortalKey portalKey(Block portalBlock) {
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            World world = portalBlock.getWorld();
            Axis axis = this.getPortalAxis(portalBlock);
            int x = portalBlock.getX();
            int y = portalBlock.getY();

            int z;
            for (z = portalBlock.getZ(); y > world.getMinHeight() && world.getBlockAt(x, y - 1, z).getType() == Material.NETHER_PORTAL; --y) {
            }

            if (axis == Axis.X) {
                while (world.getBlockAt(x - 1, y, z).getType() == Material.NETHER_PORTAL) {
                    --x;
                }
            } else {
                while (world.getBlockAt(x, y, z - 1).getType() == Material.NETHER_PORTAL) {
                    --z;
                }
            }

            return new PortalKey(world.getName(), x, y, z, axis);
        } else {
            return null;
        }
    }

    /**
     * 校验某个传送门键对应的方块是否仍是有效霜原门（世界存在、锚点归一化一致、且为萤石门）。
     */
    private boolean isPortalKeyAlive(PortalKey key) {
        World world = Bukkit.getWorld(key.worldName);
        if (world == null) {
            return false;
        } else {
            Block anchor = world.getBlockAt(key.x, key.y, key.z);
            PortalKey normalized = this.portalKey(anchor);
            return key.equals(normalized) && this.isGlowstonePortal(anchor);
        }
    }

    /**
     * 依据传送门键与朝向计算安全的出生/传送坐标（门内中心略高于底部）。
     */
    private Location portalTeleportLocation(PortalKey key, World world) {
        double x = key.axis == Axis.X ? (double) key.x + (double) 1.0F : (double) key.x + (double) 0.5F;
        double z = key.axis == Axis.Z ? (double) key.z + (double) 1.0F : (double) key.z + (double) 0.5F;
        return new Location(world, x, (double) key.y + 0.1, z);
    }

    /**
     * 从给定方块出发，返回本身或相邻六个面中的第一个下界传送门方块。
     */
    private Block findPortalBlock(Block start) {
        if (start.getType() == Material.NETHER_PORTAL) {
            return start;
        } else {
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block rel = start.getRelative(face);
                if (rel.getType() == Material.NETHER_PORTAL) {
                    return rel;
                }
            }

            return start;
        }
    }

    /**
     * 处理霜原 <-> 主世界的玩家传送：解析目标位置并发送领域标题。
     */
    private void handlePortalTeleport(Player player, Block portalBlock, World fromWorld, String fromName) {
        Location dest = null;
        if (this.isGlowstonePortal(portalBlock)) {
            if ("aeternum_frost".equalsIgnoreCase(fromName)) {
                World toWorld = this.plugin.getMainOverworld();
                if (toWorld != null) {
                    dest = this.resolvePortalDestination(portalBlock, toWorld, player.getLocation());
                }
            } else if (fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) {
                World toWorld = Bukkit.getWorld("aeternum_frost");
                if (toWorld != null) {
                    dest = this.resolvePortalDestination(portalBlock, toWorld, player.getLocation());
                }
            }

            if (dest != null) {
                player.teleport(dest);
                this.showRealmTitle(player, fromWorld, dest.getWorld());
            }

        }
    }

    /**
     * 传送门键：以世界名、锚点坐标与朝向唯一标识一座传送门。
     */
    private record PortalKey(String worldName, int x, int y, int z, Axis axis) {
        private PortalKey {
        }
    }

    /**
     * 邻近传送门的匹配结果：包含其键与推荐传送坐标。
     */
    private record PortalTarget(PortalKey key, Location location) {
        private PortalTarget {
        }
    }

    /**
     * 霜原传送门的几何描述（底部锚点、宽高与朝向），提供建造、检测与取点工具。
     */
    private static final class FrostPortalShape {
        final World world;
        final int x0;
        final int y0;
        final int z0;
        final int width;
        final int height;
        final Axis axis;

        private FrostPortalShape(World world, int x0, int y0, int z0, int width, int height, Axis axis) {
            super();
            this.world = world;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.width = width;
            this.height = height;
            this.axis = axis;
        }

        /**
         * 在指定中心与底部高度处以萤石框建造一座霜原门，返回其几何描述。
         */
        static FrostPortalShape buildAt(World world, int centerX, int bottomY, int centerZ, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int x0 = centerX - 1;
                int z0 = centerZ;
                int y0 = bottomY;

                for (int x = -1; x <= width; ++x) {
                    for (int y = -1; y <= height; ++y) {
                        Block block = world.getBlockAt(x0 + x, y0 + y, z0);
                        boolean frame = x == -1 || x == width || y == -1 || y == height;
                        block.setType(frame ? Material.GLOWSTONE : Material.AIR, false);
                    }
                }

                return new FrostPortalShape(world, x0, y0, z0, width, height, Axis.X);
            } else {
                int z0 = centerZ - 1;
                int x0 = centerX;
                int y0 = bottomY;

                for (int z = -1; z <= width; ++z) {
                    for (int y = -1; y <= height; ++y) {
                        Block block = world.getBlockAt(x0, y0 + y, z0 + z);
                        boolean frame = z == -1 || z == width || y == -1 || y == height;
                        block.setType(frame ? Material.GLOWSTONE : Material.AIR, false);
                    }
                }

                return new FrostPortalShape(world, x0, y0, z0, width, height, Axis.Z);
            }
        }

        /**
         * 以内部某格为起点，分别按 X、Z 两种朝向探测是否构成霜原门。
         */
        static @Nullable FrostPortalShape detect(Block inside) {
            if (!inside.getType().isAir() && inside.getType() != Material.WATER) {
                return null;
            } else {
                World world = inside.getWorld();
                int cx = inside.getX();
                int cy = inside.getY();
                int cz = inside.getZ();
                FrostPortalShape shape = detectWithOrientation(world, cx, cy, cz, Axis.X);
                return shape != null ? shape : detectWithOrientation(world, cx, cy, cz, Axis.Z);
            }
        }

        /**
         * 以给定朝向尝试匹配霜原门框，命中则返回门对象。
         */
        private static @Nullable FrostPortalShape detectWithOrientation(World world, int cx, int cy, int cz, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int z0 = cz;

                for (int x0 = cx - 1; x0 <= cx; ++x0) {
                    for (int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cx >= x0 && cx < x0 + width && cy >= y0 && cy < y0 + height && isValidFrameX(world, x0, y0, z0, width, height)) {
                            return new FrostPortalShape(world, x0, y0, z0, width, height, Axis.X);
                        }
                    }
                }
            } else {
                int x0 = cx;

                for (int z0 = cz - 1; z0 <= cz; ++z0) {
                    for (int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cz >= z0 && cz < z0 + width && cy >= y0 && cy < y0 + height && isValidFrameZ(world, x0, y0, z0, width, height)) {
                            return new FrostPortalShape(world, x0, y0, z0, width, height, Axis.Z);
                        }
                    }
                }
            }

            return null;
        }

        /**
         * 校验 X 轴朝向的门是否完全由萤石边框与空/水内部组成。
         */
        private static boolean isValidFrameX(World world, int x0, int y0, int z0, int width, int height) {
            for (int x = -1; x <= width; ++x) {
                for (int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0 + x, y0 + y, z0);
                    boolean frame = x == -1 || x == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != Material.GLOWSTONE) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.WATER) {
                        return false;
                    }
                }
            }

            return true;
        }

        /**
         * 校验 Z 轴朝向的门是否完全由萤石边框与空/水内部组成。
         */
        private static boolean isValidFrameZ(World world, int x0, int y0, int z0, int width, int height) {
            for (int z = -1; z <= width; ++z) {
                for (int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0, y0 + y, z0 + z);
                    boolean frame = z == -1 || z == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != Material.GLOWSTONE) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.WATER) {
                        return false;
                    }
                }
            }

            return true;
        }

        /**
         * 取得门内部第 (dx, dy) 格对应的方块。
         */
        Block getInteriorBlock(int dx, int dy) {
            return this.axis == Axis.X ? this.world.getBlockAt(this.x0 + dx, this.y0 + dy, this.z0) : this.world.getBlockAt(this.x0, this.y0 + dy, this.z0 + dx);
        }

        /**
         * 计算门中心的近似世界坐标。
         */
        Location getCenter() {
            double cx;
            double cz;
            if (this.axis == Axis.X) {
                cx = (double) this.x0 + (double) this.width / (double) 2.0F + (double) 0.5F;
                cz = (double) this.z0 + (double) 0.5F;
            } else {
                cx = (double) this.x0 + (double) 0.5F;
                cz = (double) this.z0 + (double) this.width / (double) 2.0F + (double) 0.5F;
            }

            double cy = (double) this.y0 + (double) 1.0F;
            return new Location(this.world, cx, cy, cz);
        }

        /**
         * 返回略高于中心的出生位置。
         */
        Location getSpawnLocation() {
            Location c = this.getCenter().clone();
            c.setY(c.getY() + (double) 0.5F);
            return c;
        }
    }
}
