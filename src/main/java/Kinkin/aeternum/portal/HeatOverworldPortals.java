package kinkin.aeternum.portal;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 炽热领域（aeternum_heat）主世界传送门的持久化链接管理器。
 *
 * <p>本监听器维护一组“门位置 <-> 门位置”的双向映射（{@link PortalLocation}），
 * 支持查找已登录的链接目标、登记/清除链接并写入 portal_links.yml。
 * 同时可点燃由下界疣块框构成的炽热门，并提供安全落点搜寻与开凿能力，
 * 以确保传送到目标位置时不会卡进方块。依赖 {@link AeternumSeasonsPlugin} 的配置与语言服务。</p>
 */
public final class HeatOverworldPortals implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private static final int SAFE_SPACE_RADIUS = 2;
    private final Map<PortalLocation, PortalLocation> portalConnections = new ConcurrentHashMap();
    private final AeternumSeasonsPlugin plugin;
    private final File portalDataFile;

    /**
     * 创建管理器并从数据文件加载已保存的传送门链接。
     */
    public HeatOverworldPortals(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.portalDataFile = new File(plugin.getDataFolder(), "portal_links.yml");
        this.loadPortalLinks();
    }

    /**
     * 将本监听器注册到插件的事件总线。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 注销事件监听；在非“世界自行处理传送门”模式下保存链接。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        if (!this.plugin.shouldLetWorldsHandlePortals()) {
            this.savePortalLinks();
        }

    }

    /**
     * 读取 portal_links.yml，逐条恢复门位置之间的映射。
     */
    private void loadPortalLinks() {
        this.portalConnections.clear();
        if (this.portalDataFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(this.portalDataFile);

            for (String key : config.getKeys(false)) {
                String destinationString = config.getString(key);
                if (destinationString != null && !destinationString.isBlank()) {
                    PortalLocation source = this.parsePortalString(key);
                    PortalLocation destination = this.parsePortalString(destinationString);
                    if (source != null && destination != null) {
                        this.portalConnections.put(source, destination);
                    }
                }
            }

            this.plugin.getLogger().info("[HeatOverworldPortals] Cargados " + this.portalConnections.size() + " enlaces de portal.");
        }
    }

    /**
     * 将当前所有链接写回 portal_links.yml。
     */
    private void savePortalLinks() {
        FileConfiguration config = new YamlConfiguration();

        for (Map.Entry<PortalLocation, PortalLocation> entry : this.portalConnections.entrySet()) {
            config.set(this.formatPortalString((PortalLocation) entry.getKey()), this.formatPortalString((PortalLocation) entry.getValue()));
        }

        try {
            config.save(this.portalDataFile);
            this.plugin.getLogger().info("[HeatOverworldPortals] Guardados " + this.portalConnections.size() + " enlaces de portal.");
        } catch (IOException e) {
            this.plugin.getLogger().severe("[HeatOverworldPortals] No se pudo guardar portal_links.yml: " + e.getMessage());
        }

    }

    /**
     * 将门位置序列化为“X_Y_Z_世界”格式。
     */
    private String formatPortalString(PortalLocation loc) {
        return loc.x + "_" + loc.y + "_" + loc.z + "_" + loc.worldName;
    }

    /**
     * 解析“X_Y_Z_世界”格式的字符串，格式非法时返回 null。
     */
    private @Nullable PortalLocation parsePortalString(String s) {
        String[] parts = s.split("_", 4);
        if (parts.length != 4) {
            return null;
        } else {
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                String worldName = parts[3];
                return new PortalLocation(worldName, x, y, z);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }

    /**
     * 将世界内位置转换为不含朝向的门位置键。
     */
    private PortalLocation toPortalLocation(Location loc) {
        return new PortalLocation(((World) Objects.requireNonNull(loc.getWorld())).getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * 在给定位置半径 2 的范围内查找已登录的链接目标，返回其世界坐标。
     */
    public @Nullable Location findLinkedPortal(Location from) {
        int searchRadius = 2;
        World world = from.getWorld();
        if (world == null) {
            return null;
        } else {
            for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
                for (int dy = -searchRadius; dy <= searchRadius; ++dy) {
                    for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
                        PortalLocation key = new PortalLocation(world.getName(), from.getBlockX() + dx, from.getBlockY() + dy, from.getBlockZ() + dz);
                        PortalLocation dest = (PortalLocation) this.portalConnections.get(key);
                        if (dest != null) {
                            World destWorld = Bukkit.getWorld(dest.worldName);
                            if (destWorld != null) {
                                return new Location(destWorld, (double) dest.x + (double) 0.5F, (double) dest.y, (double) dest.z + (double) 0.5F);
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    /**
     * 登记两个位置之间的双向传送链接并立即保存。
     */
    public void registerPortalLink(Location loc1, Location loc2) {
        PortalLocation p1 = this.toPortalLocation(loc1);
        PortalLocation p2 = this.toPortalLocation(loc2);
        this.portalConnections.put(p1, p2);
        this.portalConnections.put(p2, p1);
        this.savePortalLinks();
    }

    /**
     * 清空所有传送链接并立即保存。
     */
    public void clearPortalLinks() {
        this.portalConnections.clear();
        this.savePortalLinks();
    }

    /**
     * 玩家传送门事件占位处理器（本类的传送逻辑由其他监听器完成）。
     */
    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onPlayerPortal(PlayerPortalEvent event) {
    }

    /**
     * 在目标位置周围搜寻一处可站立的安全空间，找不到则开凿一个。
     */
    private Location createSafePortalExit(Location portalLocation) {
        World world = portalLocation.getWorld();
        if (world == null) {
            return portalLocation;
        } else {
            int baseX = portalLocation.getBlockX();
            int baseY = portalLocation.getBlockY();
            int baseZ = portalLocation.getBlockZ();

            for (int yOffset = 0; yOffset <= 2; ++yOffset) {
                for (int xOffset = -2; xOffset <= 2; ++xOffset) {
                    for (int zOffset = -2; zOffset <= 2; ++zOffset) {
                        int checkX = baseX + xOffset;
                        int checkY = baseY + yOffset;
                        int checkZ = baseZ + zOffset;
                        Block block = world.getBlockAt(checkX, checkY, checkZ);
                        Block blockAbove = world.getBlockAt(checkX, checkY + 1, checkZ);
                        Block blockBelow = world.getBlockAt(checkX, checkY - 1, checkZ);
                        if (this.isSafeSpace(block, blockAbove, blockBelow)) {
                            return new Location(world, (double) checkX + (double) 0.5F, (double) checkY, (double) checkZ + (double) 0.5F);
                        }
                    }
                }
            }

            return this.createSafeSpace(portalLocation);
        }
    }

    /**
     * 判断一格及其上方是否为空、下方是否为实心可站立地面。
     */
    private boolean isSafeSpace(Block block, Block blockAbove, Block blockBelow) {
        return block.getType().isAir() && blockAbove.getType().isAir() && !blockBelow.getType().isAir() && blockBelow.getType().isSolid();
    }

    /**
     * 在目标位置附近开凿一处安全空间并铺设地面，返回可站立坐标。
     */
    private Location createSafeSpace(Location portalLocation) {
        World world = portalLocation.getWorld();
        if (world == null) {
            return portalLocation;
        } else {
            int baseX = portalLocation.getBlockX();
            int baseY = portalLocation.getBlockY();
            int baseZ = portalLocation.getBlockZ();

            for (int yOffset = 0; yOffset <= 2; ++yOffset) {
                for (int xOffset = -1; xOffset <= 1; ++xOffset) {
                    for (int zOffset = -1; zOffset <= 1; ++zOffset) {
                        int checkX = baseX + xOffset;
                        int checkY = baseY + yOffset;
                        int checkZ = baseZ + zOffset;
                        if (this.canCreateSafeSpace(world, checkX, checkY, checkZ)) {
                            this.clearSpace(world, checkX, checkY, checkZ);
                            this.ensureSolidGround(world, checkX, checkY - 1, checkZ);
                            world.playSound(new Location(world, (double) checkX, (double) checkY, (double) checkZ), Sound.BLOCK_STONE_BREAK, 0.5F, 1.0F);
                            world.spawnParticle(Particle.BLOCK, new Location(world, (double) checkX + (double) 0.5F, (double) checkY, (double) checkZ + (double) 0.5F), 10, 0.3, 0.3, 0.3, 0.1, Material.STONE.createBlockData());
                            return new Location(world, (double) checkX + (double) 0.5F, (double) checkY, (double) checkZ + (double) 0.5F);
                        }
                    }
                }
            }

            return portalLocation;
        }
    }

    /**
     * 判断该位置的方块是否可被清空（排除基岩、黑曜石、水与岩浆）。
     */
    private boolean canCreateSafeSpace(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        world.getBlockAt(x, y + 1, z);
        return block.getType() != Material.BEDROCK && block.getType() != Material.OBSIDIAN && block.getType() != Material.WATER && block.getType() != Material.LAVA;
    }

    /**
     * 清空指定位置及其上方一格（非空气时置为空气）。
     */
    private void clearSpace(World world, int x, int y, int z) {
        for (int dy = 0; dy < 2; ++dy) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (!block.getType().isAir()) {
                block.setType(Material.AIR);
            }
        }

    }

    /**
     * 若地面为空或不实心，则替换为石头，保证可站立。
     */
    private void ensureSolidGround(World world, int x, int y, int z) {
        Block ground = world.getBlockAt(x, y, z);
        if (ground.getType().isAir() || !ground.getType().isSolid()) {
            ground.setType(Material.STONE);
        }

    }

    /**
     * 右键交互处理：手持打火石或火焰弹右键方块时，若目标空位可构成炽热门形状，则点亮。
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
                        HeatPortalShape shape = HeatOverworldPortals.HeatPortalShape.detect(inside);
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
     * 将传送门内部区域填充为下界传送门方块，并播放音效与粒子。
     */
    private void lightPortal(HeatPortalShape shape) {
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
     * 判断世界是否为炽热领域世界。
     */
    public boolean isHeatWorld(World world) {
        return world != null && "aeternum_heat".equalsIgnoreCase(world.getName());
    }

    /**
     * 判断传送门方块六个相邻面中是否存在下界疣块，用于识别炽热门。
     */
    public boolean isWartPortal(Block portalBlock) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block block = portalBlock.getRelative(face);
            if (block.getType() == Material.NETHER_WART_BLOCK) {
                return true;
            }
        }

        return false;
    }

    /**
     * 炽热传送门的几何描述（底部锚点、宽高与朝向），提供检测与取点工具。
     */
    private static final class HeatPortalShape {
        final World world;
        final int x0;
        final int y0;
        final int z0;
        final int width;
        final int height;
        final Axis axis;

        private HeatPortalShape(World world, int x0, int y0, int z0, int width, int height, Axis axis) {
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
         * 以内部某格为起点，分别按 X、Z 两种朝向探测是否构成炽热门。
         */
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

        /**
         * 以给定朝向尝试匹配炽热门框，命中则返回门对象。
         */
        private static @Nullable HeatPortalShape detectWithOrientation(World world, int cx, int cy, int cz, Axis axis) {
            int width = 2;
            int height = 3;
            if (axis == Axis.X) {
                int z0 = cz;

                for (int x0 = cx - 1; x0 <= cx; ++x0) {
                    for (int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cx >= x0 && cx < x0 + width && cy >= y0 && cy < y0 + height && isValidFrameX(world, x0, y0, z0, width, height)) {
                            return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.X);
                        }
                    }
                }
            } else {
                int x0 = cx;

                for (int z0 = cz - 1; z0 <= cz; ++z0) {
                    for (int y0 = cy - 2; y0 <= cy; ++y0) {
                        if (cz >= z0 && cz < z0 + width && cy >= y0 && cy < y0 + height && isValidFrameZ(world, x0, y0, z0, width, height)) {
                            return new HeatPortalShape(world, x0, y0, z0, width, height, Axis.Z);
                        }
                    }
                }
            }

            return null;
        }

        /**
         * 校验 X 轴朝向的门是否完全由下界疣块边框与空/火内部组成。
         */
        private static boolean isValidFrameX(World world, int x0, int y0, int z0, int width, int height) {
            for (int x = -1; x <= width; ++x) {
                for (int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0 + x, y0 + y, z0);
                    boolean frame = x == -1 || x == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != Material.NETHER_WART_BLOCK) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.FIRE) {
                        return false;
                    }
                }
            }

            return true;
        }

        /**
         * 校验 Z 轴朝向的门是否完全由下界疣块边框与空/火内部组成。
         */
        private static boolean isValidFrameZ(World world, int x0, int y0, int z0, int width, int height) {
            for (int z = -1; z <= width; ++z) {
                for (int y = -1; y <= height; ++y) {
                    Block block = world.getBlockAt(x0, y0 + y, z0 + z);
                    boolean frame = z == -1 || z == width || y == -1 || y == height;
                    if (frame) {
                        if (block.getType() != Material.NETHER_WART_BLOCK) {
                            return false;
                        }
                    } else if (!block.getType().isAir() && block.getType() != Material.FIRE) {
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
    }

    /**
     * 门位置键：世界名与方块坐标，作为链接映射的键。
     */
    private static final class PortalLocation {
        final String worldName;
        final int x;
        final int y;
        final int z;

        private PortalLocation(String worldName, int x, int y, int z) {
            super();
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof PortalLocation)) {
                return false;
            } else {
                PortalLocation other = (PortalLocation) obj;
                return this.x == other.x && this.y == other.y && this.z == other.z && Objects.equals(this.worldName, other.worldName);
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(new Object[]{this.worldName, this.x, this.y, this.z});
        }
    }
}
