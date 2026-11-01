package Kinkin.aeternum.fauna;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.PlatformScheduler;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

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

    public void register() {
        this.loadFauna();
        this.loadData();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.startTask();
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        this.saveData();
        HandlerList.unregisterAll(this);
    }

    public void reload() {
        this.loadFauna();
        this.registerRecipe();
        this.startTask();
    }

    private void loadFauna() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }

        this.faunaFile = new File(this.plugin.getDataFolder(), "fauna.yml");
        if (!this.faunaFile.exists()) {
            try {
                this.plugin.saveResource("fauna.yml", false);
            } catch (IllegalArgumentException var4) {
                try {
                    this.faunaFile.createNewFile();
                } catch (IOException var3) {
                }
            }
        }

        this.fauna = YamlConfiguration.loadConfiguration(this.faunaFile);
    }

    private FileConfiguration fauna() {
        return this.fauna;
    }

    private void startTask() {
        if (this.task != null) {
            this.task.cancel();
        }

        long seconds = this.fauna().getLong("fish_trap.tick_seconds", 60L);
        long periodTicks = Math.max(20L, seconds * 20L);
        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, periodTicks, periodTicks);
    }

    private void registerRecipe() {
        if (this.fauna().getBoolean("fish_trap.crafting", true)) {
            NamespacedKey key = new NamespacedKey(this.plugin, "fish_trap_item");

            try {
                Bukkit.removeRecipe(key);
            } catch (Throwable var4) {
            }

            ItemStack result = this.createTrapItem();
            ShapedRecipe r = new ShapedRecipe(key, result);
            r.shape(new String[]{"S S", "SBS", "S S"});
            r.setIngredient('S', Material.STRING);
            r.setIngredient('B', Material.BARREL);
            Bukkit.addRecipe(r);
        }
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        CraftingInventory var3 = e.getInventory();
        if (var3 instanceof CraftingInventory) {
            CraftingInventory ci = var3;
            ItemStack var6 = var3.getResult();
            if (this.isTrapItem(var6)) {
                if (!ci.getViewers().isEmpty()) {
                    Object var5 = ci.getViewers().get(0);
                    if (var5 instanceof Player) {
                        Player p = (Player)var5;
                        ci.setResult(this.createTrapItem(p));
                    }
                }
            }
        }
    }

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

            meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte)1);
            it.setItemMeta(meta);
            return it;
        }
    }

    private ItemStack createTrapItem() {
        ItemStack it = new ItemStack(Material.BARREL);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(this.plugin.lang.trServer("fishtrap.item.name"));
        String loreLine = this.plugin.lang.trServer("fishtrap.item.lore");
        if (loreLine != null && !loreLine.isBlank()) {
            meta.setLore(List.of(loreLine));
        }

        meta.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isTrapItem(ItemStack it) {
        if (it != null && it.getType() == Material.BARREL) {
            ItemMeta meta = it.getItemMeta();
            return meta == null ? false : meta.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE);
        } else {
            return false;
        }
    }

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

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.BARREL) {
            if (this.isTrapBlock(b)) {
                String id = idOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                Trap t = (Trap)this.traps.remove(id);
                e.setDropItems(false);
                b.getWorld().dropItemNaturally(b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), this.createTrapItem(e.getPlayer()));
                if (t != null) {
                    for(ItemStack it : t.items) {
                        if (it != null && it.getType() != Material.AIR) {
                            b.getWorld().dropItemNaturally(b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), it);
                        }
                    }
                }

                this.unmarkTrapBlock(b);
                this.data.set("traps." + id, (Object)null);
                this.saveData();
                e.getPlayer().sendMessage(this.plugin.lang.tr(e.getPlayer(), "fishtrap.msg.broken"));
            }
        }
    }

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

    private void openGui(Player p, Block b) {
        String id = idOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        Trap t = (Trap)this.traps.get(id);
        if (t == null) {
            t = new Trap(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), new ItemStack[this.getTrapSlots()]);
            this.traps.put(id, t);
            this.saveTrap(id);
        }

        this.ensureTrapSize(t);
        int slots = this.getTrapSlots();
        int guiSize = this.getTrapGuiSize();
        Inventory inv = Bukkit.createInventory(new FishTrapHolder(id), guiSize, this.plugin.lang.tr(p, "fishtrap.gui.title"));

        for(int i = 0; i < slots && i < t.items.length; ++i) {
            inv.setItem(i, t.items[i]);
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }

        for(int i = slots; i < guiSize; ++i) {
            inv.setItem(i, filler);
        }

        p.openInventory(inv);
    }

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

    @EventHandler(
        ignoreCancelled = true
    )
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof FishTrapHolder) {
            int topSize = e.getInventory().getSize();

            for(int slot : e.getRawSlots()) {
                if (slot >= 0 && slot < topSize) {
                    e.setCancelled(true);
                    return;
                }
            }

        }
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        InventoryHolder var3 = e.getInventory().getHolder();
        if (var3 instanceof FishTrapHolder h) {
            Trap t = (Trap)this.traps.get(h.trapId);
            if (t != null) {
                int slots = this.getTrapSlots();
                ItemStack[] newItems = new ItemStack[slots];

                for(int i = 0; i < slots && i < e.getInventory().getSize(); ++i) {
                    ItemStack it = e.getInventory().getItem(i);
                    newItems[i] = it != null && it.getType() != Material.AIR ? it.clone() : null;
                }

                t.items = newItems;
                this.saveTrap(h.trapId);
            }
        }
    }

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

    public void run() {
        if (this.fauna().getBoolean("fish_trap.enabled", true)) {
            if (!this.traps.isEmpty()) {
                for(Map.Entry<String, Trap> entry : this.traps.entrySet()) {
                    this.tryCapture((String)entry.getKey(), (Trap)entry.getValue());
                }

            }
        }
    }

    private int getTrapSlots() {
        int slots = this.fauna().getInt("fish_trap.slots", 6);
        return Math.max(1, Math.min(54, slots));
    }

    private int getTrapGuiSize() {
        int slots = this.getTrapSlots();
        int rows = (int)Math.ceil((double)slots / (double)9.0F);
        return Math.max(9, Math.min(54, rows * 9));
    }

    private int getMaxFishStack() {
        int max = this.fauna().getInt("fish_trap.max_fish_stack", 16);
        return Math.max(1, Math.min(64, max));
    }

    private boolean isFishLoot(ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            return item.getType() == Material.COD || item.getType() == Material.SALMON || item.getType() == Material.TROPICAL_FISH || item.getType() == Material.PUFFERFISH;
        } else {
            return false;
        }
    }

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

    private void tryCapture(String id, Trap t) {
        World w = Bukkit.getWorld(t.world);
        if (w != null) {
            int cx = t.x >> 4;
            int cz = t.z >> 4;
            if (w.isChunkLoaded(cx, cz)) {
                Block b = w.getBlockAt(t.x, t.y, t.z);
                if (b.getType() != Material.BARREL) {
                    this.traps.remove(id);
                    this.data.set("traps." + id, (Object)null);
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

    private boolean addLootToTrap(Trap t, ItemStack loot) {
        if (t != null && loot != null && loot.getType() != Material.AIR) {
            this.ensureTrapSize(t);
            if (this.isFishLoot(loot)) {
                int maxStack = this.getMaxFishStack();
                int remaining = loot.getAmount();

                for(int i = 0; i < t.items.length; ++i) {
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

                while(remaining > 0) {
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

    private int firstEmptySlot(ItemStack[] items) {
        for(int i = 0; i < items.length; ++i) {
            if (items[i] == null || items[i].getType() == Material.AIR) {
                return i;
            }
        }

        return -1;
    }

    private ItemStack rollLoot() {
        int fishW = this.fauna().getInt("fish_trap.weights.fish", 800);
        int junkW = this.fauna().getInt("fish_trap.weights.junk", 180);
        int treaW = this.fauna().getInt("fish_trap.weights.treasure", 20);
        int total = Math.max(1, fishW + junkW + treaW);
        int r = ThreadLocalRandom.current().nextInt(total);
        if (r < fishW) {
            return this.rollFish();
        } else {
            r -= fishW;
            return r < junkW ? this.rollJunk() : this.rollTreasure();
        }
    }

    private ItemStack rollFish() {
        Material[] fish = new Material[]{Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH};
        Material m = fish[ThreadLocalRandom.current().nextInt(fish.length)];
        int amount = 1 + ThreadLocalRandom.current().nextInt(2);
        return new ItemStack(m, amount);
    }

    private ItemStack rollJunk() {
        Material[] junk = new Material[]{Material.STRING, Material.STICK, Material.BONE, Material.LEATHER, Material.KELP, Material.BAMBOO, Material.GLASS_BOTTLE, Material.TRIPWIRE_HOOK};
        Material m = junk[ThreadLocalRandom.current().nextInt(junk.length)];
        int amount = 1 + ThreadLocalRandom.current().nextInt(2);
        return new ItemStack(m, amount);
    }

    private ItemStack rollTreasure() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 35) {
            return new ItemStack(Material.NAUTILUS_SHELL, 1);
        } else if (r < 60) {
            return new ItemStack(Material.NAME_TAG, 1);
        } else if (r < 80) {
            return new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + ThreadLocalRandom.current().nextInt(2));
        } else {
            return r < 90 ? this.rollChainmail() : this.rollEnchantedBook();
        }
    }

    private ItemStack rollChainmail() {
        Material[] pieces = new Material[]{Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS};
        return new ItemStack(pieces[ThreadLocalRandom.current().nextInt(pieces.length)], 1);
    }

    private ItemStack rollEnchantedBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
        Enchantment ench = this.randomEnchantment();
        int max = Math.max(1, ench.getMaxLevel());
        int cap = Math.max(1, this.fauna().getInt("fish_trap.book_level_cap", 3));
        int lvl = 1 + ThreadLocalRandom.current().nextInt(Math.min(max, cap));
        meta.addStoredEnchant(ench, lvl, true);
        book.setItemMeta(meta);
        return book;
    }

    private Enchantment randomEnchantment() {
        List<Enchantment> list = new ArrayList();

        for(Enchantment e : Enchantment.values()) {
            if (e != null) {
                list.add(e);
            }
        }

        return (Enchantment)list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private int countAdjacentWater(Block b) {
        int c = 0;
        if (b.getRelative(1, 0, 0).getType() == Material.WATER) {
            ++c;
        }

        if (b.getRelative(-1, 0, 0).getType() == Material.WATER) {
            ++c;
        }

        if (b.getRelative(0, 0, 1).getType() == Material.WATER) {
            ++c;
        }

        if (b.getRelative(0, 0, -1).getType() == Material.WATER) {
            ++c;
        }

        return c;
    }

    private void markTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            ts.getPersistentDataContainer().set(this.trapKey, PersistentDataType.BYTE, (byte)1);
            ts.update(true, false);
        }

    }

    private void unmarkTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            ts.getPersistentDataContainer().remove(this.trapKey);
            ts.update(true, false);
        }

    }

    private boolean isTrapBlock(Block b) {
        BlockState st = b.getState();
        if (st instanceof TileState ts) {
            return ts.getPersistentDataContainer().has(this.trapKey, PersistentDataType.BYTE);
        } else {
            return false;
        }
    }

    private void loadData() {
        this.dataFile = new File(this.plugin.getDataFolder(), "fish_traps.yml");
        if (!this.dataFile.exists()) {
            try {
                this.plugin.getDataFolder().mkdirs();
                this.dataFile.createNewFile();
            } catch (IOException var14) {
            }
        }

        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        this.traps.clear();
        ConfigurationSection sec = this.data.getConfigurationSection("traps");
        if (sec != null) {
            for(String id : sec.getKeys(false)) {
                String base = "traps." + id + ".";
                String world = this.data.getString(base + "world");
                int x = this.data.getInt(base + "x");
                int y = this.data.getInt(base + "y");
                int z = this.data.getInt(base + "z");
                List<?> list = this.data.getList(base + "items", List.of());
                ItemStack[] items = new ItemStack[this.getTrapSlots()];

                for(int i = 0; i < items.length && i < list.size(); ++i) {
                    Object o = list.get(i);
                    if (o instanceof ItemStack) {
                        ItemStack it = (ItemStack)o;
                        items[i] = it;
                    }
                }

                this.traps.put(id, new Trap(world, x, y, z, items));
            }

        }
    }

    private void saveTrap(String id) {
        Trap t = (Trap)this.traps.get(id);
        if (t != null) {
            String base = "traps." + id + ".";
            this.data.set(base + "world", t.world);
            this.data.set(base + "x", t.x);
            this.data.set(base + "y", t.y);
            this.data.set(base + "z", t.z);
            this.ensureTrapSize(t);
            List<ItemStack> list = new ArrayList();

            for(int i = 0; i < t.items.length; ++i) {
                list.add(t.items[i]);
            }

            this.data.set(base + "items", list);
            this.saveData();
        }
    }

    private void saveData() {
        if (this.data != null && this.dataFile != null) {
            try {
                this.data.save(this.dataFile);
            } catch (IOException var2) {
            }

        }
    }

    private static String idOf(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

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

    private static final class FishTrapHolder implements InventoryHolder {
        final String trapId;

        FishTrapHolder(String trapId) {
            super();
            this.trapId = trapId;
        }

        public Inventory getInventory() {
            return null;
        }
    }
}
