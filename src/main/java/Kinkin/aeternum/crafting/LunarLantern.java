package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
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
import org.bukkit.Location;
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

public final class LunarLantern implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedLanterns = Collections.synchronizedSet(new HashSet());
    private BukkitTask task;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public LunarLantern(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "lunar_lantern_item");
        this.recipeKey = new NamespacedKey(plugin, "lunar_lantern");
        this.dataFile = new File(plugin.getDataFolder(), "lunar_lantern_data.yml");
        this.loadData();
    }

    private void loadData() {
        this.placedLanterns.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedLanterns.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/lunar_lantern_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedLanterns.addAll(list);
                this.saveData();
            }

        }
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedLanterns));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar lunar_lantern_data.yml: " + ex.getMessage());
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
        ItemStack dummy = new ItemStack(Material.LANTERN);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"RLR", "RER", "RLR"});
        recipe.setIngredient('L', Material.LANTERN);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('E', Material.ENDER_PEARL);
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
                    int lanternSlots = 0;
                    int redstoneSlots = 0;
                    int pearlSlots = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material t = stack.getType();
                            if (t == Material.LANTERN) {
                                ++lanternSlots;
                            } else if (t == Material.REDSTONE) {
                                ++redstoneSlots;
                            } else {
                                if (t != Material.ENDER_PEARL) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                ++pearlSlots;
                            }
                        }
                    }

                    if (lanternSlots == 2 && redstoneSlots == 6 && pearlSlots == 1) {
                        HumanEntity var21 = e.getView().getPlayer();
                        Player var10000;
                        if (var21 instanceof Player) {
                            Player pl = (Player)var21;
                            var10000 = pl;
                        } else {
                            var10000 = null;
                        }

                        Player crafter = var10000;
                        ItemStack result = new ItemStack(Material.LANTERN);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.lunar_lantern.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.lunar_lantern.lore3");
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

    private boolean isLunarLanternItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.LANTERN) {
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
                    boolean looksLikeLunar = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_LL"))) {
                        looksLikeLunar = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String lore0 = ChatColor.stripColor((String)lore.get(0));
                        String l0 = lore0.toLowerCase(Locale.ROOT);
                        if (l0.contains("lunar") || l0.contains("luna")) {
                            looksLikeLunar = true;
                        }
                    }

                    if (looksLikeLunar) {
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
        if (this.isLunarLanternItem(inHand)) {
            Block b = e.getBlockPlaced();
            if (b.getType() == Material.LANTERN) {
                this.placedLanterns.add(this.blockKey(b));
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
        if (b.getType() == Material.LANTERN) {
            String key = this.blockKey(b);
            if (this.placedLanterns.remove(key)) {
                e.setDropItems(false);
                this.saveData();
                Player breaker = e.getPlayer();
                ItemStack drop = new ItemStack(Material.LANTERN);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.lunar_lantern.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.lunar_lantern.lore3");
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

    private boolean isLunarLanternBlock(Block b) {
        return b.getType() == Material.LANTERN && this.placedLanterns.contains(this.blockKey(b));
    }

    public void run() {
        if (!this.placedLanterns.isEmpty()) {
            for(String k : new ArrayList<>(this.placedLanterns)) {
                String[] parts = k.split(";");
                if (parts.length != 4) {
                    this.placedLanterns.remove(k);
                    this.saveData();
                } else {
                    World w = Bukkit.getWorld(parts[0]);
                    if (w == null) {
                        this.placedLanterns.remove(k);
                        this.saveData();
                    } else {
                        int x;
                        int y;
                        int z;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                            z = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException var13) {
                            this.placedLanterns.remove(k);
                            this.saveData();
                            continue;
                        }

                        Block b = w.getBlockAt(x, y, z);
                        if (b.getType() != Material.LANTERN) {
                            this.placedLanterns.remove(k);
                            this.saveData();
                        } else {
                            long time = w.getTime();
                            boolean isNight = time >= 13000L && time < 23000L;
                            if (!isNight) {
                                this.idleParticles(b);
                            } else {
                                this.nightParticles(b);
                                this.boostNocturnalPlants(b);
                                this.blessNearbyPlayers(b);
                            }
                        }
                    }
                }
            }

        }
    }

    private void idleParticles(Block center) {
        World w = center.getWorld();
        Location loc = center.getLocation().add((double)0.5F, 0.8, (double)0.5F);
        w.spawnParticle(Particle.ENCHANT, loc, 4, 0.15, 0.2, 0.15);
    }

    private void nightParticles(Block center) {
        World w = center.getWorld();
        Location loc = center.getLocation().add((double)0.5F, 0.9, (double)0.5F);
        w.spawnParticle(Particle.SOUL, loc, 8, (double)0.25F, 0.3, (double)0.25F);
        w.spawnParticle(Particle.END_ROD, loc, 4, 0.15, 0.2, 0.15);
    }

    private void boostNocturnalPlants(Block center) {
        World w = center.getWorld();
        int radius = 5;
        int attempts = 30;
        int baseY = center.getY();

        for(int i = 0; i < attempts; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            for(int dy = -3; dy <= 3; ++dy) {
                Block b = w.getBlockAt(x, baseY + dy, z);
                Material type = b.getType();
                if (type == Material.RED_MUSHROOM || type == Material.BROWN_MUSHROOM) {
                    if (this.rnd.nextDouble() < 0.3) {
                        this.spreadMushroom(b);
                    } else {
                        w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, b.getLocation().add((double)0.5F, 0.3, (double)0.5F), 4, 0.2, 0.2, 0.2);
                    }
                    break;
                }

                BlockData var15 = b.getBlockData();
                if (var15 instanceof Ageable ageable) {
                    if (ageable.getAge() < ageable.getMaximumAge()) {
                        if (this.rnd.nextDouble() < 0.35) {
                            ageable.setAge(ageable.getAge() + 1);
                            b.setBlockData(ageable, false);
                            w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, b.getLocation().add((double)0.5F, 0.4, (double)0.5F), 5, 0.2, 0.3, 0.2);
                        }
                        break;
                    }
                }
            }
        }

    }

    private void spreadMushroom(Block source) {
        World w = source.getWorld();
        Material type = source.getType();
        int radius = 2;

        for(int i = 0; i < 6; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            Block ground = source.getRelative(dx, -1, dz);
            Block air = source.getRelative(dx, 0, dz);
            if (air.getType() == Material.AIR) {
                Material gType = ground.getType();
                if (gType == Material.DIRT || gType == Material.GRASS_BLOCK || gType == Material.MYCELIUM || gType == Material.PODZOL || gType == Material.NETHERRACK || gType == Material.CRIMSON_NYLIUM || gType == Material.WARPED_NYLIUM) {
                    air.setType(type, false);
                    w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, air.getLocation().add((double)0.5F, 0.3, (double)0.5F), 6, 0.2, 0.3, 0.2);
                    break;
                }
            }
        }

    }

    private void blessNearbyPlayers(Block center) {
        World w = center.getWorld();
        Location loc = center.getLocation().add((double)0.5F, 0.9, (double)0.5F);
        int radius = 6;

        for(Entity ent : w.getNearbyEntities(loc, (double)radius, (double)radius, (double)radius)) {
            if (ent instanceof Player p) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 120, 0, true, false, true));
                w.spawnParticle(Particle.ENCHANT, p.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_LL";
    }
}
