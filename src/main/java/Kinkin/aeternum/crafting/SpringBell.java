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
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BellRingEvent;
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

public final class SpringBell implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey itemTagKey;
    private final NamespacedKey recipeKey;
    private static final String PERSISTENT_LORE_TAG;
    private final Set<String> placedBells = Collections.synchronizedSet(new HashSet());
    private static final long COOLDOWN_MS = 20000L;
    private final Map<String, Long> lastRing = new HashMap();
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final File dataFile;

    public SpringBell(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.itemTagKey = new NamespacedKey(plugin, "spring_bell_item");
        this.recipeKey = new NamespacedKey(plugin, "spring_bell");
        this.dataFile = new File(plugin.getDataFolder(), "spring_bell_data.yml");
        this.loadData();
    }

    private void loadData() {
        this.placedBells.clear();
        if (this.dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.dataFile);
            List<String> list = cfg.getStringList("blocks");
            this.placedBells.addAll(list);
        } else {
            File legacy = new File(this.plugin.getDataFolder(), "data/spring_bell_data.yml");
            if (legacy.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
                List<String> list = cfg.getStringList("blocks");
                this.placedBells.addAll(list);
                this.saveData();
            }

        }
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new ArrayList(this.placedBells));

        try {
            cfg.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[AeternumSeasons] No se pudo guardar spring_bell_data.yml: " + ex.getMessage());
        }

    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.BELL);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"AFA", "FBF", "AFA"});
        recipe.setIngredient('B', Material.BELL);
        recipe.setIngredient('A', Material.AMETHYST_SHARD);
        recipe.setIngredient('F', Material.PINK_PETALS);
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
                    int bells = 0;
                    int shards = 0;
                    int petals = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material t = stack.getType();
                            if (t == Material.BELL) {
                                bells += stack.getAmount();
                            } else if (t == Material.AMETHYST_SHARD) {
                                shards += stack.getAmount();
                            } else {
                                if (t != Material.PINK_PETALS) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                petals += stack.getAmount();
                            }
                        }
                    }

                    if (bells == 1 && shards >= 4 && petals >= 4) {
                        Player crafter = null;
                        HumanEntity var21 = e.getView().getPlayer();
                        if (var21 instanceof Player) {
                            Player pl = (Player)var21;
                            crafter = pl;
                        }

                        ItemStack result = new ItemStack(Material.BELL);
                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.spring_bell.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.spring_bell.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.spring_bell.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.spring_bell.lore3");
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

    private boolean isSpringBellItem(ItemStack stack) {
        if (stack != null && stack.getType() == Material.BELL) {
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
                    boolean looksLikeSpringBell = false;
                    if (lore.stream().anyMatch((line) -> line.contains("AET_CUSTOM_SB"))) {
                        looksLikeSpringBell = true;
                    } else if (!display.isEmpty() && !lore.isEmpty()) {
                        String lore0 = ChatColor.stripColor((String)lore.get(0));
                        String l0 = lore0.toLowerCase(Locale.ROOT);
                        if (l0.contains("spring") || l0.contains("primavera")) {
                            looksLikeSpringBell = true;
                        }
                    }

                    if (looksLikeSpringBell) {
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
        if (this.isSpringBellItem(inHand)) {
            Block b = e.getBlockPlaced();
            if (b.getType() == Material.BELL) {
                this.placedBells.add(this.blockKey(b));
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
        if (b.getType() == Material.BELL) {
            String key = this.blockKey(b);
            if (this.placedBells.remove(key)) {
                this.lastRing.remove(key);
                this.saveData();
                e.setDropItems(false);
                Player breaker = e.getPlayer();
                ItemStack drop = new ItemStack(Material.BELL);
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(this.itemTagKey, PersistentDataType.BYTE, (byte)1);

                    try {
                        String name = this.plugin.lang.tr(breaker, "item.spring_bell.name");
                        String lore1 = this.plugin.lang.tr(breaker, "item.spring_bell.lore1");
                        String lore2 = this.plugin.lang.tr(breaker, "item.spring_bell.lore2");
                        String lore3 = this.plugin.lang.tr(breaker, "item.spring_bell.lore3");
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

    private boolean isSpringBellBlock(Block b) {
        return b.getType() == Material.BELL && this.placedBells.contains(this.blockKey(b));
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBellRing(BellRingEvent e) {
        Block b = e.getBlock();
        if (this.isSpringBellBlock(b)) {
            Entity var4 = e.getEntity();
            if (var4 instanceof Player) {
                Player player = (Player)var4;
                String var10 = this.blockKey(b);
                long now = System.currentTimeMillis();
                Long last = (Long)this.lastRing.get(var10);
                if (last != null && now - last < 20000L) {
                    b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), 8, 0.3, 0.4, 0.3);
                } else {
                    this.lastRing.put(var10, now);
                    CalendarState st = this.seasons.getStateCopy(b.getWorld());
                    boolean isSpring = st.season == Season.SPRING;
                    this.spawnBellParticles(b, isSpring);
                    this.bloomCropsAround(b, isSpring);
                    this.calmBeesAround(b, player, isSpring);
                }
            }
        }
    }

    private void spawnBellParticles(Block bell, boolean isSpring) {
        World w = bell.getWorld();
        Location loc = bell.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F);
        w.spawnParticle(Particle.SOUL, loc, isSpring ? 20 : 12, (double)0.5F, (double)0.5F, (double)0.5F);
        w.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, loc, isSpring ? 14 : 8, 0.4, 0.7, 0.4);
        w.playSound(loc, Sound.BLOCK_BELL_USE, 1.0F, isSpring ? 1.4F : 1.1F);
    }

    private void bloomCropsAround(Block bell, boolean isSpring) {
        World w = bell.getWorld();
        int radius = isSpring ? 8 : 5;
        int attempts = isSpring ? 50 : 30;
        int baseY = bell.getY();

        for(int i = 0; i < attempts; ++i) {
            int dx = this.rnd.nextInt(-radius, radius + 1);
            int dz = this.rnd.nextInt(-radius, radius + 1);
            int x = bell.getX() + dx;
            int z = bell.getZ() + dz;
            boolean upgraded = false;

            for(int dy = -3; dy <= 1; ++dy) {
                Block cropBlock = w.getBlockAt(x, baseY + dy, z);
                BlockData var16 = cropBlock.getBlockData();
                if (var16 instanceof Ageable ageable) {
                    if (ageable.getAge() < ageable.getMaximumAge()) {
                        int step = isSpring ? (this.rnd.nextBoolean() ? 2 : 1) : 1;
                        int newAge = Math.min(ageable.getAge() + step, ageable.getMaximumAge());
                        ageable.setAge(newAge);
                        cropBlock.setBlockData(ageable, false);
                        w.spawnParticle(Particle.SOUL, cropBlock.getLocation().add((double)0.5F, 0.4, (double)0.5F), 6, 0.2, 0.3, 0.2);
                        upgraded = true;
                        break;
                    }
                }
            }

            if (upgraded && this.rnd.nextDouble() < 0.15) {
                Block floor = w.getBlockAt(x, baseY - 1, z);
                w.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, floor.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), 4, 0.2, 0.3, 0.2);
            }
        }

    }

    private void calmBeesAround(Block bell, Player source, boolean isSpring) {
        World w = bell.getWorld();
        Location center = bell.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F);
        int radius = isSpring ? 12 : 8;

        for(Entity ent : w.getNearbyEntities(center, (double)radius, (double)radius, (double)radius)) {
            if (ent instanceof Bee bee) {
                bee.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, isSpring ? 120 : 80, 0, true, false, true));
                bee.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, isSpring ? 120 : 80, 0, true, false, true));
                w.spawnParticle(Particle.SOUL, bee.getLocation().add((double)0.0F, 0.4, (double)0.0F), 6, 0.3, 0.3, 0.3);
            }
        }

        source.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, isSpring ? 200 : 120, 0, true, false, true));
    }

    static {
        PERSISTENT_LORE_TAG = String.valueOf(ChatColor.BLACK) + "AET_CUSTOM_SB";
    }
}
