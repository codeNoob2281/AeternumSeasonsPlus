package Kinkin.aeternum.fauna;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * 生物生成器（Creature Generator）服务。
 *
 * <p>以刷怪笼方块作为“生物陷阱/生成器”：玩家用刷怪笼物品放置后，生成器会在有玩家靠近时
 * 按周期随机产出普通刷怪蛋并存入其 3 个内部格位，同时消耗使用次数；次数耗尽后生成器碎裂。
 * 主要行为与依赖：
 * <ul>
 *     <li>注册生成器物品的合成配方，并用持久化标记区分普通刷怪笼；</li>
 *     <li>监听放置/破坏/交互/背包事件，维护生成器状态与 GUI；</li>
 *     <li>通过区块加载与周期扫描发现并处理生成器，内部存储以字节数组序列化；</li>
 *     <li>依赖 {@link AeternumSeasonsPlugin} 提供配置、语言与调度器。</li>
 * </ul>
 * 生成器以 {@link TrapKey}（世界 UUID + 坐标）唯一标识。</p>
 */
public final class CreatureGeneratorService implements Listener {
    private static final int STORAGE_SLOTS = 3;
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey trapKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey invKey;
    private final NamespacedKey eggUidKey;
    private File faunaFile;
    private FileConfiguration fauna;
    private BukkitTask task;
    private BukkitTask maintenanceTask;
    private final Set<TrapKey> knownTraps = new HashSet();
    private final Deque<TrapKey> pendingTraps = new ArrayDeque();
    private final Set<TrapKey> queuedTraps = new HashSet();
    private final Deque<ChunkKey> discoveryQueue = new ArrayDeque();
    private final Set<ChunkKey> queuedChunks = new HashSet();
    private List<WeightedType> weightedTypes = List.of();
    private int weightedTotal;
    private static final long MAINTENANCE_BUDGET_NANOS = 1500000L;
    private static final int MAX_GENERATORS_PER_TICK = 8;

