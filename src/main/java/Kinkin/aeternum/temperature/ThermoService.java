package Kinkin.aeternum.temperature;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

public final class ThermoService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private BukkitTask task;
    private BukkitTask overlayTask;
    private final FreezeOverlay freezeOverlay = new FreezeOverlay();
    private final Map<UUID, ThermoPlayerState> states = new ConcurrentHashMap();
    private final Map<UUID, TemperatureReading> temperatureReadings = new ConcurrentHashMap();
    private boolean enabled;
    private boolean heatEnabled;
    private boolean coldEnabled;
    private boolean actionbarEnabled;
    private int checkPeriodTicks;
    private int stableMinC;
    private int stableMaxC;
    private TemperatureUnit displayUnit;
    private int actionbarShowTicks;
    private int actionbarStep;
    private int normalBaseC;
    private int normalMinC;
    private int normalMaxC;
    private int hotBaseC;
    private int hotMinC;
    private int hotMaxC;
    private int snowyBaseC;
    private int snowyMinC;
    private int snowyMaxC;
    private int frozenBaseC;
    private int frozenMinC;
    private int frozenMaxC;
    private int springOffsetC;
    private int summerOffsetC;
    private int autumnOffsetC;
    private int winterOffsetC;
    private int roofScanHeight;
    private int heatSourceRadius;
    private int hotMinChainmailPieces;
    private int hotMaxSafeArmorPieces;
    private boolean hotHelmetSafeEnabled;
    private int hotShadeTempC;
    private int hotWaterTempC;
    private int hotHelmetTempC;
    private int hotDamageAfterTicks;
    private int hotDamageEveryTicks;
    private double hotDamageAmount;
    private int hotHungerEveryTicks;
    private int hotHungerDrain;
    private int hotHallucinationAfterTicks;
    private int hotSlownessLevel;
    private int hotHungerEffectLevel;
    private int hotNauseaLevel;
    private int hotDrinkReliefTicks;
    private int hotSplashReliefTicks;
    private int hotWaterStayReliefTicks;
    private int hotStormBlindnessLevel;
    private int hotStormBlindnessDurationTicks;
    private boolean hotStormEnabled;
    private int snowyMinArmorPieces;
    private int frozenMinArmorPieces;
    private int coldShelterTempC;
    private int snowyDamageEveryTicks;
    private double snowyDamageAmount;
    private int frozenDamageEveryTicks;
    private double frozenDamageAmount;
    private int coldSlownessLevel;
    private int coldFreezePerCycle;
    private int coldThawPerCycle;
    private boolean fullArmorSafe;
    private final Set<String> disabledWorlds = new HashSet();
    private final Set<Biome> hotBiomes = new HashSet();
    private final Set<Biome> snowyBiomes = new HashSet();
    private final Set<Biome> frozenBiomes = new HashSet();
    private final Set<Material> heatSourceBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> litOnlyHeatBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> warmHandItems = EnumSet.noneOf(Material.class);
    private final Set<Material> hotDisallowedHelmetItems = EnumSet.noneOf(Material.class);

    public TemperatureReading getTemperatureReading(UUID playerId) {
        return (TemperatureReading)this.temperatureReadings.get(playerId);
    }

    public ThermoService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
    }

    public void register() {
        this.reloadFromConfig();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.overlayTask != null) {
            this.overlayTask.cancel();
        }

        this.freezeOverlay.clear();
        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 20L, Math.max(1L, (long)this.checkPeriodTicks));
            BukkitScheduler var10001 = Bukkit.getScheduler();
            AeternumSeasonsPlugin var10002 = this.plugin;
            FreezeOverlay var10003 = this.freezeOverlay;
            Objects.requireNonNull(var10003);
            this.overlayTask = var10001.runTaskTimer(var10002, var10003::tick, 1L, 1L);
        }
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        if (this.overlayTask != null) {
            this.overlayTask.cancel();
        }

        this.overlayTask = null;
        this.freezeOverlay.clear();
        HandlerList.unregisterAll(this);

        for(Player p : Bukkit.getOnlinePlayers()) {
            this.thawPlayer(p);
        }

        this.states.clear();
        this.temperatureReadings.clear();
    }

    public void reload() {
        this.unregister();
        this.register();
    }

    private void reloadFromConfig() {
        FileConfiguration y = this.plugin.cfg.survival;
        this.enabled = y.getBoolean("temperature.enabled", true);
        this.heatEnabled = y.getBoolean("temperature.heat.enabled", true);
        this.coldEnabled = y.getBoolean("temperature.cold.enabled", true);
        this.checkPeriodTicks = Math.max(1, y.getInt("temperature.check_period_ticks", 20));
        this.displayUnit = TemperatureUnit.fromString(y.getString("temperature.display.unit", "C"));
        this.actionbarEnabled = y.getBoolean("temperature.display.actionbar.enabled", true);
        this.actionbarShowTicks = Math.max(20, y.getInt("temperature.display.actionbar.show_ticks", 60));
        this.actionbarStep = Math.max(1, y.getInt("temperature.display.actionbar.step_per_update", 1));
        this.stableMinC = y.getInt("temperature.stable_range.celsius.min", 10);
        this.stableMaxC = y.getInt("temperature.stable_range.celsius.max", 32);
        this.normalBaseC = y.getInt("temperature.zones.normal.base_celsius", 21);
        this.normalMinC = y.getInt("temperature.zones.normal.min_celsius", 10);
        this.normalMaxC = y.getInt("temperature.zones.normal.max_celsius", 32);
        this.hotBaseC = y.getInt("temperature.zones.hot.base_celsius", 36);
        this.hotMinC = y.getInt("temperature.zones.hot.min_celsius", 33);
        this.hotMaxC = y.getInt("temperature.zones.hot.max_celsius", 45);
        this.snowyBaseC = y.getInt("temperature.zones.snowy.base_celsius", -8);
        this.snowyMinC = y.getInt("temperature.zones.snowy.min_celsius", -19);
        this.snowyMaxC = y.getInt("temperature.zones.snowy.max_celsius", 8);
        this.frozenBaseC = y.getInt("temperature.zones.frozen.base_celsius", -18);
        this.frozenMinC = y.getInt("temperature.zones.frozen.min_celsius", -30);
        this.frozenMaxC = y.getInt("temperature.zones.frozen.max_celsius", -2);
        this.springOffsetC = y.getInt("temperature.season_offsets_celsius.SPRING", 0);
        this.summerOffsetC = y.getInt("temperature.season_offsets_celsius.SUMMER", 8);
        this.autumnOffsetC = y.getInt("temperature.season_offsets_celsius.AUTUMN", -6);
        this.winterOffsetC = y.getInt("temperature.season_offsets_celsius.WINTER", -14);
        this.roofScanHeight = Math.max(1, y.getInt("temperature.shelter.roof_scan_height", 8));
        this.heatSourceRadius = Math.max(1, y.getInt("temperature.shelter.heat_source_radius", 6));
        this.hotMinChainmailPieces = Math.max(0, y.getInt("temperature.heat.desert.min_chainmail_pieces", 2));
        this.hotMaxSafeArmorPieces = Math.max(0, y.getInt("temperature.heat.desert.max_safe_armor_pieces", 2));
        this.hotHelmetSafeEnabled = y.getBoolean("temperature.heat.desert.non_leather_helmet_is_safe", true);
        this.hotShadeTempC = y.getInt("temperature.heat.desert.shade_temp_celsius", 29);
        this.hotWaterTempC = y.getInt("temperature.heat.desert.water_temp_celsius", 24);
        this.hotHelmetTempC = y.getInt("temperature.heat.desert.helmet_temp_celsius", 28);
        this.hotDamageAfterTicks = Math.max(20, y.getInt("temperature.heat.desert.damage_after_seconds", 300) * 20);
        this.hotDamageEveryTicks = Math.max(20, y.getInt("temperature.heat.desert.damage_every_ticks", 40));
        this.hotDamageAmount = y.getDouble("temperature.heat.desert.damage_amount", (double)1.0F);
        this.hotHungerEveryTicks = Math.max(20, y.getInt("temperature.heat.desert.hunger_every_ticks", 80));
        this.hotHungerDrain = Math.max(1, y.getInt("temperature.heat.desert.hunger_drain", 1));
        this.hotHallucinationAfterTicks = Math.max(20, y.getInt("temperature.heat.desert.hallucination_after_seconds", 120) * 20);
        this.hotSlownessLevel = Math.max(0, y.getInt("temperature.heat.desert.slowness_level", 1));
        this.hotHungerEffectLevel = Math.max(0, y.getInt("temperature.heat.desert.hunger_effect_level", 1));
        this.hotNauseaLevel = Math.max(0, y.getInt("temperature.heat.desert.nausea_level", 1));
        this.hotDrinkReliefTicks = Math.max(20, y.getInt("temperature.heat.desert.drink_water_relief_seconds", 120) * 20);
        this.hotSplashReliefTicks = Math.max(20, y.getInt("temperature.heat.desert.splash_water_relief_seconds", 180) * 20);
        this.hotWaterStayReliefTicks = Math.max(20, y.getInt("temperature.heat.desert.enter_water_relief_seconds", 180) * 20);
        this.hotStormEnabled = y.getBoolean("temperature.heat.sandstorm.enabled", true);
        this.hotStormBlindnessLevel = Math.max(0, y.getInt("temperature.heat.sandstorm.blindness_level", 1));
        this.hotStormBlindnessDurationTicks = Math.max(20, y.getInt("temperature.heat.sandstorm.blindness_duration_ticks", 60));
        this.snowyMinArmorPieces = Math.max(0, y.getInt("temperature.cold.snowy.min_armor_pieces", 3));
        this.frozenMinArmorPieces = Math.max(0, y.getInt("temperature.cold.frozen.min_armor_pieces", 4));
        this.coldShelterTempC = y.getInt("temperature.cold.shelter_temp_celsius", 12);
        this.snowyDamageEveryTicks = Math.max(20, y.getInt("temperature.cold.snowy.damage_every_ticks", 60));
        this.snowyDamageAmount = y.getDouble("temperature.cold.snowy.damage_amount", (double)0.5F);
        this.frozenDamageEveryTicks = Math.max(20, y.getInt("temperature.cold.frozen.damage_every_ticks", 40));
        this.frozenDamageAmount = y.getDouble("temperature.cold.frozen.damage_amount", (double)1.0F);
        this.coldSlownessLevel = Math.max(0, y.getInt("temperature.cold.effects.slowness_level", 1));
        this.coldFreezePerCycle = Math.max(1, y.getInt("temperature.cold.effects.freeze_ticks_per_cycle", 40));
        this.coldThawPerCycle = Math.max(1, y.getInt("temperature.cold.effects.thaw_ticks_per_cycle", 60));
        this.fullArmorSafe = y.getBoolean("temperature.cold.full_armor_safe", true);
        this.disabledWorlds.clear();

        for(String s : y.getStringList("temperature.disabled_worlds")) {
            if (s != null && !s.isBlank()) {
                this.disabledWorlds.add(s.trim());
            }
        }

        this.hotBiomes.clear();
        this.snowyBiomes.clear();
        this.frozenBiomes.clear();
        this.heatSourceBlocks.clear();
        this.litOnlyHeatBlocks.clear();
        this.warmHandItems.clear();
        this.hotDisallowedHelmetItems.clear();
        this.loadBiomes(y.getStringList("temperature.zones.hot.biomes"), this.hotBiomes);
        this.loadBiomes(y.getStringList("temperature.zones.snowy.biomes"), this.snowyBiomes);
        this.loadBiomes(y.getStringList("temperature.zones.frozen.biomes"), this.frozenBiomes);
        this.loadMaterials(y.getStringList("temperature.shelter.heat_sources"), this.heatSourceBlocks);
        this.loadMaterials(y.getStringList("temperature.shelter.lit_only_heat_sources"), this.litOnlyHeatBlocks);
        this.loadMaterials(y.getStringList("temperature.shelter.warm_hand_items"), this.warmHandItems);
        this.loadMaterials(y.getStringList("temperature.heat.desert.disallowed_helmet_items"), this.hotDisallowedHelmetItems);
    }

    private void loadBiomes(List<String> list, Set<Biome> out) {
        for(String s : list) {
            if (s != null && !s.isBlank()) {
                String raw = s.trim().toLowerCase(Locale.ROOT);
                NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : NamespacedKey.minecraft(raw);
                if (key != null) {
                    Biome biome = (Biome)Registry.BIOME.get(key);
                    if (biome != null) {
                        out.add(biome);
                    } else {
                        this.plugin.getLogger().warning("[Thermo] Bioma inválido en survival.yml: " + s);
                    }
                }
            }
        }

    }

    private void loadMaterials(List<String> list, Set<Material> out) {
        for(String s : list) {
            if (s != null && !s.isBlank()) {
                try {
                    out.add(Material.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException var6) {
                }
            }
        }

    }

    public void run() {
        if (this.enabled) {
            Set<UUID> onlineIds = new HashSet();

            for(Player p : Bukkit.getOnlinePlayers()) {
                onlineIds.add(p.getUniqueId());
                this.updatePlayer(p);
            }

            this.temperatureReadings.keySet().retainAll(onlineIds);
        }
    }

    private void updatePlayer(Player p) {
        ThermoPlayerState state = (ThermoPlayerState)this.states.computeIfAbsent(p.getUniqueId(), (id) -> new ThermoPlayerState());
        state.tickDown(this.checkPeriodTicks);
        if (p.isOnline() && !p.isDead()) {
            World world = p.getWorld();
            if (world != null && !this.disabledWorlds.contains(world.getName()) && !this.plugin.isWorldDisabled(world)) {
                Location loc = p.getLocation();
                Biome biome = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                TemperatureZone zone = this.resolveZone(biome);
                PlayerClimateContext ctx = this.buildContext(p, zone, state);
                int targetC = this.computeTargetC(zone, ctx);
                this.temperatureReadings.put(p.getUniqueId(), new TemperatureReading(targetC, this.displayUnit));
                if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                    boolean stable = this.isStable(targetC);
                    this.updateActionbar(p, state, targetC, stable);
                    boolean heatDanger = this.heatEnabled && zone == TemperatureZone.HOT && this.isHeatDanger(ctx);
                    boolean coldDanger = this.coldEnabled && (zone == TemperatureZone.SNOWY || zone == TemperatureZone.FROZEN) && this.isColdDanger(zone, ctx);
                    if (heatDanger) {
                        this.handleHeat(p, state, ctx);
                    } else {
                        state.heatExposureTicks = 0;
                    }

                    if (coldDanger) {
                        this.handleCold(p, state, zone, ctx);
                    } else {
                        state.coldExposureTicks = 0;
                        this.thawPlayer(p);
                    }

                    if (this.hotStormEnabled && zone == TemperatureZone.HOT && world.hasStorm()) {
                        this.handleHotStorm(p, state, ctx);
                    }

                } else {
                    this.thawPlayer(p);
                    state.resetDanger();
                }
            } else {
                this.temperatureReadings.remove(p.getUniqueId());
                this.thawPlayer(p);
                state.resetDanger();
            }
        } else {
            this.temperatureReadings.remove(p.getUniqueId());
            this.thawPlayer(p);
        }
    }

    private TemperatureZone resolveZone(Biome biome) {
        if (biome == null) {
            return TemperatureZone.NORMAL;
        } else if (this.frozenBiomes.contains(biome)) {
            return TemperatureZone.FROZEN;
        } else if (this.snowyBiomes.contains(biome)) {
            return TemperatureZone.SNOWY;
        } else {
            return this.hotBiomes.contains(biome) ? TemperatureZone.HOT : TemperatureZone.NORMAL;
        }
    }

    private PlayerClimateContext buildContext(Player p, TemperatureZone zone, ThermoPlayerState state) {
        PlayerClimateContext ctx = new PlayerClimateContext();
        ctx.player = p;
        ctx.zone = zone;
        ctx.state = state;
        ctx.world = p.getWorld();
        ctx.loc = p.getLocation();
        ctx.block = ctx.loc.getBlock();
        ctx.canSeeSky = this.canSeeSky(ctx.block);
        ctx.underRoof = !ctx.canSeeSky || this.hasSolidRoof(ctx.block);
        ctx.nearHeatSource = this.hasHeatBlockNearby(ctx.block, this.heatSourceRadius);
        ctx.inWater = p.isInWater() || p.getLocation().getBlock().isLiquid();
        ctx.wetRelief = state.wetReliefTicks > 0;
        ctx.holdingWarmItem = this.isHoldingWarmItem(p);
        ctx.nonLeatherHelmetSafe = this.hotHelmetSafeEnabled && this.hasNonLeatherHelmet(p);
        ctx.armorPieces = this.countArmorPieces(p.getInventory());
        ctx.chainmailPieces = this.countChainmailPieces(p.getInventory());
        ctx.fullArmor = ctx.armorPieces >= 4;
        return ctx;
    }

    private int computeTargetC(TemperatureZone zone, PlayerClimateContext ctx) {
        int base;
        int min;
        int max;
        switch (zone) {
            case HOT:
                base = this.hotBaseC;
                min = this.hotMinC;
                max = this.hotMaxC;
                break;
            case SNOWY:
                base = this.snowyBaseC;
                min = this.snowyMinC;
                max = this.snowyMaxC;
                break;
            case FROZEN:
                base = this.frozenBaseC;
                min = this.frozenMinC;
                max = this.frozenMaxC;
                break;
            default:
                base = this.normalBaseC;
                min = this.normalMinC;
                max = this.normalMaxC;
        }

        int temp = this.clamp(base + this.getSeasonOffsetC(ctx.world), min, max);
        if (zone == TemperatureZone.HOT) {
            if (ctx.inWater || ctx.wetRelief) {
                return this.hotWaterTempC;
            }

            if (ctx.nonLeatherHelmetSafe) {
                return this.hotHelmetTempC;
            }

            if (ctx.underRoof) {
                return this.hotShadeTempC;
            }
        }

        if (zone != TemperatureZone.SNOWY && zone != TemperatureZone.FROZEN || !ctx.underRoof && !ctx.nearHeatSource && !ctx.holdingWarmItem) {
            return temp;
        } else {
            return this.coldShelterTempC;
        }
    }

    private int getSeasonOffsetC(World world) {
        Season s = this.seasons.getStateCopy(world).season;
        if (s == null) {
            return 0;
        } else {
            int var10000;
            switch (s) {
                case SPRING -> var10000 = this.springOffsetC;
                case SUMMER -> var10000 = this.summerOffsetC;
                case AUTUMN -> var10000 = this.autumnOffsetC;
                case WINTER -> var10000 = this.winterOffsetC;
                default -> throw new MatchException((String)null, (Throwable)null);
            }

            return var10000;
        }
    }

    private boolean isStable(int tempC) {
        return tempC >= this.stableMinC && tempC <= this.stableMaxC;
    }

    private void updateActionbar(Player p, ThermoPlayerState state, int targetC, boolean stable) {
        if (this.actionbarEnabled) {
            int targetDisplay = this.displayUnit.fromCelsiusRounded(targetC);
            if (state.displayedTemp == Integer.MIN_VALUE) {
                state.displayedTemp = targetDisplay;
            } else if (state.displayedTemp < targetDisplay) {
                state.displayedTemp = Math.min(targetDisplay, state.displayedTemp + this.actionbarStep);
                state.actionbarTicks = this.actionbarShowTicks;
            } else if (state.displayedTemp > targetDisplay) {
                state.displayedTemp = Math.max(targetDisplay, state.displayedTemp - this.actionbarStep);
                state.actionbarTicks = this.actionbarShowTicks;
            } else if (!stable) {
                state.actionbarTicks = this.actionbarShowTicks;
            }

            if (!stable && state.actionbarTicks < this.actionbarShowTicks) {
                state.actionbarTicks = this.actionbarShowTicks;
            }

            if (!stable || state.actionbarTicks > 0) {
                String key = this.displayUnit == TemperatureUnit.FAHRENHEIT ? "temperature.actionbar.fahrenheit" : "temperature.actionbar.celsius";
                String msg = this.plugin.lang.trf(p, key, Map.of("temp", state.displayedTemp));
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
            }
        }
    }

    private boolean isHeatDanger(PlayerClimateContext ctx) {
        if (!ctx.inWater && !ctx.wetRelief && !ctx.underRoof && !ctx.nonLeatherHelmetSafe) {
            return ctx.chainmailPieces < this.hotMinChainmailPieces || ctx.armorPieces > this.hotMaxSafeArmorPieces;
        } else {
            return false;
        }
    }

    private boolean isColdDanger(TemperatureZone zone, PlayerClimateContext ctx) {
        if (!ctx.underRoof && !ctx.nearHeatSource && !ctx.holdingWarmItem) {
            if (this.fullArmorSafe && ctx.fullArmor) {
                return false;
            } else {
                int required = zone == TemperatureZone.FROZEN ? this.frozenMinArmorPieces : this.snowyMinArmorPieces;
                return ctx.armorPieces < required;
            }
        } else {
            return false;
        }
    }

    private void handleHeat(Player p, ThermoPlayerState state, PlayerClimateContext ctx) {
        state.heatExposureTicks += this.checkPeriodTicks;
        int extraArmor = Math.max(0, ctx.armorPieces - this.hotMaxSafeArmorPieces);
        int slowLevel = Math.max(1, this.hotSlownessLevel + extraArmor);
        this.addShortEffect(p, PotionEffectType.SLOWNESS, 40, slowLevel);
        if (this.hotHungerEffectLevel > 0) {
            this.addShortEffect(p, PotionEffectType.HUNGER, 40, this.hotHungerEffectLevel);
        }

        if (state.heatExposureTicks >= this.hotHallucinationAfterTicks && this.hotNauseaLevel > 0) {
            this.addShortEffect(p, PotionEffectType.NAUSEA, 80, this.hotNauseaLevel);
        }

        if (state.hungerCooldownTicks <= 0) {
            p.setFoodLevel(Math.max(0, p.getFoodLevel() - this.hotHungerDrain));
            p.setSaturation(0.0F);
            state.hungerCooldownTicks = this.hotHungerEveryTicks;
        }

        if (state.heatExposureTicks >= this.hotDamageAfterTicks && state.heatDamageCooldownTicks <= 0) {
            p.damage(this.hotDamageAmount);
            state.heatDamageCooldownTicks = this.hotDamageEveryTicks;
        }

    }

    private void handleCold(Player p, ThermoPlayerState state, TemperatureZone zone, PlayerClimateContext ctx) {
        state.coldExposureTicks += this.checkPeriodTicks;
        this.addShortEffect(p, PotionEffectType.SLOWNESS, 40, this.coldSlownessLevel);
        this.freezeOverlay.hold(p, this.coldFreezePerCycle);
        if (zone == TemperatureZone.FROZEN) {
            if (state.coldDamageCooldownTicks <= 0) {
                p.damage(this.frozenDamageAmount);
                state.coldDamageCooldownTicks = this.frozenDamageEveryTicks;
            }
        } else if (state.coldDamageCooldownTicks <= 0) {
            p.damage(this.snowyDamageAmount);
            state.coldDamageCooldownTicks = this.snowyDamageEveryTicks;
        }

    }

    private void thawPlayer(Player p) {
        this.freezeOverlay.release(p);
        int current = p.getFreezeTicks();
        if (current > 0) {
            p.setFreezeTicks(Math.max(0, current - this.coldThawPerCycle));
        }

    }

    private void handleHotStorm(Player p, ThermoPlayerState state, PlayerClimateContext ctx) {
        if (ctx.canSeeSky) {
            int blindnessLevel = this.hotStormBlindnessLevel;
            if (ctx.holdingWarmItem) {
                blindnessLevel = Math.max(0, blindnessLevel - 1);
            }

            if (blindnessLevel > 0) {
                this.addShortEffect(p, PotionEffectType.BLINDNESS, this.hotStormBlindnessDurationTicks, blindnessLevel);
            }

            Location eye = p.getEyeLocation();
            p.getWorld().spawnParticle(Particle.DUST, eye, 8, 0.45, (double)0.25F, 0.45, (double)0.0F, new Particle.DustOptions(Color.fromRGB(214, 193, 156), 1.1F));
            if (state.stormSoundCooldownTicks <= 0) {
                p.playSound(p.getLocation(), Sound.BLOCK_SAND_HIT, 0.35F, 0.75F);
                state.stormSoundCooldownTicks = 40;
            }

        }
    }

    private void addShortEffect(Player p, PotionEffectType type, int duration, int level) {
        if (type != null && level > 0) {
            int amplifier = Math.max(0, level - 1);
            p.addPotionEffect(new PotionEffect(type, duration, amplifier, true, false, false));
        }
    }

    private boolean isHoldingWarmItem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        return main != null && this.warmHandItems.contains(main.getType()) || off != null && this.warmHandItems.contains(off.getType());
    }

    private boolean hasNonLeatherHelmet(Player p) {
        ItemStack helmet = p.getInventory().getHelmet();
        if (helmet != null && helmet.getType() != Material.AIR) {
            return !this.hotDisallowedHelmetItems.contains(helmet.getType());
        } else {
            return false;
        }
    }

    private int countArmorPieces(PlayerInventory inv) {
        int n = 0;
        if (this.isRealArmor(inv.getHelmet())) {
            ++n;
        }

        if (this.isRealArmor(inv.getChestplate())) {
            ++n;
        }

        if (this.isRealArmor(inv.getLeggings())) {
            ++n;
        }

        if (this.isRealArmor(inv.getBoots())) {
            ++n;
        }

        return n;
    }

    private int countChainmailPieces(PlayerInventory inv) {
        int n = 0;
        if (this.isChainmail(inv.getHelmet())) {
            ++n;
        }

        if (this.isChainmail(inv.getChestplate())) {
            ++n;
        }

        if (this.isChainmail(inv.getLeggings())) {
            ++n;
        }

        if (this.isChainmail(inv.getBoots())) {
            ++n;
        }

        return n;
    }

    private boolean isRealArmor(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    private boolean isChainmail(ItemStack item) {
        if (item == null) {
            return false;
        } else {
            boolean var10000;
            switch (item.getType()) {
                case CHAINMAIL_HELMET:
                case CHAINMAIL_CHESTPLATE:
                case CHAINMAIL_LEGGINGS:
                case CHAINMAIL_BOOTS:
                    var10000 = true;
                    break;
                default:
                    var10000 = false;
            }

            return var10000;
        }
    }

    private boolean canSeeSky(Block base) {
        World w = base.getWorld();
        int highest = w.getHighestBlockYAt(base.getX(), base.getZ());
        return base.getY() >= highest - 1;
    }

    private boolean hasSolidRoof(Block base) {
        World w = base.getWorld();
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();

        for(int dy = 1; dy <= this.roofScanHeight; ++dy) {
            Block up = w.getBlockAt(x, y + dy, z);
            if (up.getType().isSolid()) {
                return true;
            }
        }

        return false;
    }

    private boolean hasHeatBlockNearby(Block base, int radius) {
        World w = base.getWorld();
        int bx = base.getX();
        int by = base.getY();
        int bz = base.getZ();
        int r2 = radius * radius;

        for(int dx = -radius; dx <= radius; ++dx) {
            for(int dz = -radius; dz <= radius; ++dz) {
                if (dx * dx + dz * dz <= r2) {
                    int x = bx + dx;
                    int z = bz + dz;
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (w.isChunkLoaded(cx, cz)) {
                        for(int dy = -2; dy <= 2; ++dy) {
                            Block b = w.getBlockAt(x, by + dy, z);
                            Material type = b.getType();
                            if (this.heatSourceBlocks.contains(type)) {
                                if (!this.litOnlyHeatBlocks.contains(type)) {
                                    return true;
                                }

                                BlockData data = b.getBlockData();
                                if (!(data instanceof Lightable)) {
                                    return true;
                                }

                                Lightable lightable = (Lightable)data;
                                if (lightable.isLit()) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onDrinkWater(PlayerItemConsumeEvent e) {
        if (this.enabled && this.heatEnabled) {
            ItemStack item = e.getItem();
            if (item != null && item.getType() == Material.POTION) {
                ItemMeta var4 = item.getItemMeta();
                if (var4 instanceof PotionMeta) {
                    PotionMeta meta = (PotionMeta)var4;
                    if (meta.getBasePotionType() == PotionType.WATER) {
                        ThermoPlayerState state = (ThermoPlayerState)this.states.computeIfAbsent(e.getPlayer().getUniqueId(), (id) -> new ThermoPlayerState());
                        state.wetReliefTicks = Math.max(state.wetReliefTicks, this.hotDrinkReliefTicks);
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onWaterSplash(PotionSplashEvent e) {
        if (this.enabled && this.heatEnabled) {
            ItemMeta var3 = e.getPotion().getItem().getItemMeta();
            if (var3 instanceof PotionMeta) {
                PotionMeta meta = (PotionMeta)var3;
                if (meta.getBasePotionType() == PotionType.WATER) {
                    for(LivingEntity le : e.getAffectedEntities()) {
                        if (le instanceof Player) {
                            Player p = (Player)le;
                            if (!(e.getIntensity(le) <= 0.05)) {
                                ThermoPlayerState state = (ThermoPlayerState)this.states.computeIfAbsent(p.getUniqueId(), (id) -> new ThermoPlayerState());
                                state.wetReliefTicks = Math.max(state.wetReliefTicks, this.hotSplashReliefTicks);
                            }
                        }
                    }

                }
            }
        }
    }

    public static record TemperatureReading(int celsius, TemperatureUnit unit) {
        public TemperatureReading {
        }

        public int value() {
            return this.unit.fromCelsiusRounded(this.celsius);
        }

        public String symbol() {
            return this.unit == TemperatureUnit.FAHRENHEIT ? "°F" : "°C";
        }
    }

    private static final class PlayerClimateContext {
        private Player player;
        private ThermoPlayerState state;
        private TemperatureZone zone;
        private World world;
        private Location loc;
        private Block block;
        private boolean canSeeSky;
        private boolean underRoof;
        private boolean nearHeatSource;
        private boolean inWater;
        private boolean wetRelief;
        private boolean holdingWarmItem;
        private boolean nonLeatherHelmetSafe;
        private int armorPieces;
        private int chainmailPieces;
        private boolean fullArmor;

        private PlayerClimateContext() {
            super();
        }
    }
}
