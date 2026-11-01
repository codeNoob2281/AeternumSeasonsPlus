package Kinkin.aeternum;

import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.command.CmdSeasonGuide;
import Kinkin.aeternum.command.EventCommand;
import Kinkin.aeternum.command.HiddenCommandSendListener;
import Kinkin.aeternum.command.SeasonBiomeFixSpringJungleCommand;
import Kinkin.aeternum.command.SeasonCommand;
import Kinkin.aeternum.command.SeasonCraftCommand;
import Kinkin.aeternum.compat.CustomCropsSeasonHook;
import Kinkin.aeternum.compat.WorldCompat;
import Kinkin.aeternum.crafting.AdvancedComposter;
import Kinkin.aeternum.crafting.GolemUpgrades;
import Kinkin.aeternum.crafting.HarvestHoe;
import Kinkin.aeternum.crafting.LabradorBoots;
import Kinkin.aeternum.crafting.LunarLantern;
import Kinkin.aeternum.crafting.NaturalTreeAxe;
import Kinkin.aeternum.crafting.SnowStepBoots;
import Kinkin.aeternum.crafting.SolarTorch;
import Kinkin.aeternum.crafting.SpringBell;
import Kinkin.aeternum.crafting.SpringStrideBoots;
import Kinkin.aeternum.dimension.FrostWorldGenerator;
import Kinkin.aeternum.dimension.HeatWorldGenerator;
import Kinkin.aeternum.events.SeasonalEventService;
import Kinkin.aeternum.farming.CompostService;
import Kinkin.aeternum.farming.SeasonalCropGrowthListener;
import Kinkin.aeternum.fauna.AnimalMigrationService;
import Kinkin.aeternum.fauna.ColdExposureService;
import Kinkin.aeternum.fauna.CreatureGeneratorService;
import Kinkin.aeternum.fauna.FishTrapService;
import Kinkin.aeternum.fauna.StableBarnService;
import Kinkin.aeternum.food.FoodRecipeBookLoot;
import Kinkin.aeternum.food.SeasonFoods;
import Kinkin.aeternum.frost.FrostBedManager;
import Kinkin.aeternum.frost.FrostBiomeFixer;
import Kinkin.aeternum.frost.FrostBossManager;
import Kinkin.aeternum.frost.FrostEnvironmentListener;
import Kinkin.aeternum.frost.FrostMobListener;
import Kinkin.aeternum.frost.FrostWorldGuide;
import Kinkin.aeternum.heat.HeatEnvironmentListener;
import Kinkin.aeternum.heat.HeatLootListener;
import Kinkin.aeternum.heat.HeatMobScaler;
import Kinkin.aeternum.heat.HeatVariantListener;
import Kinkin.aeternum.heat.HeatWorldGuide;
import Kinkin.aeternum.hud.HudService;
import Kinkin.aeternum.items.SeasonClockService;
import Kinkin.aeternum.lang.LanguageManager;
import Kinkin.aeternum.portal.FrostOverworldPortals;
import Kinkin.aeternum.portal.HeatNetherPortals;
import Kinkin.aeternum.portal.VanillaPortalIsolation;
import Kinkin.aeternum.temperature.ThermoService;
import Kinkin.aeternum.util.Configs;
import Kinkin.aeternum.util.YamlDefaults;
import Kinkin.aeternum.weather.SeasonalWeatherService;
import Kinkin.aeternum.world.AutumnSoilPainter;
import Kinkin.aeternum.world.BiomeSpoofAdapter;
import Kinkin.aeternum.world.BiomeSpoofSpawnGuard;
import Kinkin.aeternum.world.CanopySnowPainter;
import Kinkin.aeternum.world.FastLeafDecayService;
import Kinkin.aeternum.world.IllusionerSpawnListener;
import Kinkin.aeternum.world.SeasonalFloraController;
import Kinkin.aeternum.world.SnowGolemBiomeProtection;
import Kinkin.aeternum.world.VillagerTypeOverrides;
import Kinkin.aeternum.world.WinterWorldGuardHelper;
import Kinkin.aeternum.world.WinterWorldPainter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
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

