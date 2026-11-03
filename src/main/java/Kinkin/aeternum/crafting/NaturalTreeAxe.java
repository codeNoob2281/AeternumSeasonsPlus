package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * 自然之树斧（Natural Tree Axe）。
 *
 * <p>本类负责注册并维护“自然之树斧”自定义物品，并实现一键伐木的完整流程：
 * <ul>
 *     <li>注册由任意斧 + 树叶 + 原木/苔石/垂根合成的有序配方，并在合成预览时校验材料；</li>
 *     <li>为合成的斧写入名称、描述与持久化标签；</li>
 *     <li>玩家未潜行时用其破坏树干方块，会扫描整棵树（树干、树叶、藤蔓、积雪），
 *         生成方块展示实体播放“倾倒”动画，随后在空中旋转落下并掉落所有相关掉落物；</li>
 *     <li>倾倒过程中会阻止玩家破坏同一棵树的其它方块，避免重复触发；结束时按折断的原木数消耗耐久。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 提供语言文本与命名空间。树的扫描算法通过 {@link ScanResult}
 * 缓存树干/树叶/藤蔓等集合，并用 {@link FellSession} 管理动画与结算的生命周期。</p>
 */
public final class NaturalTreeAxe implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final int MAX_LOGS_PER_USE = 220;
    private static final int MAX_LEAVES_PER_USE = 520;
    private static final int MAX_VINES_PER_USE = 320;
    private static final int MAX_TREE_HEIGHT = 56;
    private static final int FALL_ANIM_TICKS = 80;
    private static final int MAX_DISPLAYS_TOTAL = 240;
    private final Map<UUID, FellSession> active = new HashMap();
    private static final Set<Material> NATURAL_SOILS;
    private static final BlockFace[] VINE_SUPPORT_FACES;
    private static final int[][] LOG_NEIGHBOR_OFFSETS;

    public NaturalTreeAxe(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.itemTagKey = new NamespacedKey(plugin, "natural_tree_axe_item");
        this.recipeKey = new NamespacedKey(plugin, "natural_tree_axe");
    }

    /**
     * 向服务器注册事件监听器与合成配方。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    /**
     * 注销事件监听器、移除配方，并取消所有正在进行的伐木会话。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);

        for (FellSession session : this.active.values()) {
            try {
                session.cancel();
            } catch (Throwable ignored) {
            }
        }

        this.active.clear();
    }

    /**
     * 注册自然之树斧的合成配方：A 任意斧、L 树叶、S 原木/苔石/垂根。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.IRON_AXE);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"LAL", "S S", "L L"});
        Material[] axeMats = (Material[]) Arrays.stream(Material.values()).filter(Material::isItem).filter((material) -> material.name().endsWith("_AXE")).toArray((length) -> new Material[length]);
        RecipeChoice axeChoice = new RecipeChoice.MaterialChoice(axeMats);
        RecipeChoice leavesChoice = new RecipeChoice.MaterialChoice(new Material[]{Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES});
        RecipeChoice naturalChoice = new RecipeChoice.MaterialChoice(new Material[]{Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.MOSSY_COBBLESTONE, Material.HANGING_ROOTS});
        recipe.setIngredient('A', axeChoice);
        recipe.setIngredient('L', leavesChoice);
        recipe.setIngredient('S', naturalChoice);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为自然之树斧配方时，
     * 校验摆放的材料（一把斧、至少 4 个树叶/下界菌盖、至少 2 个原木类方块），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    ItemStack foundAxe = null;
                    int leaves = 0;
                    int naturals = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (isAxe(type)) {
                                if (foundAxe != null) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                foundAxe = stack;
                            } else if (!this.isLeavesMaterial(type) && !this.isNetherFoliageMaterial(type)) {
                                if (!this.isNaturalCraftBlock(type)) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                naturals += stack.getAmount();
                            } else {
                                leaves += stack.getAmount();
                            }
                        }
                    }

                    if (foundAxe != null && leaves >= 4 && naturals >= 2) {
                        HumanEntity viewer = event.getView().getPlayer();
                        Player craftingPlayer;
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            craftingPlayer = player;
                        } else {
                            craftingPlayer = null;
                        }

                        Player crafter = craftingPlayer;
                        ItemStack result = new ItemStack(foundAxe.getType());
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            meta.getPersistentDataContainer().set(this.itemTagKey, PersistentDataType.BYTE, (byte) 1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.natural_tree_axe.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.natural_tree_axe.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.natural_tree_axe.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.natural_tree_axe.lore3");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3));
                            } catch (Throwable ignored) {
                            }

                            result.setItemMeta(meta);
                        }

                        event.getInventory().setResult(result);
                    } else {
                        event.getInventory().setResult((ItemStack) null);
                    }
                }
            }
        }
    }

    /**
     * 判断材料是否为任意斧（含原版与自定义命名空间）。
     */
    private static boolean isAxe(Material material) {
        if (material == null) {
            return false;
        } else if (material.name().endsWith("_AXE")) {
            return true;
        } else {
            NamespacedKey key = material.getKey();
            return key != null && "minecraft".equals(key.getNamespace()) && key.getKey().endsWith("_axe");
        }
    }

    /**
     * 判断物品是否为任意斧。
     */
    private static boolean isAxe(ItemStack stack) {
        return stack != null && isAxe(stack.getType());
    }

    /**
     * 判断物品是否为带有自然之树斧持久化标签的斧。
     */
    private boolean isNaturalTreeAxe(ItemStack stack) {
        if (stack == null) {
            return false;
        } else if (!isAxe(stack.getType())) {
            return false;
        } else {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte) meta.getPersistentDataContainer().get(this.itemTagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        }
    }

    /**
     * 判断材料是否可用于配方中的“自然”槽位（苔石、垂根或任意树干）。
     */
    private boolean isNaturalCraftBlock(Material material) {
        return material == Material.MOSSY_COBBLESTONE || material == Material.HANGING_ROOTS || this.isTrunkMaterial(material);
    }

    /**
     * 判断材料是否为树干类方块（原木、木、菌柄、菌丝体等，含去皮变体）。
     */
    private boolean isTrunkMaterial(Material material) {
        if (material == null) {
            return false;
        } else {
            try {
                if (Tag.LOGS.isTagged(material)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }

            String name = material.name();
            if (name.startsWith("STRIPPED_")) {
                name = name.substring("STRIPPED_".length());
            }

            return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
        }
    }

    /**
     * 判断材料是否为树叶类方块。
     */
    private boolean isLeavesMaterial(Material material) {
        if (material == null) {
            return false;
        } else {
            try {
                if (Tag.LEAVES.isTagged(material)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }

            return material.name().endsWith("_LEAVES");
        }
    }

    /**
     * 判断材料是否为下界菌盖类方块。
     */
    private boolean isNetherFoliageMaterial(Material material) {
        if (material == null) {
            return false;
        } else {
            return material == Material.NETHER_WART_BLOCK || material == Material.WARPED_WART_BLOCK;
        }
    }

    /**
     * 判断材料是否为藤蔓。
     */
    private boolean isTreeVineMaterial(Material material) {
        return material == Material.VINE;
    }

    /**
     * 返回树干材料的“家族键”（去掉去皮前缀与 _LOG/_WOOD/_STEM/_HYPHAE 后缀）。
     */
    private static String trunkFamilyKey(Material material) {
        if (material == null) {
            return "";
        } else {
            String name = material.name();
            if (name.startsWith("STRIPPED_")) {
                name = name.substring("STRIPPED_".length());
            }

            String[] suffixes = new String[]{"_LOG", "_WOOD", "_STEM", "_HYPHAE"};

            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    return name.substring(0, name.length() - suffix.length());
                }
            }

            return name;
        }
    }

    /**
     * 判断材料是否为与给定家族键相同的树干材料。
     */
    private boolean isSameTrunkFamily(Material material, String familyKey) {
        if (material == null) {
            return false;
        } else if (!this.isTrunkMaterial(material)) {
            return false;
        } else {
            String computedKey = trunkFamilyKey(material);
            return !familyKey.isEmpty() && familyKey.equals(computedKey);
        }
    }

    /**
     * 判断方块是否为可一并清除的树叶（非持久化的树叶或下界菌盖）。
     */
    private boolean isFoliageBlock(Block block) {
        if (block == null) {
            return false;
        } else {
            Material material = block.getType();
            if (this.isLeavesMaterial(material)) {
                BlockData data = block.getBlockData();
                if (data instanceof Leaves) {
                    Leaves leavesData = (Leaves) data;
                    return !leavesData.isPersistent();
                } else {
                    return true;
                }
            } else {
                return this.isNetherFoliageMaterial(material);
            }
        }
    }

    /**
     * 判断材料是否为可支撑树木的自然土壤（各类泥土、草方块、菌丝体、沙等）。
     */
    private boolean isNaturalSoil(Material material) {
        if (material == null) {
            return false;
        } else if (NATURAL_SOILS.contains(material)) {
            return true;
        } else {
            String name = material.name();
            return name.contains("DIRT") || name.contains("GRASS") || name.contains("PODZOL") || name.contains("MUD") || name.contains("MOSS") || name.contains("MYCELIUM");
        }
    }

    /**
     * 从给定方块向下寻找同家族树干的基座（最多下探 12 格）。
     */
    private Block findTrunkBase(Block origin, String familyKey) {
        Block current = origin;

        for (int i = 0; i < 12; ++i) {
            Block below = current.getRelative(0, -1, 0);
            if (!this.isSameTrunkFamily(below.getType(), familyKey)) {
                break;
            }

            current = below;
            if (below.getY() <= below.getWorld().getMinHeight()) {
                break;
            }
        }

        return current;
    }

    /**
     * 快速预判给定方块附近是否像一棵树（附近有足够树叶）。
     */
    private boolean quickLooksLikeTree(Block origin) {
        return this.hasFoliageNearby(origin, 4, 6);
    }

    /**
     * 沿着树干向上扫描，统计附近一定范围内的树叶数量是否达到阈值。
     */
    private boolean hasFoliageNearby(Block origin, int radius, int min) {
        World world = origin.getWorld();
        int found = 0;
        Block current = origin;
        String computedKey = trunkFamilyKey(origin.getType());

        for (int step = 0; step < 18 && this.isSameTrunkFamily(current.getType(), computedKey); ++step) {
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (dx * dx + dz * dz <= radius * radius) {
                        for (int dy = -1; dy <= 3; ++dy) {
                            Block block = world.getBlockAt(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                            if (this.isFoliageBlock(block)) {
                                ++found;
                                if (found >= min) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }

            current = current.getRelative(0, 1, 0);
        }

        return false;
    }

    /**
     * 从基座向上统计树干高度（最多 56 格）。
     */
    private int trunkHeightFromBase(Block base, String familyKey) {
        int height = 0;
        Block current = base;

        for (int i = 0; i < 56 && this.isSameTrunkFamily(current.getType(), familyKey); ++i) {
            ++height;
            current = current.getRelative(0, 1, 0);
        }

        return height;
    }

    /**
     * 依据树干类型、高度与是否为 2x2 树，计算树枝/扫描/树叶三种半径。
     */
    private TreeRadii computeRadii(Material trunkType, int trunkHeight, boolean is2x2) {
        int leafRadius = Math.min(18, Math.max(6, trunkHeight / 3 + 3));
        if (is2x2) {
            leafRadius = Math.min(18, leafRadius + 2);
        }

        int scanRadius = Math.min(8, Math.max(3, leafRadius / 2 + 2));
        int branchRadius = Math.min(8, Math.max(4, leafRadius / 2 + 1));
        String name = trunkType.name();
        if (name.contains("JUNGLE")) {
            leafRadius = Math.max(leafRadius, 10);
            scanRadius = Math.max(scanRadius, 6);
            branchRadius = Math.max(branchRadius, 6);
        }

        if (name.contains("SPRUCE")) {
            leafRadius = Math.max(leafRadius, 9);
            scanRadius = Math.max(scanRadius, 5);
            branchRadius = Math.max(branchRadius, 5);
        }

        if (name.contains("DARK_OAK")) {
            leafRadius = Math.max(leafRadius, 8);
            scanRadius = Math.max(scanRadius, 5);
            branchRadius = Math.max(branchRadius, 5);
        }

        if (name.contains("MANGROVE")) {
            leafRadius = Math.max(leafRadius, 9);
            scanRadius = Math.max(scanRadius, 5);
            branchRadius = Math.max(branchRadius, 5);
        }

        if (name.contains("CRIMSON") || name.contains("WARPED")) {
            leafRadius = Math.max(leafRadius, 9);
            scanRadius = Math.max(scanRadius, 5);
            branchRadius = Math.max(branchRadius, 5);
        }

        leafRadius = Math.min(14, leafRadius);
        scanRadius = Math.min(8, scanRadius);
        branchRadius = Math.min(8, branchRadius);
        return new TreeRadii(branchRadius, scanRadius, leafRadius);
    }

    /**
     * 定位树干的中心点与底部脚印（1x1 或 2x2）。
     */
    private TreeCenter findTreeCenter(Block base, String familyKey) {
        World world = base.getWorld();
        int y = base.getY();

        for (int ox = -1; ox <= 0; ++ox) {
            for (int oz = -1; oz <= 0; ++oz) {
                Block b00 = world.getBlockAt(base.getX() + ox, y, base.getZ() + oz);
                Block b10 = world.getBlockAt(base.getX() + ox + 1, y, base.getZ() + oz);
                Block b01 = world.getBlockAt(base.getX() + ox, y, base.getZ() + oz + 1);
                Block b11 = world.getBlockAt(base.getX() + ox + 1, y, base.getZ() + oz + 1);
                if (this.isSameTrunkFamily(b00.getType(), familyKey) && this.isSameTrunkFamily(b10.getType(), familyKey) && this.isSameTrunkFamily(b01.getType(), familyKey) && this.isSameTrunkFamily(b11.getType(), familyKey)) {
                    Set<Block> footprint = new LinkedHashSet();
                    footprint.add(b00);
                    footprint.add(b10);
                    footprint.add(b01);
                    footprint.add(b11);
                    int cx = base.getX() + ox + 1;
                    int cz = base.getZ() + oz + 1;
                    return new TreeCenter(cx, cz, footprint);
                }
            }
        }

        Set<Block> footprint = new LinkedHashSet();
        footprint.add(base);
        return new TreeCenter(base.getX(), base.getZ(), footprint);
    }

    /**
     * 将方块坐标压缩为一个 long 键，用于集合去重。
     */
    private long blockKey(Block block) {
        long x = (long) block.getX() & 67108863L;
        long z = (long) block.getZ() & 67108863L;
        long y = (long) block.getY() & 4095L;
        return x << 38 | z << 12 | y;
    }

    /**
     * 按“最近树干”规则严格筛选树叶，剔除更靠近其它树干的叶子。
     */
    private void filterLeavesByNearestLogStrict(Set<Block> leaves, Set<Block> myLogs, int radius) {
        if (!leaves.isEmpty() && !myLogs.isEmpty()) {
            Set<Long> myLogKeys = new HashSet(myLogs.size() * 2);

            for (Block block : myLogs) {
                myLogKeys.add(this.blockKey(block));
            }

            leaves.removeIf((leaf) -> {
                int distMine = this.nearestLogDist(leaf, radius, myLogKeys);
                if (distMine == Integer.MAX_VALUE) {
                    return true;
                } else {
                    int distOther = this.nearestLogDistExcluding(leaf, radius, myLogKeys);
                    return distOther != Integer.MAX_VALUE && distMine >= distOther;
                }
            });
        }
    }

    /**
     * 在限定半径内计算到最近树干的曼哈顿距离（可限定只统计指定树干集合）。
     */
    private int nearestLogDist(Block center, int radius, Set<Long> onlyTheseLogsOrNull) {
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int best = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dy = -radius; dy <= radius; ++dy) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= radius * radius) {
                        Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (this.isTrunkMaterial(block.getType()) && (onlyTheseLogsOrNull == null || onlyTheseLogsOrNull.contains(this.blockKey(block)))) {
                            int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (dist < best) {
                                best = dist;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 在限定半径内计算到最近“非指定集合”树干的曼哈顿距离。
     */
    private int nearestLogDistExcluding(Block center, int radius, Set<Long> excludeLogs) {
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int best = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dy = -radius; dy <= radius; ++dy) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= radius * radius) {
                        Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (this.isTrunkMaterial(block.getType()) && (excludeLogs == null || !excludeLogs.contains(this.blockKey(block)))) {
                            int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (dist < best) {
                                best = dist;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 核心扫描：以破坏点为中心识别整棵树。
     * 先定位树干基座、中心与脚印，再从脚印沿树干收集原木，
     * 然后基于树干向外扩散、按最近树干规则筛选树叶，最后收集附着藤蔓，
     * 并把雪花、展示实体等一并汇总进 {@link ScanResult}。
     */
    private ScanResult scanTree(Block origin) {
        World world = origin.getWorld();
        Material trunkType = origin.getType();
        if (!this.isTrunkMaterial(trunkType)) {
            return null;
        } else {
            String familyKey = trunkFamilyKey(trunkType);
            Block base = this.findTrunkBase(origin, familyKey);
            TreeCenter center = this.findTreeCenter(base, familyKey);
            boolean is2x2 = center.footprint.size() == 4;
            int height = this.trunkHeightFromBase(base, familyKey);
            TreeRadii radii = this.computeRadii(trunkType, height, is2x2);
            int maxLogRadius = Math.min(24, Math.max(radii.leafTreeRadius + 6, 14));
            int maxLogRadius2 = maxLogRadius * maxLogRadius;
            int coreR = 2;
            int coreR2 = 4;
            Block soil = base.getRelative(0, -1, 0);
            if (!this.isNaturalSoil(soil.getType())) {
                String soilName = soil.getType().name();
                boolean nylium = soilName.endsWith("_NYLIUM") || soilName.contains("NYLIUM");
                if (!nylium) {
                    return null;
                }
            }

            ScanResult result = new ScanResult();
            result.trunkType = trunkType;
            result.center = center;
            result.radii = radii;
            Queue<Block> queue = new ArrayDeque();
            Set<Long> visited = new HashSet();

            for (Block footprintBlock : center.footprint) {
                Block current = footprintBlock;

                for (int i = 0; i < 56 && this.isSameTrunkFamily(current.getType(), familyKey); ++i) {
                    int dx = current.getX() - center.x;
                    int dz = current.getZ() - center.z;
                    if (dx * dx + dz * dz > maxLogRadius2) {
                        break;
                    }

                    long key = this.blockKey(current);
                    if (visited.add(key)) {
                        queue.add(current);
                        result.logs.add(current);
                        Block above = current.getRelative(0, 1, 0);
                        if (above.getType() == Material.SNOW) {
                            result.snow.add(above);
                        }
                    }

                    current = current.getRelative(0, 1, 0);
                }
            }

            int baseY = base.getY();

            while (!queue.isEmpty() && result.logs.size() < 220) {
                Block block = (Block) queue.poll();

                for (int[] offset : LOG_NEIGHBOR_OFFSETS) {
                    int ox = offset[0];
                    int oy = offset[1];
                    int oz = offset[2];
                    Block neighbor = block.getRelative(ox, oy, oz);
                    if (this.isSameTrunkFamily(neighbor.getType(), familyKey) && neighbor.getY() >= baseY - 1 && neighbor.getY() <= baseY + 56) {
                        int dx = neighbor.getX() - center.x;
                        int dz = neighbor.getZ() - center.z;
                        int d2 = dx * dx + dz * dz;
                        if (d2 <= maxLogRadius2) {
                            boolean inCore = d2 <= 4;
                            boolean lowTrunk = neighbor.getY() <= baseY + 2;
                            if (inCore || lowTrunk || this.logHasNearbyFoliage(neighbor)) {
                                boolean isDiagXZ = oy == 0 && ox != 0 && oz != 0;
                                if (!isDiagXZ || !lowTrunk && this.logHasNearbyFoliageTight(neighbor)) {
                                    boolean isDiag3D = oy != 0 && ox != 0 && oz != 0;
                                    if (!isDiag3D || !lowTrunk && oy >= 0 && this.logHasNearbyFoliageTight(neighbor) && this.logHasNearbyFoliageTight(block)) {
                                        long key = this.blockKey(neighbor);
                                        if (visited.add(key)) {
                                            result.logs.add(neighbor);
                                            queue.add(neighbor);
                                            Block above = neighbor.getRelative(0, 1, 0);
                                            if (above.getType() == Material.SNOW) {
                                                result.snow.add(above);
                                            }

                                            if (result.logs.size() >= 220) {
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (result.logs.size() < 2) {
                return null;
            } else {
                result.leaves.clear();
                this.seedFoliageTouchingMyLogs(result.logs, result.leaves, baseY, center, radii.leafTreeRadius);
                this.expandFoliageFloodBounded(result.leaves, 520, center, radii.leafTreeRadius);
                this.filterLeavesByNearestLogStrictVanilla7(result.leaves, result.logs);

                for (Block leaf : result.leaves) {
                    Block above = leaf.getRelative(0, 1, 0);
                    if (above.getType() == Material.SNOW) {
                        result.snow.add(above);
                    }
                }

                if (result.leaves.size() > 520) {
                    Set<Block> cut = new LinkedHashSet();
                    int i = 0;

                    for (Block block : result.leaves) {
                        cut.add(block);
                        ++i;
                        if (i >= 520) {
                            break;
                        }
                    }

                    result.leaves.clear();
                    result.leaves.addAll(cut);
                }

                this.collectAttachedTreeVines(result.logs, result.leaves, result.vines, center, radii.leafTreeRadius, baseY);
                result.pickAnimated(240);
                return result;
            }
        }
    }

    /**
     * 从树干/树叶向外收集附着在其上的藤蔓（限制在树木范围内），填入 out。
     */
    private void collectAttachedTreeVines(Set<Block> logs, Set<Block> leaves, Set<Block> out, TreeCenter center, int leafTreeRadius, int baseY) {
        if (!logs.isEmpty() || !leaves.isEmpty()) {
            Set<Long> treeBlockKeys = new HashSet((logs.size() + leaves.size()) * 2);

            for (Block log : logs) {
                treeBlockKeys.add(this.blockKey(log));
            }

            for (Block leaf : leaves) {
                treeBlockKeys.add(this.blockKey(leaf));
            }

            ArrayDeque<Block> queue = new ArrayDeque();
            Set<Long> seen = new HashSet();

            for (Block treeBlock : this.concatTreeBlocks(logs, leaves)) {
                for (BlockFace face : VINE_SUPPORT_FACES) {
                    Block vine = treeBlock.getRelative(face);
                    if (this.isTreeVineMaterial(vine.getType()) && this.isVineAttachedToTree(vine, treeBlockKeys) && this.isWithinVineBounds(vine, center, leafTreeRadius, baseY) && seen.add(this.blockKey(vine))) {
                        queue.add(vine);
                    }
                }

                Block below = treeBlock.getRelative(BlockFace.DOWN);
                if (this.isTreeVineMaterial(below.getType()) && this.isVineAttachedToTree(below, treeBlockKeys) && this.isWithinVineBounds(below, center, leafTreeRadius, baseY) && seen.add(this.blockKey(below))) {
                    queue.add(below);
                }
            }

            while (!queue.isEmpty() && out.size() < 320) {
                Block vine = (Block) queue.poll();
                out.add(vine);
                Block below = vine.getRelative(BlockFace.DOWN);
                if (this.isTreeVineMaterial(below.getType()) && this.isWithinVineBounds(below, center, leafTreeRadius, baseY) && seen.add(this.blockKey(below))) {
                    queue.add(below);
                }
            }

        }
    }

    /**
     * 将树干与树叶合并为可迭代集合。
     */
    private Iterable<Block> concatTreeBlocks(Set<Block> logs, Set<Block> leaves) {
        List<Block> blocks = new ArrayList(logs.size() + leaves.size());
        blocks.addAll(logs);
        blocks.addAll(leaves);
        return blocks;
    }

    /**
     * 判断藤蔓是否附着在树木方块上（其朝向面有树木方块）。
     */
    private boolean isVineAttachedToTree(Block vine, Set<Long> treeBlockKeys) {
        BlockData blockData = vine.getBlockData();
        if (blockData instanceof MultipleFacing faces) {
            for (BlockFace face : VINE_SUPPORT_FACES) {
                if (faces.hasFace(face) && treeBlockKeys.contains(this.blockKey(vine.getRelative(face)))) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    /**
     * 判断藤蔓是否位于树木的纵向与横向边界内。
     */
    private boolean isWithinVineBounds(Block vine, TreeCenter center, int leafTreeRadius, int baseY) {
        if (vine.getY() >= vine.getWorld().getMinHeight() && vine.getY() <= baseY + 56) {
            int radius = leafTreeRadius + 1;
            int dx = vine.getX() - center.x;
            int dz = vine.getZ() - center.z;
            return dx * dx + dz * dz <= radius * radius;
        } else {
            return false;
        }
    }

    /**
     * 判断树干 5x5x5 范围内是否有树叶（较严格的邻近判定）。
     */
    private boolean logHasNearbyFoliageTight(Block log) {
        World world = log.getWorld();
        int lx = log.getX();
        int ly = log.getY();
        int lz = log.getZ();

        for (int dy = -2; dy <= 2; ++dy) {
            int y = ly + dy;

            for (int dx = -2; dx <= 2; ++dx) {
                int x = lx + dx;

                for (int dz = -2; dz <= 2; ++dz) {
                    int z = lz + dz;
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (this.isLeavesMaterial(material) || this.isNetherFoliageMaterial(material)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 以与树干相邻的树叶作为种子，加入 out（限制在树木半径与高度内）。
     */
    private void seedFoliageTouchingMyLogs(Set<Block> myLogs, Set<Block> out, int baseY, TreeCenter center, int leafTreeRadius) {
        int r2 = leafTreeRadius * leafTreeRadius;

        for (Block log : myLogs) {
            Block[] neighbors = new Block[]{log.getRelative(1, 0, 0), log.getRelative(-1, 0, 0), log.getRelative(0, 1, 0), log.getRelative(0, -1, 0), log.getRelative(0, 0, 1), log.getRelative(0, 0, -1)};

            for (Block neighbor : neighbors) {
                int y = neighbor.getY();
                if (y >= baseY - 2 && y <= baseY + 56) {
                    int rx = neighbor.getX() - center.x;
                    int rz = neighbor.getZ() - center.z;
                    if (rx * rx + rz * rz <= r2 && this.isFoliageBlock(neighbor)) {
                        out.add(neighbor);
                        if (out.size() >= 520) {
                            return;
                        }
                    }
                }
            }
        }

    }

    /**
     * 更严格地按最近树干规则筛选树叶（模拟原版 7 格衰减）：
     * 过滤持久化树叶、距离>=7 的树叶，以及更靠近其它树干的树叶。
     */
    private void filterLeavesByNearestLogStrictVanilla7(Set<Block> leaves, Set<Block> myLogs) {
        if (!leaves.isEmpty() && !myLogs.isEmpty()) {
            Set<Long> myLogKeys = new HashSet(myLogs.size() * 2);

            for (Block block : myLogs) {
                myLogKeys.add(this.blockKey(block));
            }

            int R = 7;
            int r2 = 49;
            leaves.removeIf((leaf) -> {
                BlockData blockData = leaf.getBlockData();
                if (blockData instanceof Leaves leavesData) {
                    if (leavesData.isPersistent()) {
                        return true;
                    }

                    int dist = leavesData.getDistance();
                    if (dist >= 7) {
                        return true;
                    }
                }

                int distMine = this.nearestLogDistWithin(leaf, 7, myLogKeys, true);
                if (distMine == Integer.MAX_VALUE) {
                    return true;
                } else {
                    int distOther = this.nearestLogDistWithin(leaf, 7, myLogKeys, false);
                    return distOther != Integer.MAX_VALUE && distMine >= distOther;
                }
            });
        }
    }

    /**
     * 在限定半径内计算到最近树干的曼哈顿距离；modeOnlyMine 控制只统计我方树干或只统计其它树干。
     */
    private int nearestLogDistWithin(Block center, int radius, Set<Long> myLogKeys, boolean modeOnlyMine) {
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int best = Integer.MAX_VALUE;
        int r2 = radius * radius;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dy = -radius; dy <= radius; ++dy) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= r2) {
                        Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (this.isTrunkMaterial(block.getType())) {
                            long key = this.blockKey(block);
                            boolean isMine = myLogKeys.contains(key);
                            if (modeOnlyMine) {
                                if (!isMine) {
                                    continue;
                                }
                            } else if (isMine) {
                                continue;
                            }

                            int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (dist < best) {
                                best = dist;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 仅保留与“我方树干”连通的树叶（广度优先），最多保留 cap 个。
     */
    private Set<Block> keepOnlyFoliageConnectedToMyLogs(Set<Block> candidates, Set<Block> myLogs, TreeCenter center, int leafTreeRadius, int cap) {
        if (!candidates.isEmpty() && !myLogs.isEmpty()) {
            int r2 = leafTreeRadius * leafTreeRadius;
            Set<Long> candKeys = new HashSet(candidates.size() * 2);

            for (Block block : candidates) {
                candKeys.add(this.blockKey(block));
            }

            ArrayDeque<Block> queue = new ArrayDeque();
            Set<Long> seen = new HashSet(candidates.size() * 2);

            for (Block log : myLogs) {
                Block[] neighbors = new Block[]{log.getRelative(1, 0, 0), log.getRelative(-1, 0, 0), log.getRelative(0, 1, 0), log.getRelative(0, -1, 0), log.getRelative(0, 0, 1), log.getRelative(0, 0, -1)};

                for (Block neighbor : neighbors) {
                    long key = this.blockKey(neighbor);
                    if (candKeys.contains(key) && this.isFoliageBlock(neighbor) && seen.add(key)) {
                        int rx = neighbor.getX() - center.x;
                        int rz = neighbor.getZ() - center.z;
                        if (rx * rx + rz * rz <= r2) {
                            queue.add(neighbor);
                        }
                    }
                }
            }

            LinkedHashSet<Block> kept = new LinkedHashSet();

            while (!queue.isEmpty() && kept.size() < cap) {
                Block block = (Block) queue.poll();
                kept.add(block);

                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dz = -1; dz <= 1; ++dz) {
                            if (dx != 0 || dy != 0 || dz != 0) {
                                Block neighbor = block.getRelative(dx, dy, dz);
                                int rx = neighbor.getX() - center.x;
                                int rz = neighbor.getZ() - center.z;
                                if (rx * rx + rz * rz <= r2) {
                                    long key = this.blockKey(neighbor);
                                    if (candKeys.contains(key) && seen.add(key) && this.isFoliageBlock(neighbor)) {
                                        queue.add(neighbor);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return kept;
        } else {
            return candidates;
        }
    }

    /**
     * 以单根树干为中心，在扫描半径内收集可能的树叶种子。
     */
    private void seedFoliageAroundLog(Block log, Set<Block> out, int scanRadius, int baseY, int leafTreeRadius, TreeCenter center) {
        World world = log.getWorld();
        int cx = log.getX();
        int cy = log.getY();
        int cz = log.getZ();

        for (int dx = -scanRadius; dx <= scanRadius; ++dx) {
            for (int dz = -scanRadius; dz <= scanRadius; ++dz) {
                for (int dy = -2; dy <= 5; ++dy) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (y >= world.getMinHeight() && y <= world.getMaxHeight() - 1 && y >= baseY - 2 && y <= baseY + 56) {
                        int rx = x - center.x;
                        int rz = z - center.z;
                        if (rx * rx + rz * rz <= leafTreeRadius * leafTreeRadius) {
                            Block block = world.getBlockAt(x, y, z);
                            if (this.isFoliageBlock(block)) {
                                out.add(block);
                                if (out.size() >= 520) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    /**
     * 判断树干 5x5x5 范围内是否有树叶（较宽松的邻近判定）。
     */
    private boolean logHasNearbyFoliage(Block log) {
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dz = -2; dz <= 2; ++dz) {
                    if ((dx != 0 || dy != 0 || dz != 0) && this.isFoliageBlock(log.getRelative(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 从已有树叶种子出发进行受限的广度扩散，最多扩到 cap 个。
     */
    private void expandFoliageFloodBounded(Set<Block> leaves, int cap, TreeCenter center, int leafTreeRadius) {
        if (!leaves.isEmpty()) {
            int r2 = leafTreeRadius * leafTreeRadius;
            Queue<Block> queue = new ArrayDeque(leaves);
            Set<Long> seen = new HashSet(leaves.size() * 2);

            for (Block block : leaves) {
                seen.add(this.blockKey(block));
            }

            while (!queue.isEmpty() && leaves.size() < cap) {
                Block block = (Block) queue.poll();

                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dz = -1; dz <= 1; ++dz) {
                            if (dx != 0 || dy != 0 || dz != 0) {
                                Block neighbor = block.getRelative(dx, dy, dz);
                                int rx = neighbor.getX() - center.x;
                                int rz = neighbor.getZ() - center.z;
                                if (rx * rx + rz * rz <= r2) {
                                    long key = this.blockKey(neighbor);
                                    if (seen.add(key) && this.isFoliageBlock(neighbor)) {
                                        leaves.add(neighbor);
                                        queue.add(neighbor);
                                        if (leaves.size() >= cap) {
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * 计算倾倒动画的旋转支点（最低树干方块的中心）。
     */
    private Location computePivot(Set<Block> logs) {
        Block lowest = null;

        for (Block block : logs) {
            if (lowest == null || block.getY() < lowest.getY()) {
                lowest = block;
            }
        }

        if (lowest == null) {
            return ((Block) logs.iterator().next()).getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
        } else {
            return lowest.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
        }
    }

    /**
     * 计算树木倒向：由玩家指向树心的水平方向（退化时取视线方向或 +X）。
     */
    private Vector computeFallDir(Location pivot, Player player) {
        Vector dir = pivot.toVector().subtract(player.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = player.getLocation().getDirection();
            dir.setY(0);
        }

        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(1, 0, 0);
        }

        return dir.normalize();
    }

    /**
     * 计算旋转轴（竖直方向与倒向的叉积）。
     */
    private Vector computeAxis(Vector fallDir) {
        Vector axis = (new Vector(0, 1, 0)).crossProduct(fallDir);
        return axis.lengthSquared() < 1.0E-4 ? new Vector(1, 0, 0) : axis.normalize();
    }

    /**
     * 用四元数旋转一个向量。
     */
    private static Vector rotateVector(Vector vector, Quaternionf rotation) {
        Vector3f input = new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
        rotation.transform(input);
        return new Vector(input.x, input.y, input.z);
    }

    /**
     * 为指定方块生成一个方块展示实体，用于播放倾倒动画。
     */
    private DisplayEntry spawnBlockDisplayFor(World world, Block block) {
        try {
            BlockData blockData = block.getBlockData();
            Location at = block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
            BlockDisplay display = (BlockDisplay) world.spawn(at, BlockDisplay.class, (displayEntity) -> {
                displayEntity.setBlock(blockData);
                displayEntity.setViewRange(0.85F);
                displayEntity.setShadowRadius(0.0F);
                displayEntity.setShadowStrength(0.0F);
                displayEntity.setInterpolationDelay(0);
                displayEntity.setInterpolationDuration(2);
                displayEntity.setTeleportDuration(2);
            });
            return new DisplayEntry(display, at.toVector());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 安全地获取方块掉落物（异常时返回空列表）。
     */
    private List<ItemStack> safeDrops(Block block, ItemStack tool, Player player) {
        try {
            Collection<ItemStack> drops = block.getDrops(tool, player);
            return (List<ItemStack>) (drops == null ? Collections.emptyList() : new ArrayList<>(drops));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * 监听方块破坏：玩家未潜行、手持自然之树斧且破坏树干时，
     * 扫描整棵树并启动倾倒动画（会取消原破坏事件）。
     * 若玩家正处于某次倾倒过程中，则阻止其破坏同一棵树的方块。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGH
    )
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            if (this.active.containsKey(player.getUniqueId())) {
                Material blockType = event.getBlock().getType();
                if (this.isTrunkMaterial(blockType) || this.isLeavesMaterial(blockType) || this.isNetherFoliageMaterial(blockType) || this.isTreeVineMaterial(blockType)) {
                    event.setCancelled(true);
                }
            } else {
                ItemStack axe = player.getInventory().getItemInMainHand();
                if (this.isNaturalTreeAxe(axe)) {
                    if (!player.isSneaking()) {
                        Block origin = event.getBlock();
                        Material type = origin.getType();
                        if (this.isTrunkMaterial(type)) {
                            if (this.quickLooksLikeTree(origin)) {
                                ScanResult scan = this.scanTree(origin);
                                if (scan != null) {
                                    event.setCancelled(true);
                                    this.startFellDisplayAnimation(player, axe, origin, scan);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动一次伐木：收集掉落物、计算支点/倒向/旋转轴、为被选中的方块生成展示实体，
     * 立即清除原方块，再创建 {@link FellSession} 播放动画并在结束时结算。
     */
    private void startFellDisplayAnimation(Player player, ItemStack axe, Block origin, ScanResult scan) {
        World world = origin.getWorld();
        List<ItemStack> allDrops = new ArrayList(scan.logs.size() * 2 + scan.leaves.size() + scan.vines.size());

        for (Block block : scan.logs) {
            allDrops.addAll(this.safeDrops(block, axe, player));
        }

        for (Block block : scan.leaves) {
            allDrops.addAll(this.safeDrops(block, axe, player));
        }

        for (Block block : scan.vines) {
            allDrops.addAll(this.safeDrops(block, axe, player));
        }

        allDrops.removeIf((item) -> item == null || item.getType() == Material.AIR);
        Location pivot = this.computePivot(scan.logs);
        Vector fallDir = this.computeFallDir(pivot, player);
        Vector axis = this.computeAxis(fallDir);
        List<DisplayEntry> entries = new ArrayList(scan.animLogs.size() + scan.animLeaves.size() + scan.animVines.size());

        for (Block block : scan.animLogs) {
            DisplayEntry entry = this.spawnBlockDisplayFor(world, block);
            if (entry != null) {
                entries.add(entry);
            }
        }

        for (Block block : scan.animLeaves) {
            DisplayEntry entry = this.spawnBlockDisplayFor(world, block);
            if (entry != null) {
                entries.add(entry);
            }
        }

        for (Block block : scan.animVines) {
            DisplayEntry entry = this.spawnBlockDisplayFor(world, block);
            if (entry != null) {
                entries.add(entry);
            }
        }

        for (Block block : scan.vines) {
            block.setType(Material.AIR, false);
        }

        for (Block block : scan.logs) {
            block.setType(Material.AIR, false);
        }

        for (Block block : scan.leaves) {
            block.setType(Material.AIR, false);
        }

        for (Block snowBlock : scan.snow) {
            if (snowBlock.getType() == Material.SNOW) {
                snowBlock.setType(Material.AIR, false);
            }
        }

        world.playSound(origin.getLocation(), Sound.BLOCK_WOOD_BREAK, 0.9F, 0.95F);
        FellSession session = new FellSession(player.getUniqueId(), world, pivot, fallDir, axis, entries, allDrops, scan.logs.size());
        this.active.put(player.getUniqueId(), session);
        session.start();
    }

    /**
     * 依据本次折断的原木数扣除斧的耐久；耐久耗尽则移除手中物品并播放损坏音效。
     */
    private void damageAxe(Player player, ItemStack axe, int logsBroken) {
        if (axe != null) {
            ItemMeta meta = axe.getItemMeta();
            if (meta instanceof Damageable) {
                Damageable dmg = (Damageable) meta;
                int damage = dmg.getDamage();
                int toAdd = Math.max(1, logsBroken);
                int newDamage = damage + toAdd;
                short max = axe.getType().getMaxDurability();
                if (newDamage >= max) {
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
                    player.getInventory().setItemInMainHand((ItemStack) null);
                } else {
                    dmg.setDamage(newDamage);
                    axe.setItemMeta(dmg);
                    player.getInventory().setItemInMainHand(axe);
                }
            }
        }
    }

    static {
        NATURAL_SOILS = EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.MUD, Material.MUDDY_MANGROVE_ROOTS, Material.MOSS_BLOCK, Material.MYCELIUM, Material.SAND, Material.RED_SAND);
        VINE_SUPPORT_FACES = new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP};
        LOG_NEIGHBOR_OFFSETS = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1}, {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}, {1, 1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1}, {1, -1, 1}, {1, -1, -1}, {-1, -1, 1}, {-1, -1, -1}};
    }

    /**
     * 三种半径的聚合结果：树枝半径、树叶扫描半径、树叶树半径。
     */
    private static final class TreeRadii {
        final int branchRadius;
        final int leafScanRadius;
        final int leafTreeRadius;

        TreeRadii(int branchRadius, int leafScanRadius, int leafTreeRadius) {
            super();
            this.branchRadius = branchRadius;
            this.leafScanRadius = leafScanRadius;
            this.leafTreeRadius = leafTreeRadius;
        }
    }

    /**
     * 树的水平中心坐标与底部脚印方块集合。
     */
    private static final class TreeCenter {
        final int x;
        final int z;
        final Set<Block> footprint;

        TreeCenter(int x, int z, Set<Block> footprint) {
            super();
            this.x = x;
            this.z = z;
            this.footprint = footprint;
        }
    }

    /**
     * 一次树木扫描的结果：树干、树叶、藤蔓、积雪以及用于动画的展示方块子集。
     */
    private static final class ScanResult {
        final Set<Block> logs = new LinkedHashSet();
        Set<Block> leaves = new LinkedHashSet();
        final Set<Block> vines = new LinkedHashSet();
        final Set<Block> snow = new LinkedHashSet();
        final Set<Block> animLogs = new LinkedHashSet();
        final Set<Block> animLeaves = new LinkedHashSet();
        final Set<Block> animVines = new LinkedHashSet();
        Material trunkType;
        TreeCenter center;
        TreeRadii radii;

        private ScanResult() {
            super();
        }

        /**
         * 按“先原木、再树叶、最后藤蔓”的顺序挑选至多 maxTotal 个方块用于动画展示。
         */
        void pickAnimated(int maxTotal) {
            int left = maxTotal;

            for (Block block : this.logs) {
                if (left-- <= 0) {
                    break;
                }

                this.animLogs.add(block);
            }

            for (Block block : this.leaves) {
                if (left-- <= 0) {
                    break;
                }

                this.animLeaves.add(block);
            }

            for (Block block : this.vines) {
                if (left-- <= 0) {
                    break;
                }

                this.animVines.add(block);
            }

        }
    }

    /**
     * 单个方块展示实体及其原始中心坐标。
     */
    private static final class DisplayEntry {
        final BlockDisplay display;
        final Vector originalCenter;

        DisplayEntry(BlockDisplay display, Vector originalCenter) {
            super();
            this.display = display;
            this.originalCenter = originalCenter;
        }
    }

    /**
     * 单次伐木动画会话：负责逐 tick 旋转展示实体、播放粒子/音效，并在结束时结算掉落与耐久。
     */
    private final class FellSession {
        private final UUID playerId;
        private final World world;
        private final Location pivot;
        private final Vector fallDir;
        private final Vector axis;
        private final List<DisplayEntry> entries;
        private final List<ItemStack> drops;
        private final int logsCountForDurability;
        private int tick = 0;
        private BukkitRunnable task;

        FellSession(UUID playerId, World world, Location pivot, Vector fallDir, Vector axis, List<DisplayEntry> entries, List<ItemStack> drops, int logsCountForDurability) {
            super();
            this.playerId = playerId;
            this.world = world;
            this.pivot = pivot;
            this.fallDir = fallDir;
            this.axis = axis;
            this.entries = entries;
            this.drops = drops;
            this.logsCountForDurability = logsCountForDurability;
        }

        /**
         * 启动逐 tick 的动画任务（每 tick 执行一次）。
         */
        void start() {
            this.task = new BukkitRunnable() {
                @Override
                public void run() {
                    Player player = Bukkit.getPlayer(FellSession.this.playerId);
                    if (player != null && player.isOnline() && player.getWorld() == FellSession.this.world) {
                        ++FellSession.this.tick;
                        float progress = Math.min(1.0F, (float) FellSession.this.tick / 80.0F);
                        float ease = 1.0F - (float) Math.pow((double) (1.0F - progress), (double) 2.2F);
                        float angleRad = (float) ((double) ease * (Math.PI / 2D));
                        Quaternionf rot = (new Quaternionf()).fromAxisAngleRad((float) FellSession.this.axis.getX(), (float) FellSession.this.axis.getY(), (float) FellSession.this.axis.getZ(), angleRad);
                        Vector pivotV = FellSession.this.pivot.toVector();

                        for (DisplayEntry entry : FellSession.this.entries) {
                            BlockDisplay display = entry.display;
                            if (display != null && !display.isDead()) {
                                Vector relative = entry.originalCenter.clone().subtract(pivotV);
                                Vector rotated = NaturalTreeAxe.rotateVector(relative, rot);
                                Vector newPos = pivotV.clone().add(rotated);
                                Location loc = new Location(FellSession.this.world, newPos.getX(), newPos.getY(), newPos.getZ());
                                display.teleport(loc);
                                Transformation transform = new Transformation(new Vector3f(0.0F, 0.0F, 0.0F), rot, new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf());
                                display.setTransformation(transform);
                                if (FellSession.this.tick % 5 == 0) {
                                    Material material = display.getBlock().getMaterial();
                                    if (!material.name().endsWith("_LOG") && !material.name().endsWith("_STEM") && !material.name().endsWith("_HYPHAE")) {
                                        if (material.name().endsWith("_LEAVES") || material == Material.NETHER_WART_BLOCK || material == Material.WARPED_WART_BLOCK || material == Material.VINE) {
                                            FellSession.this.world.spawnParticle(Particle.BLOCK, loc, 1, 0.08, 0.08, 0.08, (double) 0.0F, display.getBlock());
                                        }
                                    } else {
                                        FellSession.this.world.spawnParticle(Particle.BLOCK, loc, 2, 0.08, 0.08, 0.08, (double) 0.0F, display.getBlock());
                                    }
                                }
                            }
                        }

                        if (FellSession.this.tick % 6 == 0) {
                            FellSession.this.world.playSound(FellSession.this.pivot, Sound.BLOCK_WOOD_HIT, 0.55F, 0.85F);
                        }

                        if (progress >= 1.0F) {
                            this.cancel();
                            FellSession.this.finish(player);
                        }

                    } else {
                        this.cancel();
                        FellSession.this.cleanup(true);
                    }
                }
            };
            this.task.runTaskTimer(NaturalTreeAxe.this.plugin, 1L, 1L);
        }

        /**
         * 动画结束：清理展示实体、在落点掉落全部物品并扣除斧耐久，随后移除会话。
         */
        void finish(Player player) {
            this.cleanup(false);
            Location dropAt = this.pivot.clone().add(this.fallDir.clone().multiply(1.4)).add((double) 0.0F, 0.2, (double) 0.0F);

            for (ItemStack item : this.drops) {
                if (item != null && item.getType() != Material.AIR) {
                    this.world.dropItemNaturally(dropAt, item);
                }
            }

            this.world.playSound(dropAt, Sound.BLOCK_WOOD_BREAK, 1.0F, 0.7F);
            this.world.playSound(dropAt, Sound.ENTITY_GENERIC_EXPLODE, 0.25F, 1.4F);
            ItemStack axeNow = player.getInventory().getItemInMainHand();
            if (axeNow != null && NaturalTreeAxe.this.isNaturalTreeAxe(axeNow)) {
                NaturalTreeAxe.this.damageAxe(player, axeNow, this.logsCountForDurability);
            }

            NaturalTreeAxe.this.active.remove(this.playerId);
        }

        /**
         * 移除所有展示实体；removeSession 为 true 时同时移除会话记录。
         */
        void cleanup(boolean removeSession) {
            for (DisplayEntry entry : this.entries) {
                try {
                    if (entry.display != null && !entry.display.isDead()) {
                        entry.display.remove();
                    }
                } catch (Throwable ignored) {
                }
            }

            if (removeSession) {
                NaturalTreeAxe.this.active.remove(this.playerId);
            }

        }

        /**
         * 取消底层动画任务（若存在）。
         */
        void cancel() {
            try {
                if (this.task != null) {
                    this.task.cancel();
                }
            } catch (Throwable ignored) {
            }

        }
    }
}
