package kinkin.aeternum.fauna;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.util.PlatformScheduler;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 鱼陷阱（Fish Trap）服务。
 *
 * <p>以木桶作为“鱼陷阱”方块，玩家放置后可在 GUI 中查看战利品，陷阱会按配置的周期
 * 自动尝试捕捞：当周围有足够的水时，按权重（鱼 / 杂物 / 宝藏）产出物品并放入陷阱格子。
 * 主要行为与依赖：
 * <ul>
 *     <li>通过配方合成“鱼陷阱”物品，并用持久化标记区分普通木桶；</li>
 *     <li>监听放置/破坏/交互/背包事件，维护陷阱数据并防止 GUI 被恶意操作；</li>
 *     <li>陷阱数据持久化到 fish_traps.yml，重启后自动恢复；</li>
 *     <li>依赖 {@link PlatformScheduler} 提供跨平台定时任务。</li>
 * </ul>
 * 陷阱以“世界名:x:y:z”作为唯一标识。</p>
 */
public final class FishTrapService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey trapKey;
    private File faunaFile;
    private FileConfiguration fauna;
    private File dataFile;
    private FileConfiguration data;
    private PlatformScheduler.TaskHandle task;
    private final Map<String, Trap> traps = new ConcurrentHashMap();

    public FishTrapService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.trapKey = new NamespacedKey(plugin, "fish_trap");
    }

    /**
     * 加载配置与数据、注册事件监听、注册配方并启动周期捕捞任务。
     */
    public void register() {
        this.loadFauna();
        this.loadData();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.startTask();
    }

    /**
     * 取消周期任务、保存数据并注销事件监听。
     */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        this.saveData();
        HandlerList.unregisterAll(this);
    }

    /**
     * 重新加载配置、重新注册配方并重启周期任务。
     */
    public void reload() {
        this.loadFauna();
        this.registerRecipe();
        this.startTask();
    }

    /**
     * 确保数据目录存在，并在 fauna.yml 缺失时尝试落盘默认资源或创建空文件。
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
    }

    /**
     * 返回当前加载的 fauna.yml 配置。
     */
    private FileConfiguration fauna() {
        return this.fauna;
    }

    /**
     * 按配置的秒数换算为 tick 周期并（重新）启动全局捕捞任务。
     */
    private void startTask() {
        if (this.task != null) {
            this.task.cancel();
        }

        long seconds = this.fauna().getLong("fish_trap.tick_seconds", 60L);
        long periodTicks = Math.max(20L, seconds * 20L);
        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, periodTicks, periodTicks);
    }

    /**
     * 若启用合成，则注册“鱼陷阱”物品的合成配方（先移除旧配方避免重复）。
     */
    private void registerRecipe() {
        if (this.fauna().getBoolean("fish_trap.crafting", true)) {
            NamespacedKey key = new NamespacedKey(this.plugin, "fish_trap_item");

            try {
                Bukkit.removeRecipe(key);
            } catch (Throwable ignored) {
            }

            ItemStack result = this.createTrapItem();
            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape(new String[]{"S S", "SBS", "S S"});
            recipe.setIngredient('S', Material.STRING);
            recipe.setIngredient('B', Material.BARREL);
            Bukkit.addRecipe(recipe);
        }
    }

    /**
     * 合成结果监听：把鱼陷阱物品的结果替换为按玩家语言本地化的物品。
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
                        Player player = (Player) viewer;
                        ci.setResult(this.createTrapItem(player));
                    }
                }
            }
        }
    }

    /**
     * 创建按指定玩家语言本地化的鱼陷阱物品。
     */
    private ItemStack createTrapItem(Player p) {
        ItemStack it = new ItemStack(Material.BARREL);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        } else {
            meta.setDisplayName(this.plugin.lang.tr(p, "fishtrap.item.name"));
            String loreLine = this.plugin.lang.tr(p, "fishtrap.item.lore");
            if (loreLine != null && !loreLine.isBlank()) {
                meta.setLore(List.of(loreLine));
            }

            meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
            return it;
        }
    }

    /**
     * 创建使用服务端语言（无特定玩家）的鱼陷阱物品。
     */
    private ItemStack createTrapItem() {
        ItemStack it = new ItemStack(Material.BARREL);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(this.plugin.lang.trServer("fishtrap.item.name"));
        String loreLine = this.plugin.lang.trServer("fishtrap.item.lore");
        if (loreLine != null && !loreLine.isBlank()) {
            meta.setLore(List.of(loreLine));
        }

        meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /**
     * 判断物品是否为鱼陷阱（木桶且带陷阱标记）。
     */
    private boolean isTrapItem(ItemStack it) {
        if (it != null && it.getType() == Material.BARREL) {
            ItemMeta meta = it.getItemMeta();
            return meta != null && meta.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE);
        } else {
            return false;
        }
    }

    /**
     * 放置监听：校验相邻水域数量，通过后登记陷阱数据；不足则取消放置并提示。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent e) {
        if (this.fauna().getBoolean("fish_trap.enabled", true)) {
            if (this.isTrapItem(e.getItemInHand())) {
                Block b = e.getBlockPlaced();
                if (b.getType() == Material.BARREL) {
                    int req = this.fauna().getInt("fish_trap.required_adjacent_water", 1);
                    if (this.countAdjacentWater(b) < req) {
                        e.setCancelled(true);
                        e.getPlayer().sendMessage(this.plugin.lang.tr(e.getPlayer(), "fishtrap.msg.not_in_water"));
                    } else {
                        this.markTrapBlock(b);
                        String id = idOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                        this.traps.putIfAbsent(id, new Trap(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), new ItemStack[this.getTrapSlots()]));
                        this.saveTrap(id);
                        e.getPlayer().sendMessage(this.plugin.lang.tr(e.getPlayer(), "fishtrap.msg.placed"));
                    }
                }
            }
        }
    }

    /**
     * 破坏监听：掉落陷阱物品与内部战利品，并删除持久化数据。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.BARREL) {
            if (this.isTrapBlock(b)) {
                String id = idOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                Trap t = (Trap) this.traps.remove(id);
                e.setDropItems(false);
                b.getWorld().dropItemNaturally(b.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), this.createTrapItem(e.getPlayer()));
                if (t != null) {
                    for (ItemStack it : t.items) {
                        if (it != null && it.getType() != Material.AIR) {
                            b.getWorld().dropItemNaturally(b.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F), it);
                        }
                    }
                }

                this.unmarkTrapBlock(b);
                this.data.set("traps." + id, (Object) null);
                this.saveData();
                e.getPlayer().sendMessage(this.plugin.lang.tr(e.getPlayer(), "fishtrap.msg.broken"));
            }
        }
    }

    /**
     * 右键监听：右键鱼陷阱木桶时打开其 GUI。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (e.getClickedBlock() != null) {
                Block b = e.getClickedBlock();
                if (b.getType() == Material.BARREL) {
                    if (this.isTrapBlock(b)) {
                        e.setCancelled(true);
                        this.openGui(e.getPlayer(), b);
                    }
                }
            }
        }
    }

    /**
     * 打开指定鱼陷阱的 GUI，展示其存储的格位并用灰色玻璃板填充剩余位置。
     */
    private void openGui(Player p, Block b) {
        String id = idOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        Trap t = (Trap) this.traps.get(id);
        if (t == null) {
            t = new Trap(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), new ItemStack[this.getTrapSlots()]);
            this.traps.put(id, t);
            this.saveTrap(id);
        }

        this.ensureTrapSize(t);
        int slots = this.getTrapSlots();
        int guiSize = this.getTrapGuiSize();
        Inventory inv = Bukkit.createInventory(new FishTrapHolder(id), guiSize, this.plugin.lang.tr(p, "fishtrap.gui.title"));

        for (int i = 0; i < slots && i < t.items.length; ++i) {
            inv.setItem(i, t.items[i]);
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }

        for (int i = slots; i < guiSize; ++i) {
            inv.setItem(i, filler);
        }

        p.openInventory(inv);
    }

    /**
     * 点击监听：限制鱼陷阱 GUI 的非法操作（填充区、快捷栏交换、移动等）。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onInvClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof FishTrapHolder) {
            int raw = e.getRawSlot();
            int usableSlots = this.getTrapSlots();
            int topSize = e.getInventory().getSize();
            if (raw >= usableSlots && raw < topSize) {
                e.setCancelled(true);
            } else {
                if (raw >= 0 && raw < usableSlots) {
                    ItemStack cursor = e.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        e.setCancelled(true);
                        return;
                    }

                    if (e.getAction() == InventoryAction.HOTBAR_SWAP || e.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD || e.getClick() == ClickType.NUMBER_KEY) {
                        e.setCancelled(true);
                        return;
                    }
                }

                if (e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.PLAYER && (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY || e.getAction() == InventoryAction.HOTBAR_SWAP || e.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD || e.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
                    e.setCancelled(true);
                }

            }
        }
    }

    /**
     * 拖拽监听：禁止将物品拖入鱼陷阱 GUI 的任意格位。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof FishTrapHolder) {
            int topSize = e.getInventory().getSize();

            for (int slot : e.getRawSlots()) {
                if (slot >= 0 && slot < topSize) {
                    e.setCancelled(true);
                    return;
                }
            }

        }
    }

    /**
     * 关闭监听：将 GUI 中的物品写回陷阱数据并持久化。
     */
    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof FishTrapHolder h) {
            Trap t = (Trap) this.traps.get(h.trapId);
            if (t != null) {
                int slots = this.getTrapSlots();
                ItemStack[] newItems = new ItemStack[slots];

                for (int i = 0; i < slots && i < e.getInventory().getSize(); ++i) {
                    ItemStack it = e.getInventory().getItem(i);
                    newItems[i] = it != null && it.getType() != Material.AIR ? it.clone() : null;
                }

                t.items = newItems;
                this.saveTrap(h.trapId);
            }
        }
    }

    /**
     * 漏斗/运输监听：禁止向鱼陷阱木桶中自动输入物品。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onInvMove(InventoryMoveItemEvent e) {
        Inventory dest = e.getDestination();
        if (dest.getType() == InventoryType.BARREL) {
            Location loc = dest.getLocation();
            if (loc != null && loc.getWorld() != null) {
                String id = idOf(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                if (this.traps.containsKey(id)) {
                    e.setCancelled(true);
                }

            }
        }
    }

    /**
     * 周期任务：对每个已登记陷阱尝试捕捞。
     */
    @Override
    public void run() {
        if (this.fauna().getBoolean("fish_trap.enabled", true)) {
            if (!this.traps.isEmpty()) {
                for (Map.Entry<String, Trap> entry : this.traps.entrySet()) {
                    this.tryCapture((String) entry.getKey(), (Trap) entry.getValue());
                }

            }
        }
    }

    /**
     * 读取并限制陷阱可用格位数量（1~54）。
     */
    private int getTrapSlots() {
        int slots = this.fauna().getInt("fish_trap.slots", 6);
        return Math.max(1, Math.min(54, slots));
    }

    /**
     * 依据可用格位推算 GUI 尺寸（9~54，按 9 的整数行向上取整）。
     */
    private int getTrapGuiSize() {
        int slots = this.getTrapSlots();
        int rows = (int) Math.ceil((double) slots / (double) 9.0F);
        return Math.max(9, Math.min(54, rows * 9));
    }

    /**
     * 读取并限制每种鱼的最大堆叠数量（1~64）。
     */
    private int getMaxFishStack() {
        int max = this.fauna().getInt("fish_trap.max_fish_stack", 16);
        return Math.max(1, Math.min(64, max));
    }

    /**
     * 判断物品是否为可堆叠的鱼类战利品。
     */
    private boolean isFishLoot(ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            return item.getType() == Material.COD || item.getType() == Material.SALMON || item.getType() == Material.TROPICAL_FISH || item.getType() == Material.PUFFERFISH;
        } else {
            return false;
        }
    }

    /**
     * 确保陷阱的物品数组长度与当前配置格位数一致，必要时扩容并保留旧内容。
     */
    private void ensureTrapSize(Trap trap) {
        if (trap != null) {
            int slots = this.getTrapSlots();
            if (trap.items == null || trap.items.length != slots) {
                ItemStack[] old = trap.items != null ? trap.items : new ItemStack[0];
                ItemStack[] resized = new ItemStack[slots];
                System.arraycopy(old, 0, resized, 0, Math.min(old.length, resized.length));
                trap.items = resized;
            }
        }
    }

    /**
     * 对单个陷阱尝试捕捞：校验方块仍存在且相邻水域足够，随后掷出战利品并尝试放入。
     */
    private void tryCapture(String id, Trap t) {
        World w = Bukkit.getWorld(t.world);
        if (w != null) {
            int cx = t.x >> 4;
            int cz = t.z >> 4;
            if (w.isChunkLoaded(cx, cz)) {
                Block b = w.getBlockAt(t.x, t.y, t.z);
                if (b.getType() != Material.BARREL) {
                    this.traps.remove(id);
                    this.data.set("traps." + id, (Object) null);
                    this.saveData();
                } else {
                    int req = this.fauna().getInt("fish_trap.required_adjacent_water", 1);
                    if (this.countAdjacentWater(b) >= req) {
                        this.ensureTrapSize(t);
                        ItemStack loot = this.rollLoot();
                        if (this.addLootToTrap(t, loot)) {
                            this.saveTrap(id);
                        }
                    }
                }
            }
        }
    }

    /**
     * 将战利品放入陷阱：鱼类按最大堆叠合并，其余物品放入首个空位；返回是否有放入。
     */
    private boolean addLootToTrap(Trap t, ItemStack loot) {
        if (t != null && loot != null && loot.getType() != Material.AIR) {
            this.ensureTrapSize(t);
            if (this.isFishLoot(loot)) {
                int maxStack = this.getMaxFishStack();
                int remaining = loot.getAmount();

                for (int i = 0; i < t.items.length; ++i) {
                    ItemStack current = t.items[i];
                    if (current != null && current.getType() != Material.AIR && this.isFishLoot(current) && current.isSimilar(loot)) {
                        int space = maxStack - current.getAmount();
                        if (space > 0) {
                            int add = Math.min(space, remaining);
                            current.setAmount(current.getAmount() + add);
                            remaining -= add;
                            if (remaining <= 0) {
                                return true;
                            }
                        }
                    }
                }

                while (remaining > 0) {
                    int empty = this.firstEmptySlot(t.items);
                    if (empty == -1) {
                        return remaining != loot.getAmount();
                    }

                    ItemStack clone = loot.clone();
                    int amount = Math.min(maxStack, remaining);
                    clone.setAmount(amount);
                    t.items[empty] = clone;
                    remaining -= amount;
                }

                return true;
            } else {
                int empty = this.firstEmptySlot(t.items);
                if (empty == -1) {
                    return false;
                } else {
                    t.items[empty] = loot;
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    /**
     * 返回物品数组中的首个空位索引，无空位则返回 -1。
     */
    private int firstEmptySlot(ItemStack[] items) {
        for (int i = 0; i < items.length; ++i) {
            if (items[i] == null || items[i].getType() == Material.AIR) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 按鱼/杂物/宝藏的权重掷出一份战利品。
     */
    private ItemStack rollLoot() {
        int fishW = this.fauna().getInt("fish_trap.weights.fish", 800);
        int junkW = this.fauna().getInt("fish_trap.weights.junk", 180);
        int treaW = this.fauna().getInt("fish_trap.weights.treasure", 20);
        int total = Math.max(1, fishW + junkW + treaW);
        int roll = ThreadLocalRandom.current().nextInt(total);
        if (roll < fishW) {
            return this.rollFish();
        } else {
            roll -= fishW;
            return roll < junkW ? this.rollJunk() : this.rollTreasure();
        }
    }

    /**
     * 随机产出 1~2 条鱼。
     */
    private ItemStack rollFish() {
        Material[] fish = new Material[]{Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH};
        Material material = fish[ThreadLocalRandom.current().nextInt(fish.length)];
        int amount = 1 + ThreadLocalRandom.current().nextInt(2);
        return new ItemStack(material, amount);
    }

    /**
     * 随机产出 1~2 个杂物。
     */
    private ItemStack rollJunk() {
        Material[] junk = new Material[]{Material.STRING, Material.STICK, Material.BONE, Material.LEATHER, Material.KELP, Material.BAMBOO, Material.GLASS_BOTTLE, Material.TRIPWIRE_HOOK};
        Material material = junk[ThreadLocalRandom.current().nextInt(junk.length)];
        int amount = 1 + ThreadLocalRandom.current().nextInt(2);
        return new ItemStack(material, amount);
    }

    /**
     * 按固定概率表产出宝藏类物品。
     */
    private ItemStack rollTreasure() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 35) {
            return new ItemStack(Material.NAUTILUS_SHELL, 1);
        } else if (roll < 60) {
            return new ItemStack(Material.NAME_TAG, 1);
        } else if (roll < 80) {
            return new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + ThreadLocalRandom.current().nextInt(2));
        } else {
            return roll < 90 ? this.rollChainmail() : this.rollEnchantedBook();
        }
    }

    /**
     * 随机产出一件锁链护甲。
     */
    private ItemStack rollChainmail() {
        Material[] pieces = new Material[]{Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS};
        return new ItemStack(pieces[ThreadLocalRandom.current().nextInt(pieces.length)], 1);
    }

    /**
     * 产出带随机附魔的附魔书，等级受配置上限约束。
     */
    private ItemStack rollEnchantedBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        Enchantment ench = this.randomEnchantment();
        int max = Math.max(1, ench.getMaxLevel());
        int cap = Math.max(1, this.fauna().getInt("fish_trap.book_level_cap", 3));
        int lvl = 1 + ThreadLocalRandom.current().nextInt(Math.min(max, cap));
        meta.addStoredEnchant(ench, lvl, true);
        book.setItemMeta(meta);
        return book;
    }

    /**
     * 从全部有效附魔中随机取一个。
     */
    private Enchantment randomEnchantment() {
        List<Enchantment> list = new ArrayList();

        for (Enchantment e : Enchantment.values()) {
            if (e != null) {
                list.add(e);
            }
        }

        return (Enchantment) list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /**
     * 统计目标方块四周（水平四向）的水方块数量。
     */
    private int countAdjacentWater(Block b) {
        int waterCount = 0;
        if (b.getRelative(1, 0, 0).getType() == Material.WATER) {
            ++waterCount;
        }

        if (b.getRelative(-1, 0, 0).getType() == Material.WATER) {
            ++waterCount;
        }

        if (b.getRelative(0, 0, 1).getType() == Material.WATER) {
            ++waterCount;
        }

        if (b.getRelative(0, 0, -1).getType() == Material.WATER) {
            ++waterCount;
        }

        return waterCount;
    }

    /**
     * 在方块持久化数据中打上鱼陷阱标记。
     */
    private void markTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            ts.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte) 1);
            ts.update(true, false);
        }

    }

    /**
     * 移除方块的鱼陷阱标记。
     */
    private void unmarkTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            ts.getPersistentDataContainer().remove(this.trapKey);
            ts.update(true, false);
        }

    }

    /**
     * 判断方块是否带有鱼陷阱标记。
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
     * 从 fish_traps.yml 读取所有陷阱记录并重建内存映射。
     */
    private void loadData() {
        this.dataFile = new File(this.plugin.getDataFolder(), "fish_traps.yml");
        if (!this.dataFile.exists()) {
            try {
                this.plugin.getDataFolder().mkdirs();
                this.dataFile.createNewFile();
            } catch (IOException ignored) {
            }
        }

        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        this.traps.clear();
        ConfigurationSection sec = this.data.getConfigurationSection("traps");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                String base = "traps." + id + ".";
                String world = this.data.getString(base + "world");
                int x = this.data.getInt(base + "x");
                int y = this.data.getInt(base + "y");
                int z = this.data.getInt(base + "z");
                List<?> list = this.data.getList(base + "items", List.of());
                ItemStack[] items = new ItemStack[this.getTrapSlots()];

                for (int i = 0; i < items.length && i < list.size(); ++i) {
                    Object o = list.get(i);
                    if (o instanceof ItemStack) {
                        ItemStack it = (ItemStack) o;
                        items[i] = it;
                    }
                }

                this.traps.put(id, new Trap(world, x, y, z, items));
            }

        }
    }

    /**
     * 将单个陷阱的数据写回配置并保存文件。
     */
    private void saveTrap(String id) {
        Trap t = (Trap) this.traps.get(id);
        if (t != null) {
            String base = "traps." + id + ".";
            this.data.set(base + "world", t.world);
            this.data.set(base + "x", t.x);
            this.data.set(base + "y", t.y);
            this.data.set(base + "z", t.z);
            this.ensureTrapSize(t);
            List<ItemStack> list = new ArrayList();

            for (int i = 0; i < t.items.length; ++i) {
                list.add(t.items[i]);
            }

            this.data.set(base + "items", list);
            this.saveData();
        }
    }

    /**
     * 保存 fish_traps.yml 到磁盘，忽略 IO 异常。
     */
    private void saveData() {
        if (this.data != null && this.dataFile != null) {
            try {
                this.data.save(this.dataFile);
            } catch (IOException ignored) {
            }

        }
    }

    /**
     * 生成陷阱的唯一标识“世界名:x:y:z”。
     */
    private static String idOf(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /**
     * 单个鱼陷阱的运行时数据：世界名、方块坐标与其物品格位。
     */
    private static final class Trap {
        final String world;
        final int x;
        final int y;
        final int z;
        ItemStack[] items;

        Trap(String world, int x, int y, int z, ItemStack[] items) {
            super();
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.items = items;
        }
    }

    /**
     * 鱼陷阱 GUI 的背包持有者，携带陷阱标识以便关闭时回写。
     */
    private static final class FishTrapHolder implements InventoryHolder {
        final String trapId;

        FishTrapHolder(String trapId) {
            super();
            this.trapId = trapId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