public final class AeternumSeasonsPlugin extends JavaPlugin {
    public Configs cfg;
    private SeasonService seasons;
    private HudService hud;
    public LanguageManager lang;
    private WinterWorldPainter winterPainter;
    private BiomeSpoofAdapter biomeSpoof;
    private SnowGolemBiomeProtection snowGolemProtection;
    private SeasonalWeatherService seasonalWeather;
    private SeasonalCropGrowthListener cropGrowth;
    private SeasonalEventService eventService;
    private AutumnSoilPainter autumnSoilPainter;
    private CanopySnowPainter canopySnowPainter;
    public AnimalMigrationService migration;
    public SnowStepBoots snowStepBoots;
    public LabradorBoots labradorBoots;
    public SpringStrideBoots springStrideBoots;
    public SolarTorch solarTorch;
    public AdvancedComposter advancedComposter;
    public SpringBell springBell;
    public LunarLantern lunarLantern;
    public HarvestHoe harvestHoe;
    public NaturalTreeAxe naturalTreeAxe;
    private GolemUpgrades golemUpgrades;
    private FrostEnvironmentListener frostEnv;
    private FrostOverworldPortals frostOverworldPortals;
    private FrostBedManager frostBedManager;
    private FrostMobListener frostMobListener;
    private FrostBossManager frostBossManager;
    private FrostWorldGuide frostWorldGuide;
    private FrostBiomeFixer frostBiomeFixer;
    private HeatWorldGuide heatWorldGuide;
    private HeatNetherPortals heatNetherPortals;
    private VanillaPortalIsolation vanillaPortalIsolation;
    private HeatEnvironmentListener heatEnvironmentListener;
    private HeatMobScaler heatMobScaler;
    private HeatLootListener heatLootListener;
    private HeatVariantListener heatVariantListener;
    private SeasonFoods foods;
    private FoodRecipeBookLoot foodRecipeBookLoot;
    private SeasonalFloraController flora;
    private SeasonClockService seasonClock;
    private CompostService compost;
    private FastLeafDecayService fastLeafDecay;
    private VillagerTypeOverrides villagerTypes;
    private List<String> disabledWorlds = new ArrayList();
    private IllusionerSpawnListener illusionerSpawn;
    private FishTrapService fishTraps;
    private CreatureGeneratorService creatureGenerator;
    private StableBarnService stableBarn;
    private ColdExposureService coldExposure;
    private BiomeSpoofSpawnGuard biomeSpoofSpawnGuard;
    private SeasonCraftCommand seasonCraft;
    private boolean customCropsHooked = false;
    private boolean worldsPluginPresent = false;
    private ThermoService thermo;
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private static final String FROST_WORLD_NAME = "aeternum_frost";

    public AeternumSeasonsPlugin() {
        super();
    }

