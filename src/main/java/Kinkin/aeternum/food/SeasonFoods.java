package Kinkin.aeternum.food;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.farming.SeasonalCropGrowthListener;
import Kinkin.aeternum.lang.LanguageManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class SeasonFoods implements Listener {
    private static final String ZOMBIE_DROP_CHANCE_PATH = "season_foods.loot.zombies.drop_chance";
    private static final double DEFAULT_ZOMBIE_DROP_CHANCE = 0.1;
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager lang;
    private final SeasonFoodCropDestructionListener cropDestructionListener;
    private final NamespacedKey FOOD_ID_KEY;
    private final NamespacedKey CROP_VISUAL_LOCATION_KEY;
    private static final String ID_TOMATO = "tomato";
    private static final String ID_ONION = "onion";
    private static final String ID_RICE = "rice";
    private static final String ID_TOMATO_SALAD = "tomato_salad";
    private static final String ID_VEGETABLE_BREAD = "vegetable_bread";
    private static final String ID_MEAT_SANDWICH = "meat_sandwich";
    private static final String ID_BEEF_RICE_STEW = "beef_rice_stew";
    private static final String ID_COFFEE = "coffee";
    private static final String ID_HERBAL_TEA = "herbal_tea";
    private static final String ID_ENERGY_DRINK = "energy_drink";
    private static final String ID_HOT_CHOCOLATE = "hot_chocolate";
    private static final int CMD_TOMATO = 2301;
    private static final int CMD_ONION = 2302;
    private static final int CMD_RICE = 2303;
    private static final int CMD_TOMATO_SALAD = 2304;
    private static final int CMD_VEGETABLE_BREAD = 2305;
    private static final int CMD_MEAT_SANDWICH = 2306;
    private static final int CMD_BEEF_RICE_STEW = 2307;
    private static final int CMD_COFFEE = 2310;
    private static final int CMD_HERBAL_TEA = 2311;
    private static final int CMD_ENERGY_DRINK = 2312;
    private static final int CMD_HOT_CHOCOLATE = 2313;
    private static final int CMD_CROP_DISPLAY_BASE = 990100;
    private static final Set<String> DISH_IDS = new HashSet(Arrays.asList("tomato_salad", "vegetable_bread", "meat_sandwich", "beef_rice_stew", "coffee", "herbal_tea", "energy_drink", "hot_chocolate"));
    private static final String[] RECIPE_KEYS = new String[]{"tomato_salad", "vegetable_bread", "meat_sandwich", "beef_rice_stew", "coffee", "herbal_tea", "energy_drink", "hot_chocolate"};
    private ItemStack tomatoProto;
    private ItemStack onionProto;
    private ItemStack riceProto;
    private final Map<String, String> customCrops = new HashMap();
    private final Set<String> riceBases = Collections.synchronizedSet(new HashSet());
    private final File cropsFile;
    private BukkitTask saveTask;
    private BukkitTask visualRefreshTask;
    private final Map<String, CropVisual> cropVisuals = new HashMap();
    private final Map<UUID, Boolean> bedrockPlayerCache = new HashMap();
    private volatile boolean cropsDirty = false;
    private double zombieDropChance = 0.1;
    private final Map<String, EnumSet<Season>> growthSeasons;
    private static final double CROP_VISUAL_RANGE_SQUARED = (double)16384.0F;
    private static final String[] RICE_VISUAL_MODELS = new String[]{"rice_seedling", "rice_stalk", "rice_young_top", "rice_ripe_top"};

    private void removeRecipes() {
        for(String k : RECIPE_KEYS) {
            Bukkit.removeRecipe(new NamespacedKey(this.plugin, k));
        }

    }

    public SeasonFoods(AeternumSeasonsPlugin plugin, LanguageManager lang) {
        super();
        this.growthSeasons = Map.of("tomato", EnumSet.of(Season.SUMMER, Season.AUTUMN), "onion", EnumSet.of(Season.SPRING, Season.SUMMER), "rice", EnumSet.of(Season.SPRING, Season.SUMMER, Season.AUTUMN));
        this.plugin = plugin;
        this.lang = lang;
        this.cropDestructionListener = new SeasonFoodCropDestructionListener(this);
        this.FOOD_ID_KEY = new NamespacedKey(plugin, "food_id");
        this.CROP_VISUAL_LOCATION_KEY = new NamespacedKey(plugin, "crop_visual_location");
        this.cropsFile = new File(plugin.getDataFolder(), "seasonfoods_crops.yml");
    }

    public void register() {
        this.loadLootConfig();
        this.buildIngredientPrototypes();
        this.registerRecipes();
        this.loadCrops();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        Bukkit.getPluginManager().registerEvents(this.cropDestructionListener, this.plugin);
        this.startVisualRefresh();

        for(Player player : Bukkit.getOnlinePlayers()) {
            this.removeFoodGlint(player);
        }

    }

    private void loadLootConfig() {
        if (this.plugin.cfg != null && this.plugin.cfg.crops != null) {
            double configuredChance = this.plugin.cfg.crops.getDouble("season_foods.loot.zombies.drop_chance", 0.1);
            if (!Double.isFinite(configuredChance)) {
                this.plugin.getLogger().warning("[SeasonFoods] season_foods.loot.zombies.drop_chance must be a finite number. Using 0.1.");
                this.zombieDropChance = 0.1;
            } else {
                this.zombieDropChance = Math.max((double)0.0F, Math.min((double)1.0F, configuredChance));
                if (this.zombieDropChance != configuredChance) {
                    this.plugin.getLogger().warning("[SeasonFoods] season_foods.loot.zombies.drop_chance must be between 0.0 and 1.0. Using " + this.zombieDropChance + ".");
                }

            }
        } else {
            this.zombieDropChance = 0.1;
        }
    }

    public void unregister() {
        if (this.visualRefreshTask != null) {
            this.visualRefreshTask.cancel();
            this.visualRefreshTask = null;
        }

        for(Player player : Bukkit.getOnlinePlayers()) {
            this.restoreRealCropBlocks(player);
        }

        this.removeAllCropVisuals();
        this.bedrockPlayerCache.clear();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(this.cropDestructionListener);
        this.removeRecipes();
        this.saveCropsNow();
        if (this.saveTask != null) {
            this.saveTask.cancel();
            this.saveTask = null;
        }

    }

    private void buildIngredientPrototypes() {
        this.tomatoProto = this.buildIngredient("tomato", Material.BEETROOT_SEEDS, 2301);
        this.onionProto = this.buildIngredient("onion", Material.WHEAT_SEEDS, 2302);
        this.riceProto = this.buildIngredient("rice", Material.BEETROOT_SEEDS, 2303);
    }

    private ItemStack buildIngredient(String id, Material base, int cmd) {
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            String baseKey = "items.food." + id;
            meta.setDisplayName(this.lang.trServer(baseKey + ".name"));
            List<String> lore = new ArrayList();
            lore.add(this.lang.trServer(baseKey + ".lore_1"));
            lore.add(this.lang.trServer(baseKey + ".lore_2"));
            this.appendGrowthLore((Player)null, lore, id);
            meta.setLore(lore);
            meta.setCustomModelData(cmd);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS});
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(this.FOOD_ID_KEY, PersistentDataType.STRING, id);
            if ("tomato".equals(id)) {
                this.configureTomatoFood(meta);
            }

            item.setItemMeta(meta);
            if ("tomato".equals(id)) {
                this.configurePaperConsumable(item);
            }

            return item;
        }
    }

    private void configureTomatoFood(ItemMeta meta) {
        FoodComponent food = meta.getFood();
        food.setNutrition(2);
        food.setSaturation(0.1F);
        this.configureLegacyConsumeTime(food);
        this.configureSpigotConsumable(meta);
        meta.setFood(food);
    }

    private void configureLegacyConsumeTime(FoodComponent food) {
        try {
            FoodComponent.class.getMethod("setEatSeconds", Float.TYPE).invoke(food, 1.6F);
        } catch (NoSuchMethodException var3) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure legacy tomato consumption: " + ((Throwable)ex).getMessage());
        }

    }

    private void configureSpigotConsumable(ItemMeta meta) {
        try {
            Class<?> componentType = Class.forName("org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
            Class<?> animationType = Class.forName("org.bukkit.inventory.meta.components.consumable.ConsumableComponent$Animation");
            Object consumable = ItemMeta.class.getMethod("getConsumable").invoke(meta);
            componentType.getMethod("setConsumeSeconds", Float.TYPE).invoke(consumable, 1.6F);
            Object eatAnimation = animationType.getField("EAT").get((Object)null);
            componentType.getMethod("setAnimation", animationType).invoke(consumable, eatAnimation);
            ItemMeta.class.getMethod("setConsumable", componentType).invoke(meta, consumable);
        } catch (NoSuchMethodException | ClassNotFoundException var6) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure Spigot tomato consumption: " + ((Throwable)ex).getMessage());
        }

    }

    private void configurePaperConsumable(ItemStack item) {
        try {
            Class<?> dataComponentTypes = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            Class<?> valuedType = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
            Class<?> builderType = Class.forName("io.papermc.paper.datacomponent.DataComponentBuilder");
            Class<?> consumableType = Class.forName("io.papermc.paper.datacomponent.item.Consumable");
            Class<?> consumableBuilderType = Class.forName("io.papermc.paper.datacomponent.item.Consumable$Builder");
            Class<?> animationType = Class.forName("io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation");
            Object componentKey = dataComponentTypes.getField("CONSUMABLE").get((Object)null);
            Object builder = consumableType.getMethod("consumable").invoke((Object)null);
            consumableBuilderType.getMethod("consumeSeconds", Float.TYPE).invoke(builder, 1.6F);
            Object eatAnimation = animationType.getField("EAT").get((Object)null);
            consumableBuilderType.getMethod("animation", animationType).invoke(builder, eatAnimation);
            ItemStack.class.getMethod("setData", valuedType, builderType).invoke(item, componentKey, builder);
        } catch (NoSuchMethodException | ClassNotFoundException var11) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure Paper tomato consumption: " + ((Throwable)ex).getMessage());
        }

    }

    private EnumSet<Season> growthSeasonsFor(String cropId) {
        if (cropId == null) {
            return null;
        } else {
            EnumSet<Season> seasons = (EnumSet)this.growthSeasons.get(cropId);
            return seasons == null ? null : EnumSet.copyOf(seasons);
        }
    }

    private void appendGrowthLore(Player p, List<String> lore, String cropId) {
        EnumSet<Season> ss = (EnumSet)this.growthSeasons.get(cropId);
        if (ss != null && !ss.isEmpty()) {
            if (lore != null) {
                String seasonsText = (String)ss.stream().map((s) -> p != null ? this.lang.tr(p, "season." + s.name()) : this.lang.trServer("season." + s.name())).collect(Collectors.joining(", "));
                String template = p != null ? this.lang.tr(p, "items.food.grows_in") : this.lang.trServer("items.food.grows_in");
                String line = template.replace("{seasons}", seasonsText);
                if (!lore.contains(line)) {
                    lore.add(line);
                }
            }
        }
    }

    private void registerRecipes() {
        ItemStack tomatoSalad = this.buildDishBase("tomato_salad", Material.MUSHROOM_STEW, 2304);
        NamespacedKey saladKey = new NamespacedKey(this.plugin, "tomato_salad");
        ShapelessRecipe saladRecipe = new ShapelessRecipe(saladKey, tomatoSalad);
        saladRecipe.addIngredient(Material.BEETROOT_SEEDS);
        saladRecipe.addIngredient(Material.BEETROOT_SEEDS);
        saladRecipe.addIngredient(Material.BOWL);
        Bukkit.addRecipe(saladRecipe);
        ItemStack vegBread = this.buildDishBase("vegetable_bread", Material.BREAD, 2305);
        NamespacedKey vegKey = new NamespacedKey(this.plugin, "vegetable_bread");
        ShapedRecipe vegRecipe = new ShapedRecipe(vegKey, vegBread);
        vegRecipe.shape(new String[]{" T ", "BO ", "   "});
        vegRecipe.setIngredient('B', Material.BREAD);
        vegRecipe.setIngredient('T', Material.BEETROOT_SEEDS);
        vegRecipe.setIngredient('O', Material.WHEAT_SEEDS);
        Bukkit.addRecipe(vegRecipe);
        ItemStack meatSandwich = this.buildDishBase("meat_sandwich", Material.BREAD, 2306);
        NamespacedKey sandwichKey = new NamespacedKey(this.plugin, "meat_sandwich");
        ShapelessRecipe sandwichRecipe = new ShapelessRecipe(sandwichKey, meatSandwich);
        sandwichRecipe.addIngredient(Material.BREAD);
        sandwichRecipe.addIngredient(new RecipeChoice.MaterialChoice(new Material[]{Material.COOKED_BEEF, Material.COOKED_MUTTON, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.COOKED_RABBIT, Material.COOKED_SALMON, Material.COOKED_COD}));
        sandwichRecipe.addIngredient(Material.BEETROOT_SEEDS);
        sandwichRecipe.addIngredient(Material.WHEAT_SEEDS);
        Bukkit.addRecipe(sandwichRecipe);
        ItemStack beefRiceStew = this.buildDishBase("beef_rice_stew", Material.RABBIT_STEW, 2307);
        NamespacedKey stewKey = new NamespacedKey(this.plugin, "beef_rice_stew");
        ShapelessRecipe stewRecipe = new ShapelessRecipe(stewKey, beefRiceStew);
        stewRecipe.addIngredient(Material.BOWL);
        stewRecipe.addIngredient(new RecipeChoice.MaterialChoice(new Material[]{Material.COOKED_BEEF, Material.COOKED_MUTTON, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN}));
        stewRecipe.addIngredient(Material.BEETROOT_SEEDS);
        Bukkit.addRecipe(stewRecipe);
        ItemStack coffee = this.buildDishBase("coffee", Material.HONEY_BOTTLE, 2310);
        NamespacedKey coffeeKey = new NamespacedKey(this.plugin, "coffee");
        ShapelessRecipe coffeeRecipe = new ShapelessRecipe(coffeeKey, coffee);
        coffeeRecipe.addIngredient(Material.GLASS_BOTTLE);
        coffeeRecipe.addIngredient(Material.COCOA_BEANS);
        coffeeRecipe.addIngredient(Material.SUGAR);
        Bukkit.addRecipe(coffeeRecipe);
        ItemStack herbalTea = this.buildDishBase("herbal_tea", Material.HONEY_BOTTLE, 2311);
        NamespacedKey teaKey = new NamespacedKey(this.plugin, "herbal_tea");
        ShapelessRecipe teaRecipe = new ShapelessRecipe(teaKey, herbalTea);
        teaRecipe.addIngredient(Material.GLASS_BOTTLE);
        teaRecipe.addIngredient(new RecipeChoice.MaterialChoice(new Material[]{Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.BLUE_ORCHID, Material.ALLIUM, Material.CORNFLOWER, Material.OXEYE_DAISY}));
        Bukkit.addRecipe(teaRecipe);
        ItemStack energyDrink = this.buildDishBase("energy_drink", Material.HONEY_BOTTLE, 2312);
        NamespacedKey energyKey = new NamespacedKey(this.plugin, "energy_drink");
        ShapelessRecipe energyRecipe = new ShapelessRecipe(energyKey, energyDrink);
        energyRecipe.addIngredient(Material.GLASS_BOTTLE);
        energyRecipe.addIngredient(Material.SUGAR);
        energyRecipe.addIngredient(Material.REDSTONE);
        energyRecipe.addIngredient(Material.GLOWSTONE_DUST);
        Bukkit.addRecipe(energyRecipe);
        ItemStack hotChocolate = this.buildDishBase("hot_chocolate", Material.HONEY_BOTTLE, 2313);
        NamespacedKey chocoKey = new NamespacedKey(this.plugin, "hot_chocolate");
        ShapelessRecipe chocoRecipe = new ShapelessRecipe(chocoKey, hotChocolate);
        chocoRecipe.addIngredient(Material.GLASS_BOTTLE);
        chocoRecipe.addIngredient(Material.COCOA_BEANS);
        chocoRecipe.addIngredient(Material.MILK_BUCKET);
        Bukkit.addRecipe(chocoRecipe);
    }

    private ItemStack buildDishBase(String id, Material base, int cmd) {
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            String baseKey = "items.food." + id;
            meta.setDisplayName(this.lang.trServer(baseKey + ".name"));
            meta.setLore(Arrays.asList(this.lang.trServer(baseKey + ".lore_1"), this.lang.trServer(baseKey + ".lore_2")));
            meta.setCustomModelData(cmd);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS});
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(this.FOOD_ID_KEY, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
            return item;
        }
    }

    private String getFoodId(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return null;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                return (String)pdc.get(this.FOOD_ID_KEY, PersistentDataType.STRING);
            }
        } else {
            return null;
        }
    }

    private void applyDishLocalization(Player p, ItemStack item, String foodId) {
        if (foodId != null && item != null && DISH_IDS.contains(foodId)) {
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.removeEnchant(Enchantment.LURE);
                    String baseKey = "items.food." + foodId;
                    meta.setDisplayName(this.lang.tr(p, baseKey + ".name"));
                    meta.setLore(Arrays.asList(this.lang.tr(p, baseKey + ".lore_1"), this.lang.tr(p, baseKey + ".lore_2")));
                    item.setItemMeta(meta);
                }
            }
        }
    }

    private String locKey(Block b) {
        String var10000 = b.getWorld().getName();
        return var10000 + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private void loadCrops() {
        try {
            this.customCrops.clear();
            this.riceBases.clear();
            if (!this.cropsFile.exists()) {
                return;
            }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(this.cropsFile);
            ConfigurationSection cc = cfg.getConfigurationSection("custom_crops");
            if (cc != null) {
                for(String k : cc.getKeys(false)) {
                    String id = cc.getString(k);
                    if (id != null) {
                        this.customCrops.put(k, id);
                    }
                }
            }

            List<String> rb = cfg.getStringList("rice_bases");
            if (rb != null && !rb.isEmpty()) {
                this.riceBases.addAll(rb);
            }
        } catch (Exception ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not load seasonfoods_crops.yml: " + ex.getMessage());
        }

    }

    private void markCropsDirty() {
        this.cropsDirty = true;
        if (this.saveTask == null) {
            this.saveTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                this.saveTask = null;
                this.saveCropsNow();
            }, 100L);
        }
    }

    private void saveCropsNow() {
        if (this.cropsDirty) {
            try {
                this.plugin.getDataFolder().mkdirs();
                YamlConfiguration cfg = new YamlConfiguration();

                for(Map.Entry<String, String> e : this.customCrops.entrySet()) {
                    cfg.set("custom_crops." + (String)e.getKey(), e.getValue());
                }

                cfg.set("rice_bases", new ArrayList(this.riceBases));
                cfg.save(this.cropsFile);
                this.cropsDirty = false;
            } catch (IOException ex) {
                this.plugin.getLogger().warning("[SeasonFoods] Could not save seasonfoods_crops.yml: " + ex.getMessage());
                this.cropsDirty = true;
            }

        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        HumanEntity var3 = e.getWhoClicked();
        if (var3 instanceof Player p) {
            ItemStack result = e.getCurrentItem();
            if (result != null) {
                String foodId = this.getFoodId(result);
                if (foodId != null) {
                    this.applyDishLocalization(p, result, foodId);
                }
            }
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        HumanEntity var3 = e.getView().getPlayer();
        if (var3 instanceof Player p) {
            CraftingInventory inv = e.getInventory();
            ItemStack result = inv.getResult();
            if (result != null) {
                String resultId = this.getFoodId(result);
                if (resultId != null) {
                    ItemStack[] matrix = inv.getMatrix();
                    switch (resultId) {
                        case "tomato_salad":
                            int tomatoCount = 0;
                            int bowls = 0;
                            ItemStack[] var28 = matrix;
                            int var31 = matrix.length;
                            int var34 = 0;

                            for(; var34 < var31; ++var34) {
                                ItemStack stack = var28[var34];
                                if (stack != null && stack.getType() != Material.AIR) {
                                    if (stack.getType() == Material.BOWL) {
                                        ++bowls;
                                        if (bowls > 1) {
                                            inv.setResult((ItemStack)null);
                                            return;
                                        }
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if (!"tomato".equals(id)) {
                                            inv.setResult((ItemStack)null);
                                            return;
                                        }

                                        tomatoCount += stack.getAmount();
                                    }
                                }
                            }

                            if (bowls != 1 || tomatoCount < 2) {
                                inv.setResult((ItemStack)null);
                                return;
                            }

                            this.applyDishLocalization(p, result, "tomato_salad");
                            inv.setResult(result);
                            break;
                        case "vegetable_bread":
                            int bread = 0;
                            int tomato = 0;
                            int onion = 0;

                            for(ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material t = stack.getType();
                                    if (t == Material.BREAD) {
                                        ++bread;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if ("tomato".equals(id)) {
                                            ++tomato;
                                        } else {
                                            if (!"onion".equals(id)) {
                                                inv.setResult((ItemStack)null);
                                                return;
                                            }

                                            ++onion;
                                        }
                                    }
                                }
                            }

                            if (bread != 1 || tomato < 1 || onion < 1) {
                                inv.setResult((ItemStack)null);
                                return;
                            }

                            this.applyDishLocalization(p, result, "vegetable_bread");
                            inv.setResult(result);
                            break;
                        case "meat_sandwich":
                            int msBread = 0;
                            int msTomato = 0;
                            int msOnion = 0;
                            int msMeat = 0;

                            for(ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material t = stack.getType();
                                    if (t == Material.BREAD) {
                                        ++msBread;
                                    } else if (this.isCookedMeat(t)) {
                                        ++msMeat;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if ("tomato".equals(id)) {
                                            ++msTomato;
                                        } else {
                                            if (!"onion".equals(id)) {
                                                inv.setResult((ItemStack)null);
                                                return;
                                            }

                                            ++msOnion;
                                        }
                                    }
                                }
                            }

                            if (msBread != 1 || msTomato < 1 || msOnion < 1 || msMeat < 1) {
                                inv.setResult((ItemStack)null);
                                return;
                            }

                            this.applyDishLocalization(p, result, "meat_sandwich");
                            inv.setResult(result);
                            break;
                        case "beef_rice_stew":
                            int bowl = 0;
                            int rice = 0;
                            int stewMeat = 0;

                            for(ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material t = stack.getType();
                                    if (t == Material.BOWL) {
                                        ++bowl;
                                    } else if (this.isCookedMeat(t)) {
                                        ++stewMeat;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if (!"rice".equals(id)) {
                                            inv.setResult((ItemStack)null);
                                            return;
                                        }

                                        ++rice;
                                    }
                                }
                            }

                            if (bowl < 1 || rice < 1 || stewMeat < 1) {
                                inv.setResult((ItemStack)null);
                                return;
                            }

                            this.applyDishLocalization(p, result, "beef_rice_stew");
                            inv.setResult(result);
                            break;
                        default:
                            this.applyDishLocalization(p, result, resultId);
                            inv.setResult(result);
                    }

                }
            }
        }
    }

    private boolean isCookedMeat(Material m) {
        return m == Material.COOKED_BEEF || m == Material.COOKED_MUTTON || m == Material.COOKED_PORKCHOP || m == Material.COOKED_CHICKEN || m == Material.COOKED_RABBIT || m == Material.COOKED_SALMON || m == Material.COOKED_COD;
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onConsumeFood(PlayerItemConsumeEvent e) {
        String foodId = this.getFoodId(e.getItem());
        if ("tomato".equals(foodId)) {
            if (ThreadLocalRandom.current().nextDouble() < 0.1) {
                e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 200, 0, false, true, true));
            }

        } else if (foodId != null && DISH_IDS.contains(foodId)) {
            Player p = e.getPlayer();
            int targetFood = Math.min(20, p.getFoodLevel() + this.foodPoints(foodId));
            float targetSaturation = Math.min(20.0F, p.getSaturation() + this.saturationPoints(foodId));
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (p.isOnline()) {
                    p.setFoodLevel(targetFood);
                    p.setSaturation(Math.min((float)targetFood, targetSaturation));
                    this.applyConsumedFoodEffects(p, foodId);
                }
            });
        }
    }

    private int foodPoints(String foodId) {
        byte var10000;
        switch (foodId) {
            case "tomato_salad":
                var10000 = 4;
                break;
            case "vegetable_bread":
                var10000 = 7;
                break;
            case "meat_sandwich":
                var10000 = 8;
                break;
            case "beef_rice_stew":
                var10000 = 10;
                break;
            case "coffee":
            case "herbal_tea":
                var10000 = 3;
                break;
            case "energy_drink":
                var10000 = 2;
                break;
            case "hot_chocolate":
                var10000 = 5;
                break;
            default:
                var10000 = 0;
        }

        return var10000;
    }

    private float saturationPoints(String foodId) {
        float var10000;
        switch (foodId) {
            case "tomato_salad":
                var10000 = 4.0F;
                break;
            case "vegetable_bread":
                var10000 = 6.0F;
                break;
            case "meat_sandwich":
                var10000 = 7.0F;
                break;
            case "beef_rice_stew":
                var10000 = 8.0F;
                break;
            case "coffee":
            case "herbal_tea":
                var10000 = 3.0F;
                break;
            case "energy_drink":
                var10000 = 2.0F;
                break;
            case "hot_chocolate":
                var10000 = 5.0F;
                break;
            default:
                var10000 = 0.0F;
        }

        return var10000;
    }

    private void applyConsumedFoodEffects(Player p, String foodId) {
        switch (foodId) {
            case "tomato_salad" -> this.eatTomatoSalad(p);
            case "vegetable_bread" -> this.eatVegetableBread(p);
            case "meat_sandwich" -> this.eatMeatSandwich(p);
            case "beef_rice_stew" -> this.eatBeefRiceStew(p);
            case "coffee" -> this.drinkCoffee(p);
            case "herbal_tea" -> this.drinkHerbalTea(p);
            case "energy_drink" -> this.drinkEnergy(p);
            case "hot_chocolate" -> this.drinkHotChocolate(p);
        }

    }

    private void consumeOneFromHand(Player p, ItemStack inHand) {
        int amount = inHand.getAmount();
        if (amount <= 1) {
            p.getInventory().setItemInMainHand((ItemStack)null);
        } else {
            inHand.setAmount(amount - 1);
        }

    }

    private void eatTomatoSalad(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 0, false, true, true));
    }

    private void eatVegetableBread(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 0, false, true, true));
    }

    private void eatMeatSandwich(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 160, 0, false, true, true));
    }

    private void eatBeefRiceStew(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 60, 0, false, true, true));
    }

    private void drinkCoffee(Player p) {
        p.setFreezeTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 900, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 1200, 0, false, true, true));
    }

    private void drinkHerbalTea(Player p) {
        p.setFreezeTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 600, 0, false, true, true));
    }

    private void drinkEnergy(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 500, 1, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 500, 1, false, true, true));
    }

    private void drinkHotChocolate(Player p) {
        p.setFreezeTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 400, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 600, 1, false, true, true));
    }

    @EventHandler
    public void onLootGenerate(LootGenerateEvent e) {
        LootTable table = e.getLootTable();
        if (table != null) {
            NamespacedKey key = table.getKey();
            if (key != null) {
                String path = key.getKey();
                if (VillageFoodLoot.matches(key)) {
                    List<ItemStack> loot = e.getLoot();
                    Random rnd = ThreadLocalRandom.current();
                    Entity var9 = e.getEntity();
                    Player var10000;
                    if (var9 instanceof Player) {
                        Player pl = (Player)var9;
                        var10000 = pl;
                    } else {
                        var10000 = null;
                    }

                    Player p = var10000;
                    if (rnd.nextDouble() < 0.35) {
                        ItemStack t = this.tomatoProto.clone();
                        this.applyFoodLocalization(p, t, "tomato");
                        loot.add(t);
                    }

                    if (rnd.nextDouble() < 0.35) {
                        ItemStack o = this.onionProto.clone();
                        this.applyFoodLocalization(p, o, "onion");
                        loot.add(o);
                    }

                    if (rnd.nextDouble() < 0.3) {
                        ItemStack r = this.riceProto.clone();
                        this.applyFoodLocalization(p, r, "rice");
                        loot.add(r);
                    }

                }
            }
        }
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent e) {
        LivingEntity entity = e.getEntity();
        EntityType type = entity.getType();
        if (type == EntityType.ZOMBIE || type == EntityType.HUSK || type == EntityType.DROWNED) {
            Random rnd = ThreadLocalRandom.current();
            if (!(rnd.nextDouble() >= this.zombieDropChance)) {
                int roll = rnd.nextInt(3);
                String foodId;
                ItemStack drop;
                if (roll == 0) {
                    drop = this.tomatoProto.clone();
                    foodId = "tomato";
                } else if (roll == 1) {
                    drop = this.onionProto.clone();
                    foodId = "onion";
                } else {
                    drop = this.riceProto.clone();
                    foodId = "rice";
                }

                Player killer = entity.getKiller();
                this.applyFoodLocalization(killer, drop, foodId);
                drop.setAmount(1);
                e.getDrops().add(drop);
            }
        }
    }

    private void startVisualRefresh() {
        if (this.visualRefreshTask != null) {
            this.visualRefreshTask.cancel();
        }

        this.visualRefreshTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            this.pruneInvalidCropVisuals();

            for(Player player : Bukkit.getOnlinePlayers()) {
                this.refreshCropVisuals(player);
            }

        }, 20L, 40L);
    }

    @EventHandler
    public void onCropVisualJoin(PlayerJoinEvent e) {
        this.removeFoodGlint(e.getPlayer());
        this.bedrockPlayerCache.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshCropVisuals(e.getPlayer()), 10L);
    }

    @EventHandler
    public void onCropVisualQuit(PlayerQuitEvent e) {
        this.bedrockPlayerCache.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCropVisualWorldChange(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshCropVisuals(e.getPlayer()), 2L);
    }

    @EventHandler
    public void onCropVisualMove(PlayerMoveEvent e) {
        if (e.getTo() != null) {
            if (e.getFrom().getBlockX() >> 4 != e.getTo().getBlockX() >> 4 || e.getFrom().getBlockZ() >> 4 != e.getTo().getBlockZ() >> 4 || !e.getFrom().getWorld().equals(e.getTo().getWorld())) {
                Bukkit.getScheduler().runTask(this.plugin, () -> this.refreshCropVisuals(e.getPlayer()));
            }
        }
    }

    @EventHandler
    public void onCustomCropGrow(BlockGrowEvent e) {
        Block changed = e.getBlock();
        String cropId = (String)this.customCrops.get(this.locKey(changed));
        Material newType = e.getNewState().getType();
        if (cropId != null || newType == Material.KELP || newType == Material.KELP_PLANT) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.refreshVisualAt(changed));
        }

    }

    @EventHandler
    public void onCustomCropFertilize(BlockFertilizeEvent e) {
        List<Block> affectedCustomCrops = new ArrayList();
        e.getBlocks().forEach((state) -> {
            Block block = state.getBlock();
            if (this.customCrops.containsKey(this.locKey(block))) {
                affectedCustomCrops.add(block);
            } else {
                Material type = state.getType();
                if (type == Material.KELP || type == Material.KELP_PLANT) {
                    Block base = this.findKelpBase(block.getRelative(BlockFace.DOWN));
                    if (base != null && this.riceBases.contains(this.locKey(base))) {
                        affectedCustomCrops.add(block);
                    }
                }

            }
        });
        if (!affectedCustomCrops.isEmpty()) {
            for(Block block : affectedCustomCrops) {
                this.scheduleVisualRefresh(block);
            }

        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBoneMealVisualRefresh(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (e.getItem() != null && e.getItem().getType() == Material.BONE_MEAL) {
                    Block clicked = e.getClickedBlock();
                    if (clicked != null) {
                        if (this.customCrops.containsKey(this.locKey(clicked))) {
                            this.scheduleVisualRefresh(clicked);
                        } else {
                            Block riceBase = this.findKelpBase(clicked);
                            if (riceBase != null && this.riceBases.contains(this.locKey(riceBase))) {
                                this.scheduleVisualRefresh(clicked);
                            }

                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onCropVisualInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            String locationKey = this.cropVisualLocation(e.getRightClicked());
            if (locationKey != null) {
                e.setCancelled(true);
                ItemStack item = e.getPlayer().getInventory().getItemInMainHand();
                if (item.getType() == Material.BONE_MEAL) {
                    Block target = this.trackedCropBlock(locationKey);
                    if (target != null) {
                        String cropId = this.artificialCropIdAt(target);
                        EnumSet<Season> allowedSeasons = this.growthSeasonsFor(cropId);
                        SeasonalCropGrowthListener seasonalGrowth = this.plugin.getSeasonalCropGrowthListener();
                        if (seasonalGrowth == null || seasonalGrowth.allowsArtificialGrowthAttempt(target, allowedSeasons)) {
                            int growthBefore = this.artificialCropGrowthValue(target, cropId);
                            boolean apiReportedSuccess = target.applyBoneMeal(BlockFace.UP);
                            int growthAfter = this.artificialCropGrowthValue(target, cropId);
                            boolean actuallyGrew = growthBefore >= 0 && growthAfter > growthBefore;
                            if ((apiReportedSuccess || actuallyGrew) && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
                                this.consumeOneFromHand(e.getPlayer(), item);
                            }

                            this.scheduleVisualRefresh(target);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onCropVisualAttack(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Player player) {
            String locationKey = this.cropVisualLocation(e.getEntity());
            if (locationKey != null) {
                e.setCancelled(true);
                Block target = this.trackedCropBlock(locationKey);
                if (target != null) {
                    player.breakBlock(target);
                }

            }
        }
    }

    private String cropVisualLocation(Entity entity) {
        return !(entity instanceof Interaction) ? null : (String)entity.getPersistentDataContainer().get(this.CROP_VISUAL_LOCATION_KEY, PersistentDataType.STRING);
    }

    private Block trackedCropBlock(String locationKey) {
        Block block = this.blockFromKey(locationKey);
        if (block == null) {
            return null;
        } else {
            String landCrop = (String)this.customCrops.get(locationKey);
            if (landCrop != null && this.cropMaterialFor(landCrop) == block.getType()) {
                return block;
            } else {
                Block riceBase = this.findKelpBase(block);
                return riceBase != null && this.riceBases.contains(this.locKey(riceBase)) ? block : null;
            }
        }
    }

    private void scheduleVisualRefresh(Block block) {
        Location location = block.getLocation();
        long[] delays = new long[]{1L, 3L, 8L, 20L};

        for(long delay : delays) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshVisualAt(location.getBlock()), delay);
        }

    }

    private void refreshVisualAt(Block changed) {
        String cropId = (String)this.customCrops.get(this.locKey(changed));
        if (cropId != null) {
            this.broadcastLandCropVisual(changed, cropId);
        } else {
            Block base = this.findKelpBase(changed);
            if (base != null && this.riceBases.contains(this.locKey(base))) {
                this.broadcastRiceVisual(base);
            } else {
                Block below = changed.getRelative(BlockFace.DOWN);
                base = this.findKelpBase(below);
                if (base != null && this.riceBases.contains(this.locKey(base))) {
                    this.broadcastRiceVisual(base);
                }

            }
        }
    }

    private void refreshCropVisuals(Player player) {
        for(Map.Entry<String, String> entry : this.customCrops.entrySet()) {
            Block block = this.blockFromKey((String)entry.getKey());
            if (block != null && this.isNear(player, block)) {
                Material expected = this.cropMaterialFor((String)entry.getValue());
                if (expected == block.getType()) {
                    this.sendLandCropVisual(player, block, (String)entry.getValue());
                } else {
                    this.removeCropVisualAt(block);
                }
            }
        }

        List<String> riceKeys;
        synchronized(this.riceBases) {
            riceKeys = new ArrayList(this.riceBases);
        }

        for(String key : riceKeys) {
            Block base = this.blockFromKey(key);
            if (base != null && this.isNear(player, base)) {
                if (this.isKelp(base)) {
                    this.sendRiceVisual(player, base);
                } else {
                    this.removeCropVisualRoot(key);
                }
            }
        }

    }

    private void restoreRealCropBlocks(Player player) {
        for(String key : this.customCrops.keySet()) {
            Block block = this.blockFromKey(key);
            if (block != null && block.getWorld().equals(player.getWorld())) {
                player.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        }

        List<String> riceKeys;
        synchronized(this.riceBases) {
            riceKeys = new ArrayList(this.riceBases);
        }

        for(String key : riceKeys) {
            Block base = this.blockFromKey(key);
            if (base != null && base.getWorld().equals(player.getWorld())) {
                Block current = base;

                for(int guard = 0; this.isKelp(current) && guard++ < 64; current = current.getRelative(BlockFace.UP)) {
                    player.sendBlockChange(current.getLocation(), current.getBlockData());
                }
            }
        }

    }

    private Block blockFromKey(String key) {
        String[] parts = key.split(";", 4);
        if (parts.length != 4) {
            return null;
        } else {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return null;
            } else {
                try {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    return !world.isChunkLoaded(x >> 4, z >> 4) ? null : world.getBlockAt(x, y, z);
                } catch (NumberFormatException var7) {
                    return null;
                }
            }
        }
    }

    private boolean isNear(Player player, Block block) {
        if (!player.getWorld().equals(block.getWorld())) {
            return false;
        } else {
            return player.getLocation().distanceSquared(block.getLocation()) <= (double)16384.0F;
        }
    }

    private int cropStage(Block block) {
        BlockData var3 = block.getBlockData();
        if (var3 instanceof Ageable ageable) {
            int max = ageable.getMaximumAge();
            return max <= 0 ? 0 : Math.min(3, ageable.getAge() * 4 / (max + 1));
        } else {
            return 0;
        }
    }

    private String landCropModelName(String cropId, int stage) {
        String crop = "onion".equals(cropId) ? "onion" : "tomato";
        return crop + "_stage_" + Math.max(0, Math.min(3, stage));
    }

    private int cropDisplayModelIndex(String modelName) {
        if (modelName.startsWith("tomato_stage_")) {
            return Integer.parseInt(modelName.substring("tomato_stage_".length()));
        } else if (modelName.startsWith("onion_stage_")) {
            return 4 + Integer.parseInt(modelName.substring("onion_stage_".length()));
        } else {
            for(int i = 0; i < RICE_VISUAL_MODELS.length; ++i) {
                if (RICE_VISUAL_MODELS[i].equals(modelName)) {
                    return 8 + i;
                }
            }

            throw new IllegalArgumentException("Unknown crop display model: " + modelName);
        }
    }

    private ItemStack cropDisplayItem(String modelName) {
        int modelIndex = this.cropDisplayModelIndex(modelName);
        ItemStack item = new ItemStack(Material.DEBUG_STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        } else {
            meta.setCustomModelData(990100 + modelIndex);
            meta.setEnchantmentGlintOverride(false);

            try {
                NamespacedKey itemModel = NamespacedKey.fromString("aeternum:crop_display/" + modelName);
                ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(meta, itemModel);
            } catch (NoSuchMethodException var6) {
            } catch (LinkageError | ReflectiveOperationException ex) {
                this.plugin.getLogger().warning("[SeasonFoods] Could not set crop item_model: " + ((Throwable)ex).getMessage());
            }

            item.setItemMeta(meta);
            return item;
        }
    }

    private CropVisual ensureCropVisual(Block block, String modelName, String rootKey) {
        String locationKey = this.locKey(block);
        CropVisual visual = (CropVisual)this.cropVisuals.get(locationKey);
        if (visual != null && (!visual.display().isValid() || !visual.interaction().isValid())) {
            this.removeCropVisual(locationKey, visual);
            visual = null;
        }

        if (visual != null && !visual.rootKey().equals(rootKey)) {
            this.removeCropVisual(locationKey, visual);
            visual = null;
        }

        if (visual == null) {
            Location displayLocation = block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
            ItemDisplay display = (ItemDisplay)block.getWorld().spawnEntity(displayLocation, EntityType.ITEM_DISPLAY);
            display.setItemDisplayTransform(ItemDisplayTransform.NONE);
            display.setBillboard(Billboard.FIXED);
            display.setItemStack(this.cropDisplayItem(modelName));
            display.setViewRange(2.0F);
            display.setShadowRadius(0.0F);
            display.setShadowStrength(0.0F);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            Location interactionLocation = block.getLocation().add((double)0.5F, (double)0.0F, (double)0.5F);
            Interaction interaction = (Interaction)block.getWorld().spawnEntity(interactionLocation, EntityType.INTERACTION);
            interaction.setInteractionWidth(1.0F);
            interaction.setInteractionHeight(1.0F);
            interaction.setResponsive(true);
            interaction.setGravity(false);
            interaction.setPersistent(false);
            interaction.setSilent(true);
            interaction.setVisibleByDefault(false);
            interaction.getPersistentDataContainer().set(this.CROP_VISUAL_LOCATION_KEY, PersistentDataType.STRING, locationKey);
            visual = new CropVisual(display, interaction, modelName, rootKey);
            this.cropVisuals.put(locationKey, visual);

            for(Player online : Bukkit.getOnlinePlayers()) {
                this.applyVisualAudience(online, visual);
            }
        } else if (!visual.modelName().equals(modelName)) {
            visual.display().setItemStack(this.cropDisplayItem(modelName));
            visual = new CropVisual(visual.display(), visual.interaction(), modelName, visual.rootKey());
            this.cropVisuals.put(locationKey, visual);
        }

        return visual;
    }

    private void applyVisualAudience(Player player, CropVisual visual) {
        if (this.isBedrockPlayer(player)) {
            player.hideEntity(this.plugin, visual.display());
            player.hideEntity(this.plugin, visual.interaction());
        } else {
            player.showEntity(this.plugin, visual.display());
            player.showEntity(this.plugin, visual.interaction());
        }

    }

    private boolean isBedrockPlayer(Player player) {
        return (Boolean)this.bedrockPlayerCache.computeIfAbsent(player.getUniqueId(), this::detectBedrockPlayer);
    }

    private boolean detectBedrockPlayer(UUID uuid) {
        return this.invokeBedrockCheck("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", uuid) ? true : this.invokeBedrockCheck("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", uuid);
    }

    private boolean invokeBedrockCheck(String className, String accessor, String checkMethod, UUID uuid) {
        try {
            Class<?> apiClass = Class.forName(className);
            Object api = apiClass.getMethod(accessor).invoke((Object)null);
            if (api == null) {
                return false;
            } else {
                Object result = apiClass.getMethod(checkMethod, UUID.class).invoke(api, uuid);
                return Boolean.TRUE.equals(result);
            }
        } catch (LinkageError | ReflectiveOperationException var8) {
            return false;
        }
    }

    private BlockData bedrockCropVisualData(String modelName) {
        return this.bedrockCropVisualData(modelName, false);
    }

    private BlockData bedrockCropVisualData(String modelName, boolean waterlogged) {
        int lightLevel = 4 + this.cropDisplayModelIndex(modelName);
        return Bukkit.createBlockData("minecraft:light[level=" + lightLevel + ",waterlogged=" + waterlogged + "]");
    }

    private void sendLandCropVisual(Player player, Block block, String cropId) {
        String modelName = this.landCropModelName(cropId, this.cropStage(block));
        CropVisual visual = this.ensureCropVisual(block, modelName, this.locKey(block));
        this.applyVisualAudience(player, visual);
        player.sendBlockChange(block.getLocation(), this.isBedrockPlayer(player) ? this.bedrockCropVisualData(modelName) : Material.AIR.createBlockData());
    }

    private void broadcastLandCropVisual(Block block, String cropId) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (this.isNear(player, block)) {
                this.sendLandCropVisual(player, block, cropId);
            }
        }

    }

    private void sendRiceVisual(Player player, Block base) {
        List<Block> column = this.riceColumn(base);
        String rootKey = this.locKey(base);
        Set<String> expectedLocations = new HashSet();
        int height = column.size();

        for(int i = 0; i < height; ++i) {
            int visualStage;
            if (height == 1) {
                visualStage = 0;
            } else if (i == height - 1) {
                visualStage = height >= 3 ? 3 : 2;
            } else {
                visualStage = 1;
            }

            Block segment = (Block)column.get(i);
            String modelName = RICE_VISUAL_MODELS[visualStage];
            expectedLocations.add(this.locKey(segment));
            CropVisual visual = this.ensureCropVisual(segment, modelName, rootKey);
            this.applyVisualAudience(player, visual);
            player.sendBlockChange(segment.getLocation(), this.isBedrockPlayer(player) ? this.bedrockCropVisualData(modelName, true) : Material.WATER.createBlockData());
        }

        this.removeCropVisualRootExcept(rootKey, expectedLocations);
    }

    private void broadcastRiceVisual(Block base) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (this.isNear(player, base)) {
                this.sendRiceVisual(player, base);
            }
        }

    }

    private List<Block> riceColumn(Block base) {
        List<Block> result = new ArrayList();
        Block current = base;

        for(int guard = 0; this.isKelp(current) && guard++ < 64; current = current.getRelative(BlockFace.UP)) {
            result.add(current);
        }

        return result;
    }

    private boolean isKelp(Block block) {
        return block.getType() == Material.KELP || block.getType() == Material.KELP_PLANT;
    }

    private Block findKelpBase(Block start) {
        if (start != null && this.isKelp(start)) {
            Block base = start;

            for(int guard = 0; this.isKelp(base.getRelative(BlockFace.DOWN)) && guard++ < 64; base = base.getRelative(BlockFace.DOWN)) {
            }

            return base;
        } else {
            return null;
        }
    }

    private void removeCropVisualAt(Block block) {
        String locationKey = this.locKey(block);
        CropVisual visual = (CropVisual)this.cropVisuals.get(locationKey);
        if (visual != null) {
            this.removeCropVisual(locationKey, visual);
        }

    }

    private void removeCropVisualRoot(String rootKey) {
        for(Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            if (((CropVisual)entry.getValue()).rootKey().equals(rootKey)) {
                this.removeCropVisual((String)entry.getKey(), (CropVisual)entry.getValue());
            }
        }

    }

    private void removeCropVisualRootExcept(String rootKey, Set<String> expectedLocations) {
        for(Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            if (((CropVisual)entry.getValue()).rootKey().equals(rootKey) && !expectedLocations.contains(entry.getKey())) {
                this.removeCropVisual((String)entry.getKey(), (CropVisual)entry.getValue());
            }
        }

    }

    private void removeCropVisual(String locationKey, CropVisual visual) {
        this.cropVisuals.remove(locationKey, visual);
        if (visual.display().isValid()) {
            visual.display().remove();
        }

        if (visual.interaction().isValid()) {
            visual.interaction().remove();
        }

    }

    private void removeAllCropVisuals() {
        for(Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            this.removeCropVisual((String)entry.getKey(), (CropVisual)entry.getValue());
        }

    }

    private void pruneInvalidCropVisuals() {
        for(Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            CropVisual visual = (CropVisual)entry.getValue();
            if (visual.display().isValid() && visual.interaction().isValid()) {
                Block root = this.blockFromKey(visual.rootKey());
                if (root != null) {
                    String landCrop = (String)this.customCrops.get(visual.rootKey());
                    if (landCrop != null) {
                        if (!((String)entry.getKey()).equals(visual.rootKey()) || this.cropMaterialFor(landCrop) != root.getType()) {
                            this.removeCropVisual((String)entry.getKey(), visual);
                        }
                    } else if (this.riceBases.contains(visual.rootKey()) && this.isKelp(root)) {
                        Block segment = this.blockFromKey((String)entry.getKey());
                        if (segment != null && !this.isKelp(segment)) {
                            this.removeCropVisual((String)entry.getKey(), visual);
                        }
                    } else {
                        this.removeCropVisual((String)entry.getKey(), visual);
                    }
                }
            } else {
                this.removeCropVisual((String)entry.getKey(), visual);
            }
        }

    }

    private void broadcastRealBlockLater(Location location) {
        Location copy = location.clone();
        this.removeCropVisualAt(copy.getBlock());
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Block block = copy.getBlock();

            for(Player player : Bukkit.getOnlinePlayers()) {
                if (this.isNear(player, block)) {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                }
            }

        });
    }

    @EventHandler
    public void onRicePlant(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = e.getItem();
                if (item != null) {
                    String foodId = this.getFoodId(item);
                    if ("rice".equals(foodId)) {
                        Block clicked = e.getClickedBlock();
                        if (clicked != null) {
                            Block waterBlock = clicked.getType() == Material.WATER ? clicked : clicked.getRelative(BlockFace.UP);
                            if (waterBlock.getType() == Material.WATER) {
                                BlockData var7 = waterBlock.getBlockData();
                                if (var7 instanceof Levelled) {
                                    Levelled lvl = (Levelled)var7;
                                    if (lvl.getLevel() == 0) {
                                        e.setCancelled(true);
                                        this.consumeOneFromHand(e.getPlayer(), item);
                                        waterBlock.setType(Material.KELP, false);
                                        this.riceBases.add(this.locKey(waterBlock));
                                        this.markCropsDirty();
                                        this.broadcastRiceVisual(waterBlock);
                                        this.scheduleVisualRefresh(waterBlock);
                                        return;
                                    }
                                }
                            }

                            if (clicked.getType() == Material.FARMLAND && clicked.getRelative(BlockFace.UP).getType().isAir()) {
                                e.setCancelled(true);
                            }

                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onCustomSeedPlant(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = e.getItem();
                if (item != null) {
                    String id = this.getFoodId(item);
                    if (id != null) {
                        if ("tomato".equals(id) || "onion".equals(id)) {
                            this.tryPlantCustomCrop(e, id);
                        }

                    }
                }
            }
        }
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onRiceBreak(BlockBreakEvent e) {
        Block broken = e.getBlock();
        boolean brokeRiceBlock = this.isKelp(broken);
        Block base;
        if (brokeRiceBlock) {
            base = this.findKelpBase(broken);
            if (base == null) {
                return;
            }
        } else {
            base = broken.getRelative(BlockFace.UP);
        }

        String key = this.locKey(base);
        if (this.riceBases.contains(key)) {
            if (brokeRiceBlock) {
                e.setDropItems(false);
            }

            World w = base.getWorld();
            Location dropLoc = base.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
            int segments = 0;
            List<Location> clearedLocations = new ArrayList();

            for(Block current = base; current.getType() == Material.KELP || current.getType() == Material.KELP_PLANT; current = current.getRelative(BlockFace.UP)) {
                ++segments;
                clearedLocations.add(current.getLocation());
                current.setType(Material.WATER, false);
            }

            this.riceBases.remove(key);
            this.markCropsDirty();
            if (!brokeRiceBlock) {
                this.scheduleVanillaCropDropCleanup(dropLoc, EnumSet.of(Material.KELP));
            }

            int amount = Math.max(1, segments);
            ItemStack seeds = this.riceProto.clone();
            seeds.setAmount(amount);
            Player breaker = e.getPlayer();
            this.applyFoodLocalization(breaker, seeds, "rice");
            w.dropItemNaturally(dropLoc, seeds);

            for(Location location : clearedLocations) {
                this.broadcastRealBlockLater(location);
            }

        }
    }

    private void scheduleVanillaCropDropCleanup(Location cropDropLocation, Set<Material> vanillaDrops) {
        Location center = cropDropLocation.clone();
        long[] delays = new long[]{1L, 3L};

        for(long delay : delays) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                World world = center.getWorld();
                if (world != null) {
                    for(Entity entity : world.getNearbyEntities(center, (double)1.25F, (double)1.25F, (double)1.25F)) {
                        if (entity instanceof Item) {
                            Item dropped = (Item)entity;
                            if (dropped.getTicksLived() <= 10) {
                                ItemStack stack = dropped.getItemStack();
                                if (vanillaDrops.contains(stack.getType()) && this.getFoodId(stack) == null) {
                                    dropped.remove();
                                }
                            }
                        }
                    }

                }
            }, delay);
        }

    }

    private void applyFoodLocalization(Player p, ItemStack item, String foodId) {
        if (item != null && foodId != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (this.isIngredientId(foodId)) {
                    meta.removeEnchant(Enchantment.LURE);
                }

                String baseKey = "items.food." + foodId;
                String name = p != null ? this.lang.tr(p, baseKey + ".name") : this.lang.trServer(baseKey + ".name");
                String lore1 = p != null ? this.lang.tr(p, baseKey + ".lore_1") : this.lang.trServer(baseKey + ".lore_1");
                String lore2 = p != null ? this.lang.tr(p, baseKey + ".lore_2") : this.lang.trServer(baseKey + ".lore_2");
                meta.setDisplayName(name);
                List<String> lore = new ArrayList();
                lore.add(lore1);
                lore.add(lore2);
                this.appendGrowthLore(p, lore, foodId);
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }
    }

    private boolean isIngredientId(String foodId) {
        return "tomato".equals(foodId) || "onion".equals(foodId) || "rice".equals(foodId);
    }

    private boolean isFoodId(String foodId) {
        return this.isIngredientId(foodId) || DISH_IDS.contains(foodId);
    }

    private void removeFoodGlint(Player player) {
        for(ItemStack item : player.getInventory().getContents()) {
            String foodId = this.getFoodId(item);
            if (this.isFoodId(foodId) && item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    boolean changed = false;
                    if (meta.hasEnchant(Enchantment.LURE)) {
                        meta.removeEnchant(Enchantment.LURE);
                        changed = true;
                    }

                    if ("tomato".equals(foodId)) {
                        this.configureTomatoFood(meta);
                        changed = true;
                    }

                    if (changed) {
                        item.setItemMeta(meta);
                        if ("tomato".equals(foodId)) {
                            this.configurePaperConsumable(item);
                        }
                    }
                }
            }
        }

    }

    public String getArtificialCropId(Block block) {
        return block == null ? null : (String)this.customCrops.get(this.locKey(block));
    }

    boolean untrackArtificialLandCrop(Block block, String expectedCropId) {
        if (block != null && expectedCropId != null) {
            if (!this.customCrops.remove(this.locKey(block), expectedCropId)) {
                return false;
            } else {
                this.markCropsDirty();
                this.broadcastRealBlockLater(block.getLocation());
                return true;
            }
        } else {
            return false;
        }
    }

    public EnumSet<Season> getArtificialGrowthSeasons(Block block) {
        return this.growthSeasonsFor(this.artificialCropIdAt(block));
    }

    private String artificialCropIdAt(Block block) {
        if (block == null) {
            return null;
        } else {
            String landCrop = (String)this.customCrops.get(this.locKey(block));
            if (landCrop != null) {
                return landCrop;
            } else {
                Block riceBase = this.findKelpBase(block);
                return riceBase != null && this.riceBases.contains(this.locKey(riceBase)) ? "rice" : null;
            }
        }
    }

    private int artificialCropGrowthValue(Block block, String cropId) {
        if (block != null && cropId != null) {
            if ("rice".equals(cropId)) {
                Block riceBase = this.findKelpBase(block);
                return riceBase == null ? -1 : this.riceColumn(riceBase).size();
            } else {
                BlockData var4 = block.getBlockData();
                if (var4 instanceof Ageable) {
                    Ageable ageable = (Ageable)var4;
                    return ageable.getAge();
                } else {
                    return -1;
                }
            }
        } else {
            return -1;
        }
    }

    public boolean isArtificialSeed(ItemStack item, String cropId) {
        return item != null && cropId != null && cropId.equals(this.getFoodId(item));
    }

    public boolean isAnyArtificialSeed(ItemStack item) {
        return item != null && this.isIngredientId(this.getFoodId(item));
    }

    public Material getArtificialSeedMaterial(String cropId) {
        Material var10000;
        switch (cropId) {
            case "tomato" -> var10000 = Material.BEETROOT_SEEDS;
            case "onion" -> var10000 = Material.WHEAT_SEEDS;
            default -> var10000 = null;
        }

        return var10000;
    }

    public ItemStack createArtificialCropDrop(String cropId, Player player, int amount) {
        ItemStack var10000;
        switch (cropId) {
            case "tomato" -> var10000 = this.tomatoProto;
            case "onion" -> var10000 = this.onionProto;
            default -> var10000 = null;
        }

        ItemStack prototype = var10000;
        if (prototype == null) {
            return null;
        } else {
            ItemStack drop = prototype.clone();
            drop.setAmount(Math.max(1, amount));
            this.applyFoodLocalization(player, drop, cropId);
            return drop;
        }
    }

    public void finishArtificialHarvest(Block block, String cropId, boolean replant) {
        if (block != null && cropId != null) {
            this.customCrops.remove(this.locKey(block));
            if (replant) {
                Material cropMaterial = this.cropMaterialFor(cropId);
                if (cropMaterial != null) {
                    block.setType(cropMaterial, false);
                    BlockData var6 = block.getBlockData();
                    if (var6 instanceof Ageable) {
                        Ageable ageable = (Ageable)var6;
                        ageable.setAge(0);
                        block.setBlockData(ageable, false);
                    }

                    this.customCrops.put(this.locKey(block), cropId);
                    this.broadcastLandCropVisual(block, cropId);
                    this.scheduleVisualRefresh(block);
                }
            } else {
                this.broadcastRealBlockLater(block.getLocation());
            }

            this.markCropsDirty();
        }
    }

    private Material cropMaterialFor(String id) {
        Material var10000;
        switch (id) {
            case "tomato" -> var10000 = Material.BEETROOTS;
            case "onion" -> var10000 = Material.WHEAT;
            default -> var10000 = null;
        }

        return var10000;
    }

    private boolean tryPlantCustomCrop(PlayerInteractEvent e, String cropId) {
        Block clicked = e.getClickedBlock();
        if (clicked == null) {
            return false;
        } else if (clicked.getType() != Material.FARMLAND) {
            return false;
        } else {
            Block plantAt = clicked.getRelative(BlockFace.UP);
            if (!plantAt.getType().isAir()) {
                return false;
            } else {
                Material cropMat = this.cropMaterialFor(cropId);
                if (cropMat == null) {
                    return false;
                } else {
                    e.setCancelled(true);
                    this.consumeOneFromHand(e.getPlayer(), e.getItem());
                    plantAt.setType(cropMat, false);
                    this.customCrops.put(this.locKey(plantAt), cropId);
                    this.markCropsDirty();
                    this.broadcastLandCropVisual(plantAt, cropId);
                    this.scheduleVisualRefresh(plantAt);
                    return true;
                }
            }
        }
    }

    private static record CropVisual(ItemDisplay display, Interaction interaction, String modelName, String rootKey) {
        private CropVisual {
        }
    }
}
