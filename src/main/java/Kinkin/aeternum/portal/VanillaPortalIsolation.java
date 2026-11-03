package kinkin.aeternum.portal;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/**
 * 原版传送门隔离器。
 *
 * <p>当玩家/实体使用原版（黑曜石框）传送门前往的目标位置恰好落在自定义门上时，
 * 本监听器会将其重定向到附近（或新建）的原版传送门，避免原版传送与自定义门混淆。
 * 它通过 {@link PortalFrameClassifier} 判断源门与目标门的类型，并借助
 * {@link PortalBuildProtection} 选择安全建造点。依赖 {@link AeternumSeasonsPlugin} 的日志服务。</p>
 */
public final class VanillaPortalIsolation implements Listener {
    private static final int DESTINATION_PROBE_RADIUS = 8;
    private static final int VANILLA_SEARCH_RADIUS = 16;
    private static final int VANILLA_SEARCH_Y = 48;
    private final AeternumSeasonsPlugin plugin;
    private boolean registered;

    /**
     * 创建隔离器（不立即注册监听）。
     */
    public VanillaPortalIsolation(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    /**
     * 幂等地将本监听器注册到服务器的事件总线。
     */
    public void register() {
        if (!this.registered) {
            this.registered = true;
            this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        }
    }

    /**
     * 注销事件监听。
     */
    public void unregister() {
        this.registered = false;
        HandlerList.unregisterAll(this);
    }

    /**
     * 玩家传送门事件处理：若检测到原版门通往自定义门，则改派到隔离后的原版落点；
     * 无法找到落点时取消传送。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onPlayerPortal(PlayerPortalEvent event) {
        IsolationResult result = this.isolateVanillaRoute(event.getFrom(), event.getTo());
        if (result.intercepted()) {
            event.setCanCreatePortal(false);
            if (result.destination() == null) {
                event.setCancelled(true);
            } else {
                Location destination = result.destination().clone();
                destination.setYaw(event.getFrom().getYaw());
                destination.setPitch(event.getFrom().getPitch());
                event.setTo(destination);
            }
        }
    }

    /**
     * 实体传送门事件处理：与玩家逻辑一致，将实体改派到隔离后的原版落点或取消。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onEntityPortal(EntityPortalEvent event) {
        IsolationResult result = this.isolateVanillaRoute(event.getFrom(), event.getTo());
        if (result.intercepted()) {
            if (result.destination() == null) {
                event.setCancelled(true);
            } else {
                event.setTo(result.destination().clone());
            }
        }
    }

    /**
     * 判定并隔离一次原版传送：仅当源门为原版门、目标处为自定义门时拦截，
     * 并尝试在目标附近寻找或新建原版门作为落点。
     *
     * @param from                 源位置
     * @param requestedDestination 原版逻辑给出的目标位置（可空）
     * @return 隔离结果；包含是否拦截与实际落点
     */
    private IsolationResult isolateVanillaRoute(Location from, @Nullable Location requestedDestination) {
        if (requestedDestination != null && requestedDestination.getWorld() != null) {
            Block sourcePortal = PortalFrameClassifier.findPortalBlock(from, 2);
            if (PortalFrameClassifier.classify(sourcePortal) != PortalFrameClassifier.Type.VANILLA) {
                return VanillaPortalIsolation.IsolationResult.NOT_INTERCEPTED;
            } else {
                Block selectedPortal = PortalFrameClassifier.findPortalBlock(requestedDestination, 8);
                PortalFrameClassifier.Type selectedType = PortalFrameClassifier.classify(selectedPortal);
                if (!selectedType.isCustom() && selectedType != PortalFrameClassifier.Type.UNKNOWN) {
                    return VanillaPortalIsolation.IsolationResult.NOT_INTERCEPTED;
                } else if (selectedPortal == null) {
                    return VanillaPortalIsolation.IsolationResult.NOT_INTERCEPTED;
                } else {
                    World destinationWorld = requestedDestination.getWorld();
                    Location vanilla = this.findNearbyVanillaPortal(requestedDestination, 16);
                    if (vanilla == null) {
                        vanilla = this.buildSeparatedVanillaPortal(destinationWorld, requestedDestination, PortalFrameClassifier.axis(sourcePortal));
                    }

                    if (vanilla == null) {
                        Logger logger = this.plugin.getLogger();
                        String worldName = destinationWorld.getName();
                        logger.warning("[PortalIsolation] Could not isolate vanilla portal near " + worldName + " " + requestedDestination.getBlockX() + "," + requestedDestination.getBlockY() + "," + requestedDestination.getBlockZ());
                    }

                    return new IsolationResult(true, vanilla);
                }
            }
        } else {
            return VanillaPortalIsolation.IsolationResult.NOT_INTERCEPTED;
        }
    }

    /**
     * 在给定中心周围指定水平半径、纵向 ±48 的范围内查找最近的原版传送门落点。
     */
    private @Nullable Location findNearbyVanillaPortal(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        } else {
            int bx = center.getBlockX();
            int by = center.getBlockY();
            int bz = center.getBlockZ();
            int minY = Math.max(world.getMinHeight(), by - 48);
            int maxY = Math.min(world.getMaxHeight() - 1, by + 48);
            Location best = null;
            double bestDistance = Double.MAX_VALUE;

            for (int x = bx - radius; x <= bx + radius; ++x) {
                for (int z = bz - radius; z <= bz + radius; ++z) {
                    for (int y = minY; y <= maxY; ++y) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.NETHER_PORTAL && PortalFrameClassifier.classify(block) == PortalFrameClassifier.Type.VANILLA) {
                            Location teleport = PortalFrameClassifier.teleportLocation(block);
                            if (teleport != null) {
                                double distance = teleport.distanceSquared(center);
                                if (distance < bestDistance) {
                                    best = teleport;
                                    bestDistance = distance;
                                }
                            }
                        }
                    }
                }
            }