    private void ensureFrostWorld() {
        String name = "aeternum_frost";
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            String msg = this.lang.trf((Player)null, "log.frost_world_loaded", Map.of("name", name));
            this.getLogger().info(ChatColor.stripColor(msg));
        } else {
            WorldCreator creator = new WorldCreator(name);
            creator.environment(Environment.NORMAL);
            creator.generator(new FrostWorldGenerator());
            World created = creator.createWorld();
            if (created != null) {
                String msg = this.lang.trf((Player)null, "log.frost_world_created", Map.of("name", name));
                this.getLogger().info(ChatColor.stripColor(msg));
            } else {
                String msg = this.lang.trf((Player)null, "log.frost_world_failed", Map.of("name", name));
                this.getLogger().warning(ChatColor.stripColor(msg));
            }

        }
    }

    public FrostWorldGuide getFrostWorldGuide() {
        return this.frostWorldGuide;
    }

    private void setupHeatWorld() {
        String name = "aeternum_heat";
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            String msg = this.lang.trf((Player)null, "log.heat_world_loaded", Map.of("name", name));
            this.getLogger().info(ChatColor.stripColor(msg));
        } else {
            String creatingMsg = this.lang.trf((Player)null, "log.heat_world_creating", Map.of("name", name));
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
                String msg = this.lang.trf((Player)null, "log.heat_world_created", Map.of("name", name));
                this.getLogger().info(ChatColor.stripColor(msg));
            }

        }
    }

    private void loadWorldExclusionList() {
        this.disabledWorlds = this.getConfig().getStringList("worlds.disabled_season_fx");
        if (this.disabledWorlds == null) {
            this.disabledWorlds = Collections.emptyList();
        }

        Logger var10000 = this.getLogger();
        int var10001 = this.disabledWorlds.size();
        var10000.info("[AeternumSeasons] FX deshabilitados en " + var10001 + " mundos: " + String.valueOf(this.disabledWorlds));
    }

    public boolean isWorldDisabled(World world) {
        return world == null ? true : this.disabledWorlds.contains(world.getName());
    }

    private boolean isSeasonFoodEnabled() {
        return this.getConfig().contains("SeasonFood") ? this.getConfig().getBoolean("SeasonFood", true) : this.getConfig().getBoolean("features.season_foods.enabled", true);
    }

    public World getMainOverworld() {
        World byName = Bukkit.getWorld("world");
        if (byName != null && !byName.getName().equalsIgnoreCase("aeternum_frost")) {
            return byName;
        } else {
            for(World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == Environment.NORMAL) {
                    String name = w.getName();
                    if (!name.equalsIgnoreCase("aeternum_frost")) {
                        return w;
                    }
                }
            }

            return null;
        }
    }

    public String getMainOverworldName() {
        World w = this.getMainOverworld();
        return w != null ? w.getName() : "world";
    }

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
        ((PluginCommand)Objects.requireNonNull(this.getCommand("seasoncraft"), "Falta seasoncraft en plugin.yml")).setExecutor(this.seasonCraft);
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

    private void hookCustomCrops() {
        if (!this.customCropsHooked) {
            if (this.seasons != null) {
                if (Bukkit.getPluginManager().isPluginEnabled("CustomCrops")) {
                    try {
                        CustomCropsSeasonHook.register(this);
                        this.customCropsHooked = true;
                    } catch (Throwable t) {
                        this.getLogger().warning("[AeternumSeasons] Failed to hook CustomCrops: " + t.getMessage());
                    }

                }
            }
        }
    }

    private void registerCustomCropsHookListener() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(
                priority = EventPriority.MONITOR
            )
            public void onPluginEnable(PluginEnableEvent e) {
                Plugin p = e.getPlugin();
                if (p != null && p.getName().equalsIgnoreCase("CustomCrops")) {
                    AeternumSeasonsPlugin.this.hookCustomCrops();
                }

            }
        }, this);
    }

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
        } catch (Throwable var12) {
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
        ((PluginCommand)Objects.requireNonNull(this.getCommand("seasoncraft"), "Falta seasoncraft en plugin.yml")).setExecutor(this.seasonCraft);
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

    private void removeAllPluginRecipes() {
        String ns = this.getName().toLowerCase(Locale.ROOT);
        List<NamespacedKey> keys = new ArrayList();
        Iterator<Recipe> it = Bukkit.recipeIterator();

        while(it.hasNext()) {
            Recipe r = (Recipe)it.next();
            if (r instanceof Keyed keyed) {
                NamespacedKey k = keyed.getKey();
                if (k != null && ns.equalsIgnoreCase(k.getNamespace())) {
                    keys.add(k);
                }
            }
        }

        int removed = 0;

        for(NamespacedKey k : keys) {
            if (Bukkit.removeRecipe(k)) {
                ++removed;
            }
        }

        this.getLogger().info("[AeternumSeasons] Removed " + removed + " old recipes for namespace '" + ns + "'.");
    }

    public SeasonService getSeasons() {
        return this.seasons;
    }

    public SeasonalEventService getEventService() {
        return this.eventService;
    }

    public SeasonFoods getSeasonFoods() {
        return this.foods;
    }

    public SeasonalCropGrowthListener getSeasonalCropGrowthListener() {
        return this.cropGrowth;
    }

    public boolean isWorldsPluginPresent() {
        return this.worldsPluginPresent;
    }

    public boolean shouldLetWorldsHandlePortals() {
        return this.worldsPluginPresent;
    }

    public ThermoService getThermo() {
        return this.thermo;
    }
}
