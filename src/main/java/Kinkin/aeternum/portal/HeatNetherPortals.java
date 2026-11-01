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

public final class HeatNetherPortals implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final File portalDataFile;
    private final Map<PortalKey, PortalKey> portalConnections = new ConcurrentHashMap();
    private boolean registered;

    public HeatNetherPortals(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.portalDataFile = new File(plugin.getDataFolder(), "heat_nether_links.yml");
        this.loadPortalLinks();
    }

    public void register() {
        if (!this.registered) {
            this.registered = true;
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
        }
    }

    public void unregister() {
        this.registered = false;
        HandlerList.unregisterAll(this);
        this.savePortalLinks();
    }

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

    private String formatPortalKey(PortalKey key) {
        String var10000 = key.worldName;
        return var10000 + "|" + key.x + "|" + key.y + "|" + key.z + "|" + key.axis.name() + "|" + key.kind.name();
    }

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
            } catch (Exception var12) {
                return null;
            }
        }
    }

    private void registerPortalLink(PortalKey a, PortalKey b) {
        this.unlinkPortalEndpoint(a);
        this.unlinkPortalEndpoint(b);
        this.portalConnections.put(a, b);
        this.portalConnections.put(b, a);
        this.savePortalLinks();
    }

    private void removePortalLink(PortalKey a) {
        this.unlinkPortalEndpoint(a);
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
            if (e.getClickedBlock() != null) {
                Material item = e.getItem() != null ? e.getItem().getType() : Material.AIR;
                if (item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE) {
                    Block clicked = e.getClickedBlock();
                    BlockFace face = e.getBlockFace();
                    Block inside = clicked.getRelative(face);
                    if (inside.getType().isAir()) {
                        HeatPortalShape shape = HeatNetherPortals.HeatPortalShape.detect(inside);
                        if (shape != null) {
                            e.setCancelled(true);
                            this.lightPortal(shape);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        if (proj.getShooter() instanceof Player) {
            Block hit = e.getHitBlock();
            if (hit != null) {
                BlockFace face = e.getHitBlockFace();
                Block inside = face != null ? hit.getRelative(face) : hit;
                HeatPortalShape shape = HeatNetherPortals.HeatPortalShape.detect(inside);
                if (shape != null) {
                    this.lightPortal(shape);
                }
            }
        }
    }

    private void lightPortal(HeatPortalShape shape) {
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

    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.HIGHEST
    )
    public void onPortal(PlayerPortalEvent e) {
        if (this.isHeatPortalAt(e.getFrom())) {
            e.setCancelled(true);
            e.setCanCreatePortal(false);
            PortalTarget target = this.resolvePortalTarget(e.getFrom());
            if (target != null) {
                World fromWorld = e.getFrom().getWorld();
                Location to = target.teleportLocation.clone();
                to.setYaw(e.getFrom().getYaw());
                to.setPitch(e.getFrom().getPitch());
                e.setTo(to);
                e.setCancelled(false);
                e.setCanCreatePortal(false);
                this.scheduleRealmTitle(e.getPlayer(), fromWorld, to.getWorld());
            }
        }
    }

    @EventHandler(
        ignoreCancelled = false,
        priority = EventPriority.HIGHEST
    )
    public void onEntityPortal(EntityPortalEvent e) {
        if (this.isHeatPortalAt(e.getFrom())) {
            e.setCancelled(true);
            PortalTarget target = this.resolvePortalTarget(e.getFrom());
            if (target != null) {
                e.setTo(target.teleportLocation.clone());
                e.setCancelled(false);
            }
        }
    }

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

    private boolean isHeatPortalAt(Location from) {
        Block portalBlock = this.findPortalBlock(from);
        if (portalBlock != null && portalBlock.getType() == Material.NETHER_PORTAL) {
            PortalBounds bounds = this.getPortalBounds(portalBlock);
            return bounds != null && this.getPortalKind(bounds, portalBlock.getWorld()) == HeatNetherPortals.PortalKind.HEAT;
        } else {
            return false;
        }
    }

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

    private Axis getPortalAxis(Block portalBlock) {
        try {
            if (portalBlock.getBlockData() instanceof Orientable) {
                return ((Orientable)portalBlock.getBlockData()).getAxis();
            }
        } catch (Exception var3) {
        }

        return Axis.X;
    }

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
                        Block b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        if (b.getType() == Material.NETHER_PORTAL) {
                            PortalBounds bounds = this.getPortalBounds(b);
                            if (bounds != null && bounds.contains(from)) {
                                double score = bounds.distanceSquaredToCenter(from);
                                if (score < bestScore) {
                                    bestScore = score;
                                    best = b;
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
                            Block b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                            if (b.getType() == Material.NETHER_PORTAL) {
                                Location c = b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
                                double score = c.distanceSquared(from);
                                if (score < bestScore) {
                                    bestScore = score;
                                    best = b;
                                }
                            }
                        }
                    }
                }

                return best;
            }
        }
    }

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
                    Block b = world.getBlockAt(ix, iy, iz);
                    if (b.getType() == Material.NETHER_PORTAL) {
                        PortalBounds bounds = this.getPortalBounds(b);
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

    private int clampNetherY(World world, int preferredY) {
        int min = world.getMinHeight() + 8;
        int max = world.getMaxHeight() - 16;
        return Math.max(min, Math.min(max, preferredY));
    }

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

    private static final class PortalTarget {
        final Location teleportLocation;
        final PortalKey key;

        private PortalTarget(Location teleportLocation, PortalKey key) {
            super();
            this.teleportLocation = teleportLocation;
            this.key = key;
        }
    }

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

        static HeatPortalShape buildAt(World w, int centerX, int bottomY, int centerZ, Axis axis, Material frameMaterial) {
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
                        b.setType(frame ? frameMaterial : Material.AIR, false);
                    }
                }

                return new HeatPortalShape(w, x0, y0, z0, width, height, Axis.X, frameMaterial);
            } else {
                int z0 = centerZ - 1;
                int x0 = centerX;
                int y0 = bottomY;

                for(int z = -1; z <= width; ++z) {
                    for(int y = -1; y <= height; ++y) {
                        Block b = w.getBlockAt(x0, y0 + y, z0 + z);
                        boolean frame = z == -1 || z == width || y == -1 || y == height;
                        b.setType(frame ? frameMaterial : Material.AIR, false);
                    }
                }

                return new HeatPortalShape(w, x0, y0, z0, width, height, Axis.Z, frameMaterial);
            }
        }

        static @Nullable HeatPortalShape detect(Block inside) {
            if (!inside.getType().isAir() && inside.getType() != Material.FIRE) {
                return null;
            } else {
                World w = inside.getWorld();
                int cx = inside.getX();
                int cy = inside.getY();
                int cz = inside.getZ();
                HeatPortalShape shape = detectWithOrientation(w, cx, cy, cz, Axis.X);
                return shape != null ? shape : detectWithOrientation(w, cx, cy, cz, Axis.Z);
            }
        }

        private static @Nullable HeatPortalShape detectWithOrientation(World w, int cx, int cy, int cz, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int z0 = cz;

                for(int x0 = cx - 1; x0 <= cx; ++x0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cx >= x0 && cx < x0 + width && cy >= y0 && cy < y0 + height && isValidFrameX(w, x0, y0, z0, width, height, Material.NETHER_WART_BLOCK)) {
                            return new HeatPortalShape(w, x0, y0, z0, width, height, Axis.X, Material.NETHER_WART_BLOCK);
                        }
                    }
                }
            } else {
                int x0 = cx;

                for(int z0 = cz - 1; z0 <= cz; ++z0) {
                    for(int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cz >= z0 && cz < z0 + width && cy >= y0 && cy < y0 + height && isValidFrameZ(w, x0, y0, z0, width, height, Material.NETHER_WART_BLOCK)) {
                            return new HeatPortalShape(w, x0, y0, z0, width, height, Axis.Z, Material.NETHER_WART_BLOCK);
                        }
                    }
                }
            }

            return null;
        }

        private static boolean isValidFrameX(World w, int x0, int y0, int z0, int width, int height, Material frameMaterial) {
            for(int x = -1; x <= width; ++x) {
                for(int y = -1; y <= height; ++y) {
                    Block b = w.getBlockAt(x0 + x, y0 + y, z0);
                    boolean frame = x == -1 || x == width || y == -1 || y == height;
                    if (frame) {
                        if (b.getType() != frameMaterial) {
                            return false;
                        }
                    } else if (!b.getType().isAir() && b.getType() != Material.FIRE) {
                        return false;
                    }
                }
            }

            return true;
        }

        private static boolean isValidFrameZ(World w, int x0, int y0, int z0, int width, int height, Material frameMaterial) {
            for(int z = -1; z <= width; ++z) {
                for(int y = -1; y <= height; ++y) {
                    Block b = w.getBlockAt(x0, y0 + y, z0 + z);
                    boolean frame = z == -1 || z == width || y == -1 || y == height;
                    if (frame) {
                        if (b.getType() != frameMaterial) {
                            return false;
                        }
                    } else if (!b.getType().isAir() && b.getType() != Material.FIRE) {
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
            c.setY((double)this.y0 + 0.1);
            return c;
        }
    }
}
