package Kinkin.aeternum.portal;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.logging.Logger;
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

public final class VanillaPortalIsolation implements Listener {
    private static final int DESTINATION_PROBE_RADIUS = 8;
    private static final int VANILLA_SEARCH_RADIUS = 16;
    private static final int VANILLA_SEARCH_Y = 48;
    private final AeternumSeasonsPlugin plugin;
    private boolean registered;

    public VanillaPortalIsolation(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        if (!this.registered) {
            this.registered = true;
            this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        }
    }

    public void unregister() {
        this.registered = false;
        HandlerList.unregisterAll(this);
    }

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
                        Logger var10000 = this.plugin.getLogger();
                        String var10001 = destinationWorld.getName();
                        var10000.warning("[PortalIsolation] Could not isolate vanilla portal near " + var10001 + " " + requestedDestination.getBlockX() + "," + requestedDestination.getBlockY() + "," + requestedDestination.getBlockZ());
                    }

                    return new IsolationResult(true, vanilla);
                }
            }
        } else {
            return VanillaPortalIsolation.IsolationResult.NOT_INTERCEPTED;
        }
    }

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

            for(int x = bx - radius; x <= bx + radius; ++x) {
                for(int z = bz - radius; z <= bz + radius; ++z) {
                    for(int y = minY; y <= maxY; ++y) {
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

    private Block buildPortal(World world, int centerX, int bottomY, int centerZ, Axis axis) {
        int width = 2;
        int height = 3;
        int x0 = axis == Axis.X ? centerX - 1 : centerX;
        int z0 = axis == Axis.Z ? centerZ - 1 : centerZ;

        for(int horizontal = -1; horizontal <= width; ++horizontal) {
            for(int vertical = -1; vertical <= height; ++vertical) {
                int x = axis == Axis.X ? x0 + horizontal : x0;
                int z = axis == Axis.Z ? z0 + horizontal : z0;
                boolean frame = horizontal == -1 || horizontal == width || vertical == -1 || vertical == height;
                world.getBlockAt(x, bottomY + vertical, z).setType(frame ? Material.OBSIDIAN : Material.AIR, false);
            }
        }

        Orientable portalData = (Orientable)Material.NETHER_PORTAL.createBlockData();
        portalData.setAxis(axis);

        for(int horizontal = 0; horizontal < width; ++horizontal) {
            for(int vertical = 0; vertical < height; ++vertical) {
                int x = axis == Axis.X ? x0 + horizontal : x0;
                int z = axis == Axis.Z ? z0 + horizontal : z0;
                world.getBlockAt(x, bottomY + vertical, z).setBlockData(portalData, false);
            }
        }

        return world.getBlockAt(x0, bottomY, z0);
    }

    private static record IsolationResult(boolean intercepted, @Nullable Location destination) {
        private static final IsolationResult NOT_INTERCEPTED = new IsolationResult(false, (Location)null);

        private IsolationResult {
        }
    }
}
