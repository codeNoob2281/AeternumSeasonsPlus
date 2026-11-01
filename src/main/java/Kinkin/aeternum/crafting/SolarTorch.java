package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class SolarTorch implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedTorches = Collections.synchronizedSet(new HashSet());
    private BukkitTask task;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public SolarTorch(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "solar_torch_item");
        this.recipeKey = new NamespacedKey(plugin, "solar_torch");
        this.dataFile = new File(plugin.getDataFolder(), "solar_torch_data.yml");
        this.loadData();
    }

    private void loadData() {
        this.placedTorches.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedTorches.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/solar_torch_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedTorches.addAll(list);
                this.saveData();
            }

        }
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedTorches));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar solar_torch_data.yml: " + ex.getMessage());
        }

    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
        this.startTask();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        this.stopTask();
        Bukkit.removeRecipe(this.recipeKey);
    }

    private void startTask() {
        this.stopTask();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 40L);
    }

    private void stopTask() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

    }

    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.TORCH);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"BBB", " T ", " F "});
        recipe.setIngredient('B', Material.BLAZE_POWDER);
        recipe.setIngredient('T', Material.TORCH);
        recipe.setIngredient('F', Material.SUNFLOWER);
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
                    int blaze = 0;
                    int torch = 0;
                    int flower = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material t = stack.getType();
                            if (t == Material.BLAZE_POWDER) {
                                blaze += stack.getAmount();
                            } else if (t == Material.TORCH) {
                                torch += stack.getAmount();
                            } else {
                                if (t != Material.SUNFLOWER) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                flower += stack.getAmount();
                            }
                        }
                    }

                    if (blaze >= 1 && torch >= 1 && flower >= 1) {
                        Player crafter = null;
                        HumanEntity var21 = e.getView().getPlayer();
                        if (var21 instanceof Player) {
                            Player pl = (Player)var21;
                            crafter = pl;
                        }

                        ItemStack result = new ItemStack(Material.TORCH);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.solar_torch.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.solar_torch.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.solar_torch.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.solar_torch.lore3");
                                List<String> finalLore = new ArrayList(Arrays.asList(lore1, lore2, lore3));
                                finalLore.add(PERSISTENT_LORE_TAG);
                                meta.setDisplayName(name);
                                meta.setLore(finalLore);
                            } catch (Throwable var16) {
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

    private boolean isSolarTorchItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.TORCH) {
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
                    boolean looksLikeSolar = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_ST"))) {
                        looksLikeSolar = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String lore0 = ChatColor.stripColor((String)lore.get(0));
                        String l0 = lore0.toLowerCase(Locale.ROOT);
                        if (l0.contains("solar") || l0.contains("sol")) {
                            looksLikeSolar = true;
                        }
                    }

                    if (looksLikeSolar) {
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
        if (this.isSolarTorchItem(inHand)) {
            Block b = e.getBlockPlaced();
            Material type = b.getType();
            if (type == Material.TORCH || type == Material.WALL_TORCH) {
                this.placedTorches.add(this.blockKey(b));
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
        if (b.getType() == Material.TORCH || b.getType() == Material.WALL_TORCH) {
            String key = this.blockKey(b);
            if (this.placedTorches.remove(key)) {
                e.setDropItems(false);
                this.saveData();
                Player breaker = e.getPlayer();
                ItemStack drop = new ItemStack(Material.TORCH);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.solar_torch.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.solar_torch.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.solar_torch.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.solar_torch.lore3");
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

    public void run() {
        if (!this.placedTorches.isEmpty()) {
            for(String k : new ArrayList<>(this.placedTorches)) {
                String[] parts = k.split(";");
                if (parts.length != 4) {
                    this.placedTorches.remove(k);
                    this.saveData();
                } else {
                    World w = Bukkit.getWorld(parts[0]);
                    if (w == null) {
                        this.placedTorches.remove(k);
                        this.saveData();
                    } else {
                        int x;
                        int y;
                        int z;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                            z = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException var14) {
                            this.placedTorches.remove(k);
                            this.saveData();
                            continue;
                        }

                        Block b = w.getBlockAt(x, y, z);
                        if (!this.isSolarTorchBlock(b)) {
                            this.placedTorches.remove(k);
                            this.saveData();
                        } else {
                            long time = w.getTime();
                            boolean isDay = time >= 0L && time < 12000L;
                            Season season = this.seasons.getStateCopy(w).season;
                            if (season == Season.SUMMER && isDay) {
                                this.boostNearbyCrops(b);
                            }

                            if (!isDay) {
                                this.repelMobs(b);
                            }
                        }
                    }
                }
            }

        }
    }

    private void boostNearbyCrops(Block center) {
        World w = center.getWorld();
        int radius = 4;

        for(int i = 0; i < 10; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            Block soil = w.getBlockAt(center.getX() + dx, center.getY() - 1, center.getZ() + dz);
            Block cropBlock = soil.getRelative(0, 1, 0);
            BlockData var10 = cropBlock.getBlockData();
            if (var10 instanceof Ageable ageable) {
                if (ageable.getAge() < ageable.getMaximumAge() && this.rnd.nextDouble() < 0.35) {
                    ageable.setAge(ageable.getAge() + 1);
                    cropBlock.setBlockData(ageable, false);
                    w.spawnParticle(Particle.HAPPY_VILLAGER, cropBlock.getLocation().add((double)0.5F, 0.4, (double)0.5F), 4, 0.2, 0.2, 0.2);
                }
            }
        }

    }

    private void repelMobs(Block center) {
        World w = center.getWorld();
        int radius = 6;

        for(Entity ent : w.getNearbyEntities(center.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), (double)radius, (double)radius, (double)radius)) {
            if (ent instanceof Monster mob) {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
                if (this.rnd.nextDouble() < 0.1) {
                    mob.damage((double)0.5F, (Entity)null);
                }

                w.spawnParticle(Particle.END_ROD, mob.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    private boolean isSolarTorchBlock(Block b) {
        Material type = b.getType();
        return (type == Material.TORCH || type == Material.WALL_TORCH) && this.placedTorches.contains(this.blockKey(b));
    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_ST";
    }
}
