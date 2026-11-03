package kinkin.aeternum.food;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.farming.SeasonalCropGrowthListener;
import kinkin.aeternum.lang.LanguageManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.*;
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
import org.bukkit.event.player.*;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 季节食物系统（SeasonFoods）。
 *
 * <p>本类集中管理插件中与“季节性食物”相关的全部行为，主要包括：
 * <ul>
 *     <li>构建并注册自定义食材（番茄、洋葱、水稻）及各类菜肴的物品原型与工作台配方；</li>
 *     <li>处理合成、消耗、生物掉落（僵尸/尸壳/溺尸）、村庄战利品表等事件，并为物品做本地化与效果结算；</li>
 *     <li>维护“人工作物”的种植与破坏逻辑（耕地作物、水稻水培），并持久化到 seasonfoods_crops.yml；</li>
 *     <li>通过 ItemDisplay + Interaction 实体渲染作物外观（含基岩版兼容），并周期性刷新、清理。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin}（插件主体、调度器、配置）、{@link LanguageManager}（多语言文本）、
 * {@link SeasonFoodCropDestructionListener}（作物破坏监听），以及可选的 Geyser/Floodgate 以识别基岩版玩家。</p>
 */
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
    private static final double CROP_VISUAL_RANGE_SQUARED = (double) 16384.0F;
    private static final String[] RICE_VISUAL_MODELS = new String[]{"rice_seedling", "rice_stalk", "rice_young_top", "rice_ripe_top"};

    private void removeRecipes() {
        for (String key : RECIPE_KEYS) {
            Bukkit.removeRecipe(new NamespacedKey(this.plugin, key));
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

    /**
     * 加载掉落配置、构建食材原型、注册配方与作物数据，并注册事件监听、启动外观刷新任务。
     */
    public void register() {
        this.loadLootConfig();
        this.buildIngredientPrototypes();
        this.registerRecipes();
        this.loadCrops();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        Bukkit.getPluginManager().registerEvents(this.cropDestructionListener, this.plugin);
        this.startVisualRefresh();

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.removeFoodGlint(player);
        }

    }

    /**
     * 读取并校验僵尸掉落食材概率配置，非法值回退到默认 0.1。
     */
    private void loadLootConfig() {
        if (this.plugin.cfg != null && this.plugin.cfg.crops != null) {
            double configuredChance = this.plugin.cfg.crops.getDouble("season_foods.loot.zombies.drop_chance", 0.1);
            if (!Double.isFinite(configuredChance)) {
                this.plugin.getLogger().warning("[SeasonFoods] season_foods.loot.zombies.drop_chance must be a finite number. Using 0.1.");
                this.zombieDropChance = 0.1;
            } else {
                this.zombieDropChance = Math.max((double) 0.0F, Math.min((double) 1.0F, configuredChance));
                if (this.zombieDropChance != configuredChance) {
                    this.plugin.getLogger().warning("[SeasonFoods] season_foods.loot.zombies.drop_chance must be between 0.0 and 1.0. Using " + this.zombieDropChance + ".");
                }

            }
        } else {
            this.zombieDropChance = 0.1;
        }
    }

    /**
     * 取消任务、还原玩家看到的真实作物方块、清理外观实体、注销监听与配方，并落盘作物数据。
     */
    public void unregister() {
        if (this.visualRefreshTask != null) {
            this.visualRefreshTask.cancel();
            this.visualRefreshTask = null;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
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

    /**
     * 以基础材料为模板构建一种自定义食材（番茄/洋葱/水稻）的原型物品，并写入名称、lore、模型与食物 ID。
     */
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
            this.appendGrowthLore((Player) null, lore, id);
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
        } catch (NoSuchMethodException noSuchMethod) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure legacy tomato consumption: " + ((Throwable) ex).getMessage());
        }

    }

    private void configureSpigotConsumable(ItemMeta meta) {
        try {
            Class<?> componentType = Class.forName("org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
            Class<?> animationType = Class.forName("org.bukkit.inventory.meta.components.consumable.ConsumableComponent$Animation");
            Object consumable = ItemMeta.class.getMethod("getConsumable").invoke(meta);
            componentType.getMethod("setConsumeSeconds", Float.TYPE).invoke(consumable, 1.6F);
            Object eatAnimation = animationType.getField("EAT").get((Object) null);
            componentType.getMethod("setAnimation", animationType).invoke(consumable, eatAnimation);
            ItemMeta.class.getMethod("setConsumable", componentType).invoke(meta, consumable);
        } catch (NoSuchMethodException | ClassNotFoundException missingApi) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure Spigot tomato consumption: " + ((Throwable) ex).getMessage());
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
            Object componentKey = dataComponentTypes.getField("CONSUMABLE").get((Object) null);
            Object builder = consumableType.getMethod("consumable").invoke((Object) null);
            consumableBuilderType.getMethod("consumeSeconds", Float.TYPE).invoke(builder, 1.6F);
            Object eatAnimation = animationType.getField("EAT").get((Object) null);
            consumableBuilderType.getMethod("animation", animationType).invoke(builder, eatAnimation);
            ItemStack.class.getMethod("setData", valuedType, builderType).invoke(item, componentKey, builder);
        } catch (NoSuchMethodException | ClassNotFoundException missingApi) {
        } catch (LinkageError | ReflectiveOperationException ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not configure Paper tomato consumption: " + ((Throwable) ex).getMessage());
        }

    }

    private EnumSet<Season> growthSeasonsFor(String cropId) {
        if (cropId == null) {
            return null;
        } else {
            EnumSet<Season> seasons = (EnumSet) this.growthSeasons.get(cropId);
            return seasons == null ? null : EnumSet.copyOf(seasons);
        }
    }

    /**
     * 向物品 lore 追加“生长季节”一行；传入玩家时使用玩家语言，否则使用服务端默认语言。
     */
    private void appendGrowthLore(Player player, List<String> lore, String cropId) {
        EnumSet<Season> seasons = (EnumSet) this.growthSeasons.get(cropId);
        if (seasons != null && !seasons.isEmpty()) {
            if (lore != null) {
                String seasonsText = (String) seasons.stream().map((season) -> player != null ? this.lang.tr(player, "season." + season.name()) : this.lang.trServer("season." + season.name())).collect(Collectors.joining(", "));
                String template = player != null ? this.lang.tr(player, "items.food.grows_in") : this.lang.trServer("items.food.grows_in");
                String line = template.replace("{seasons}", seasonsText);
                if (!lore.contains(line)) {
                    lore.add(line);
                }
            }
        }
    }

    /**
     * 注册全部自定义菜肴的工作台配方（无序/有序）。
     */
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

    /**
     * 构建菜肴原型物品：写入本地化名称、lore、自定义模型数据与食物 ID。
     */
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
                return (String) pdc.get(this.FOOD_ID_KEY, PersistentDataType.STRING);
            }
        } else {
            return null;
        }
    }

    /**
     * 对菜肴物品应用针对指定玩家的本地化名称与 lore（会清除诱饵附魔）。
     */
    private void applyDishLocalization(Player player, ItemStack item, String foodId) {
        if (foodId != null && item != null && DISH_IDS.contains(foodId)) {
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.removeEnchant(Enchantment.LURE);
                    String baseKey = "items.food." + foodId;
                    meta.setDisplayName(this.lang.tr(player, baseKey + ".name"));
                    meta.setLore(Arrays.asList(this.lang.tr(player, baseKey + ".lore_1"), this.lang.tr(player, baseKey + ".lore_2")));
                    item.setItemMeta(meta);
                }
            }
        }
    }

    /**
     * 将方块坐标编码为“世界名;x;y;z”的字符串键，用于作物记录与外观索引。
     */
    private String locKey(Block block) {
        String worldName = block.getWorld().getName();
        return worldName + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    /**
     * 从 seasonfoods_crops.yml 载入人工作物记录与水稻基座列表。
     */
    private void loadCrops() {
        try {
            this.customCrops.clear();
            this.riceBases.clear();
            if (!this.cropsFile.exists()) {
                return;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(this.cropsFile);
            ConfigurationSection cropsSection = config.getConfigurationSection("custom_crops");
            if (cropsSection != null) {
                for (String key : cropsSection.getKeys(false)) {
                    String id = cropsSection.getString(key);
                    if (id != null) {
                        this.customCrops.put(key, id);
                    }
                }
            }

            List<String> riceBaseList = config.getStringList("rice_bases");
            if (riceBaseList != null && !riceBaseList.isEmpty()) {
                this.riceBases.addAll(riceBaseList);
            }
        } catch (Exception ex) {
            this.plugin.getLogger().warning("[SeasonFoods] Could not load seasonfoods_crops.yml: " + ex.getMessage());
        }

    }

    /**
     * 标记作物数据已变更，并延迟合并保存（100 tick 内的多次变更只会触发一次落盘）。
     */
    private void markCropsDirty() {
        this.cropsDirty = true;
        if (this.saveTask == null) {
            this.saveTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                this.saveTask = null;
                this.saveCropsNow();
            }, 100L);
        }
    }

    /**
     * 若存在未保存变更，则立即将人工作物记录与水稻基座写入 seasonfoods_crops.yml。
     */
    private void saveCropsNow() {
        if (this.cropsDirty) {
            try {
                this.plugin.getDataFolder().mkdirs();
                YamlConfiguration config = new YamlConfiguration();

                for (Map.Entry<String, String> entry : this.customCrops.entrySet()) {
                    config.set("custom_crops." + (String) entry.getKey(), entry.getValue());
                }

                config.set("rice_bases", new ArrayList(this.riceBases));
                config.save(this.cropsFile);
                this.cropsDirty = false;
            } catch (IOException ex) {
                this.plugin.getLogger().warning("[SeasonFoods] Could not save seasonfoods_crops.yml: " + ex.getMessage());
                this.cropsDirty = true;
            }

        }
    }

    /**
     * 监听合成结果：若产物是自定义菜肴，则对产物物品应用对应玩家的本地化文本。
     */
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        HumanEntity clicker = event.getWhoClicked();
        if (clicker instanceof Player player) {
            ItemStack result = event.getCurrentItem();
            if (result != null) {
                String foodId = this.getFoodId(result);
                if (foodId != null) {
                    this.applyDishLocalization(player, result, foodId);
                }
            }
        }
    }

    /**
     * 监听合成预览：按产物类型校验配方材料是否正确（如番茄沙拉、蔬菜面包、肉三明治、牛肉饭炖等），
     * 材料不符时清空合成结果，符合时再对结果物品应用本地化文本。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        HumanEntity viewer = event.getView().getPlayer();
        if (viewer instanceof Player player) {
            CraftingInventory inv = event.getInventory();
            ItemStack result = inv.getResult();
            if (result != null) {
                String resultId = this.getFoodId(result);
                if (resultId != null) {
                    ItemStack[] matrix = inv.getMatrix();
                    switch (resultId) {
                        case "tomato_salad":
                            int tomatoCount = 0;
                            int bowls = 0;

                            for (ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    if (stack.getType() == Material.BOWL) {
                                        ++bowls;
                                        if (bowls > 1) {
                                            inv.setResult((ItemStack) null);
                                            return;
                                        }
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if (!"tomato".equals(id)) {
                                            inv.setResult((ItemStack) null);
                                            return;
                                        }

                                        tomatoCount += stack.getAmount();
                                    }
                                }
                            }

                            if (bowls != 1 || tomatoCount < 2) {
                                inv.setResult((ItemStack) null);
                                return;
                            }

                            this.applyDishLocalization(player, result, "tomato_salad");
                            inv.setResult(result);
                            break;
                        case "vegetable_bread":
                            int bread = 0;
                            int tomato = 0;
                            int onion = 0;

                            for (ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material material = stack.getType();
                                    if (material == Material.BREAD) {
                                        ++bread;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if ("tomato".equals(id)) {
                                            ++tomato;
                                        } else {
                                            if (!"onion".equals(id)) {
                                                inv.setResult((ItemStack) null);
                                                return;
                                            }

                                            ++onion;
                                        }
                                    }
                                }
                            }

                            if (bread != 1 || tomato < 1 || onion < 1) {
                                inv.setResult((ItemStack) null);
                                return;
                            }

                            this.applyDishLocalization(player, result, "vegetable_bread");
                            inv.setResult(result);
                            break;
                        case "meat_sandwich":
                            int msBread = 0;
                            int msTomato = 0;
                            int msOnion = 0;
                            int msMeat = 0;

                            for (ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material material = stack.getType();
                                    if (material == Material.BREAD) {
                                        ++msBread;
                                    } else if (this.isCookedMeat(material)) {
                                        ++msMeat;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if ("tomato".equals(id)) {
                                            ++msTomato;
                                        } else {
                                            if (!"onion".equals(id)) {
                                                inv.setResult((ItemStack) null);
                                                return;
                                            }

                                            ++msOnion;
                                        }
                                    }
                                }
                            }

                            if (msBread != 1 || msTomato < 1 || msOnion < 1 || msMeat < 1) {
                                inv.setResult((ItemStack) null);
                                return;
                            }

                            this.applyDishLocalization(player, result, "meat_sandwich");
                            inv.setResult(result);
                            break;
                        case "beef_rice_stew":
                            int bowl = 0;
                            int rice = 0;
                            int stewMeat = 0;

                            for (ItemStack stack : matrix) {
                                if (stack != null && stack.getType() != Material.AIR) {
                                    Material material = stack.getType();
                                    if (material == Material.BOWL) {
                                        ++bowl;
                                    } else if (this.isCookedMeat(material)) {
                                        ++stewMeat;
                                    } else {
                                        String id = this.getFoodId(stack);
                                        if (!"rice".equals(id)) {
                                            inv.setResult((ItemStack) null);
                                            return;
                                        }

                                        ++rice;
                                    }
                                }
                            }

                            if (bowl < 1 || rice < 1 || stewMeat < 1) {
                                inv.setResult((ItemStack) null);
                                return;
                            }

                            this.applyDishLocalization(player, result, "beef_rice_stew");
                            inv.setResult(result);
                            break;
                        default:
                            this.applyDishLocalization(player, result, resultId);
                            inv.setResult(result);
                    }

                }
            }
        }
    }

    /**
     * 判断材料是否为任一“熟肉”类型，用于肉三明治与牛肉饭炖的配方校验。
     */
    private boolean isCookedMeat(Material material) {
        return material == Material.COOKED_BEEF || material == Material.COOKED_MUTTON || material == Material.COOKED_PORKCHOP || material == Material.COOKED_CHICKEN || material == Material.COOKED_RABBIT || material == Material.COOKED_SALMON || material == Material.COOKED_COD;
    }

    /**
     * 监听食物被消耗：番茄有概率附加饥饿效果；自定义菜肴则延迟一 tick 结算饥饿值与饱和度，
     * 并触发对应菜肴的特殊效果。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onConsumeFood(PlayerItemConsumeEvent event) {
        String foodId = this.getFoodId(event.getItem());
        if ("tomato".equals(foodId)) {
            if (ThreadLocalRandom.current().nextDouble() < 0.1) {
                event.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 200, 0, false, true, true));
            }

        } else if (foodId != null && DISH_IDS.contains(foodId)) {
            Player player = event.getPlayer();
            int targetFood = Math.min(20, player.getFoodLevel() + this.foodPoints(foodId));
            float targetSaturation = Math.min(20.0F, player.getSaturation() + this.saturationPoints(foodId));
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (player.isOnline()) {
                    player.setFoodLevel(targetFood);
                    player.setSaturation(Math.min((float) targetFood, targetSaturation));
                    this.applyConsumedFoodEffects(player, foodId);
                }
            });
        }
    }

    /**
     * 返回指定菜肴恢复的饥饿值点数。
     */
    private int foodPoints(String foodId) {
        byte points;
        switch (foodId) {
            case "tomato_salad":
                points = 4;
                break;
            case "vegetable_bread":
                points = 7;
                break;
            case "meat_sandwich":
                points = 8;
                break;
            case "beef_rice_stew":
                points = 10;
                break;
            case "coffee":
            case "herbal_tea":
                points = 3;
                break;
            case "energy_drink":
                points = 2;
                break;
            case "hot_chocolate":
                points = 5;
                break;
            default:
                points = 0;
        }

        return points;
    }

    /**
     * 返回指定菜肴恢复的饱和度点数。
     */
    private float saturationPoints(String foodId) {
        float saturation;
        switch (foodId) {
            case "tomato_salad":
                saturation = 4.0F;
                break;
            case "vegetable_bread":
                saturation = 6.0F;
                break;
            case "meat_sandwich":
                saturation = 7.0F;
                break;
            case "beef_rice_stew":
                saturation = 8.0F;
                break;
            case "coffee":
            case "herbal_tea":
                saturation = 3.0F;
                break;
            case "energy_drink":
                saturation = 2.0F;
                break;
            case "hot_chocolate":
                saturation = 5.0F;
                break;
            default:
                saturation = 0.0F;
        }

        return saturation;
    }

    /**
     * 根据菜肴 ID 分派到对应进食/饮用效果。
     */
    private void applyConsumedFoodEffects(Player player, String foodId) {
        switch (foodId) {
            case "tomato_salad" -> this.eatTomatoSalad(player);
            case "vegetable_bread" -> this.eatVegetableBread(player);
            case "meat_sandwich" -> this.eatMeatSandwich(player);
            case "beef_rice_stew" -> this.eatBeefRiceStew(player);
            case "coffee" -> this.drinkCoffee(player);
            case "herbal_tea" -> this.drinkHerbalTea(player);
            case "energy_drink" -> this.drinkEnergy(player);
            case "hot_chocolate" -> this.drinkHotChocolate(player);
        }

    }

    /**
     * 从玩家主手消耗一个物品（数量为 1 时清空该槽位）。
     */
    private void consumeOneFromHand(Player player, ItemStack inHand) {
        int amount = inHand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand((ItemStack) null);
        } else {
            inHand.setAmount(amount - 1);
        }

    }

    /**
     * 番茄沙拉：给予生命恢复。
     */
    private void eatTomatoSalad(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 0, false, true, true));
    }

    /**
     * 蔬菜面包：给予伤害吸收。
     */
    private void eatVegetableBread(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 0, false, true, true));
    }

    /**
     * 肉三明治：给予抗性提升与力量。
     */
    private void eatMeatSandwich(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 160, 0, false, true, true));
    }

    /**
     * 牛肉饭炖：给予生命恢复与饱和。
     */
    private void eatBeefRiceStew(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 60, 0, false, true, true));
    }

    /**
     * 咖啡：解除冰冻，给予速度与夜视。
     */
    private void drinkCoffee(Player player) {
        player.setFreezeTicks(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 900, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 1200, 0, false, true, true));
    }

    /**
     * 花草茶：解除冰冻，给予生命恢复与生命提升。
     */
    private void drinkHerbalTea(Player player) {
        player.setFreezeTicks(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 600, 0, false, true, true));
    }

    /**
     * 能量饮料：给予二级速度与二级急迫。
     */
    private void drinkEnergy(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 500, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 500, 1, false, true, true));
    }

    /**
     * 热巧克力：解除冰冻，给予抗性提升与伤害吸收。
     */
    private void drinkHotChocolate(Player player) {
        player.setFreezeTicks(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 400, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 600, 1, false, true, true));
    }

    /**
     * 监听村庄食物战利品生成：向匹配的战利品表按概率追加番茄、洋葱、水稻食材。
     */
    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        LootTable table = event.getLootTable();
        if (table != null) {
            NamespacedKey key = table.getKey();
            if (key != null) {
                String path = key.getKey();
                if (VillageFoodLoot.matches(key)) {
                    List<ItemStack> loot = event.getLoot();
                    Random random = ThreadLocalRandom.current();
                    Entity entity = event.getEntity();
                    Player playerSource;
                    if (entity instanceof Player) {
                        Player sourcePlayer = (Player) entity;
                        playerSource = sourcePlayer;
                    } else {
                        playerSource = null;
                    }

                    Player player = playerSource;
                    if (random.nextDouble() < 0.35) {
                        ItemStack tomatoItem = this.tomatoProto.clone();
                        this.applyFoodLocalization(player, tomatoItem, "tomato");
                        loot.add(tomatoItem);
                    }

                    if (random.nextDouble() < 0.35) {
                        ItemStack onionItem = this.onionProto.clone();
                        this.applyFoodLocalization(player, onionItem, "onion");
                        loot.add(onionItem);
                    }

                    if (random.nextDouble() < 0.3) {
                        ItemStack riceItem = this.riceProto.clone();
                        this.applyFoodLocalization(player, riceItem, "rice");
                        loot.add(riceItem);
                    }

                }
            }
        }
    }

    /**
     * 监听僵尸/尸壳/溺尸死亡：按配置概率掉落一份随机自定义食材。
     */
    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EntityType type = entity.getType();
        if (type == EntityType.ZOMBIE || type == EntityType.HUSK || type == EntityType.DROWNED) {
            Random random = ThreadLocalRandom.current();
            if (!(random.nextDouble() >= this.zombieDropChance)) {
                int roll = random.nextInt(3);
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
                event.getDrops().add(drop);
            }
        }
    }

    /**
     * 启动周期性外观刷新任务：每 40 tick 清理失效外观并刷新所有在线玩家附近的作物显示。
     */
    private void startVisualRefresh() {
        if (this.visualRefreshTask != null) {
            this.visualRefreshTask.cancel();
        }

        this.visualRefreshTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            this.pruneInvalidCropVisuals();

            for (Player player : Bukkit.getOnlinePlayers()) {
                this.refreshCropVisuals(player);
            }

        }, 20L, 40L);
    }

    /**
     * 玩家加入：清除物品发光、重置基岩版缓存，并延迟刷新其附近的作物外观。
     */
    @EventHandler
    public void onCropVisualJoin(PlayerJoinEvent event) {
        this.removeFoodGlint(event.getPlayer());
        this.bedrockPlayerCache.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshCropVisuals(event.getPlayer()), 10L);
    }

    /**
     * 玩家退出：清理其基岩版识别缓存。
     */
    @EventHandler
    public void onCropVisualQuit(PlayerQuitEvent event) {
        this.bedrockPlayerCache.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 玩家切换世界：延迟刷新其附近的作物外观。
     */
    @EventHandler
    public void onCropVisualWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshCropVisuals(event.getPlayer()), 2L);
    }

    /**
     * 玩家跨区块移动时，刷新其附近的作物外观。
     */
    @EventHandler
    public void onCropVisualMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            if (event.getFrom().getBlockX() >> 4 != event.getTo().getBlockX() >> 4 || event.getFrom().getBlockZ() >> 4 != event.getTo().getBlockZ() >> 4 || !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
                Bukkit.getScheduler().runTask(this.plugin, () -> this.refreshCropVisuals(event.getPlayer()));
            }
        }
    }

    /**
     * 作物生长：若为受管的人工作物或海带（水稻），刷新其外观。
     */
    @EventHandler
    public void onCustomCropGrow(BlockGrowEvent event) {
        Block changed = event.getBlock();
        String cropId = (String) this.customCrops.get(this.locKey(changed));
        Material newType = event.getNewState().getType();
        if (cropId != null || newType == Material.KELP || newType == Material.KELP_PLANT) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.refreshVisualAt(changed));
        }

    }

    /**
     * 骨粉催熟：收集受影响的人工作物/水稻方块，并为其安排外观刷新。
     */
    @EventHandler
    public void onCustomCropFertilize(BlockFertilizeEvent event) {
        List<Block> affectedCustomCrops = new ArrayList();
        event.getBlocks().forEach((blockState) -> {
            Block block = blockState.getBlock();
            if (this.customCrops.containsKey(this.locKey(block))) {
                affectedCustomCrops.add(block);
            } else {
                Material type = blockState.getType();
                if (type == Material.KELP || type == Material.KELP_PLANT) {
                    Block base = this.findKelpBase(block.getRelative(BlockFace.DOWN));
                    if (base != null && this.riceBases.contains(this.locKey(base))) {
                        affectedCustomCrops.add(block);
                    }
                }

            }
        });
        if (!affectedCustomCrops.isEmpty()) {
            for (Block block : affectedCustomCrops) {
                this.scheduleVisualRefresh(block);
            }

        }
    }

    /**
     * 对作物右键使用骨粉时，安排其外观刷新（原版逻辑仍由服务器处理）。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onBoneMealVisualRefresh(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (event.getItem() != null && event.getItem().getType() == Material.BONE_MEAL) {
                    Block clicked = event.getClickedBlock();
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

    /**
     * 右键点击作物外观实体：拦截交互，若手持骨粉则代为对真实作物方块的催熟，
     * 并在非创造模式下消耗一个骨粉，随后刷新外观。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCropVisualInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            String locationKey = this.cropVisualLocation(event.getRightClicked());
            if (locationKey != null) {
                event.setCancelled(true);
                ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
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
                            if ((apiReportedSuccess || actuallyGrew) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                                this.consumeOneFromHand(event.getPlayer(), item);
                            }

                            this.scheduleVisualRefresh(target);
                        }
                    }
                }
            }
        }
    }

    /**
     * 攻击作物外观实体：拦截攻击并改为破坏其对应的真实作物方块。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCropVisualAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            String locationKey = this.cropVisualLocation(event.getEntity());
            if (locationKey != null) {
                event.setCancelled(true);
                Block target = this.trackedCropBlock(locationKey);
                if (target != null) {
                    player.breakBlock(target);
                }

            }
        }
    }

    /**
     * 取得外观实体记录的作物坐标键（仅 Interaction 实体带有该标记）。
     */
    private String cropVisualLocation(Entity entity) {
        return !(entity instanceof Interaction) ? null : (String) entity.getPersistentDataContainer().get(this.CROP_VISUAL_LOCATION_KEY, PersistentDataType.STRING);
    }

    /**
     * 根据坐标键解析出对应的真实作物方块，并校验其仍为受管作物（耕地作物或水稻）。
     */
    private Block trackedCropBlock(String locationKey) {
        Block block = this.blockFromKey(locationKey);
        if (block == null) {
            return null;
        } else {
            String landCrop = (String) this.customCrops.get(locationKey);
            if (landCrop != null && this.cropMaterialFor(landCrop) == block.getType()) {
                return block;
            } else {
                Block riceBase = this.findKelpBase(block);
                return riceBase != null && this.riceBases.contains(this.locKey(riceBase)) ? block : null;
            }
        }
    }

    /**
     * 在若干延迟后多次刷新指定方块的外观，以覆盖服务器方块状态同步的时间差。
     */
    private void scheduleVisualRefresh(Block block) {
        Location location = block.getLocation();
        long[] delays = new long[]{1L, 3L, 8L, 20L};

        for (long delay : delays) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.refreshVisualAt(location.getBlock()), delay);
        }

    }

    /**
     * 立即刷新单个方块的外观：区分耕地作物与水稻（含向下的基座回溯）。
     */
    private void refreshVisualAt(Block changed) {
        String cropId = (String) this.customCrops.get(this.locKey(changed));
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

    /**
     * 向指定玩家刷新其附近所有受管作物的外观（发送或移除显示）。
     */
    private void refreshCropVisuals(Player player) {
        for (Map.Entry<String, String> entry : this.customCrops.entrySet()) {
            Block block = this.blockFromKey((String) entry.getKey());
            if (block != null && this.isNear(player, block)) {
                Material expected = this.cropMaterialFor((String) entry.getValue());
                if (expected == block.getType()) {
                    this.sendLandCropVisual(player, block, (String) entry.getValue());
                } else {
                    this.removeCropVisualAt(block);
                }
            }
        }

        List<String> riceKeys;
        synchronized (this.riceBases) {
            riceKeys = new ArrayList(this.riceBases);
        }

        for (String key : riceKeys) {
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

    /**
     * 将玩家所在世界内的真实作物方块状态重新发送给客户端，用于卸载时还原显示。
     */
    private void restoreRealCropBlocks(Player player) {
        for (String key : this.customCrops.keySet()) {
            Block block = this.blockFromKey(key);
            if (block != null && block.getWorld().equals(player.getWorld())) {
                player.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        }

        List<String> riceKeys;
        synchronized (this.riceBases) {
            riceKeys = new ArrayList(this.riceBases);
        }

        for (String key : riceKeys) {
            Block base = this.blockFromKey(key);
            if (base != null && base.getWorld().equals(player.getWorld())) {
                Block current = base;

                for (int guard = 0; this.isKelp(current) && guard++ < 64; current = current.getRelative(BlockFace.UP)) {
                    player.sendBlockChange(current.getLocation(), current.getBlockData());
                }
            }
        }

    }

    /**
     * 将“世界名;x;y;z”键解析回方块；区块未加载或格式非法时返回 null。
     */
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
                } catch (NumberFormatException invalidNumber) {
                    return null;
                }
            }
        }
    }

    /**
     * 判断方块是否位于玩家的外观可见半径内（同一世界且距离平方不超过阈值）。
     */
    private boolean isNear(Player player, Block block) {
        if (!player.getWorld().equals(block.getWorld())) {
            return false;
        } else {
            return player.getLocation().distanceSquared(block.getLocation()) <= (double) 16384.0F;
        }
    }

    /**
     * 将可年龄化方块（Ageable）的生长进度映射为 0~3 的外观阶段。
     */
    private int cropStage(Block block) {
        BlockData blockData = block.getBlockData();
        if (blockData instanceof Ageable ageable) {
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

    /**
     * 由作物模型名反推外观模型索引（番茄 0-3，洋葱 4-7，水稻 8-11）。
     */
    private int cropDisplayModelIndex(String modelName) {
        if (modelName.startsWith("tomato_stage_")) {
            return Integer.parseInt(modelName.substring("tomato_stage_".length()));
        } else if (modelName.startsWith("onion_stage_")) {
            return 4 + Integer.parseInt(modelName.substring("onion_stage_".length()));
        } else {
            for (int index = 0; index < RICE_VISUAL_MODELS.length; ++index) {
                if (RICE_VISUAL_MODELS[index].equals(modelName)) {
                    return 8 + index;
                }
            }

            throw new IllegalArgumentException("Unknown crop display model: " + modelName);
        }
    }

    /**
     * 构建用于渲染作物外观的调试棒物品，写入自定义模型数据与 item_model。
     */
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
            } catch (NoSuchMethodException noSuchMethod) {
            } catch (LinkageError | ReflectiveOperationException ex) {
                this.plugin.getLogger().warning("[SeasonFoods] Could not set crop item_model: " + ((Throwable) ex).getMessage());
            }

            item.setItemMeta(meta);
            return item;
        }
    }

    /**
     * 确保指定方块存在正确的作物外观实体（ItemDisplay + Interaction）；模型变化时更新显示。
     */
    private CropVisual ensureCropVisual(Block block, String modelName, String rootKey) {
        String locationKey = this.locKey(block);
        CropVisual visual = (CropVisual) this.cropVisuals.get(locationKey);
        if (visual != null && (!visual.display().isValid() || !visual.interaction().isValid())) {
            this.removeCropVisual(locationKey, visual);
            visual = null;
        }

        if (visual != null && !visual.rootKey().equals(rootKey)) {
            this.removeCropVisual(locationKey, visual);
            visual = null;
        }

        if (visual == null) {
            Location displayLocation = block.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
            ItemDisplay display = (ItemDisplay) block.getWorld().spawnEntity(displayLocation, EntityType.ITEM_DISPLAY);
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
            Location interactionLocation = block.getLocation().add((double) 0.5F, (double) 0.0F, (double) 0.5F);
            Interaction interaction = (Interaction) block.getWorld().spawnEntity(interactionLocation, EntityType.INTERACTION);
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

            for (Player online : Bukkit.getOnlinePlayers()) {
                this.applyVisualAudience(online, visual);
            }
        } else if (!visual.modelName().equals(modelName)) {
            visual.display().setItemStack(this.cropDisplayItem(modelName));
            visual = new CropVisual(visual.display(), visual.interaction(), modelName, visual.rootKey());
            this.cropVisuals.put(locationKey, visual);
        }

        return visual;
    }

    /**
     * 按玩家类型设置外观实体可见性：基岩版玩家隐藏实体，Java 版玩家显示实体。
     */
    private void applyVisualAudience(Player player, CropVisual visual) {
        if (this.isBedrockPlayer(player)) {
            player.hideEntity(this.plugin, visual.display());
            player.hideEntity(this.plugin, visual.interaction());
        } else {
            player.showEntity(this.plugin, visual.display());
            player.showEntity(this.plugin, visual.interaction());
        }

    }

    /**
     * 判断玩家是否为基岩版（结果按 UUID 缓存）。
     */
    private boolean isBedrockPlayer(Player player) {
        return (Boolean) this.bedrockPlayerCache.computeIfAbsent(player.getUniqueId(), this::detectBedrockPlayer);
    }

    /**
     * 依次尝试 Geyser 与 Floodgate 的 API 识别基岩版玩家。
     */
    private boolean detectBedrockPlayer(UUID uuid) {
        return this.invokeBedrockCheck("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", uuid) || this.invokeBedrockCheck("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", uuid);
    }

    /**
     * 通过反射调用指定类的单例访问方法与判断方法，检查给定 UUID 是否命中；任一环节失败均返回 false。
     */
    private boolean invokeBedrockCheck(String className, String accessor, String checkMethod, UUID uuid) {
        try {
            Class<?> apiClass = Class.forName(className);
            Object api = apiClass.getMethod(accessor).invoke((Object) null);
            if (api == null) {
                return false;
            } else {
                Object result = apiClass.getMethod(checkMethod, UUID.class).invoke(api, uuid);
                return Boolean.TRUE.equals(result);
            }
        } catch (LinkageError | ReflectiveOperationException reflectionFailure) {
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

    /**
     * 向指定玩家发送单株耕地作物的外观显示。
     */
    private void sendLandCropVisual(Player player, Block block, String cropId) {
        String modelName = this.landCropModelName(cropId, this.cropStage(block));
        CropVisual visual = this.ensureCropVisual(block, modelName, this.locKey(block));
        this.applyVisualAudience(player, visual);
        player.sendBlockChange(block.getLocation(), this.isBedrockPlayer(player) ? this.bedrockCropVisualData(modelName) : Material.AIR.createBlockData());
    }

    /**
     * 向附近所有玩家广播单株耕地作物的外观。
     */
    private void broadcastLandCropVisual(Block block, String cropId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.isNear(player, block)) {
                this.sendLandCropVisual(player, block, cropId);
            }
        }

    }

    /**
     * 向指定玩家发送整株水稻（多段海带柱）的外观显示，并清理多余分段。
     */
    private void sendRiceVisual(Player player, Block base) {
        List<Block> column = this.riceColumn(base);
        String rootKey = this.locKey(base);
        Set<String> expectedLocations = new HashSet();
        int height = column.size();

        for (int index = 0; index < height; ++index) {
            int visualStage;
            if (height == 1) {
                visualStage = 0;
            } else if (index == height - 1) {
                visualStage = height >= 3 ? 3 : 2;
            } else {
                visualStage = 1;
            }

            Block segment = (Block) column.get(index);
            String modelName = RICE_VISUAL_MODELS[visualStage];
            expectedLocations.add(this.locKey(segment));
            CropVisual visual = this.ensureCropVisual(segment, modelName, rootKey);
            this.applyVisualAudience(player, visual);
            player.sendBlockChange(segment.getLocation(), this.isBedrockPlayer(player) ? this.bedrockCropVisualData(modelName, true) : Material.WATER.createBlockData());
        }

        this.removeCropVisualRootExcept(rootKey, expectedLocations);
    }

    /**
     * 向附近所有玩家广播水稻整株外观。
     */
    private void broadcastRiceVisual(Block base) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.isNear(player, base)) {
                this.sendRiceVisual(player, base);
            }
        }

    }

    /**
     * 自基座向上收集连续的海带方块，构成水稻整株的方块列表。
     */
    private List<Block> riceColumn(Block base) {
        List<Block> result = new ArrayList();
        Block current = base;

        for (int guard = 0; this.isKelp(current) && guard++ < 64; current = current.getRelative(BlockFace.UP)) {
            result.add(current);
        }

        return result;
    }

    /**
     * 判断方块是否为海带或海带植株（用于表示水稻）。
     */
    private boolean isKelp(Block block) {
        return block.getType() == Material.KELP || block.getType() == Material.KELP_PLANT;
    }

    /**
     * 自给定方块向下回溯，找到整株海带（水稻）的基座方块。
     */
    private Block findKelpBase(Block start) {
        if (start != null && this.isKelp(start)) {
            Block base = start;

            for (int guard = 0; this.isKelp(base.getRelative(BlockFace.DOWN)) && guard++ < 64; base = base.getRelative(BlockFace.DOWN)) {
            }

            return base;
        } else {
            return null;
        }
    }

    /**
     * 移除单个方块对应的作物外观实体。
     */
    private void removeCropVisualAt(Block block) {
        String locationKey = this.locKey(block);
        CropVisual visual = (CropVisual) this.cropVisuals.get(locationKey);
        if (visual != null) {
            this.removeCropVisual(locationKey, visual);
        }

    }

    /**
     * 移除属于指定根节点的全部作物外观实体（用于水稻整株）。
     */
    private void removeCropVisualRoot(String rootKey) {
        for (Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            if (((CropVisual) entry.getValue()).rootKey().equals(rootKey)) {
                this.removeCropVisual((String) entry.getKey(), (CropVisual) entry.getValue());
            }
        }

    }

    /**
     * 移除指定根节点下、但不在期望位置集合中的作物外观实体。
     */
    private void removeCropVisualRootExcept(String rootKey, Set<String> expectedLocations) {
        for (Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            if (((CropVisual) entry.getValue()).rootKey().equals(rootKey) && !expectedLocations.contains(entry.getKey())) {
                this.removeCropVisual((String) entry.getKey(), (CropVisual) entry.getValue());
            }
        }

    }

    /**
     * 从索引移除并销毁单个作物外观实体（展示实体 + 交互实体）。
     */
    private void removeCropVisual(String locationKey, CropVisual visual) {
        this.cropVisuals.remove(locationKey, visual);
        if (visual.display().isValid()) {
            visual.display().remove();
        }

        if (visual.interaction().isValid()) {
            visual.interaction().remove();
        }

    }

    /**
     * 销毁当前所有作物外观实体。
     */
    private void removeAllCropVisuals() {
        for (Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            this.removeCropVisual((String) entry.getKey(), (CropVisual) entry.getValue());
        }

    }

    /**
     * 周期清理：移除实体失效或方块已不再符合受管作物条件的过期外观。
     */
    private void pruneInvalidCropVisuals() {
        for (Map.Entry<String, CropVisual> entry : new ArrayList<>(this.cropVisuals.entrySet())) {
            CropVisual visual = (CropVisual) entry.getValue();
            if (visual.display().isValid() && visual.interaction().isValid()) {
                Block root = this.blockFromKey(visual.rootKey());
                if (root != null) {
                    String landCrop = (String) this.customCrops.get(visual.rootKey());
                    if (landCrop != null) {
                        if (!((String) entry.getKey()).equals(visual.rootKey()) || this.cropMaterialFor(landCrop) != root.getType()) {
                            this.removeCropVisual((String) entry.getKey(), visual);
                        }
                    } else if (this.riceBases.contains(visual.rootKey()) && this.isKelp(root)) {
                        Block segment = this.blockFromKey((String) entry.getKey());
                        if (segment != null && !this.isKelp(segment)) {
                            this.removeCropVisual((String) entry.getKey(), visual);
                        }
                    } else {
                        this.removeCropVisual((String) entry.getKey(), visual);
                    }
                }
            } else {
                this.removeCropVisual((String) entry.getKey(), visual);
            }
        }

    }

    /**
     * 移除指定位置的作物外观，并在下一 tick 向附近玩家重新发送真实方块状态。
     */
    private void broadcastRealBlockLater(Location location) {
        Location copy = location.clone();
        this.removeCropVisualAt(copy.getBlock());
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Block block = copy.getBlock();

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (this.isNear(player, block)) {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                }
            }

        });
    }

    /**
     * 玩家手持水稻种子右键水源：在水源方块上生成海带（水稻）并登记基座。
     */
    @EventHandler
    public void onRicePlant(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = event.getItem();
                if (item != null) {
                    String foodId = this.getFoodId(item);
                    if ("rice".equals(foodId)) {
                        Block clicked = event.getClickedBlock();
                        if (clicked != null) {
                            Block waterBlock = clicked.getType() == Material.WATER ? clicked : clicked.getRelative(BlockFace.UP);
                            if (waterBlock.getType() == Material.WATER) {
                                BlockData blockData = waterBlock.getBlockData();
                                if (blockData instanceof Levelled) {
                                    Levelled levelData = (Levelled) blockData;
                                    if (levelData.getLevel() == 0) {
                                        event.setCancelled(true);
                                        this.consumeOneFromHand(event.getPlayer(), item);
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
                                event.setCancelled(true);
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 玩家手持番茄/洋葱种子右键：交由 tryPlantCustomCrop 尝试在耕地上种植。
     */
    @EventHandler
    public void onCustomSeedPlant(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = event.getItem();
                if (item != null) {
                    String id = this.getFoodId(item);
                    if (id != null) {
                        if ("tomato".equals(id) || "onion".equals(id)) {
                            this.tryPlantCustomCrop(event, id);
                        }

                    }
                }
            }
        }
    }

    /**
     * 监听破坏水稻：破坏海带段或基座上方方块时，将整株海带转为水源、移除记录，
     * 并掉落对应数量的水稻种子（并清理原版海带掉落物）。
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onRiceBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
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
                event.setDropItems(false);
            }

            World world = base.getWorld();
            Location dropLoc = base.getLocation().add((double) 0.5F, (double) 0.5F, (double) 0.5F);
            int segments = 0;
            List<Location> clearedLocations = new ArrayList();

            for (Block current = base; current.getType() == Material.KELP || current.getType() == Material.KELP_PLANT; current = current.getRelative(BlockFace.UP)) {
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
            Player breaker = event.getPlayer();
            this.applyFoodLocalization(breaker, seeds, "rice");
            world.dropItemNaturally(dropLoc, seeds);

            for (Location location : clearedLocations) {
                this.broadcastRealBlockLater(location);
            }

        }
    }

    /**
     * 在掉落点附近延迟清理指定种类的原版掉落物（仅移除存活时间很短的物品，避免误删其他掉落）。
     */
    private void scheduleVanillaCropDropCleanup(Location cropDropLocation, Set<Material> vanillaDrops) {
        Location center = cropDropLocation.clone();
        long[] delays = new long[]{1L, 3L};

        for (long delay : delays) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                World world = center.getWorld();
                if (world != null) {
                    for (Entity entity : world.getNearbyEntities(center, (double) 1.25F, (double) 1.25F, (double) 1.25F)) {
                        if (entity instanceof Item) {
                            Item dropped = (Item) entity;
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

    /**
     * 对物品应用食物本地化（名称与 lore，含生长季节）；传入玩家用其语言，否则用服务端默认语言。
     */
    private void applyFoodLocalization(Player player, ItemStack item, String foodId) {
        if (item != null && foodId != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (this.isIngredientId(foodId)) {
                    meta.removeEnchant(Enchantment.LURE);
                }

                String baseKey = "items.food." + foodId;
                String name = player != null ? this.lang.tr(player, baseKey + ".name") : this.lang.trServer(baseKey + ".name");
                String lore1 = player != null ? this.lang.tr(player, baseKey + ".lore_1") : this.lang.trServer(baseKey + ".lore_1");
                String lore2 = player != null ? this.lang.tr(player, baseKey + ".lore_2") : this.lang.trServer(baseKey + ".lore_2");
                meta.setDisplayName(name);
                List<String> lore = new ArrayList();
                lore.add(lore1);
                lore.add(lore2);
                this.appendGrowthLore(player, lore, foodId);
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }
    }

    /**
     * 判断是否为自定义食材 ID（番茄/洋葱/水稻）。
     */
    private boolean isIngredientId(String foodId) {
        return "tomato".equals(foodId) || "onion".equals(foodId) || "rice".equals(foodId);
    }

    /**
     * 判断是否为自定义食物 ID（食材或菜肴）。
     */
    private boolean isFoodId(String foodId) {
        return this.isIngredientId(foodId) || DISH_IDS.contains(foodId);
    }

    /**
     * 清除玩家背包内自定义食物上的诱饵附魔（发光）标记，并补全番茄的食用组件。
     */
    private void removeFoodGlint(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
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

    /**
     * 返回该方块记录的人工作物 ID（仅耕地作物），无记录时返回 null。
     */
    public String getArtificialCropId(Block block) {
        return block == null ? null : (String) this.customCrops.get(this.locKey(block));
    }

    /**
     * 取消某方块的人工作物登记；仅当当前记录与期望值一致时才移除，并通知刷新真实方块。
     */
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

    /**
     * 返回该方块处人工作物的允许生长季节集合。
     */
    public EnumSet<Season> getArtificialGrowthSeasons(Block block) {
        return this.growthSeasonsFor(this.artificialCropIdAt(block));
    }

    /**
     * 解析方块处的人工作物 ID：优先耕地作物记录，其次回溯水稻基座。
     */
    private String artificialCropIdAt(Block block) {
        if (block == null) {
            return null;
        } else {
            String landCrop = (String) this.customCrops.get(this.locKey(block));
            if (landCrop != null) {
                return landCrop;
            } else {
                Block riceBase = this.findKelpBase(block);
                return riceBase != null && this.riceBases.contains(this.locKey(riceBase)) ? "rice" : null;
            }
        }
    }

    /**
     * 返回人工作物的当前生长值（水稻按海带柱高度，耕地作物按年龄）；无法判断时返回 -1。
     */
    private int artificialCropGrowthValue(Block block, String cropId) {
        if (block != null && cropId != null) {
            if ("rice".equals(cropId)) {
                Block riceBase = this.findKelpBase(block);
                return riceBase == null ? -1 : this.riceColumn(riceBase).size();
            } else {
                BlockData blockData = block.getBlockData();
                if (blockData instanceof Ageable) {
                    Ageable ageable = (Ageable) blockData;
                    return ageable.getAge();
                } else {
                    return -1;
                }
            }
        } else {
            return -1;
        }
    }

    /**
     * 判断物品是否为指定人工作物的种子。
     */
    public boolean isArtificialSeed(ItemStack item, String cropId) {
        return item != null && cropId != null && cropId.equals(this.getFoodId(item));
    }

    /**
     * 判断物品是否为任一种人工作物种子（番茄/洋葱/水稻）。
     */
    public boolean isAnyArtificialSeed(ItemStack item) {
        return item != null && this.isIngredientId(this.getFoodId(item));
    }

    /**
     * 返回指定人工作物种子的基础材料。
     */
    public Material getArtificialSeedMaterial(String cropId) {
        Material seedMaterial;
        switch (cropId) {
            case "tomato" -> seedMaterial = Material.BEETROOT_SEEDS;
            case "onion" -> seedMaterial = Material.WHEAT_SEEDS;
            default -> seedMaterial = null;
        }

        return seedMaterial;
    }

    /**
     * 构建指定人工作物的收获掉落物（数量至少为 1），并应用本地化。
     */
    public ItemStack createArtificialCropDrop(String cropId, Player player, int amount) {
        ItemStack chosenPrototype;
        switch (cropId) {
            case "tomato" -> chosenPrototype = this.tomatoProto;
            case "onion" -> chosenPrototype = this.onionProto;
            default -> chosenPrototype = null;
        }

        ItemStack prototype = chosenPrototype;
        if (prototype == null) {
            return null;
        } else {
            ItemStack drop = prototype.clone();
            drop.setAmount(Math.max(1, amount));
            this.applyFoodLocalization(player, drop, cropId);
            return drop;
        }
    }

    /**
     * 结束一次人工作物收获：按需补种（重置年龄）或还原真实方块，并标记数据待保存。
     */
    public void finishArtificialHarvest(Block block, String cropId, boolean replant) {
        if (block != null && cropId != null) {
            this.customCrops.remove(this.locKey(block));
            if (replant) {
                Material cropMaterial = this.cropMaterialFor(cropId);
                if (cropMaterial != null) {
                    block.setType(cropMaterial, false);
                    BlockData blockData = block.getBlockData();
                    if (blockData instanceof Ageable) {
                        Ageable ageable = (Ageable) blockData;
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

    /**
     * 返回人工作物 ID 对应的真实作物方块材料。
     */
    private Material cropMaterialFor(String id) {
        Material cropMaterial;
        switch (id) {
            case "tomato" -> cropMaterial = Material.BEETROOTS;
            case "onion" -> cropMaterial = Material.WHEAT;
            default -> cropMaterial = null;
        }

        return cropMaterial;
    }

    /**
     * 尝试在耕地上方种植人工作物种子，成功后登记记录、广播外观并返回 true。
     */
    private boolean tryPlantCustomCrop(PlayerInteractEvent event, String cropId) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return false;
        } else if (clicked.getType() != Material.FARMLAND) {
            return false;
        } else {
            Block plantAt = clicked.getRelative(BlockFace.UP);
            if (!plantAt.getType().isAir()) {
                return false;
            } else {
                Material cropMaterial = this.cropMaterialFor(cropId);
                if (cropMaterial == null) {
                    return false;
                } else {
                    event.setCancelled(true);
                    this.consumeOneFromHand(event.getPlayer(), event.getItem());
                    plantAt.setType(cropMaterial, false);
                    this.customCrops.put(this.locKey(plantAt), cropId);
                    this.markCropsDirty();
                    this.broadcastLandCropVisual(plantAt, cropId);
                    this.scheduleVisualRefresh(plantAt);
                    return true;
                }
            }
        }
    }

    /**
     * 作物外观记录：绑定某作物方块对应的展示实体（{@link ItemDisplay}）、交互实体（{@link Interaction}）、
     * 当前使用的模型名，以及所属根节点键（用于水稻多段柱状结构的归属判断）。
     */
    private record CropVisual(ItemDisplay display, Interaction interaction, String modelName, String rootKey) {
        private CropVisual {
        }
    }
}
