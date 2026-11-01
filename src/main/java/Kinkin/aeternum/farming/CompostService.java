package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.PlatformScheduler;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class CompostService implements Listener, Runnable {
    private static final long FERMENT_TICKS = 48000L;
    private static final int WATER_RADIUS = 1;
    private static final double LAND_BOOST_CHANCE = 0.45;
    private static final int LAND_MAX_EXTRA_AGE = 1;
    private static final long LAND_COOLDOWN_TICKS = 0L;
    private static final int LAND_MAX_BOOSTS_PER_TICK = 5000;
    private static final int SUGARCANE_MAX_PUSHES_PER_RUN = 10;
    private static final double SUGARCANE_STEP_CHANCE = 0.45;
    private static final long SUGARCANE_COOLDOWN_TICKS = 120L;
    private static final int SUGARCANE_MAX_HEIGHT = 3;
    private final Map<Location, Long> landNextBoostAt = new ConcurrentHashMap();
    private final Map<Location, CompostData> compostBlocks = new ConcurrentHashMap();
    private PlatformScheduler.TaskHandle task;
    private static final Set<Material> LAND_CROPS;
    private static final Set<Material> KELP_TYPES;
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey KEY_STAGE;
    private final NamespacedKey KEY_RECIPE;
    private static final int KELP_MAX_PUSHES_PER_RUN = 10;
    private static final int KELP_MAX_STEPS_PER_PUSH = 1;
    private static final double KELP_STEP_CHANCE = 0.45;
    private static final long KELP_COOLDOWN_TICKS = 120L;
    private final File storageFile;

    public CompostService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.KEY_STAGE = new NamespacedKey(plugin, "compost_stage");
        this.KEY_RECIPE = new NamespacedKey(plugin, "compost_block");
        this.storageFile = new File(plugin.getDataFolder(), "compost-blocks.yml");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.loadState();
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, 100L, 400L);
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.saveState();
        this.compostBlocks.clear();
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;

        for(Map.Entry<Location, CompostData> entry : this.compostBlocks.entrySet()) {
            Location loc = (Location)entry.getKey();
            CompostData data = (CompostData)entry.getValue();
            int var10000 = i++;
            String path = "blocks." + var10000;
            y.set(path + ".world", loc.getWorld().getName());
            y.set(path + ".x", loc.getBlockX());
            y.set(path + ".y", loc.getBlockY());
            y.set(path + ".z", loc.getBlockZ());
            y.set(path + ".stage", data.stage.name());
            y.set(path + ".readyTick", data.readyTick);
        }

        try {
            y.save(this.storageFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[Compost] Could not save compost-blocks.yml: " + ex.getMessage());
        }

    }

    private void loadState() {
        this.compostBlocks.clear();
        if (this.storageFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(this.storageFile);
            ConfigurationSection sec = y.getConfigurationSection("blocks");
            if (sec != null) {
                for(String key : sec.getKeys(false)) {
                    String base = "blocks." + key;
                    String worldName = y.getString(base + ".world");
                    World w = Bukkit.getWorld(worldName);
                    if (w != null) {
                        int x = y.getInt(base + ".x");
                        int yy = y.getInt(base + ".y");
                        int z = y.getInt(base + ".z");
                        String stageStr = y.getString(base + ".stage", "RAW");
                        long readyTick = y.getLong(base + ".readyTick", 0L);
                        if ("READY".equalsIgnoreCase(stageStr)) {
                            stageStr = "READY_LAND";
                        }

                        Stage stage;
                        try {
                            stage = CompostService.Stage.valueOf(stageStr);
                        } catch (IllegalArgumentException var16) {
                            continue;
                        }

                        Location loc = new Location(w, (double)x, (double)yy, (double)z);
                        this.compostBlocks.put(loc, new CompostData(stage, readyTick));
                    }
                }

            }
        }
    }

    private void registerRecipe() {
        ItemStack result = this.createItem(CompostService.Stage.RAW);
        ShapedRecipe recipe = new ShapedRecipe(this.KEY_RECIPE, result);
        recipe.shape(new String[]{"DRR", "WWB", "BBB"});
        recipe.setIngredient('D', Material.DIRT);
        recipe.setIngredient('R', Material.ROTTEN_FLESH);
        recipe.setIngredient('B', Material.BONE_MEAL);
        recipe.setIngredient('W', Material.WHEAT);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createItem(Stage stage) {
        return this.createItem(stage, (Player)null);
    }

    private ItemStack createItem(Stage stage, Player viewer) {
        ItemStack item = new ItemStack(Material.ROOTED_DIRT, 1);
        ItemMeta meta = item.getItemMeta();
        boolean isRaw = stage == CompostService.Stage.RAW;
        String nameKey = isRaw ? "farming.compost.raw_name" : "farming.compost.ready_name";
        String lore1Key = isRaw ? "farming.compost.raw_lore_1" : "farming.compost.ready_lore_1";
        String lore2Key = isRaw ? "farming.compost.raw_lore_2" : "farming.compost.ready_lore_2";
        String name;
        String lore1;
        String lore2;
        if (this.plugin.lang != null) {
            if (viewer != null) {
                name = this.plugin.lang.tr(viewer, nameKey);
                lore1 = this.plugin.lang.tr(viewer, lore1Key);
                lore2 = this.plugin.lang.tr(viewer, lore2Key);
            } else {
                name = this.plugin.lang.trServer(nameKey);
                lore1 = this.plugin.lang.trServer(lore1Key);
                lore2 = this.plugin.lang.trServer(lore2Key);
            }
        } else {
            name = nameKey;
            lore1 = lore1Key;
            lore2 = lore2Key;
        }

        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore1, lore2));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(this.KEY_STAGE, PersistentDataType.STRING, stage.name());
        item.setItemMeta(meta);
        return item;
    }

    private Stage readStage(ItemStack item) {
        if (item == null) {
            return null;
        } else if (!item.hasItemMeta()) {
            return null;
        } else {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            String s = (String)pdc.get(this.KEY_STAGE, PersistentDataType.STRING);
            if (s == null) {
                return null;
            } else if ("READY".equalsIgnoreCase(s)) {
                return CompostService.Stage.READY_LAND;
            } else {
                try {
                    return CompostService.Stage.valueOf(s);
                } catch (IllegalArgumentException var5) {
                    return null;
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent e) {
        Stage stage = this.readStage(e.getItemInHand());
        if (stage != null) {
            Block b = e.getBlockPlaced();
            Location key = this.blockKey(b);
            long ready = 0L;
            if (stage == CompostService.Stage.RAW) {
                b.setType(Material.ROOTED_DIRT);
                ready = b.getWorld().getFullTime() + 48000L;
            } else if (stage == CompostService.Stage.READY_LAND) {
                b.setType(Material.FARMLAND);
            } else {
                b.setType(this.pickReadyWaterMaterial(key));
            }

            this.compostBlocks.put(key, new CompostData(stage, ready));
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add((double)0.5F, 0.6, (double)0.5F), 8, 0.3, 0.3, 0.3, 0.01);
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        this.landNextBoostAt.remove(this.blockKey(b));
        Location key = this.blockKey(b);
        CompostData data = (CompostData)this.compostBlocks.remove(key);
        if (data != null) {
            e.setDropItems(false);
            ItemStack drop = this.createItem(data.stage, e.getPlayer());
            b.getWorld().dropItemNaturally(b.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), drop);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        HumanEntity var3 = e.getView().getPlayer();
        if (var3 instanceof Player p) {
            Recipe recipe = e.getRecipe();
            if (recipe instanceof Keyed keyed) {
                if (keyed.getKey().equals(this.KEY_RECIPE)) {
                    CraftingInventory inv = e.getInventory();
                    inv.setResult(this.createItem(CompostService.Stage.RAW, p));
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onHoe(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (e.getClickedBlock() != null) {
                if (e.getItem() != null) {
                    if (e.getHand() == EquipmentSlot.HAND) {
                        Material tool = e.getItem().getType();
                        if (this.isHoe(tool)) {
                            Block soil = e.getClickedBlock();
                            Location key = this.blockKey(soil);
                            CompostData data = (CompostData)this.compostBlocks.get(key);
                            if (data != null) {
                                if (data.stage == CompostService.Stage.READY_WATER) {
                                    if (!this.isFlooded(key)) {
                                        Block above = soil.getRelative(BlockFace.UP);
                                        if (above.getType().isAir()) {
                                            data.stage = CompostService.Stage.READY_LAND;
                                            soil.setType(Material.FARMLAND, false);
                                            soil.getWorld().playSound(soil.getLocation(), Sound.ITEM_HOE_TILL, 1.0F, 1.0F);
                                            soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, soil.getLocation().add((double)0.5F, 0.8, (double)0.5F), 10, (double)0.25F, 0.2, (double)0.25F, 0.01);
                                            e.setCancelled(true);
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

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onGrow(BlockGrowEvent e) {
        Block crop = e.getBlock();
        Material newType = e.getNewState().getType();
        if (LAND_CROPS.contains(newType)) {
            Block farmland = crop.getRelative(BlockFace.DOWN);
            CompostData data = (CompostData)this.compostBlocks.get(this.blockKey(farmland));
            if (data != null && data.stage == CompostService.Stage.READY_LAND) {
                BlockData bd = e.getNewState().getBlockData();
                if (bd instanceof Ageable) {
                    Ageable age = (Ageable)bd;
                    double multiplier = this.plugin.getConfig().getDouble("farming.fertilized_soil.growth_multiplier", (double)2.0F);
                    if (!(multiplier <= (double)1.0F)) {
                        int curAge = age.getAge();
                        int max = age.getMaximumAge();
                        if (curAge < max) {
                            int extraSteps = (int)(multiplier - (double)1.0F);
                            int newAge = Math.min(curAge + extraSteps, max);
                            if (newAge != curAge) {
                                age.setAge(newAge);
                                e.getNewState().setBlockData(age);
                                if (this.plugin.getConfig().getBoolean("farming.compost.particles.enabled", true)) {
                                    crop.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, crop.getLocation().add((double)0.5F, 0.2, (double)0.5F), 5, 0.2, 0.2, 0.2, 0.02);
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onTrample(EntityChangeBlockEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND) {
            if (e.getTo() == Material.DIRT) {
                CompostData data = (CompostData)this.compostBlocks.get(this.blockKey(e.getBlock()));
                if (data != null && data.stage == CompostService.Stage.READY_LAND) {
                    e.setCancelled(true);
                }
            }
        }
    }

    public void run() {
        if (!this.compostBlocks.isEmpty()) {
            List<Location> locs = new ArrayList(this.compostBlocks.keySet());
            AtomicInteger kelpBudget = new AtomicInteger(10);
            AtomicInteger caneBudget = new AtomicInteger(10);

            for(Location loc : locs) {
                if (loc != null) {
                    PlatformScheduler.executeAtLocation(this.plugin, loc, () -> this.tickOne(loc, kelpBudget, caneBudget));
                }
            }

        }
    }

    private void tickOne(Location loc, AtomicInteger kelpBudget, AtomicInteger caneBudget) {
        CompostData data = (CompostData)this.compostBlocks.get(loc);
        if (data != null) {
            World w = loc.getWorld();
            if (w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                Block b = w.getBlockAt(loc);
                if (b.getType() == Material.AIR) {
                    this.compostBlocks.remove(loc);
                } else if (data.stage == CompostService.Stage.READY_LAND) {
                    if (this.isFlooded(loc)) {
                        data.stage = CompostService.Stage.READY_WATER;
                        b.setType(Material.MUD, false);
                    } else if (b.getType() != Material.FARMLAND) {
                        data.stage = CompostService.Stage.READY_WATER;
                        b.setType(this.pickReadyWaterMaterial(loc), false);
                    } else {
                        Block crop = b.getRelative(BlockFace.UP);
                        if (LAND_CROPS.contains(crop.getType())) {
                            this.tryBoostCrop(crop);
                        }

                    }
                } else if (data.stage == CompostService.Stage.RAW) {
                    if (this.hasWaterNearby(loc)) {
                        w.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add((double)0.5F, 0.6, (double)0.5F), 6, (double)0.25F, (double)0.25F, (double)0.25F, 0.01);
                        if (w.getFullTime() >= data.readyTick) {
                            data.stage = CompostService.Stage.READY_LAND;
                            b.setType(Material.FARMLAND, false);
                            w.playSound(b.getLocation(), Sound.BLOCK_COMPOSTER_READY, 1.0F, 1.0F);
                        }
                    }
                } else {
                    if (data.stage == CompostService.Stage.READY_WATER) {
                        Material want = this.pickReadyWaterMaterial(loc);
                        if (b.getType() != want) {
                            b.setType(want, false);
                        }

                        long now = w.getGameTime();
                        if (this.isFlooded(loc) && kelpBudget.get() > 0 && now >= data.kelpNextPushAt && this.tryPushKelpOnSoil(b)) {
                            kelpBudget.decrementAndGet();
                            data.kelpNextPushAt = now + 120L;
                        }

                        if (want == Material.MUD && caneBudget.get() > 0 && now >= data.caneNextPushAt && this.tryPushSugarCaneOnSoil(b)) {
                            caneBudget.decrementAndGet();
                            data.caneNextPushAt = now + 120L;
                        }
                    }

                }
            }
        }
    }

    private void tryBoostCrop(Block crop) {
        if (!(Math.random() > 0.3)) {
            BlockData bd = crop.getBlockData();
            if (bd instanceof Ageable) {
                Ageable age = (Ageable)bd;
                int max = age.getMaximumAge();
                if (age.getAge() < max) {
                    age.setAge(age.getAge() + 1);
                    crop.setBlockData(age);
                    crop.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, crop.getLocation().add((double)0.5F, 0.3, (double)0.5F), 3, 0.1, 0.1, 0.1, 0.02);
                }

            }
        }
    }

    private boolean tryPushSugarCaneOnSoil(Block soil) {
        Block first = soil.getRelative(BlockFace.UP);
        if (first.getType() != Material.SUGAR_CANE) {
            return false;
        } else {
            Block top = first;
            int height = 1;

            while(top.getRelative(BlockFace.UP).getType() == Material.SUGAR_CANE) {
                top = top.getRelative(BlockFace.UP);
                ++height;
                if (height > 16) {
                    break;
                }
            }

            if (height >= 3) {
                return true;
            } else if (Math.random() > 0.45) {
                return true;
            } else {
                Block above = top.getRelative(BlockFace.UP);
                if (!above.getType().isAir()) {
                    return true;
                } else {
                    above.setType(Material.SUGAR_CANE, true);
                    soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, above.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 4, 0.2, 0.2, 0.2, 0.01);
                    return true;
                }
            }
        }
    }

    private boolean isHoe(Material m) {
        return m == Material.WOODEN_HOE || m == Material.STONE_HOE || m == Material.IRON_HOE || m == Material.GOLDEN_HOE || m == Material.DIAMOND_HOE || m == Material.NETHERITE_HOE;
    }

    private Material pickReadyWaterMaterial(Location soilLoc) {
        if (this.isFlooded(soilLoc)) {
            return Material.MUD;
        } else {
            return this.hasWaterNearby(soilLoc) ? Material.MUD : Material.DIRT;
        }
    }

    private boolean isFlooded(Location soilLoc) {
        World w = soilLoc.getWorld();
        if (w == null) {
            return false;
        } else {
            Block soil = w.getBlockAt(soilLoc);
            Block up = soil.getRelative(BlockFace.UP);
            return this.isWaterColumn(up);
        }
    }

    private boolean hasWaterNearby(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            Block base = w.getBlockAt(loc);
            int radius = this.plugin.getConfig().getInt("farming.fertilized_soil.water_radius", 4);

            for(int dx = -radius; dx <= radius; ++dx) {
                for(int dz = -radius; dz <= radius; ++dz) {
                    for(int dy = -1; dy <= 1; ++dy) {
                        Block b = base.getRelative(dx, dy, dz);
                        Material m = b.getType();
                        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
                            return true;
                        }

                        BlockData bd = b.getBlockData();
                        if (bd instanceof Waterlogged) {
                            Waterlogged wl = (Waterlogged)bd;
                            if (wl.isWaterlogged()) {
                                return true;
                            }
                        }
                    }
                }
            }

            return false;
        }
    }

    private boolean isWaterColumn(Block b) {
        Material m = b.getType();
        if (m != Material.WATER && m != Material.BUBBLE_COLUMN) {
            if (m != Material.KELP && m != Material.KELP_PLANT) {
                if (m != Material.SEAGRASS && m != Material.TALL_SEAGRASS) {
                    BlockData bd = b.getBlockData();
                    boolean var10000;
                    if (bd instanceof Waterlogged) {
                        Waterlogged wl = (Waterlogged)bd;
                        if (wl.isWaterlogged()) {
                            var10000 = true;
                            return var10000;
                        }
                    }

                    var10000 = false;
                    return var10000;
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    private boolean isKelp(Material m) {
        return KELP_TYPES.contains(m);
    }

    private Location blockKey(Block b) {
        return new Location(b.getWorld(), (double)b.getX(), (double)b.getY(), (double)b.getZ());
    }

    private boolean tryPushKelpOnSoil(Block soil) {
        Block first = soil.getRelative(BlockFace.UP);
        Material mFirst = first.getType();
        if (mFirst != Material.KELP && mFirst != Material.KELP_PLANT) {
            return false;
        } else {
            Block top = first;
            int height = 1;

            do {
                Block up = top.getRelative(BlockFace.UP);
                Material mu = up.getType();
                if (mu != Material.KELP && mu != Material.KELP_PLANT) {
                    break;
                }

                top = up;
                ++height;
            } while(height <= 32);

            if (height >= 25) {
                return true;
            } else {
                Block above = top.getRelative(BlockFace.UP);
                if (!this.isWaterColumn(above)) {
                    return true;
                } else if (Math.random() > 0.45) {
                    return true;
                } else {
                    if (top.getType() == Material.KELP) {
                        top.setType(Material.KELP_PLANT, true);
                    }

                    Block next = top.getRelative(BlockFace.UP);
                    if (!this.isWaterColumn(next)) {
                        return true;
                    } else {
                        next.setType(Material.KELP, true);
                        soil.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, next.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 4, 0.2, 0.2, 0.2, 0.01);
                        return true;
                    }
                }
            }
        }
    }

    static {
        LAND_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP, Material.PUMPKIN_STEM, Material.MELON_STEM, Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM, Material.SWEET_BERRY_BUSH);
        KELP_TYPES = EnumSet.of(Material.KELP, Material.KELP_PLANT);
    }

    private static enum Stage {
        RAW,
        READY_LAND,
        READY_WATER;

        private Stage() {
        }

        // $FF: synthetic method
        private static Stage[] $values() {
            return new Stage[]{RAW, READY_LAND, READY_WATER};
        }
    }

    private static final class CompostData {
        Stage stage;
        long readyTick;
        long kelpNextPushAt;
        long caneNextPushAt;

        CompostData(Stage stage, long readyTick) {
            super();
            this.stage = stage;
            this.readyTick = readyTick;
            this.kelpNextPushAt = 0L;
            this.caneNextPushAt = 0L;
        }
    }
}