    public CreatureGeneratorService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.trapKey = new NamespacedKey(plugin, "creature_trap");
        this.usesKey = new NamespacedKey(plugin, "creature_trap_uses");
        this.invKey = new NamespacedKey(plugin, "creature_trap_inv");
        this.eggUidKey = new NamespacedKey(plugin, "trap_egg_uid");
    }

    /**
     * 加载配置、注册事件监听与配方、启动任务并扫描已加载区块中的生成器。
     */
    public void register() {
        this.loadFauna();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipes();
        this.startTask();
        this.enqueueLoadedChunks();
    }

    /**
     * 停止任务、注销监听并清空所有队列与已知生成器缓存。
     */
    public void unregister() {
        this.stopTask();
        HandlerList.unregisterAll(this);
        this.knownTraps.clear();
        this.pendingTraps.clear();
        this.queuedTraps.clear();
        this.discoveryQueue.clear();
        this.queuedChunks.clear();
    }

    /**
     * 重新加载配置、重新注册配方并重启任务与区块扫描。
     */
    public void reload() {
        this.loadFauna();
        this.registerRecipes();
        this.startTask();
        this.enqueueLoadedChunks();
    }

    /**
     * 加载 fauna.yml，并重建加权生物类型列表。
     */
    private void loadFauna() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }

        this.faunaFile = new File(this.plugin.getDataFolder(), "fauna.yml");
        if (!this.faunaFile.exists()) {
            try {
                this.plugin.saveResource("fauna.yml", false);
            } catch (IllegalArgumentException ignored) {
                try {
                    this.faunaFile.createNewFile();
                } catch (IOException ignoredToo) {
                }
            }
        }

        this.fauna = YamlConfiguration.loadConfiguration(this.faunaFile);
        this.rebuildWeightedTypes();
    }

    /**
     * 生成器功能是否启用。
     */
    private boolean enabled() {
        return this.fauna.getBoolean("creature_generator.enabled", true);
    }

    /**
     * 默认使用次数（至少 1）。
     */
    private int defaultUses() {
        return Math.max(1, this.fauna.getInt("creature_generator.uses", 10));
    }

    /**
     * 生成周期（秒，至少 5）。
     */
    private int tickSeconds() {
        return Math.max(5, this.fauna.getInt("creature_generator.tick_seconds", 180));
    }

    /**
     * 生成时是否需要有玩家在附近。
     */
    private boolean requirePlayerNearby() {
        return this.fauna.getBoolean("creature_generator.require_player_nearby", true);
    }

    /**
     * 玩家有效范围（格，至少 1）。
     */
    private int playerRange() {
        return Math.max(1, this.fauna.getInt("creature_generator.player_range", 26));
    }

    /**
     * 读取允许生成的可存活生物类型列表（排除凋灵/末影龙）。
     */
    private List<EntityType> allowedTypes() {
        List<String> raw = this.fauna.getStringList("creature_generator.allowed");
        if (raw != null && !raw.isEmpty()) {
            List<EntityType> out = new ArrayList();

            for(String s : raw) {
                if (s != null) {
                    try {
                        EntityType t = EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
                        if (t.isAlive() && t != EntityType.WITHER && t != EntityType.ENDER_DRAGON) {
                            out.add(t);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (out.isEmpty()) {
                out = List.of(EntityType.RABBIT, EntityType.FROG, EntityType.BEE, EntityType.PARROT);
            }

            return out;
        } else {
            return List.of(EntityType.RABBIT, EntityType.FROG, EntityType.BEE, EntityType.PARROT);
        }
    }

    /**
     * 注册生成器物品的合成配方（先移除旧配方避免重复）。
     */
    private void registerRecipes() {
        if (this.enabled()) {
            if (this.fauna.getBoolean("creature_generator.crafting.generator", true)) {
                NamespacedKey key = new NamespacedKey(this.plugin, "creature_trap_item");

                try {
                    Bukkit.removeRecipe(key);
                } catch (Throwable ignored) {
                }

                ItemStack result = this.createTrapItem(this.defaultUses());
                ShapedRecipe r = new ShapedRecipe(key, result);
                r.shape(new String[]{"SIS", "ITI", "SIS"});
                r.setIngredient('S', Material.STRING);
                r.setIngredient('I', Material.IRON_INGOT);
                r.setIngredient('T', Material.STICK);
                Bukkit.addRecipe(r);
            }
        }
    }

    /**
     * 按权重随机挑选一种生成类型；无权重配置时退回普通随机。
     */
    private EntityType pickWeightedType() {
        if (!this.weightedTypes.isEmpty() && this.weightedTotal > 0) {
            int roll = ThreadLocalRandom.current().nextInt(this.weightedTotal);
            int acc = 0;

            for(WeightedType entry : this.weightedTypes) {
                acc += entry.weight();
                if (roll < acc) {
                    return entry.type();
                }
            }

            return ((WeightedType)this.weightedTypes.get(this.weightedTypes.size() - 1)).type();
        } else {
            List<EntityType> list = this.allowedTypes();
            return (EntityType)list.get(ThreadLocalRandom.current().nextInt(list.size()));
        }
    }

    /**
     * 从配置的 allowed_weighted 段重建加权类型列表与总权重。
     */
    private void rebuildWeightedTypes() {
        ConfigurationSection section = this.fauna.getConfigurationSection("creature_generator.allowed_weighted");
        if (section == null) {
            this.weightedTypes = List.of();
            this.weightedTotal = 0;
        } else {
            List<WeightedType> entries = new ArrayList();
            int total = 0;

            for(String key : section.getKeys(false)) {
                EntityType type;
                try {
                    type = EntityType.valueOf(key.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    continue;
                }

                if (type.isAlive() && type != EntityType.WITHER && type != EntityType.ENDER_DRAGON) {
                    int weight = Math.max(0, section.getInt(key));
                    if (weight > 0 && total <= Integer.MAX_VALUE - weight) {
                        entries.add(new WeightedType(type, weight));
                        total += weight;
                    }
                }
            }

            this.weightedTypes = List.copyOf(entries);
            this.weightedTotal = total;
        }
    }

    /**
     * 创建服务端语言版本的生成器物品。
     */
    public ItemStack createTrapItem(int uses) {
        ItemStack it = new ItemStack(Material.SPAWNER, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            meta.setDisplayName(color(this.plugin.lang.trServer("generator.item.name")));
            List<String> lore = new ArrayList();
            lore.add(color(this.plugin.lang.trServer("generator.item.lore1").replace("{uses}", String.valueOf(uses))));
            lore.add(color(this.plugin.lang.trServer("generator.item.lore2").replace("{mob}", "RANDOM")));
            lore.add(color(this.plugin.lang.trServer("generator.item.lore3")));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte)1);
            meta.getPersistentDataContainer().set(this.usesKey, PersistentDataType.INTEGER, uses);
            it.setItemMeta(meta);
            return it;
        }
    }

    /**
     * 判断物品是否为生成器（刷怪笼且带生成器标记）。
     */
    private boolean isTrapItem(ItemStack it) {
        if (it != null && it.getType() == Material.SPAWNER) {
            ItemMeta meta = it.getItemMeta();
            return meta == null ? false : meta.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE);
        } else {
            return false;
        }
    }

    /**
     * 放置监听：将合法的生成器物品写入刷怪笼方块并登记为已知生成器。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent e) {
        if (this.enabled()) {
            if (this.isTrapItem(e.getItemInHand())) {
                Block b = e.getBlockPlaced();
                if (b.getType() == Material.SPAWNER) {
                    int uses = this.defaultUses();
                    ItemMeta meta = e.getItemInHand().getItemMeta();
                    if (meta != null) {
                        Integer u = (Integer)meta.getPersistentDataContainer().get(this.usesKey, PersistentDataType.INTEGER);
                        if (u != null) {
                            uses = Math.max(1, u);
                        }
                    }

                    BlockState st = b.getState();
                    if (st instanceof TileState) {
                        TileState ts = (TileState)st;
                        PersistentDataContainer pdc = ts.getPersistentDataContainer();
                        pdc.set(this.trapKey, PersistentDataType.BYTE, (byte)1);
                        pdc.set(this.usesKey, PersistentDataType.INTEGER, uses);
                        this.saveInv(pdc, new ItemStack[3]);
                        ts.update(true, false);
                        if (st instanceof CreatureSpawner) {
                            CreatureSpawner sp = (CreatureSpawner)st;

                            try {
                                sp.setSpawnedType(EntityType.AREA_EFFECT_CLOUD);
                            } catch (Exception ignored) {
                                sp.setSpawnedType(EntityType.BAT);
                            }

                            sp.setSpawnCount(0);
                            sp.setSpawnRange(0);
                            sp.setRequiredPlayerRange(0);
                            sp.setMaxNearbyEntities(0);
                            sp.setMaxSpawnDelay(Integer.MAX_VALUE);
                            sp.setMinSpawnDelay(Integer.MAX_VALUE);
                            sp.setDelay(Integer.MAX_VALUE);
                            sp.update(true, false);
                        }

                        this.knownTraps.add(CreatureGeneratorService.TrapKey.of(b));
                        e.getPlayer().sendMessage(color(this.plugin.lang.tr(e.getPlayer(), "generator.msg.placed").replace("{uses}", String.valueOf(uses))));
                    }
                }
            }
        }
    }

    /**
     * 合成结果监听：把生成器物品结果替换为按玩家语言本地化的物品。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        CraftingInventory inventory = e.getInventory();
        if (inventory instanceof CraftingInventory) {
            CraftingInventory ci = inventory;
            ItemStack result = inventory.getResult();
            if (this.isTrapItem(result)) {
                if (!ci.getViewers().isEmpty()) {
                    Object viewer = ci.getViewers().get(0);
                    if (viewer instanceof Player) {
                        Player player = (Player)viewer;
                        ci.setResult(this.createTrapItem(this.defaultUses(), player));
                    }
                }
            }
        }
    }

    /**
     * 创建按指定玩家语言本地化的生成器物品。
     */
    public ItemStack createTrapItem(int uses, Player p) {
        ItemStack it = new ItemStack(Material.SPAWNER, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            meta.setDisplayName(color(this.plugin.lang.tr(p, "generator.item.name")));
            List<String> lore = new ArrayList();
            lore.add(color(this.plugin.lang.tr(p, "generator.item.lore1").replace("{uses}", String.valueOf(uses))));
            lore.add(color(this.plugin.lang.tr(p, "generator.item.lore2").replace("{mob}", "RANDOM")));
            lore.add(color(this.plugin.lang.tr(p, "generator.item.lore3")));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte)1);
            meta.getPersistentDataContainer().set(this.usesKey, PersistentDataType.INTEGER, uses);
            it.setItemMeta(meta);
            return it;
        }
    }

    /**
     * 方块经验监听：生成器方块被破坏时不掉落经验。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onBlockExp(BlockExpEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.SPAWNER) {
            if (this.isTrapBlock(b)) {
                e.setExpToDrop(0);
            }
        }
    }

    /**
     * 刷怪笼生成监听：生成器方块自身的刷怪被取消。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onSpawnerSpawn(SpawnerSpawnEvent e) {
        Block b = e.getSpawner().getBlock();
        if (b.getType() == Material.SPAWNER) {
            if (this.isTrapBlock(b)) {
                e.setCancelled(true);
            }
        }
    }

    /**
     * 破坏监听：回收生成器物品、掉落存储内容并扣减次数。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.SPAWNER) {
            if (this.isTrapBlock(b)) {
                this.knownTraps.remove(CreatureGeneratorService.TrapKey.of(b));
                e.setDropItems(false);
                e.setExpToDrop(0);
                TileState ts = (TileState)b.getState();
                PersistentDataContainer pdc = ts.getPersistentDataContainer();
                ItemStack[] inv = this.loadInv(pdc);
                this.dropStored(b.getLocation(), inv);
                int uses = this.getUses(pdc);
                uses = Math.max(0, uses - 1);
                if (uses <= 0) {
                    e.getPlayer().sendMessage(color(this.plugin.lang.tr(e.getPlayer(), "generator.msg.broken")));
                } else {
                    b.getWorld().dropItemNaturally(b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), this.createTrapItem(uses, e.getPlayer()));
                }
            }
        }
    }

    /**
     * 交互监听：右键生成器打开 GUI；手持刷怪蛋时禁止交互并提示。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent e) {
        if (this.enabled()) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (e.getClickedBlock() != null) {
                    Block b = e.getClickedBlock();
                    if (b.getType() == Material.SPAWNER) {
                        if (this.isTrapBlock(b)) {
                            ItemStack hand = e.getItem();
                            if (hand != null && hand.getType().name().endsWith("_SPAWN_EGG")) {
                                e.setCancelled(true);
                                e.getPlayer().sendMessage(color(this.plugin.lang.tr(e.getPlayer(), "generator.msg.not_allowed")));
                            } else {
                                e.setCancelled(true);
                                this.openGui(e.getPlayer(), b);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 打开生成器的 GUI，展示 3 个存储格位并填充剩余位置。
     */
    private void openGui(Player p, Block b) {
        TileState ts = (TileState)b.getState();
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        Inventory inv = Bukkit.createInventory(new TrapHolder(b.getLocation()), 9, color(this.plugin.lang.tr(p, "generator.gui.title")));
        ItemStack[] stored = this.loadInv(pdc);

        for(int i = 0; i < 3; ++i) {
            inv.setItem(i, stored[i]);
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }

        for(int i = 3; i < 9; ++i) {
            inv.setItem(i, filler);
        }

        p.openInventory(inv);
    }

    /**
     * 生成器 GUI 点击监听：限制存储区与非法操作。
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onGuiClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TrapHolder) {
            if (e.isShiftClick() && e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.PLAYER) {
                e.setCancelled(true);
            } else if (e.getAction() != InventoryAction.HOTBAR_SWAP && e.getAction() != InventoryAction.HOTBAR_MOVE_AND_READD && e.getAction() != InventoryAction.COLLECT_TO_CURSOR) {
                int raw = e.getRawSlot();
                if (raw >= 0) {
                    if (raw < 9) {
                        if (raw >= 3) {
                            e.setCancelled(true);
                            return;
                        }

                        ItemStack cursor = e.getCursor();
                        if (cursor != null && cursor.getType() != Material.AIR) {
                            e.setCancelled(true);
                        }
                    }

                }
            } else {
                e.setCancelled(true);
            }
        }
    }

    /**
     * 生成器 GUI 拖拽监听：禁止拖入物品。
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TrapHolder) {
            for(int slot : e.getRawSlots()) {
                if (slot >= 0 && slot < 9) {
                    e.setCancelled(true);
                    return;
                }
            }

        }
    }

    /**
     * 生成器 GUI 关闭监听：把物品写回方块持久化数据。
     */
    @EventHandler
    public void onGuiClose(InventoryCloseEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof TrapHolder h) {
            Block b = h.loc.getBlock();
            if (b.getType() == Material.SPAWNER) {
                if (this.isTrapBlock(b)) {
                    TileState ts = (TileState)b.getState();
                    PersistentDataContainer pdc = ts.getPersistentDataContainer();
                    ItemStack[] items = new ItemStack[3];

                    for(int i = 0; i < 3; ++i) {
                        ItemStack it = e.getInventory().getItem(i);
                        items[i] = it != null && it.getType() != Material.AIR ? it.clone() : null;
                    }

                    this.saveInv(pdc, items);
                    ts.update(true, false);
                }
            }
        }
    }

    /**
     * 启动生成周期任务与每 tick 的维护任务。
     */
    private void startTask() {
        this.stopTask();
        if (this.enabled()) {
            long period = (long)this.tickSeconds() * 20L;
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::enqueueGenerationCycle, period, period);
            this.maintenanceTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::maintenanceTick, 1L, 1L);
        }
    }

    /**
     * 停止所有任务并清空待处理队列。
     */
    private void stopTask() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

        if (this.maintenanceTask != null) {
            this.maintenanceTask.cancel();
            this.maintenanceTask = null;
        }

        this.pendingTraps.clear();
        this.queuedTraps.clear();
        this.discoveryQueue.clear();
        this.queuedChunks.clear();
    }

    /**
     * 生成周期：把全部已知生成器加入待处理队列。
     */
    private void enqueueGenerationCycle() {
        if (this.enabled()) {
            for(TrapKey key : this.knownTraps) {
                if (this.queuedTraps.add(key)) {
                    this.pendingTraps.addLast(key);
                }
            }

        }
    }

    /**
     * 维护 tick：在时间预算内处理若干生成器与一个区块发现任务。
     */
    private void maintenanceTick() {
        if (this.enabled()) {
            long deadline = System.nanoTime() + 1500000L;

            for(int processed = 0; processed < 8 && !this.pendingTraps.isEmpty() && System.nanoTime() < deadline; ++processed) {
                TrapKey key = (TrapKey)this.pendingTraps.pollFirst();
                this.queuedTraps.remove(key);
                this.processTrap(key);
            }

            if (!this.discoveryQueue.isEmpty() && System.nanoTime() < deadline) {
                ChunkKey key = (ChunkKey)this.discoveryQueue.pollFirst();
                this.queuedChunks.remove(key);
                this.discoverChunk(key);
            }

        }
    }

    /**
     * 把当前所有已加载区块加入发现队列。
     */
    private void enqueueLoadedChunks() {
        for(World world : Bukkit.getWorlds()) {
            for(Chunk chunk : world.getLoadedChunks()) {
                this.enqueueChunk(chunk);
            }
        }

    }

    /**
     * 区块加载监听：把新加载区块加入发现队列。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (this.enabled()) {
            this.enqueueChunk(event.getChunk());
        }

    }

    /**
     * 把单个区块加入去重后的发现队列。
     */
    private void enqueueChunk(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (this.queuedChunks.add(key)) {
            this.discoveryQueue.addLast(key);
        }

    }

    /**
     * 扫描区块内的方块实体，登记带生成器标记的刷怪笼。
     */
    private void discoverChunk(ChunkKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world != null && world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
            Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());

            for(BlockState state : chunk.getTileEntities()) {
                if (state instanceof CreatureSpawner && state instanceof TileState) {
                    TileState tile = (TileState)state;
                    if (tile.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE)) {
                        this.knownTraps.add(CreatureGeneratorService.TrapKey.of(tile.getBlock()));
                    }
                }
            }

        }
    }

    /**
     * 处理单个生成器：校验方块、近玩家条件、剩余次数并产出一枚刷怪蛋。
     */
    private void processTrap(TrapKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            this.knownTraps.remove(key);
        } else if (world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.SPAWNER) {
                this.knownTraps.remove(key);
            } else {
                BlockState state = block.getState();
                if (state instanceof CreatureSpawner && state instanceof TileState) {
                    TileState tile = (TileState)state;
                    PersistentDataContainer pdc = tile.getPersistentDataContainer();
                    if (!pdc.has(this.trapKey, PersistentDataType.BYTE)) {
                        this.knownTraps.remove(key);
                    } else {
                        int range = this.playerRange();
                        int rangeSq = range * range;
                        if (this.requirePlayerNearby()) {
                            boolean near = false;
                            double centerX = (double)key.x() + (double)0.5F;
                            double centerY = (double)key.y() + (double)0.5F;
                            double centerZ = (double)key.z() + (double)0.5F;

                            for(Player player : world.getPlayers()) {
                                Location location = player.getLocation();
                                double dx = location.getX() - centerX;
                                double dy = location.getY() - centerY;
                                double dz = location.getZ() - centerZ;
                                if (dx * dx + dy * dy + dz * dz <= (double)rangeSq) {
                                    near = true;
                                    break;
                                }
                            }

                            if (!near) {
                                return;
                            }
                        }

                        int uses = this.getUses(pdc);
                        if (uses <= 0) {
                            this.breakTrap(block);
                        } else {
                            ItemStack[] inventory = this.loadInv(pdc);
                            int slot = this.firstEmpty(inventory);
                            if (slot != -1) {
                                ItemStack egg = this.createVanillaSpawnEggNoStack(this.pickWeightedType());
                                if (egg != null) {
                                    inventory[slot] = egg;
                                    this.saveInv(pdc, inventory);
                                    --uses;
                                    pdc.set(this.usesKey, PersistentDataType.INTEGER, uses);
                                    tile.update(true, false);
                                    if (uses <= 0) {
                                        this.breakTrap(block);
                                    }

                                }
                            }
                        }
                    }
                } else {
                    this.knownTraps.remove(key);
                }
            }
        }
    }

    /**
     * 遍历所有已加载区块中的生成器并逐一处理（备用全量处理入口）。
     */
    private void tickAllLoadedTraps() {
        if (this.enabled()) {
            List<EntityType> pool = this.allowedTypes();
            if (!pool.isEmpty()) {
                int range = this.playerRange();
                int rangeSq = range * range;

                for(World w : Bukkit.getWorlds()) {
                    for(Chunk c : w.getLoadedChunks()) {
                        for(BlockState st : c.getTileEntities()) {
                            if (st instanceof TileState) {
                                TileState ts = (TileState)st;
                                if (st instanceof CreatureSpawner) {
                                    PersistentDataContainer pdc = ts.getPersistentDataContainer();
                                    if (pdc.has(this.trapKey, PersistentDataType.BYTE)) {
                                        Block b = ts.getBlock();
                                        if (b.getType() == Material.SPAWNER) {
                                            if (this.requirePlayerNearby()) {
                                                boolean near = false;
                                                Location at = b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);

                                                for(Player p : w.getPlayers()) {
                                                    if (p.getLocation().distanceSquared(at) <= (double)rangeSq) {
                                                        near = true;
                                                        break;
                                                    }
                                                }

                                                if (!near) {
                                                    continue;
                                                }
                                            }

                                            int uses = this.getUses(pdc);
                                            if (uses <= 0) {
                                                this.breakTrap(b);
                                            } else {
                                                ItemStack[] inv = this.loadInv(pdc);
                                                int slot = this.firstEmpty(inv);
                                                if (slot != -1) {
                                                    EntityType type = this.pickWeightedType();
                                                    ItemStack egg = this.createVanillaSpawnEggNoStack(type);
                                                    if (egg != null) {
                                                        inv[slot] = egg;
                                                        this.saveInv(pdc, inv);
                                                        --uses;
                                                        pdc.set(this.usesKey, PersistentDataType.INTEGER, uses);
                                                        ts.update(true, false);
                                                        if (uses <= 0) {
                                                            this.breakTrap(b);
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
                }

            }
        }
    }

    /**
     * 返回 3 个存储格位中的首个空位，无空位返回 -1。
     */
    private int firstEmpty(ItemStack[] inv) {
        for(int i = 0; i < 3; ++i) {
            ItemStack it = inv[i];
            if (it == null || it.getType() == Material.AIR) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 创建带唯一 UID 且不堆叠的原版刷怪蛋。
     */
    private ItemStack createVanillaSpawnEggNoStack(EntityType type) {
        Material mat = Material.matchMaterial(type.name() + "_SPAWN_EGG");
        if (mat == null) {
            return null;
        } else {
            ItemStack it = new ItemStack(mat, 1);
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                return it;
            } else {
                meta.getPersistentDataContainer().set(this.eggUidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
                it.setItemMeta(meta);
                return it;
            }
        }
    }

    /**
     * 使生成器碎裂：掉落存储内容、播放音效并通知范围内玩家。
     */
    private void breakTrap(Block b) {
        if (b.getType() == Material.SPAWNER) {
            if (this.isTrapBlock(b)) {
                this.knownTraps.remove(CreatureGeneratorService.TrapKey.of(b));
                TileState ts = (TileState)b.getState();
                PersistentDataContainer pdc = ts.getPersistentDataContainer();
                ItemStack[] inv = this.loadInv(pdc);
                this.dropStored(b.getLocation(), inv);
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.7F, 1.6F);
                b.setType(Material.AIR);
                int range = Math.max(8, this.playerRange());
                int rangeSq = range * range;
                Location at = b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);

                for(Player p : b.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(at) <= (double)rangeSq) {
                        p.sendMessage(color(this.plugin.lang.tr(p, "generator.msg.broken")));
                    }
                }

            }
        }
    }

    /**
     * 在指定位置掉落生成器存储的物品。
     */
    private void dropStored(Location loc, ItemStack[] inv) {
        World w = loc.getWorld();
        if (w != null && inv != null) {
            Location drop = loc.clone().add((double)0.5F, (double)0.5F, (double)0.5F);

            for(int i = 0; i < 3; ++i) {
                ItemStack it = inv[i];
                if (it != null && it.getType() != Material.AIR) {
                    w.dropItemNaturally(drop, it);
                }
            }

        }
    }

    /**
     * 判断方块是否带有生成器标记。
     */
    private boolean isTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            return ts.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE);
        } else {
            return false;
        }
    }

    /**
     * 读取生成器剩余使用次数（缺省为默认值）。
     */
    private int getUses(PersistentDataContainer pdc) {
        Integer v = (Integer)pdc.get(this.usesKey, PersistentDataType.INTEGER);
        return v == null ? this.defaultUses() : Math.max(0, v);
    }

    /**
     * 从持久化数据反序列化 3 格存储物品。
     */
    private ItemStack[] loadInv(PersistentDataContainer pdc) {
        byte[] raw = (byte[])pdc.get(this.invKey, PersistentDataType.BYTE_ARRAY);
        if (raw != null && raw.length != 0) {
            try {
                BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw));

                readBlock: {
                    ItemStack[] loaded;
                    try {
                        Object o = in.readObject();
                        if (!(o instanceof ItemStack[])) {
                            break readBlock;
                        }

                        ItemStack[] arr = (ItemStack[])o;
                        ItemStack[] fixed = new ItemStack[3];

                        for(int i = 0; i < 3; ++i) {
                            fixed[i] = i < arr.length ? arr[i] : null;
                        }

                        loaded = fixed;
                    } catch (Throwable readError) {
                        try {
                            in.close();
                        } catch (Throwable closeError) {
                            readError.addSuppressed(closeError);
                        }

                        throw readError;
                    }

                    in.close();
                    return loaded;
                }

                in.close();
            } catch (Exception ignored) {
            }

            return new ItemStack[3];
        } else {
            return new ItemStack[3];
        }
    }

    /**
     * 把 3 格存储物品序列化写入持久化数据。
     */
    private void saveInv(PersistentDataContainer pdc, ItemStack[] items) {
        ItemStack[] fixed = new ItemStack[3];

        for(int i = 0; i < 3; ++i) {
            fixed[i] = items != null && i < items.length ? items[i] : null;
        }

        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();

            try {
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(bout);

                try {
                    out.writeObject(fixed);
                    out.flush();
                    pdc.set(this.invKey, PersistentDataType.BYTE_ARRAY, bout.toByteArray());
                } catch (Throwable writeError) {
                    try {
                        out.close();
                    } catch (Throwable outCloseError) {
                        writeError.addSuppressed(outCloseError);
                    }

                    throw writeError;
                }

                out.close();
            } catch (Throwable streamError) {
                try {
                    bout.close();
                } catch (Throwable boutCloseError) {
                    streamError.addSuppressed(boutCloseError);
                }

                throw streamError;
            }

            bout.close();
        } catch (Exception ex) {
            this.plugin.getLogger().warning("[CreatureTrap] Failed to save inv: " + ex.getMessage());
        }

    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /**
     * 生成器 GUI 的背包持有者，携带方块位置以便关闭时回写。
     */
    private static final class TrapHolder implements InventoryHolder {
        final Location loc;

        TrapHolder(Location loc) {
            super();
            this.loc = loc;
        }

        public Inventory getInventory() {
            return null;
        }
    }

    /**
     * 生成器的唯一键：世界 UUID 与方块坐标。
     */
    private static record TrapKey(UUID worldId, int x, int y, int z) {
        private TrapKey {
        }

        private static TrapKey of(Block block) {
            return new TrapKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    /**
     * 区块键：世界 UUID 与区块坐标。
     */
    private static record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
        private ChunkKey {
        }
    }

    /**
     * 带权重的生成类型条目。
     */
    private static record WeightedType(EntityType type, int weight) {
        private WeightedType {
        }
    }
}