            return best;
        }
    }

    /**
     * 在目标世界选择安全站点并建造一座分离的原版门，返回其传送落点。
     */
    private @Nullable Location buildSeparatedVanillaPortal(World world, Location preferred, Axis axis) {
        int x = preferred.getBlockX();
        int z = preferred.getBlockZ();
        boolean followSurface = world.getEnvironment() != Environment.NETHER;
        int preferredBottomY = followSurface ? world.getHighestBlockYAt(x, z) + 1 : Math.max(world.getMinHeight() + 8, Math.min(world.getMaxHeight() - 16, preferred.getBlockY()));
        PortalBuildProtection.Site site = PortalBuildProtection.findSafeSite(world, x, preferredBottomY, z, axis, followSurface);
        if (site == null) {
            return null;
        } else {
            Block anchor = this.buildPortal(world, site.centerX(), site.bottomY(), site.centerZ(), axis);
            return PortalFrameClassifier.teleportLocation(anchor);
        }
    }

    /**
     * 在指定中心/底部高度以黑曜石框建造一座 2x3 的原版门并点亮，返回黑洞门方块。
     */
    private Block buildPortal(World world, int centerX, int bottomY, int centerZ, Axis axis) {
        int width = 2;
        int height = 3;
        int x0 = axis == Axis.X ? centerX - 1 : centerX;
        int z0 = axis == Axis.Z ? centerZ - 1 : centerZ;

        for (int horizontal = -1; horizontal <= width; ++horizontal) {
            for (int vertical = -1; vertical <= height; ++vertical) {
                int x = axis == Axis.X ? x0 + horizontal : x0;
                int z = axis == Axis.Z ? z0 + horizontal : z0;
                boolean frame = horizontal == -1 || horizontal == width || vertical == -1 || vertical == height;
                world.getBlockAt(x, bottomY + vertical, z).setType(frame ? Material.OBSIDIAN : Material.AIR, false);
            }
        }

        Orientable portalData = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portalData.setAxis(axis);

        for (int horizontal = 0; horizontal < width; ++horizontal) {
            for (int vertical = 0; vertical < height; ++vertical) {
                int x = axis == Axis.X ? x0 + horizontal : x0;
                int z = axis == Axis.Z ? z0 + horizontal : z0;
                world.getBlockAt(x, bottomY + vertical, z).setBlockData(portalData, false);
            }
        }

        return world.getBlockAt(x0, bottomY, z0);
    }

    /**
     * 隔离结果：是否拦截，以及（可空）实际落点。
     */
    private record IsolationResult(boolean intercepted, @Nullable Location destination) {
        private static final IsolationResult NOT_INTERCEPTED = new IsolationResult(false, (Location) null);

        private IsolationResult {
        }
    }
}
