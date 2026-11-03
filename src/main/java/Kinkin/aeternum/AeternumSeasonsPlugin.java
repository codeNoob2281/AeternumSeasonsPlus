package kinkin.aeternum;

import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.command.*;
import kinkin.aeternum.compat.CustomCropsSeasonHook;
import kinkin.aeternum.compat.WorldCompat;
import kinkin.aeternum.crafting.*;
import kinkin.aeternum.dimension.FrostWorldGenerator;
import kinkin.aeternum.dimension.HeatWorldGenerator;
import kinkin.aeternum.events.SeasonalEventService;
import kinkin.aeternum.farming.CompostService;
import kinkin.aeternum.farming.SeasonalCropGrowthListener;
import kinkin.aeternum.fauna.*;
import kinkin.aeternum.food.FoodRecipeBookLoot;
import kinkin.aeternum.food.SeasonFoods;
import kinkin.aeternum.frost.*;
import kinkin.aeternum.heat.*;
import kinkin.aeternum.hud.HudService;
import kinkin.aeternum.items.SeasonClockService;
import kinkin.aeternum.lang.LanguageManager;
import kinkin.aeternum.portal.FrostOverworldPortals;
import kinkin.aeternum.portal.HeatNetherPortals;
import kinkin.aeternum.portal.VanillaPortalIsolation;
import kinkin.aeternum.temperature.ThermoService;
import kinkin.aeternum.util.Configs;
import kinkin.aeternum.util.YamlDefaults;
import kinkin.aeternum.weather.SeasonalWeatherService;
import kinkin.aeternum.world.*;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * AeternumSeasons 插件主类。
 *
 * <p>本插件为一套围绕“四季”展开的综合性生存玩法扩展，主类负责装配并串联以下子系统：
 * 日历/季节、HUD、温度、天气、作物生长、季节事件、动物迁徙、经济作物与合成物品、
 * 霜界（Frost）与热界（Heat）两个自定义维度的环境/怪物/战利品/传送门，以及世界美化绘制器等。</p>
 *
 * <p><b>启动流程（{@link #onEnable()}）：</b></p>
 * <ol>
 *     <li>调用 {@code saveDefaultConfig()} 并借助 {@link YamlDefaults} 逐份合并 defaults（config、calendar、climate、
 *     crops、events、fauna、hud、lang、survival、visual 以及各语言文件），确保升级后新增配置键存在；</li>
 *     <li>创建 {@link Configs} 与 {@link LanguageManager} 并注册语言监听，加载全部配置；</li>
 *     <li>读取“禁用季节特效世界”列表，初始化 {@link WinterWorldGuardHelper}；读取霜界/热界传送门启用开关；</li>
 *     <li>构造核心服务与监听器：季节、HUD、冬季绘制、生物群系伪装及其生成守卫、季节天气、作物生长、
 *     季节事件、秋土/树冠绘制、动物迁徙、霜界与热界各自的监听/管理器、植物、快速落叶、村民覆盖、鱼笼、
 *     生物生成、牛棚、寒冷暴露、传送门、温度等；</li>
 *     <li>构造合成物品（靴子、火把、灯笼、铃铛、锄头、斧头、堆肥器、魔像升级等）与 {@link SeasonCraftCommand}，
 *     并注册各个命令（seasoncraft、season、seasonbiomefix、asevent、seasonguide）及其 Tab 补全；</li>
 *     <li>统一注册上述服务/监听器；随后按配置决定是否启用霜界、热界、季节食物；</li>
 *     <li>注册季节钟物品与（若存在）PlaceholderAPI 扩展 {@link AeternumPlaceholders}。</li>
 * </ol>
 *
 * <p><b>关闭流程（{@link #onDisable()}）：</b>逆序注销所有服务与监听器，持久化季节数据、保存全部配置，
 * 并清理本插件注册的所有合成配方，确保重载/关服后无残留。</p>
 *
 * <p><b>依赖：</b>Bukkit/Paper 服务端；可选软依赖 PlaceholderAPI、CustomCrops、Worlds。</p>
 */
public final class AeternumSeasonsPlugin extends JavaPlugin {
    /**
     * 配置访问器，封装 config/calendar/climate 等文件的读取。
     */
    public Configs cfg;
    /**
     * 季节/日历核心服务。
     */
    private SeasonService seasons;
    /**
     * HUD 显示服务。
     */
    private HudService hud;
    /**
     * 多语言管理器。
     */
    public LanguageManager lang;
    /**
     * 冬季地形绘制器。
     */
    private WinterWorldPainter winterPainter;
    /**
     * 生物群系伪装适配器。
     */
    private BiomeSpoofAdapter biomeSpoof;
    /**
     * 雪傀儡生物群系保护。
     */
    private SnowGolemBiomeProtection snowGolemProtection;
    /**
     * 季节天气服务。
     */
    private SeasonalWeatherService seasonalWeather;
    /**
     * 季节作物生长监听器。
     */
    private SeasonalCropGrowthListener cropGrowth;
    /**
     * 季节事件服务。
     */
    private SeasonalEventService eventService;
    /**
     * 秋季土壤绘制器。
     */
    private AutumnSoilPainter autumnSoilPainter;
    /**
     * 树冠积雪绘制器。
     */
    private CanopySnowPainter canopySnowPainter;
    /**
     * 动物迁徙服务。
     */
    public AnimalMigrationService migration;
    /**
     * 雪地靴（合成物品）。
     */
    public SnowStepBoots snowStepBoots;
    /**
     * 拉布拉多靴（合成物品）。
     */
    public LabradorBoots labradorBoots;
    /**
     * 春日疾步靴（合成物品）。
     */
    public SpringStrideBoots springStrideBoots;
    /**
     * 太阳能火把（合成物品）。
     */
    public SolarTorch solarTorch;
    /**
     * 高级堆肥器（合成物品）。
     */
    public AdvancedComposter advancedComposter;
    /**
     * 春铃（合成物品）。
     */
    public SpringBell springBell;
    /**
     * 月亮灯笼（合成物品）。
     */
    public LunarLantern lunarLantern;
    /**
     * 收获锄（合成物品）。
     */
    public HarvestHoe harvestHoe;
    /**
     * 自然之斧（合成物品）。
     */
    public NaturalTreeAxe naturalTreeAxe;
    /**
     * 魔像升级（合成物品）。
     */
    private GolemUpgrades golemUpgrades;
    /**
     * 霜界环境监听器。
     */
    private FrostEnvironmentListener frostEnv;
    /**
     * 霜界主世界传送门。
     */
    private FrostOverworldPortals frostOverworldPortals;
    /**
     * 霜界床铺管理器。
     */
    private FrostBedManager frostBedManager;
    /**
     * 霜界怪物监听器。
     */
    private FrostMobListener frostMobListener;
    /**
     * 霜界 Boss 管理器。
     */
    private FrostBossManager frostBossManager;
    /**
     * 霜界世界指南。
     */
    private FrostWorldGuide frostWorldGuide;
    /**
     * 霜界生物群系修复器。
     */
    private FrostBiomeFixer frostBiomeFixer;
    /**
     * 热界世界指南。
     */
    private HeatWorldGuide heatWorldGuide;
    /**
     * 热界下界传送门。
     */
    private HeatNetherPortals heatNetherPortals;
    /**
     * 原版传送门隔离逻辑。
     */
    private VanillaPortalIsolation vanillaPortalIsolation;
    /**
     * 热界环境监听器。
     */
    private HeatEnvironmentListener heatEnvironmentListener;
    /**
     * 热界怪物强度缩放器。
     */
    private HeatMobScaler heatMobScaler;
    /**
     * 热界战利品监听器。
     */
    private HeatLootListener heatLootListener;
    /**
     * 热界怪物变体监听器。
     */
    private HeatVariantListener heatVariantListener;
    /**
     * 季节食物系统。
     */
    private SeasonFoods foods;
    /**
     * 食物配方书战利品。
     */
    private FoodRecipeBookLoot foodRecipeBookLoot;
    /**
     * 季节植物控制器。
     */
    private SeasonalFloraController flora;
    /**
     * 季节钟物品服务。
     */
    private SeasonClockService seasonClock;
    /**
     * 堆肥服务。
     */
    private CompostService compost;
    /**
     * 快速落叶服务。
     */
    private FastLeafDecayService fastLeafDecay;
    /**
     * 村民类型覆盖。
     */
    private VillagerTypeOverrides villagerTypes;
    /**
     * 禁用季节特效的世界名列表。
     */
    private List<String> disabledWorlds = new ArrayList();
    /**
     * 幻术师生成监听器。
     */
    private IllusionerSpawnListener illusionerSpawn;
    /**
     * 鱼笼服务。
     */
    private FishTrapService fishTraps;
    /**
     * 生物生成服务。
     */
    private CreatureGeneratorService creatureGenerator;
    /**
     * 马厩/牛棚服务。
     */
    private StableBarnService stableBarn;
    /**
     * 寒冷暴露服务。
     */
    private ColdExposureService coldExposure;
    /**
     * 生物群系伪装生成守卫。
     */
    private BiomeSpoofSpawnGuard biomeSpoofSpawnGuard;
    /**
     * 季节合成命令。
     */
    private SeasonCraftCommand seasonCraft;
    /**
     * CustomCrops 是否已成功挂钩。
     */
    private boolean customCropsHooked = false;
    /**
     * 服务端是否安装了 Worlds 插件。
     */
    private boolean worldsPluginPresent = false;
    /**
     * 温度服务。
     */
    private ThermoService thermo;
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private static final String FROST_WORLD_NAME = "aeternum_frost";

    public AeternumSeasonsPlugin() {
        super();
    }

    /**
     * 确保霜界世界 {@code aeternum_frost} 存在：已存在则记录日志，否则用 {@link FrostWorldGenerator} 创建。
     */
    private void ensureFrostWorld() {
        String name = "aeternum_frost";
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            String msg = this.lang.trf((Player) null, "log.frost_world_loaded", Map.of("name", name));
            this.getLogger().info(ChatColor.stripColor(msg));
        } else {
            WorldCreator creator = new WorldCreator(name);
            creator.environment(Environment.NORMAL);
            creator.generator(new FrostWorldGenerator());
            World created = creator.createWorld();
            if (created != null) {
                String msg = this.lang.trf((Player) null, "log.frost_world_created", Map.of("name", name));
                this.getLogger().info(ChatColor.stripColor(msg));
            } else {
                String msg = this.lang.trf((Player) null, "log.frost_world_failed", Map.of("name", name));
                this.getLogger().warning(ChatColor.stripColor(msg));
            }

        }
    }

    public FrostWorldGuide getFrostWorldGuide() {
        return this.frostWorldGuide;
    }

    /**
     * 确保热界世界 {@code aeternum_heat} 存在：已存在则记录日志，否则以 {@link HeatWorldGenerator}
     * 创建一个下界类型世界，并关闭其昼夜/天气循环、保持出生点常驻内存。
     */
    private void setupHeatWorld() {
        String name = "aeternum_heat";
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            String msg = this.lang.trf((Player) null, "log.heat_world_loaded", Map.of("name", name));
            this.getLogger().info(ChatColor.stripColor(msg));
        } else {
            String creatingMsg = this.lang.trf((Player) null, "log.heat_world_creating", Map.of("name", name));
            this.getLogger().info(ChatColor.stripColor(creatingMsg));
            WorldCreator creator = new WorldCreator(name);
            creator.environment(Environment.NETHER);
            creator.type(WorldType.NORMAL);
            creator.generator(new HeatWorldGenerator());
            World heat = creator.createWorld();
            if (heat != null) {
                heat.setKeepSpawnInMemory(true);
                WorldCompat.disableDaylightCycle(heat, this);
                WorldCompat.disableWeatherCycle(heat, this);
                String msg = this.lang.trf((Player) null, "log.heat_world_created", Map.of("name", name));
                this.getLogger().info(ChatColor.stripColor(msg));
            }

        }
    }

    /**
     * 从主配置读取“禁用季节特效”的世界名列表，并在为空时退化为不可变空列表。
     */
    private void loadWorldExclusionList() {
        this.disabledWorlds = this.getConfig().getStringList("worlds.disabled_season_fx");
        if (this.disabledWorlds == null) {
            this.disabledWorlds = Collections.emptyList();
        }

        Logger logger = this.getLogger();
        int disabledCount = this.disabledWorlds.size();
        logger.info("[AeternumSeasons] FX deshabilitados en " + disabledCount + " mundos: " + this.disabledWorlds);
    }

    /**
     * 判断指定世界是否在“禁用季节特效”名单中（{@code null} 视为禁用）。
     */
    public boolean isWorldDisabled(World world) {
        return world == null || this.disabledWorlds.contains(world.getName());
    }

    /**
     * 判断季节食物功能是否开启。
     *
     * <p>同时兼容新版 {@code SeasonFood} 布尔键与旧版 {@code features.season_foods.enabled} 键。</p>
     */
    private boolean isSeasonFoodEnabled() {
        return this.getConfig().contains("SeasonFood") ? this.getConfig().getBoolean("SeasonFood", true) : this.getConfig().getBoolean("features.season_foods.enabled", true);
    }

    /**
     * 获取“主世界”世界对象。
     *
     * <p>优先返回名为 {@code world} 且不是霜界的普通世界；否则遍历所有世界，返回第一个非霜界的
     * 普通（{@link Environment#NORMAL}）世界；都不存在时返回 {@code null}。</p>
     */
    public World getMainOverworld() {
        World byName = Bukkit.getWorld("world");
        if (byName != null && !"aeternum_frost".equalsIgnoreCase(byName.getName())) {
            return byName;
        } else {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() == Environment.NORMAL) {
                    String name = world.getName();
                    if (!"aeternum_frost".equalsIgnoreCase(name)) {
                        return world;
                    }
                }
            }

            return null;
        }
    }

    /**
     * 获取主世界名称；主世界不存在时回退为 {@code "world"}。
     */
    public String getMainOverworldName() {
        World overworld = this.getMainOverworld();
        return overworld != null ? overworld.getName() : "world";
    }

    /**
     * 插件启动入口：完成配置初始化、服务装配、监听器/命令注册与世界创建。
     *
     * <p>大致步骤见类注释；此处额外说明：霜界/热界相关世界与系统仅在对应传送门配置开启时创建，
     * 季节食物按配置注册或反注册，PlaceholderAPI 扩展仅在检测到该插件时注册。</p>
     */
    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        YamlDefaults.merge(this, "config.yml");
        YamlDefaults.merge(this, "calendar.yml");
        YamlDefaults.merge(this, "climate.yml");
        YamlDefaults.merge(this, "crops.yml");
        YamlDefaults.merge(this, "events.yml");
        YamlDefaults.merge(this, "fauna.yml");
        YamlDefaults.merge(this, "hud.yml");
        YamlDefaults.merge(this, "lang.yml");
        YamlDefaults.merge(this, "survival.yml");
        YamlDefaults.merge(this, "visual.yml");
        YamlDefaults.merge(this, "lang/en_US.yml");
        YamlDefaults.merge(this, "lang/es_ES.yml");
        YamlDefaults.merge(this, "lang/es_MX.yml");
        YamlDefaults.merge(this, "lang/de_DE.yml");
        YamlDefaults.merge(this, "lang/fr_FR.yml");
        YamlDefaults.merge(this, "lang/id_ID.yml");
        YamlDefaults.merge(this, "lang/it_IT.yml");
        YamlDefaults.merge(this, "lang/pl_PL.yml");
        YamlDefaults.merge(this, "lang/pt_BR.yml");
        YamlDefaults.merge(this, "lang/ru_RU.yml");
        YamlDefaults.merge(this, "lang/tr_TR.yml");
        YamlDefaults.merge(this, "lang/vi_VN.yml");
        YamlDefaults.merge(this, "lang/zh_CN.yml");
        YamlDefaults.merge(this, "lang/zh_TW.yml");
        this.cfg = new Configs(this);
        this.lang = new LanguageManager(this);
        this.lang.register();
        this.cfg.loadAll();
        this.loadWorldExclusionList();
        WinterWorldGuardHelper.init(this);
        boolean frostEnabled = this.getConfig().getBoolean("features.portals.frost.enabled", true);
        boolean heatEnabled = this.getConfig().getBoolean("features.portals.heat.enabled", true);
        this.seasons = new SeasonService(this);
        this.hookCustomCrops();
        this.hud = new HudService(this, this.seasons);
        this.winterPainter = new WinterWorldPainter(this, this.seasons);
        this.biomeSpoof = new BiomeSpoofAdapter(this, this.seasons);
        this.snowGolemProtection = new SnowGolemBiomeProtection(this);
        this.biomeSpoof.setEnabled(this.cfg.climate.getBoolean("biome_spoof.enabled", true));
        this.biomeSpoofSpawnGuard = new BiomeSpoofSpawnGuard(this, this.biomeSpoof);
        this.biomeSpoofSpawnGuard.setEnabled(this.cfg.climate.getBoolean("biome_spoof.spawn_guard.enabled", true));
        this.seasonalWeather = new SeasonalWeatherService(this, this.seasons);
        this.cropGrowth = new SeasonalCropGrowthListener(this, this.seasons);
        this.eventService = new SeasonalEventService(this, this.seasons);
        this.autumnSoilPainter = new AutumnSoilPainter(this, this.seasons, this.biomeSpoof);
        this.migration = new AnimalMigrationService(this, this.seasons);
        this.canopySnowPainter = new CanopySnowPainter(this, this.seasons, this.winterPainter);
        this.frostEnv = new FrostEnvironmentListener(this);
        this.frostOverworldPortals = new FrostOverworldPortals(this, this.frostBedManager);
        this.frostMobListener = new FrostMobListener(this);
        this.frostBossManager = new FrostBossManager(this, this.seasons);
        this.frostWorldGuide = new FrostWorldGuide(this);
        this.frostBiomeFixer = new FrostBiomeFixer(this);
        this.heatWorldGuide = new HeatWorldGuide(this);
        this.heatEnvironmentListener = new HeatEnvironmentListener(this);
        this.heatMobScaler = new HeatMobScaler(this);
        this.heatLootListener = new HeatLootListener(this);
        this.heatVariantListener = new HeatVariantListener(this);
        this.flora = new SeasonalFloraController(this, this.seasons);
        this.fastLeafDecay = new FastLeafDecayService(this);
        this.villagerTypes = new VillagerTypeOverrides(this, this.lang);
        this.illusionerSpawn = new IllusionerSpawnListener(this);
        this.fishTraps = new FishTrapService(this);
        this.creatureGenerator = new CreatureGeneratorService(this);
        this.stableBarn = new StableBarnService(this);
        this.coldExposure = new ColdExposureService(this, this.seasons);
        this.heatNetherPortals = new HeatNetherPortals(this);
        this.vanillaPortalIsolation = new VanillaPortalIsolation(this);
        this.thermo = new ThermoService(this, this.seasons);
        this.worldsPluginPresent = Bukkit.getPluginManager().isPluginEnabled("Worlds");
        if (this.worldsPluginPresent) {
            this.getLogger().info("[AeternumSeasons] Worlds detectado. Los portales de dimensiones serán manejados por Worlds.");
        }

        this.getServer().getPluginManager().registerEvents(this.villagerTypes, this);
        this.getServer().getPluginManager().registerEvents(this.illusionerSpawn, this);
        this.snowStepBoots = new SnowStepBoots(this);
        this.labradorBoots = new LabradorBoots(this);
        this.springStrideBoots = new SpringStrideBoots(this);
        this.solarTorch = new SolarTorch(this, this.seasons);
        this.advancedComposter = new AdvancedComposter(this, this.seasons);
        this.springBell = new SpringBell(this, this.seasons);
        this.lunarLantern = new LunarLantern(this, this.seasons);
        this.harvestHoe = new HarvestHoe(this);
        this.naturalTreeAxe = new NaturalTreeAxe(this);
        this.golemUpgrades = new GolemUpgrades(this, this.seasons);
        this.foods = new SeasonFoods(this, this.lang);
        this.foodRecipeBookLoot = new FoodRecipeBookLoot(this, this.lang);
        this.seasonClock = new SeasonClockService(this, this.seasons, this.eventService);
        this.compost = new CompostService(this);
        this.seasonCraft = new SeasonCraftCommand(this, this.harvestHoe, this.naturalTreeAxe, this.snowStepBoots, this.springStrideBoots, this.labradorBoots, this.solarTorch, this.lunarLantern, this.springBell, this.advancedComposter, this.golemUpgrades);
        ((PluginCommand) Objects.requireNonNull(this.getCommand("seasoncraft"), "Falta seasoncraft en plugin.yml")).setExecutor(this.seasonCraft);
        Bukkit.getPluginManager().registerEvents(this.seasonCraft, this);
        Bukkit.getPluginManager().registerEvents(new HiddenCommandSendListener(), this);
        SeasonCommand cmd = new SeasonCommand(this, this.seasons, this.hud, this.biomeSpoof);
        PluginCommand seasonCmd = this.getCommand("season");
        if (seasonCmd != null) {
            seasonCmd.setExecutor(cmd);
            seasonCmd.setTabCompleter(cmd);
        }

        PluginCommand fix = this.getCommand("seasonbiomefix");
        if (fix != null) {
            SeasonBiomeFixSpringJungleCommand biomeFixCmd = new SeasonBiomeFixSpringJungleCommand(this, this.biomeSpoof);
            fix.setExecutor(biomeFixCmd);
            fix.setTabCompleter(biomeFixCmd);
        }

        EventCommand ecmd = new EventCommand(this, this.eventService);
        this.getCommand("asevent").setExecutor(ecmd);
        this.getCommand("asevent").setTabCompleter(ecmd);
        CmdSeasonGuide guideCmd = new CmdSeasonGuide(this, this.lang, this.seasons);
        if (this.getCommand("seasonguide") != null) {
            this.getCommand("seasonguide").setExecutor(guideCmd);
            this.getCommand("seasonguide").setTabCompleter(guideCmd);
        }

        this.seasons.register();
        this.hud.register();
        this.winterPainter.register();
        this.biomeSpoof.register();
        this.snowGolemProtection.register();
        this.biomeSpoofSpawnGuard.register();
        this.migration.register();
        this.autumnSoilPainter.register();
        this.eventService.register();
        this.cropGrowth.register();
        this.seasonalWeather.register();
        this.canopySnowPainter.register();
        this.flora.register();
        this.fastLeafDecay.register();
        this.fishTraps.register();
        this.creatureGenerator.register();
        this.stableBarn.register();
        this.coldExposure.register();
        this.thermo.register();
        this.vanillaPortalIsolation.register();
        if (frostEnabled) {
            this.frostEnv.register();
            this.frostOverworldPortals.register();
            this.ensureFrostWorld();
            this.frostBiomeFixer.register();
            this.frostMobListener.register();
            this.frostBossManager.register();
            this.frostWorldGuide.register();
            this.getLogger().info("[AeternumSeasons] Frost world & portals ENABLED.");
        } else {
            this.getLogger().info("[AeternumSeasons] Frost world & portals DISABLED by config.");
        }

        if (heatEnabled) {
            this.setupHeatWorld();
            this.heatWorldGuide.register();
            this.heatNetherPortals.register();
            this.heatEnvironmentListener.register();
            this.heatMobScaler.register();
            this.heatLootListener.register();
            this.heatVariantListener.register();
            this.getLogger().info("[AeternumSeasons] Heat world & portals ENABLED.");
        } else {
            this.getLogger().info("[AeternumSeasons] Heat world & portals DISABLED by config.");
        }

        if (this.isSeasonFoodEnabled()) {
            this.foods.register();
            this.foodRecipeBookLoot.register();
            this.getLogger().info("[AeternumSeasons] SeasonFoods ENABLED.");
        } else {
            this.foods.unregister();
            this.foodRecipeBookLoot.unregister();
            this.getLogger().info("[AeternumSeasons] SeasonFoods DISABLED by config (SeasonFood=false).");
        }

        this.compost.register();
        Bukkit.removeRecipe(new NamespacedKey(this, "season_clock"));
        this.seasonClock.register();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            (new AeternumPlaceholders(this)).register();
            this.getLogger().info("[AeternumSeasons] PlaceholderAPI detected, placeholders registered.");
        } else {
            this.getLogger().info("[AeternumSeasons] PlaceholderAPI not found, skipping placeholder registration.");
        }

        this.getLogger().info("AeternumSeasons enabled.");
    }

    /**
     * 尝试挂钩 CustomCrops 插件（仅在尚未挂钩、季节服务已就绪且 CustomCrops 已启用时执行）。
     *
     * <p>挂钩失败只记录警告，不影响插件其余功能。</p>
     */
    private void hookCustomCrops() {
        if (!this.customCropsHooked) {
            if (this.seasons != null) {
                if (Bukkit.getPluginManager().isPluginEnabled("CustomCrops")) {
                    try {
                        CustomCropsSeasonHook.register(this);
                        this.customCropsHooked = true;
                    } catch (Throwable exception) {
                        this.getLogger().warning("[AeternumSeasons] Failed to hook CustomCrops: " + exception.getMessage());
                    }

                }
            }
        }
    }

    /**
     * 注册一个监听器，用于在 CustomCrops 稍后启用时（插件加载顺序不确定）补做挂钩。
     */
    private void registerCustomCropsHookListener() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(
                    priority = EventPriority.MONITOR
            )
            public void onPluginEnable(PluginEnableEvent event) {
                Plugin enabledPlugin = event.getPlugin();
                if (enabledPlugin != null && "CustomCrops".equalsIgnoreCase(enabledPlugin.getName())) {
                    AeternumSeasonsPlugin.this.hookCustomCrops();
                }

            }
        }, this);
    }

    /**
     * 插件关闭入口：逆序注销所有服务与事件监听器，持久化季节数据与配置，并移除本插件的合成配方。
     *
     * <p>每一步都先判空，以容忍启动中途失败或部分系统未初始化的情况。</p>
     */
    @Override
    public void onDisable() {
        if (this.hud != null) {
            this.hud.unregister();
        }

        if (this.seasons != null) {
            this.seasons.persistNow();
            this.seasons.unregister();
        }

        if (this.cfg != null) {
            this.cfg.saveAll();
        }

        if (this.winterPainter != null) {
            this.winterPainter.unregister();
        }

        if (this.biomeSpoof != null) {
            this.biomeSpoof.unregister();
        }

        if (this.snowGolemProtection != null) {
            this.snowGolemProtection.unregister();
        }

        if (this.biomeSpoofSpawnGuard != null) {
            this.biomeSpoofSpawnGuard.unregister();
        }

        if (this.seasonalWeather != null) {
            this.seasonalWeather.unregister();
        }

        if (this.cropGrowth != null) {
            this.cropGrowth.unregister();
        }

        if (this.eventService != null) {
            this.eventService.unregister();
        }

        if (this.autumnSoilPainter != null) {
            this.autumnSoilPainter.unregister();
        }

        if (this.migration != null) {
            this.migration.unregister();
        }

        if (this.frostEnv != null) {
            this.frostEnv.unregister();
        }

        if (this.frostBossManager != null) {
            this.frostBossManager.unregister();
        }

        if (this.frostWorldGuide != null) {
            this.frostWorldGuide.unregister();
        }

        if (this.frostBiomeFixer != null) {
            this.frostBiomeFixer.unregister();
        }

        if (this.heatNetherPortals != null) {
            this.heatNetherPortals.unregister();
        }

        if (this.vanillaPortalIsolation != null) {
            this.vanillaPortalIsolation.unregister();
        }

        if (this.heatEnvironmentListener != null) {
            this.heatEnvironmentListener.unregister();
        }

        if (this.heatMobScaler != null) {
            this.heatMobScaler.unregister();
        }

        if (this.heatLootListener != null) {
            this.heatLootListener.unregister();
        }

        if (this.heatVariantListener != null) {
            this.heatVariantListener.unregister();
        }

        if (this.heatWorldGuide != null) {
            this.heatWorldGuide.unregister();
        }

        if (this.flora != null) {
            this.flora.unregister();
        }

        if (this.fishTraps != null) {
            this.fishTraps.unregister();
        }

        if (this.creatureGenerator != null) {
            this.creatureGenerator.unregister();
        }

        if (this.stableBarn != null) {
            this.stableBarn.unregister();
        }

        if (this.coldExposure != null) {
            this.coldExposure.unregister();
        }

        if (this.fastLeafDecay != null) {
            this.fastLeafDecay.unregister();
        }

        if (this.villagerTypes != null) {
            HandlerList.unregisterAll(this.villagerTypes);
        }

        if (this.illusionerSpawn != null) {
            HandlerList.unregisterAll(this.illusionerSpawn);
        }

        if (this.lang != null) {
            HandlerList.unregisterAll(this.lang);
        }

        if (this.snowStepBoots != null) {
            this.snowStepBoots.unregister();
        }

        if (this.labradorBoots != null) {
            this.labradorBoots.unregister();
        }

        if (this.springStrideBoots != null) {
            this.springStrideBoots.unregister();
        }

        if (this.solarTorch != null) {
            this.solarTorch.unregister();
        }

        if (this.advancedComposter != null) {
            this.advancedComposter.unregister();
        }

        if (this.springBell != null) {
            this.springBell.unregister();
        }

        if (this.lunarLantern != null) {
            this.lunarLantern.unregister();
        }

        if (this.harvestHoe != null) {
            this.harvestHoe.unregister();
        }

        if (this.naturalTreeAxe != null) {
            this.naturalTreeAxe.unregister();
        }

        if (this.canopySnowPainter != null) {
            this.canopySnowPainter.unregister();
        }

        if (this.golemUpgrades != null) {
            this.golemUpgrades.unregister();
        }

        if (this.frostOverworldPortals != null) {
            this.frostOverworldPortals.unregister();
        }

        if (this.seasonCraft != null) {
            HandlerList.unregisterAll(this.seasonCraft);
            this.seasonCraft = null;
        }

        if (this.frostMobListener != null) {
            this.frostMobListener.unregister();
        }

        if (this.foods != null) {
            this.foods.unregister();
        }

        if (this.seasonClock != null) {
            this.seasonClock.unregister();
        }

        if (this.compost != null) {
            this.compost.unregister();
        }

        if (this.thermo != null) {
            this.thermo.unregister();
        }

        this.removeAllPluginRecipes();
    }

    /**
     * 全量重载：先注销所有现有服务/监听器并保存季节数据，再重新读取配置、重建并注册全部子系统。
     *
     * <p>与 {@link #onEnable()} 类似，但不会重新创建世界；另外会检测是否运行于 Folia，
     * 若是则出于线程安全考虑强制关闭生物群系伪装。</p>
     */
    public synchronized void reloadEverything() {
        this.getLogger().info("[AeternumSeasons] Reload start...");
        if (this.hud != null) {
            this.hud.unregister();
        }

        if (this.winterPainter != null) {
            this.winterPainter.unregister();
        }

        if (this.biomeSpoof != null) {
            this.biomeSpoof.unregister();
        }

        if (this.snowGolemProtection != null) {
            this.snowGolemProtection.unregister();
        }

        if (this.biomeSpoofSpawnGuard != null) {
            this.biomeSpoofSpawnGuard.unregister();
        }

        if (this.seasons != null) {
            this.seasons.persistNow();
            this.seasons.unregister();
            this.seasons = null;
        }

        if (this.seasonalWeather != null) {
            this.seasonalWeather.unregister();
        }

        if (this.cropGrowth != null) {
            this.cropGrowth.unregister();
        }

        if (this.eventService != null) {
            this.eventService.unregister();
        }

        if (this.autumnSoilPainter != null) {
            this.autumnSoilPainter.unregister();
        }

        if (this.migration != null) {
            this.migration.unregister();
        }

        if (this.frostEnv != null) {
            this.frostEnv.unregister();
        }

        if (this.frostBossManager != null) {
            this.frostBossManager.unregister();
        }

        if (this.frostWorldGuide != null) {
            this.frostWorldGuide.unregister();
        }

        if (this.frostBiomeFixer != null) {
            this.frostBiomeFixer.unregister();
        }

        if (this.heatNetherPortals != null) {
            this.heatNetherPortals.unregister();
        }

        if (this.vanillaPortalIsolation != null) {
            this.vanillaPortalIsolation.unregister();
        }

        if (this.heatEnvironmentListener != null) {
            this.heatEnvironmentListener.unregister();
        }

        if (this.heatMobScaler != null) {
            this.heatMobScaler.unregister();
        }

        if (this.heatLootListener != null) {
            this.heatLootListener.unregister();
        }

        if (this.heatVariantListener != null) {
            this.heatVariantListener.unregister();
        }

        if (this.heatWorldGuide != null) {
            this.heatWorldGuide.unregister();
        }

        if (this.flora != null) {
            this.flora.unregister();
        }

        if (this.fishTraps != null) {
            this.fishTraps.unregister();
        }

        if (this.creatureGenerator != null) {
            this.creatureGenerator.unregister();
        }

        if (this.stableBarn != null) {
            this.stableBarn.unregister();
        }

        if (this.coldExposure != null) {
            this.coldExposure.unregister();
        }

        if (this.thermo != null) {
            this.thermo.unregister();
        }

        if (this.fastLeafDecay != null) {
            this.fastLeafDecay.unregister();
        }

        if (this.villagerTypes != null) {
            HandlerList.unregisterAll(this.villagerTypes);
        }

        if (this.illusionerSpawn != null) {
            HandlerList.unregisterAll(this.illusionerSpawn);
        }

        if (this.snowStepBoots != null) {
            this.snowStepBoots.unregister();
        }

        if (this.labradorBoots != null) {
            this.labradorBoots.unregister();
        }

        if (this.springStrideBoots != null) {
            this.springStrideBoots.unregister();
        }

        if (this.solarTorch != null) {
            this.solarTorch.unregister();
        }

        if (this.advancedComposter != null) {
            this.advancedComposter.unregister();
        }

        if (this.springBell != null) {
            this.springBell.unregister();
        }

        if (this.lunarLantern != null) {
            this.lunarLantern.unregister();
        }

        if (this.harvestHoe != null) {
            this.harvestHoe.unregister();
        }

        if (this.naturalTreeAxe != null) {
            this.naturalTreeAxe.unregister();
        }

        if (this.canopySnowPainter != null) {
            this.canopySnowPainter.unregister();
        }

        if (this.golemUpgrades != null) {
            this.golemUpgrades.unregister();
        }

        if (this.frostOverworldPortals != null) {
            this.frostOverworldPortals.unregister();
        }

        if (this.seasonCraft != null) {
            HandlerList.unregisterAll(this.seasonCraft);
            this.seasonCraft = null;
        }

        if (this.frostMobListener != null) {
            this.frostMobListener.unregister();
        }

        if (this.foods != null) {
            this.foods.unregister();
        }

        if (this.seasonClock != null) {
            this.seasonClock.unregister();
        }

        if (this.compost != null) {
            this.compost.unregister();
        }

        this.removeAllPluginRecipes();
        this.reloadConfig();
        this.cfg.loadAll();
        this.loadWorldExclusionList();
        WinterWorldGuardHelper.init(this);
        boolean frostEnabled = this.getConfig().getBoolean("features.portals.frost.enabled", true);
        boolean heatEnabled = this.getConfig().getBoolean("features.portals.heat.enabled", true);
        if (this.lang != null) {
            HandlerList.unregisterAll(this.lang);
        }

        this.lang = new LanguageManager(this);
        this.lang.register();
        this.seasons = new SeasonService(this);
        this.registerCustomCropsHookListener();
        this.hookCustomCrops();
        this.hud = new HudService(this, this.seasons);
        this.winterPainter = new WinterWorldPainter(this, this.seasons);
        this.biomeSpoof = new BiomeSpoofAdapter(this, this.seasons);
        this.snowGolemProtection = new SnowGolemBiomeProtection(this);
        this.biomeSpoofSpawnGuard = new BiomeSpoofSpawnGuard(this, this.biomeSpoof);
        boolean biomeSpoofEnabled = this.cfg.climate.getBoolean("biome_spoof.enabled", true);
        boolean isFolia = false;

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (Throwable ignored) {
            // 未找到 Folia 专属类，说明运行在普通 Paper 上。
        }

        if (isFolia && biomeSpoofEnabled) {
            this.getLogger().warning("[AeternumSeasons] BiomeSpoof desactivado en Folia por seguridad (evita crash + region-safety).");
            biomeSpoofEnabled = false;
        }

        this.biomeSpoof.setEnabled(biomeSpoofEnabled);
        this.biomeSpoofSpawnGuard.setEnabled(this.cfg.climate.getBoolean("biome_spoof.spawn_guard.enabled", true));
        this.seasonalWeather = new SeasonalWeatherService(this, this.seasons);
        this.cropGrowth = new SeasonalCropGrowthListener(this, this.seasons);
        this.eventService = new SeasonalEventService(this, this.seasons);
        this.autumnSoilPainter = new AutumnSoilPainter(this, this.seasons, this.biomeSpoof);
        this.migration = new AnimalMigrationService(this, this.seasons);
        this.canopySnowPainter = new CanopySnowPainter(this, this.seasons, this.winterPainter);
        this.frostEnv = new FrostEnvironmentListener(this);
        this.frostOverworldPortals = new FrostOverworldPortals(this, this.frostBedManager);
        this.frostMobListener = new FrostMobListener(this);
        this.frostBossManager = new FrostBossManager(this, this.seasons);
        this.frostWorldGuide = new FrostWorldGuide(this);
        this.frostBiomeFixer = new FrostBiomeFixer(this);
        this.heatWorldGuide = new HeatWorldGuide(this);
        this.heatEnvironmentListener = new HeatEnvironmentListener(this);
        this.heatMobScaler = new HeatMobScaler(this);
        this.heatLootListener = new HeatLootListener(this);
        this.heatVariantListener = new HeatVariantListener(this);
        this.heatNetherPortals = new HeatNetherPortals(this);
        this.vanillaPortalIsolation = new VanillaPortalIsolation(this);
        this.flora = new SeasonalFloraController(this, this.seasons);
        this.fastLeafDecay = new FastLeafDecayService(this);
        this.villagerTypes = new VillagerTypeOverrides(this, this.lang);
        this.illusionerSpawn = new IllusionerSpawnListener(this);
        this.fishTraps = new FishTrapService(this);
        this.creatureGenerator = new CreatureGeneratorService(this);
        this.stableBarn = new StableBarnService(this);
        this.coldExposure = new ColdExposureService(this, this.seasons);
        this.thermo = new ThermoService(this, this.seasons);
        this.snowStepBoots = new SnowStepBoots(this);
        this.labradorBoots = new LabradorBoots(this);
        this.springStrideBoots = new SpringStrideBoots(this);
        this.solarTorch = new SolarTorch(this, this.seasons);
        this.advancedComposter = new AdvancedComposter(this, this.seasons);
        this.springBell = new SpringBell(this, this.seasons);
        this.lunarLantern = new LunarLantern(this, this.seasons);
        this.harvestHoe = new HarvestHoe(this);
        this.naturalTreeAxe = new NaturalTreeAxe(this);
        this.golemUpgrades = new GolemUpgrades(this, this.seasons);
        this.foods = new SeasonFoods(this, this.lang);
        this.foodRecipeBookLoot = new FoodRecipeBookLoot(this, this.lang);
        this.seasonClock = new SeasonClockService(this, this.seasons, this.eventService);
        this.compost = new CompostService(this);
        this.seasonCraft = new SeasonCraftCommand(this, this.harvestHoe, this.naturalTreeAxe, this.snowStepBoots, this.springStrideBoots, this.labradorBoots, this.solarTorch, this.lunarLantern, this.springBell, this.advancedComposter, this.golemUpgrades);
        ((PluginCommand) Objects.requireNonNull(this.getCommand("seasoncraft"), "Falta seasoncraft en plugin.yml")).setExecutor(this.seasonCraft);
        Bukkit.getPluginManager().registerEvents(this.seasonCraft, this);
        SeasonCommand seasonCmdImpl = new SeasonCommand(this, this.seasons, this.hud, this.biomeSpoof);
        PluginCommand seasonCmd = this.getCommand("season");
        if (seasonCmd != null) {
            seasonCmd.setExecutor(seasonCmdImpl);
            seasonCmd.setTabCompleter(seasonCmdImpl);
        }

        CmdSeasonGuide guideCmd = new CmdSeasonGuide(this, this.lang, this.seasons);
        PluginCommand guide = this.getCommand("seasonguide");
        if (guide != null) {
            guide.setExecutor(guideCmd);
            guide.setTabCompleter(guideCmd);
        }

        EventCommand ecmd2 = new EventCommand(this, this.eventService);
        this.getCommand("asevent").setExecutor(ecmd2);
        this.getCommand("asevent").setTabCompleter(ecmd2);
        PluginCommand fix = this.getCommand("seasonbiomefix");
        if (fix != null) {
            SeasonBiomeFixSpringJungleCommand biomeFixCmd = new SeasonBiomeFixSpringJungleCommand(this, this.biomeSpoof);
            fix.setExecutor(biomeFixCmd);
            fix.setTabCompleter(biomeFixCmd);
        }

        this.seasons.register();
        this.hud.register();
        this.winterPainter.register();
        this.biomeSpoof.register();
        this.snowGolemProtection.register();
        this.biomeSpoofSpawnGuard.register();
        this.migration.register();
        this.autumnSoilPainter.register();
        this.eventService.register();
        this.cropGrowth.register();
        this.seasonalWeather.register();
        this.canopySnowPainter.register();
        this.flora.register();
        this.fastLeafDecay.register();
        this.fishTraps.register();
        this.creatureGenerator.register();
        this.stableBarn.register();
        this.coldExposure.register();
        this.thermo.register();
        this.vanillaPortalIsolation.register();
        this.getServer().getPluginManager().registerEvents(this.villagerTypes, this);
        this.getServer().getPluginManager().registerEvents(this.illusionerSpawn, this);
        if (frostEnabled) {
            this.frostEnv.register();
            this.frostOverworldPortals.register();
            this.frostBiomeFixer.register();
            this.frostWorldGuide.register();
            this.frostMobListener.register();
            this.frostBossManager.register();
            this.ensureFrostWorld();
        }

        if (heatEnabled) {
            this.setupHeatWorld();
            this.heatWorldGuide.register();
            this.heatNetherPortals.register();
            this.heatEnvironmentListener.register();
            this.heatMobScaler.register();
            this.heatLootListener.register();
            this.heatVariantListener.register();
        }

        this.compost.register();
        if (this.isSeasonFoodEnabled()) {
            this.foods.register();
            this.foodRecipeBookLoot.register();
            this.getLogger().info("[AeternumSeasons] SeasonFoods ENABLED.");
        } else {
            this.foods.unregister();
            this.foodRecipeBookLoot.unregister();
            this.getLogger().info("[AeternumSeasons] SeasonFoods DISABLED by config (SeasonFood=false).");
        }

        Bukkit.removeRecipe(new NamespacedKey(this, "season_clock"));
        this.seasonClock.register();
        this.getLogger().info("[AeternumSeasons] Reload done.");
    }

    /**
     * 移除本插件命名空间下的所有合成配方，用于重载或关闭时避免旧配方残留重复。
     *
     * <p>先收集所有属于本插件命名空间的 {@link NamespacedKey}，再逐个删除并统计数量。</p>
     */
    private void removeAllPluginRecipes() {
        String ns = this.getName().toLowerCase(Locale.ROOT);
        List<NamespacedKey> keys = new ArrayList();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            Recipe recipe = (Recipe) iterator.next();
            if (recipe instanceof Keyed keyed) {
                NamespacedKey key = keyed.getKey();
                if (key != null && ns.equalsIgnoreCase(key.getNamespace())) {
                    keys.add(key);
                }
            }
        }

        int removed = 0;

        for (NamespacedKey key : keys) {
            if (Bukkit.removeRecipe(key)) {
                ++removed;
            }
        }

        this.getLogger().info("[AeternumSeasons] Removed " + removed + " old recipes for namespace '" + ns + "'.");
    }

    /**
     * 获取季节/日历服务。
     */
    public SeasonService getSeasons() {
        return this.seasons;
    }

    /**
     * 获取季节事件服务。
     */
    public SeasonalEventService getEventService() {
        return this.eventService;
    }

    /**
     * 获取季节食物系统。
     */
    public SeasonFoods getSeasonFoods() {
        return this.foods;
    }

    /**
     * 获取季节作物生长监听器。
     */
    public SeasonalCropGrowthListener getSeasonalCropGrowthListener() {
        return this.cropGrowth;
    }

    /**
     * 服务端是否安装了 Worlds 插件。
     */
    public boolean isWorldsPluginPresent() {
        return this.worldsPluginPresent;
    }

    /**
     * 是否应把传送门处理交给 Worlds 插件（与 {@link #isWorldsPluginPresent()} 一致）。
     */
    public boolean shouldLetWorldsHandlePortals() {
        return this.worldsPluginPresent;
    }

    /**
     * 获取温度服务。
     */
    public ThermoService getThermo() {
        return this.thermo;
    }
}
