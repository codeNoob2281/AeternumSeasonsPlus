package Kinkin.aeternum.portal;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.frost.FrostBedManager;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
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

public final class FrostOverworldPortals implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private final FrostBedManager bedManager;
    private final File portalDataFile;
    private final Map<PortalKey, PortalKey> portalConnections = new HashMap();

    public FrostOverworldPortals(AeternumSeasonsPlugin plugin, FrostBedManager bedManager) {
        super();
        this.plugin = plugin;
        this.bedManager = bedManager;
        this.portalDataFile = new File(plugin.getDataFolder(), "frost_portal_links.yml");
        this.loadPortalLinks();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        this.savePortalLinks();
    }

    private void loadPortalLinks() {
        this.portalConnections.clear();
        if (this.portalDataFile.isFile()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(this.portalDataFile);
            Map<PortalKey, PortalKey> loaded = new HashMap();

            for(String rawSource : config.getKeys(false)) {
                PortalKey source = this.parsePortalKey(rawSource);
                PortalKey destination = this.parsePortalKey(config.getString(rawSource));
                if (source != null && destination != null && !source.equals(destination)) {
                    loaded.put(source, destination);
                }
            }

            for(Map.Entry<PortalKey, PortalKey> entry : loaded.entrySet()) {
                PortalKey source = (PortalKey)entry.getKey();
                PortalKey destination = (PortalKey)entry.getValue();
                PortalKey reverse = (PortalKey)loaded.get(destination);
                if ((reverse == null || reverse.equals(source)) && !this.portalConnections.containsKey(source) && !this.portalConnections.containsKey(destination)) {
                    this.linkPortalEndpoints(source, destination);
                }
            }

        }
    }

    private void savePortalLinks() {
        FileConfiguration config = new YamlConfiguration();

        for(Map.Entry<PortalKey, PortalKey> entry : this.portalConnections.entrySet()) {
            config.set(this.formatPortalKey((PortalKey)entry.getKey()), this.formatPortalKey((PortalKey)entry.getValue()));
        }

        try {
            config.save(this.portalDataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[FrostPortals] Could not save frost_portal_links.yml: " + ex.getMessage());
        }

    }

    private String formatPortalKey(PortalKey key) {
        String var10000 = key.worldName;
        return var10000 + "|" + key.x + "|" + key.y + "|" + key.z + "|" + key.axis.name();
    }

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
                } catch (IllegalArgumentException var4) {
                    return null;
                }
            }
        }
    }

    private void registerPortalLink(PortalKey source, PortalKey destination) {
        this.unlinkPortalEndpoint(source);
        this.unlinkPortalEndpoint(destination);
        this.linkPortalEndpoints(source, destination);
        this.savePortalLinks();
    }

    private void linkPortalEndpoints(PortalKey source, PortalKey destination) {
        this.portalConnections.put(source, destination);
        this.portalConnections.put(destination, source);
    }

    private void removePortalLink(PortalKey endpoint) {
        this.unlinkPortalEndpoint(endpoint);
        this.savePortalLinks();
    }

    private void unlinkPortalEndpoint(PortalKey endpoint) {
        PortalKey other = (PortalKey)this.portalConnections.remove(endpoint);
        if (other != null && endpoint.equals(this.portalConnections.get(other))) {
            this.portalConnections.remove(other);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (e.getMaterial() == Material.FLINT_AND_STEEL || e.getMaterial() == Material.FIRE_CHARGE) {
                Block clicked = e.getClickedBlock();
                if (clicked == null) {
                    return;
                }

                Block attempt = clicked.getRelative(e.getBlockFace());
                FrostPortalShape shape = FrostOverworldPortals.FrostPortalShape.detect(attempt);
                if (shape != null) {
                    e.setCancelled(true);
                }
            }

        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSnowballHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        if (proj instanceof Snowball) {
            Block hit = e.getHitBlock();
            if (hit != null) {
                BlockFace face = e.getHitBlockFace();
                Block inside = face != null ? hit.getRelative(face) : hit;
                FrostPortalShape shape = FrostOverworldPortals.FrostPortalShape.detect(inside);
                if (shape != null) {
                    this.lightPortal(shape);
                }
            }
        }
    }

    private void lightPortal(FrostPortalShape shape) {
        World w = shape.world;
        Orientable data = (Orientable)Material.NETHER_PORTAL.createBlockData();
        data.setAxis(shape.axis);

        for(int x = 0; x < shape.width; ++x) {
            for(int y = 0; y < shape.height; ++y) {
                Block b = shape.getInteriorBlock(x, y);
                b.setBlockData(data, false);
            }
        }

        w.playSound(shape.getCenter(), Sound.BLOCK_PORTAL_TRAVEL, 1.0F, 1.0F);
        w.spawnParticle(Particle.PORTAL, shape.getCenter(), 60, (double)0.5F, (double)1.0F, (double)0.5F, 0.1);
    }

    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.LOWEST
    )
    public void onPortal(PlayerPortalEvent e) {
        if (this.plugin.shouldLetWorldsHandlePortals() && e.isCancelled()) {
            Player p = e.getPlayer();
            Block portalBlock = this.findPortalBlock(e.getFrom().getBlock());
            if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
                World fromWorld = portalBlock.getWorld();
                String fromName = fromWorld.getName();
                if ((fromName.equalsIgnoreCase("aeternum_frost") || fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) && this.isGlowstonePortal(portalBlock)) {
                    e.setCancelled(true);
                    this.handlePortalTeleport(p, portalBlock, fromWorld, fromName);
                }
            }

        } else {
            Block base = e.getFrom().getBlock();
            Block portalBlock = base;
            if (base.getType() != Material.NETHER_PORTAL) {
                for(BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    Block rel = base.getRelative(face);
                    if (rel.getType() == Material.NETHER_PORTAL) {
                        portalBlock = rel;
                        break;
                    }
                }
            }

            if (portalBlock.getType() == Material.NETHER_PORTAL) {
                final Player p = e.getPlayer();
                final World fromWorld = portalBlock.getWorld();
                String fromName = fromWorld.getName();
                if (fromName.equalsIgnoreCase("aeternum_frost") || fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) {
                    if (fromName.equalsIgnoreCase("aeternum_frost")) {
                        if (this.isGlowstonePortal(portalBlock)) {
                            e.setCancelled(true);
                            e.setCanCreatePortal(false);
                            final World finalTo = this.plugin.getMainOverworld();
                            if (finalTo != null) {
                                Location from = e.getFrom();
                                Location dest = this.resolvePortalDestination(portalBlock, finalTo, from);
                                if (dest == null) {
                                    dest = finalTo.getSpawnLocation();
                                }

                                p.teleport(dest);
                                (new BukkitRunnable() {
                                    public void run() {
                                        FrostOverworldPortals.this.showRealmTitle(p, fromWorld, finalTo);
                                    }
                                }).runTask(this.plugin);
                            }
                        }
                    } else if (this.isGlowstonePortal(portalBlock)) {
                        e.setCancelled(true);
                        e.setCanCreatePortal(false);
                        final World toWorld = Bukkit.getWorld("aeternum_frost");
                        if (toWorld != null) {
                            Location from = e.getFrom();
                            Location dest = this.resolvePortalDestination(portalBlock, toWorld, from);
                            if (dest != null) {
                                p.teleport(dest);
                                (new BukkitRunnable() {
                                    public void run() {
                                        FrostOverworldPortals.this.showRealmTitle(p, fromWorld, toWorld);
                                    }
                                }).runTask(this.plugin);
                            }

                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.LOWEST
    )
    public void onEntityPortal(EntityPortalEvent e) {
        Block portalBlock = this.findPortalBlock(e.getFrom().getBlock());
        if (portalBlock.getType() == Material.NETHER_PORTAL) {
            World fromWorld = portalBlock.getWorld();
            String fromName = fromWorld.getName();
            Location dest = null;
            if (fromName.equalsIgnoreCase("aeternum_frost")) {
                if (!this.isGlowstonePortal(portalBlock)) {
                    return;
                }

                e.setCancelled(true);
                World toWorld = this.plugin.getMainOverworld();
                if (toWorld == null) {
                    return;
                }

                dest = this.resolvePortalDestination(portalBlock, toWorld, e.getFrom());
            } else {
                if (!fromName.equalsIgnoreCase(this.plugin.getMainOverworldName()) || !this.isGlowstonePortal(portalBlock)) {
                    return;
                }

                e.setCancelled(true);
                World toWorld = Bukkit.getWorld("aeternum_frost");
                if (toWorld == null) {
                    return;
                }

                dest = this.resolvePortalDestination(portalBlock, toWorld, e.getFrom());
            }

            if (dest != null) {
                Entity entity = e.getEntity();
                Vector velocity = entity.getVelocity().clone();
                Location to = dest.clone();
                e.setTo(to);
                e.setCancelled(false);
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

    private void showRealmTitle(Player p, World from, World to) {
        String fromName = from.getName();
        String toName = to.getName();
        String titleKey = null;
        String subtitleKey = null;
        String realmName = null;
        if (toName.equalsIgnoreCase("aeternum_frost")) {
            realmName = this.plugin.lang.tr(p, "realm.frost_overworld");
            titleKey = "realm.frost_title";
            subtitleKey = "realm.frost_subtitle";
        } else if (toName.equalsIgnoreCase(this.plugin.getMainOverworldName()) && fromName.equalsIgnoreCase("aeternum_frost")) {
            realmName = this.plugin.lang.tr(p, "realm.frost_overworld");
            titleKey = "realm.overworld_title";
            subtitleKey = "realm.returned_from";
        }

        if (titleKey != null) {
            String title = this.plugin.lang.trf(p, titleKey, Map.of("name", realmName));
            String subtitle;
            if ("realm.returned_from".equals(subtitleKey)) {
                subtitle = this.plugin.lang.trf(p, subtitleKey, Map.of("name", realmName));
            } else {
                subtitle = this.plugin.lang.tr(p, subtitleKey);
            }

            title = ChatColor.translateAlternateColorCodes('&', title);
            subtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
            p.sendTitle(title, subtitle, 10, 60, 10);
        }
    }

    private boolean isGlowstonePortal(Block portalBlock) {
        for(BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block b = portalBlock.getRelative(face);
            if (b.getType() == Material.GLOWSTONE) {
                return true;
            }
        }

        return false;
    }

    private Axis getPortalAxis(Block portalBlock) {
        try {
            BlockData var3 = portalBlock.getBlockData();
            if (var3 instanceof Orientable o) {
                return o.getAxis();
            }
        } catch (Exception var4) {
        }

        return Axis.X;
    }

    private @Nullable Location resolvePortalDestination(Block sourcePortal, World expectedWorld, Location from) {
        PortalKey sourceKey = this.portalKey(sourcePortal);
        if (sourceKey == null) {
            return null;
        } else {
            Location linked = this.findLinkedPortal(sourceKey, expectedWorld);
            return linked != null ? linked : this.findOrCreatePortal(sourceKey, expectedWorld, from.getX(), from.getY(), from.getZ(), this.getPortalAxis(sourcePortal));
        }
    }

    private @Nullable Location findLinkedPortal(PortalKey sourceKey, World expectedWorld) {
        PortalKey destinationKey = (PortalKey)this.portalConnections.get(sourceKey);
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

    private @Nullable Location findOrCreatePortal(PortalKey sourceKey, World world, double x, double y, double z, Axis axis) {
        PortalTarget existing = this.findNearbyPortal(sourceKey, world, x, y, z, 32);
        if (existing != null) {
            this.registerPortalLink(sourceKey, existing.key);
            return existing.location;
        } else {
            int bx = (int)Math.round(x);
            int bz = (int)Math.round(z);
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

    private @Nullable PortalTarget findNearbyPortal(PortalKey sourceKey, World world, double x, double y, double z, int radius) {
        int bx = (int)Math.round(x);
        int bz = (int)Math.round(z);
        PortalTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        Set<PortalKey> examined = new HashSet();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for(int ix = bx - radius; ix <= bx + radius; ++ix) {
            for(int iz = bz - radius; iz <= bz + radius; ++iz) {
                for(int iy = minY; iy <= maxY; ++iy) {
                    Block b = world.getBlockAt(ix, iy, iz);
                    if (b.getType() == Material.NETHER_PORTAL && this.isGlowstonePortal(b)) {
                        PortalKey candidateKey = this.portalKey(b);
                        if (candidateKey != null && examined.add(candidateKey)) {
                            PortalKey owner = (PortalKey)this.portalConnections.get(candidateKey);
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

    private @Nullable PortalKey portalKey(Block portalBlock) {
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            World world = portalBlock.getWorld();
            Axis axis = this.getPortalAxis(portalBlock);
            int x = portalBlock.getX();
            int y = portalBlock.getY();

            int z;
            for(z = portalBlock.getZ(); y > world.getMinHeight() && world.getBlockAt(x, y - 1, z).getType() == Material.NETHER_PORTAL; --y) {
            }

            if (axis == Axis.X) {
                while(world.getBlockAt(x - 1, y, z).getType() == Material.NETHER_PORTAL) {
                    --x;
                }
            } else {
                while(world.getBlockAt(x, y, z - 1).getType() == Material.NETHER_PORTAL) {
                    --z;
                }
            }

            return new PortalKey(world.getName(), x, y, z, axis);
        } else {
            return null;
        }
    }

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

    private Location portalTeleportLocation(PortalKey key, World world) {
        double x = key.axis == Axis.X ? (double)key.x + (double)1.0F : (double)key.x + (double)0.5F;
        double z = key.axis == Axis.Z ? (double)key.z + (double)1.0F : (double)key.z + (double)0.5F;
        return new Location(world, x, (double)key.y + 0.1, z);
    }

    private Block findPortalBlock(Block start) {
        if (start.getType() == Material.NETHER_PORTAL) {
            return start;
        } else {
            for(BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block rel = start.getRelative(face);
                if (rel.getType() == Material.NETHER_PORTAL) {
                    return rel;
                }
            }

            return start;
        }
    }

    private void handlePortalTeleport(Player p, Block portalBlock, World fromWorld, String fromName) {
        Location dest = null;
        if (this.isGlowstonePortal(portalBlock)) {
            if (fromName.equalsIgnoreCase("aeternum_frost")) {
                World toWorld = this.plugin.getMainOverworld();
                if (toWorld != null) {
                    dest = this.resolvePortalDestination(portalBlock, toWorld, p.getLocation());
                }
            } else if (fromName.equalsIgnoreCase(this.plugin.getMainOverworldName())) {
                World toWorld = Bukkit.getWorld("aeternum_frost");
                if (toWorld != null) {
                    dest = this.resolvePortalDestination(portalBlock, toWorld, p.getLocation());
                }
            }

            if (dest != null) {
                p.teleport(dest);
                this.showRealmTitle(p, fromWorld, dest.getWorld());
            }

        }
    }

    private static record PortalKey(String worldName, int x, int y, int z, Axis axis) {
        private PortalKey {
        }
    }

    private static record PortalTarget(PortalKey key, Location location) {
        private PortalTarget {
        }
    }

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

        static FrostPortalShape buildAt(World w, int centerX, int bottomY, int centerZ, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int x0 = centerX - 1;
                int z0 = centerZ;
                int y0 = bottomY;

                for(int x = -1; x <= width; ++x) {
                    for(int y = -1; y <= height; ++y) {
                        Block b = w.getBlockAt(x0 + x, y0 + y, z0);
                        boolean frame = x == -1 || x == width || y == -1 || y == height;
                        b.setType(frame ? Material.GLOWSTONE : Material.AIR, false);
                    }
                }

                return new FrostPortalShape(w, x0, y0, z0, width, height, Axis.X);
            } else {
                int z0 = centerZ - 1;
                int x0 = centerX;
                int y0 = bottomY;

                for(int z = -1; z <= width; ++z) {
                    for(int y = -1; y <= height; ++y) {
                        Block b = w.getBlockAt(x0, y0 + y, z0 + z);
                        boolean frame = z == -1 || z == width || y == -1 || y == height;
                        b.setType(frame ? Material.GLOWSTONE : Material.AIR, false);
                    }
                }

                return new FrostPortalShape(w, x0, y0, z0, width, height, Axis.Z);
            }
        }

        static @Nullable FrostPortalShape detect(Block inside) {
            if (!inside.getType().isAir() && inside.getType() != Material.WATER) {
                return null;
            } else {
                World w = inside.getWorld();
                int cx = inside.getX();
                int cy = inside.getY();
                int cz = inside.getZ();
                FrostPortalShape shape = detectWithOrientation(w, cx, cy, cz, Axis.X);
                return shape != null ? shape : detectWithOrientation(w, cx, cy, cz, Axis.Z);
            }
        }

        private static @Nullable FrostPortalShape detectWithOrientation(World w, int cx, int cy, int cz, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int z0 = cz;

                for(int x0 = cx - 1; x0 <= cx; ++x0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cx >= x0 && cx < x0 + width && cy >= y0 && cy < y0 + height && isValidFrameX(w, x0, y0, z0, width, height)) {
                            return new FrostPortalShape(w, x0, y0, z0, width, height, Axis.X);
                        }
                    }
                }
            } else {
                int x0 = cx;

                for(int z0 = cz - 1; z0 <= cz; ++z0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cz >= z0 && cz < z0 + width && cy >= y0 && cy < y0 + height && isValidFrameZ(w, x0, y0, z0, width, height)) {
                            return new FrostPortalShape(w, x0, y0, z0, width, height, Axis.Z);
                        }
                    }
                }
            }

            return null;
        }

        private static boolean isValidFrameX(World w, int x0, int y0, int z0, int width, int height) {
            for(int x = -1; x <= width; ++x) {
                for(int y = -1; y <= height; ++y) {
                    Block b = w.getBlockAt(x0 + x, y0 + y, z0);
                    boolean frame = x == -1 || x == width || y == -1 || y == height;
                    if (frame) {
                        if (b.getType() != Material.GLOWSTONE) {
                            return false;
                        }
                    } else if (!b.getType().isAir() && b.getType() != Material.WATER) {
                        return false;
                    }
                }
            }

            return true;
        }

        private static boolean isValidFrameZ(World w, int x0, int y0, int z0, int width, int height) {
            for(int z = -1; z <= width; ++z) {
                for(int y = -1; y <= height; ++y) {
                    Block b = w.getBlockAt(x0, y0 + y, z0 + z);
                    boolean frame = z == -1 || z == width || y == -1 || y == height;
                    if (frame) {
                        if (b.getType() != Material.GLOWSTONE) {
                            return false;
                        }
                    } else if (!b.getType().isAir() && b.getType() != Material.WATER) {
                        return false;
                    }
                }
            }

            return true;
        }

        Block getInteriorBlock(int dx, int dy) {
            return this.axis == Axis.X ? this.world.getBlockAt(this.x0 + dx, this.y0 + dy, this.z0) : this.world.getBlockAt(this.x0, this.y0 + dy, this.z0 + dx);
        }

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

        Location getSpawnLocation() {
            Location c = this.getCenter().clone();
            c.setY(c.getY() + (double)0.5F);
            return c;
        }
    }
}
