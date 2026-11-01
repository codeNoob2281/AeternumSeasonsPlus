package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public final class AdvancedComposter implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> advancedComposters = Collections.synchronizedSet(new HashSet());
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;
    private final Map<String, BukkitTask> pendingTransfers = new HashMap();
    private static final Set<Material> COMPOSTABLE;

    public AdvancedComposter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "advanced_composter_item");
        this.recipeKey = new NamespacedKey(plugin, "advanced_composter");
        this.dataFile = new File(plugin.getDataFolder(), "advanced_composter_data.yml");
        this.loadData();
    }

    private void loadData() {
        this.advancedComposters.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.advancedComposters.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/advanced_composter_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.advancedComposters.addAll(list);
                this.saveData();
            }

        }
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.advancedComposters));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar advanced_composter_data.yml: " + ex.getMessage());
        }

    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    public void unregister() {
        this.pendingTransfers.values().forEach(BukkitTask::cancel);
        this.pendingTransfers.clear();
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.COMPOSTER);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"LHL", "PCP", "LHL"});
        RecipeChoice leavesChoice = new RecipeChoice.MaterialChoice(new Material[]{Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES});
        recipe.setIngredient('L', leavesChoice);
        recipe.setIngredient('H', Material.HOPPER);
        recipe.setIngredient('C', Material.COMPOSTER);
        recipe.setIngredient('P', Material.PUMPKIN);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() != null) {
            Recipe var3 = e.getRecipe();
            if (var3 instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe)var3;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = e.getInventory().getMatrix();
                    int composters = 0;
                    int hoppers = 0;
                    int pumpkins = 0;
                    int leaves = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material t = stack.getType();
                            if (t == Material.COMPOSTER) {
                                composters += stack.getAmount();
                            } else if (t == Material.HOPPER) {
                                hoppers += stack.getAmount();
                            } else if (t == Material.PUMPKIN) {
                                pumpkins += stack.getAmount();
                            } else {
                                if (!this.isLeaves(t)) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                leaves += stack.getAmount();
                            }
                        }
                    }

                    if (composters == 1 && hoppers >= 2 && pumpkins >= 2 && leaves >= 4) {
                        Player crafter = null;
                        HumanEntity var22 = e.getView().getPlayer();
                        if (var22 instanceof Player) {
                            Player pl = (Player)var22;
                            crafter = pl;
                        }

                        ItemStack result = new ItemStack(Material.COMPOSTER);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.advanced_composter.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.advanced_composter.lore3");
                                List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                                finalLore.add(PERSISTENT_LORE_TAG);
                                meta.setDisplayName(name);
                                meta.setLore(finalLore);
                            } catch (Throwable var17) {
                            }

                            result.setItemMeta(meta);
                        }

                        e.getInventory().setResult(result);
                    } else {
                        e.getInventory().setResult((ItemStack)null);
                    }
                }
            }
        }
    }

    private boolean isLeaves(Material m) {
        return m.name().endsWith("_LEAVES");
    }

    private boolean isAdvancedComposterItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.COMPOSTER) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Byte flag = (Byte)pdc.get(this.itemTagKey, PersistentDataType.BYTE);
                if (flag != null && flag == 1) {
                    return true;
                } else {
                    String displayRaw = meta.hasDisplayName() ? meta.getDisplayName() : "";
                    String display = ChatColor.stripColor(displayRaw);
                    List<String> lore = meta.getLore() != null ? meta.getLore() : Collections.emptyList();
                    boolean looksLikeAdvanced = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_AC"))) {
                        looksLikeAdvanced = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String lore0 = ChatColor.stripColor((String)lore.get(0));
                        String l0 = lore0.toLowerCase(Locale.ROOT);
                        if (l0.contains("composter") || l0.contains("compostador")) {
                            looksLikeAdvanced = true;
                        }
                    }

                    if (looksLikeAdvanced) {
                        pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);
                        stack.setItemMeta(meta);
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else {
            return false;
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        if (this.isAdvancedComposterItem(inHand)) {
            Block b = e.getBlockPlaced();
            if (b.getType() == Material.COMPOSTER) {
                this.advancedComposters.add(this.blockKey(b));
                this.saveData();
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.COMPOSTER) {
            String key = this.blockKey(b);
            if (this.advancedComposters.remove(key)) {
                e.setDropItems(false);
                this.saveData();
                Player breaker = e.getPlayer();
                ItemStack drop = new ItemStack(Material.COMPOSTER);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.advanced_composter.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.advanced_composter.lore3");
                        List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                        finalLore.add(PERSISTENT_LORE_TAG);
                        meta.setDisplayName(name);
                        meta.setLore(finalLore);
                    } catch (Throwable var13) {
                    }

                    drop.setItemMeta(meta);
                }

                b.getWorld().dropItemNaturally(b.getLocation().add((double)0.5F, 0.1, (double)0.5F), drop);
            }
        }
    }

    private String blockKey(Block b) {
        String var10000 = b.getWorld().getName();
        return var10000 + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean isAdvancedComposterBlock(Block b) {
        return b.getType() == Material.COMPOSTER && this.advancedComposters.contains(this.blockKey(b));
    }

    private Block inventoryBlock(Inventory inventory) {
        Location location = inventory.getLocation();
        return location != null && location.getWorld() != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) ? location.getBlock() : null;
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onHopperTransfer(InventoryMoveItemEvent event) {
        Block destination = this.inventoryBlock(event.getDestination());
        Block source = this.inventoryBlock(event.getSource());
        boolean inserting = destination != null && this.isAdvancedComposterBlock(destination);
        Block composter = inserting ? destination : source;
        if (composter != null && this.isAdvancedComposterBlock(composter)) {
            event.setCancelled(true);
            String key = this.blockKey(composter);
            if (!this.pendingTransfers.containsKey(key)) {
                ItemStack item = event.getItem().clone();
                item.setAmount(1);
                Inventory other = inserting ? event.getSource() : event.getDestination();
                this.pendingTransfers.put(key, Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.pendingTransfers.remove(key);
                    if (composter.getWorld().isChunkLoaded(composter.getX() >> 4, composter.getZ() >> 4) && this.isAdvancedComposterBlock(composter)) {
                        Block otherBlock = this.inventoryBlock(other);
                        if (otherBlock != null) {
                            BlockState patt0$temp = otherBlock.getState();
                            if (patt0$temp instanceof Hopper) {
                                Hopper hopper = (Hopper)patt0$temp;
                                BlockData patt1$temp = otherBlock.getBlockData();
                                if (patt1$temp instanceof org.bukkit.block.data.type.Hopper) {
                                    org.bukkit.block.data.type.Hopper hopperData = (org.bukkit.block.data.type.Hopper)patt1$temp;
                                    if (hopperData.isEnabled()) {
                                        if (inserting && !otherBlock.getRelative(hopperData.getFacing()).equals(composter)) {
                                            return;
                                        }

                                        if (!inserting && !otherBlock.getRelative(BlockFace.UP).equals(composter)) {
                                            return;
                                        }

                                        Inventory inventory = hopper.getInventory();
                                        Levelled data = (Levelled)composter.getBlockData();
                                        boolean autumn = this.seasons.getStateCopy(composter.getWorld()).season == Season.AUTUMN;
                                        if (inserting) {
                                            if (data.getLevel() >= data.getMaximumLevel() || !COMPOSTABLE.contains(item.getType())) {
                                                return;
                                            }

                                            if (!inventory.containsAtLeast(item, 1)) {
                                                return;
                                            }

                                            if (!inventory.removeItem(new ItemStack[]{item.clone()}).isEmpty()) {
                                                return;
                                            }

                                            int increment = (this.tryCompost(item.getType(), autumn) ? 1 : 0) + (this.tryCompost(item.getType(), autumn) ? 1 : 0);
                                            data.setLevel(Math.min(data.getMaximumLevel(), data.getLevel() + increment));
                                        } else {
                                            if (data.getLevel() < data.getMaximumLevel()) {
                                                return;
                                            }

                                            ItemStack output = new ItemStack(Material.BONE_MEAL, this.rnd.nextDouble() < (autumn ? 0.6 : 0.35) ? 2 : 1);
                                            int capacity = 0;

                                            for(ItemStack slot : inventory.getStorageContents()) {
                                                if (slot != null && !slot.getType().isAir()) {
                                                    if (slot.isSimilar(output)) {
                                                        capacity += Math.max(0, Math.min(inventory.getMaxStackSize(), slot.getMaxStackSize()) - slot.getAmount());
                                                    }
                                                } else {
                                                    capacity += Math.min(inventory.getMaxStackSize(), output.getMaxStackSize());
                                                }
                                            }

                                            if (capacity < output.getAmount()) {
                                                return;
                                            }

                                            inventory.addItem(new ItemStack[]{output});
                                            data.setLevel(0);
                                        }

                                        composter.setBlockData(data, true);
                                        return;
                                    }
                                }

                                return;
                            }
                        }

                    }
                }));
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = e.getClickedBlock();
            if (clicked != null) {
                if (this.isAdvancedComposterBlock(clicked)) {
                    e.setCancelled(true);
                    BlockData var4 = clicked.getBlockData();
                    if (var4 instanceof Levelled) {
                        Levelled data = (Levelled)var4;
                        int var12 = data.getLevel();
                        int max = data.getMaximumLevel();
                        Player p = e.getPlayer();
                        CalendarState st = this.seasons.getStateCopy(clicked.getWorld());
                        boolean isAutumn = st.season == Season.AUTUMN;
                        ItemStack hand = e.getItem();
                        Material handType = hand != null ? hand.getType() : Material.AIR;
                        if (var12 >= max) {
                            this.extractBoneMeal(clicked, data, p, isAutumn);
                        } else if (hand != null && handType != Material.AIR) {
                            if (COMPOSTABLE.contains(handType)) {
                                this.consumeOneFromHand(p, e.getHand(), hand);
                                int inc = 0;
                                if (this.tryCompost(handType, isAutumn)) {
                                    ++inc;
                                }

                                if (this.tryCompost(handType, isAutumn)) {
                                    ++inc;
                                }

                                if (inc <= 0) {
                                    clicked.getWorld().spawnParticle(Particle.ASH, clicked.getLocation().add((double)0.5F, 0.8, (double)0.5F), 4, 0.2, 0.2, 0.2);
                                    clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_COMPOSTER_FILL, 0.6F, 0.4F);
                                } else {
                                    var12 += inc;
                                    if (var12 > max) {
                                        var12 = max;
                                    }

                                    data.setLevel(var12);
                                    clicked.setBlockData(data, true);
                                    clicked.getWorld().spawnParticle(Particle.CHERRY_LEAVES, clicked.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), 12, 0.4, 0.3, 0.4);
                                    clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.8F, 1.0F);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void consumeOneFromHand(Player p, EquipmentSlot slot, ItemStack hand) {
        if (hand != null) {
            int amount = hand.getAmount();
            if (amount <= 1) {
                if (slot == EquipmentSlot.HAND) {
                    p.getInventory().setItemInMainHand((ItemStack)null);
                } else if (slot == EquipmentSlot.OFF_HAND) {
                    p.getInventory().setItemInOffHand((ItemStack)null);
                }
            } else {
                hand.setAmount(amount - 1);
                if (slot == EquipmentSlot.HAND) {
                    p.getInventory().setItemInMainHand(hand);
                } else if (slot == EquipmentSlot.OFF_HAND) {
                    p.getInventory().setItemInOffHand(hand);
                }
            }

        }
    }

    private boolean tryCompost(Material m, boolean isAutumn) {
        double base = 0.55;
        if (isAutumn) {
            base += 0.2;
        }

        return this.rnd.nextDouble() < base;
    }

    private void extractBoneMeal(Block composter, Levelled data, Player p, boolean isAutumn) {
        World w = composter.getWorld();
        double extraChance = isAutumn ? 0.6 : 0.35;
        int amount = 1;
        if (this.rnd.nextDouble() < extraChance) {
            ++amount;
        }

        ItemStack boneMeal = new ItemStack(Material.BONE_MEAL, amount);
        w.dropItemNaturally(composter.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), boneMeal);
        data.setLevel(0);
        composter.setBlockData(data, true);
        w.spawnParticle(Particle.CHERRY_LEAVES, composter.getLocation().add((double)0.5F, 1.2, (double)0.5F), 16, (double)0.5F, 0.4, (double)0.5F);
        w.playSound(composter.getLocation(), Sound.BLOCK_COMPOSTER_EMPTY, 0.9F, 1.0F);
    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_AC";
        COMPOSTABLE = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.PUMPKIN_SEEDS, Material.MELON_SEEDS, Material.TORCHFLOWER_SEEDS, Material.PITCHER_POD, Material.WHEAT, Material.BEETROOT, Material.CARROT, Material.POTATO, Material.MELON_SLICE, Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, Material.GRASS_BLOCK, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.PINK_TULIP, Material.WHITE_TULIP, Material.RED_TULIP, Material.ORANGE_TULIP);
    }
}
