package kinkin.aeternum.temperature;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 温度系统核心服务。
 *
 * <p>实现 {@link Listener} 与 {@link Runnable}，按配置的检查周期遍历在线玩家，
 * 依据其所在生物群系、季节偏移、遮蔽/热源/涉水/护甲/手持保暖物等条件计算目标温度，
 * 并驱动以下行为：
 * <ul>
 *     <li>通过动作栏显示平滑变化的温度（摄氏度或华氏度）；</li>
 *     <li>炎热区域的饥饿、减速、幻觉（反胃）与周期伤害；</li>
 *     <li>寒冷区域（寒冷/冰冻）的冻结、减速与周期伤害，并借助 {@link FreezeOverlay} 维持冻结；</li>
 *     <li>沙暴天气下的失明与沙尘粒子；</li>
 *     <li>饮水/水瓶溅射带来的降温缓解。</li>
 * </ul>
 * 所有温度相关参数均从 {@code survival.yml} 读取。依赖 {@link AeternumSeasonsPlugin}
 * 提供配置、语言与调度，依赖 {@link SeasonService} 获取季节偏移。</p>
 */
public final class ThermoService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private BukkitTask task;
    private BukkitTask overlayTask;
    private final FreezeOverlay freezeOverlay = new FreezeOverlay();
    /**
     * 每个玩家的温度状态缓存。
     */
    private final Map<UUID, ThermoPlayerState> states = new ConcurrentHashMap();
    /**
     * 每个玩家最近一次计算得到的温度读数，供外部查询。
     */
    private final Map<UUID, TemperatureReading> temperatureReadings = new ConcurrentHashMap();
    // 以下为从 survival.yml 读取的温度系统配置项
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
    /**
     * 温度系统不生效的世界名称集合。
     */
    private final Set<String> disabledWorlds = new HashSet();
    /**
     * 炎热 / 寒冷 / 冰冻温区对应的生物群系集合。
     */
    private final Set<Biome> hotBiomes = new HashSet();
    private final Set<Biome> snowyBiomes = new HashSet();
    private final Set<Biome> frozenBiomes = new HashSet();
    /**
     * 视为热源的方块、需点燃才生效的热源方块、手持保暖物品、以及头盔禁用材质。
     */
    private final Set<Material> heatSourceBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> litOnlyHeatBlocks = EnumSet.noneOf(Material.class);
    private final Set<Material> warmHandItems = EnumSet.noneOf(Material.class);
    private final Set<Material> hotDisallowedHelmetItems = EnumSet.noneOf(Material.class);

    /**
     * 获取指定玩家当前的温度读数，若尚未计算则返回 null。
     */
    public TemperatureReading getTemperatureReading(UUID playerId) {
        return (TemperatureReading) this.temperatureReadings.get(playerId);
    }

    public ThermoService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
    }

    /**
     * 读取配置、注册事件并启动温度计算与冻结遮罩两个定时任务。
     */
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
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 20L, Math.max(1L, (long) this.checkPeriodTicks));
            BukkitScheduler scheduler = Bukkit.getScheduler();
            AeternumSeasonsPlugin pluginInstance = this.plugin;
            FreezeOverlay overlay = this.freezeOverlay;
            Objects.requireNonNull(overlay);
            this.overlayTask = scheduler.runTaskTimer(pluginInstance, overlay::tick, 1L, 1L);
        }
    }

    /**
     * 取消定时任务、清理冻结遮罩与状态缓存，并对所有在线玩家执行解冻。
     */
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

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.thawPlayer(player);
        }

        this.states.clear();
        this.temperatureReadings.clear();
    }

    /**
     * 重新装载温度系统（先注销再注册）。
     */
    public void reload() {
        this.unregister();
        this.register();
    }

    /**
     * 从 {@code survival.yml} 读取全部温度相关配置，并加载生物群系/方块/物品集合。
     */
    private void reloadFromConfig() {
        FileConfiguration survivalConfig = this.plugin.cfg.survival;
        this.enabled = survivalConfig.getBoolean("temperature.enabled", true);
        this.heatEnabled = survivalConfig.getBoolean("temperature.heat.enabled", true);
        this.coldEnabled = survivalConfig.getBoolean("temperature.cold.enabled", true);
        this.checkPeriodTicks = Math.max(1, survivalConfig.getInt("temperature.check_period_ticks", 20));
        this.displayUnit = TemperatureUnit.fromString(survivalConfig.getString("temperature.display.unit", "C"));
        this.actionbarEnabled = survivalConfig.getBoolean("temperature.display.actionbar.enabled", true);
        this.actionbarShowTicks = Math.max(20, survivalConfig.getInt("temperature.display.actionbar.show_ticks", 60));
        this.actionbarStep = Math.max(1, survivalConfig.getInt("temperature.display.actionbar.step_per_update", 1));
        this.stableMinC = survivalConfig.getInt("temperature.stable_range.celsius.min", 10);
        this.stableMaxC = survivalConfig.getInt("temperature.stable_range.celsius.max", 32);
        this.normalBaseC = survivalConfig.getInt("temperature.zones.normal.base_celsius", 21);
        this.normalMinC = survivalConfig.getInt("temperature.zones.normal.min_celsius", 10);
        this.normalMaxC = survivalConfig.getInt("temperature.zones.normal.max_celsius", 32);
        this.hotBaseC = survivalConfig.getInt("temperature.zones.hot.base_celsius", 36);
        this.hotMinC = survivalConfig.getInt("temperature.zones.hot.min_celsius", 33);
        this.hotMaxC = survivalConfig.getInt("temperature.zones.hot.max_celsius", 45);
        this.snowyBaseC = survivalConfig.getInt("temperature.zones.snowy.base_celsius", -8);
        this.snowyMinC = survivalConfig.getInt("temperature.zones.snowy.min_celsius", -19);
        this.snowyMaxC = survivalConfig.getInt("temperature.zones.snowy.max_celsius", 8);
        this.frozenBaseC = survivalConfig.getInt("temperature.zones.frozen.base_celsius", -18);
        this.frozenMinC = survivalConfig.getInt("temperature.zones.frozen.min_celsius", -30);
        this.frozenMaxC = survivalConfig.getInt("temperature.zones.frozen.max_celsius", -2);
        this.springOffsetC = survivalConfig.getInt("temperature.season_offsets_celsius.SPRING", 0);
        this.summerOffsetC = survivalConfig.getInt("temperature.season_offsets_celsius.SUMMER", 8);
        this.autumnOffsetC = survivalConfig.getInt("temperature.season_offsets_celsius.AUTUMN", -6);
        this.winterOffsetC = survivalConfig.getInt("temperature.season_offsets_celsius.WINTER", -14);
        this.roofScanHeight = Math.max(1, survivalConfig.getInt("temperature.shelter.roof_scan_height", 8));
        this.heatSourceRadius = Math.max(1, survivalConfig.getInt("temperature.shelter.heat_source_radius", 6));
        this.hotMinChainmailPieces = Math.max(0, survivalConfig.getInt("temperature.heat.desert.min_chainmail_pieces", 2));
        this.hotMaxSafeArmorPieces = Math.max(0, survivalConfig.getInt("temperature.heat.desert.max_safe_armor_pieces", 2));
        this.hotHelmetSafeEnabled = survivalConfig.getBoolean("temperature.heat.desert.non_leather_helmet_is_safe", true);
        this.hotShadeTempC = survivalConfig.getInt("temperature.heat.desert.shade_temp_celsius", 29);
        this.hotWaterTempC = survivalConfig.getInt("temperature.heat.desert.water_temp_celsius", 24);
        this.hotHelmetTempC = survivalConfig.getInt("temperature.heat.desert.helmet_temp_celsius", 28);
        this.hotDamageAfterTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.damage_after_seconds", 300) * 20);
        this.hotDamageEveryTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.damage_every_ticks", 40));
        this.hotDamageAmount = survivalConfig.getDouble("temperature.heat.desert.damage_amount", (double) 1.0F);
        this.hotHungerEveryTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.hunger_every_ticks", 80));
        this.hotHungerDrain = Math.max(1, survivalConfig.getInt("temperature.heat.desert.hunger_drain", 1));
        this.hotHallucinationAfterTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.hallucination_after_seconds", 120) * 20);
        this.hotSlownessLevel = Math.max(0, survivalConfig.getInt("temperature.heat.desert.slowness_level", 1));
        this.hotHungerEffectLevel = Math.max(0, survivalConfig.getInt("temperature.heat.desert.hunger_effect_level", 1));
        this.hotNauseaLevel = Math.max(0, survivalConfig.getInt("temperature.heat.desert.nausea_level", 1));
        this.hotDrinkReliefTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.drink_water_relief_seconds", 120) * 20);
        this.hotSplashReliefTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.splash_water_relief_seconds", 180) * 20);
        this.hotWaterStayReliefTicks = Math.max(20, survivalConfig.getInt("temperature.heat.desert.enter_water_relief_seconds", 180) * 20);
        this.hotStormEnabled = survivalConfig.getBoolean("temperature.heat.sandstorm.enabled", true);
        this.hotStormBlindnessLevel = Math.max(0, survivalConfig.getInt("temperature.heat.sandstorm.blindness_level", 1));
        this.hotStormBlindnessDurationTicks = Math.max(20, survivalConfig.getInt("temperature.heat.sandstorm.blindness_duration_ticks", 60));
        this.snowyMinArmorPieces = Math.max(0, survivalConfig.getInt("temperature.cold.snowy.min_armor_pieces", 3));
        this.frozenMinArmorPieces = Math.max(0, survivalConfig.getInt("temperature.cold.frozen.min_armor_pieces", 4));
        this.coldShelterTempC = survivalConfig.getInt("temperature.cold.shelter_temp_celsius", 12);
        this.snowyDamageEveryTicks = Math.max(20, survivalConfig.getInt("temperature.cold.snowy.damage_every_ticks", 60));
        this.snowyDamageAmount = survivalConfig.getDouble("temperature.cold.snowy.damage_amount", (double) 0.5F);
        this.frozenDamageEveryTicks = Math.max(20, survivalConfig.getInt("temperature.cold.frozen.damage_every_ticks", 40));
        this.frozenDamageAmount = survivalConfig.getDouble("temperature.cold.frozen.damage_amount", (double) 1.0F);
        this.coldSlownessLevel = Math.max(0, survivalConfig.getInt("temperature.cold.effects.slowness_level", 1));
        this.coldFreezePerCycle = Math.max(1, survivalConfig.getInt("temperature.cold.effects.freeze_ticks_per_cycle", 40));
        this.coldThawPerCycle = Math.max(1, survivalConfig.getInt("temperature.cold.effects.thaw_ticks_per_cycle", 60));
        this.fullArmorSafe = survivalConfig.getBoolean("temperature.cold.full_armor_safe", true);
        this.disabledWorlds.clear();

        for (String worldName : survivalConfig.getStringList("temperature.disabled_worlds")) {
            if (worldName != null && !worldName.isBlank()) {
                this.disabledWorlds.add(worldName.trim());
            }
        }

        this.hotBiomes.clear();
        this.snowyBiomes.clear();
        this.frozenBiomes.clear();
        this.heatSourceBlocks.clear();
        this.litOnlyHeatBlocks.clear();
        this.warmHandItems.clear();
        this.hotDisallowedHelmetItems.clear();
        this.loadBiomes(survivalConfig.getStringList("temperature.zones.hot.biomes"), this.hotBiomes);
        this.loadBiomes(survivalConfig.getStringList("temperature.zones.snowy.biomes"), this.snowyBiomes);
        this.loadBiomes(survivalConfig.getStringList("temperature.zones.frozen.biomes"), this.frozenBiomes);
        this.loadMaterials(survivalConfig.getStringList("temperature.shelter.heat_sources"), this.heatSourceBlocks);
        this.loadMaterials(survivalConfig.getStringList("temperature.shelter.lit_only_heat_sources"), this.litOnlyHeatBlocks);
        this.loadMaterials(survivalConfig.getStringList("temperature.shelter.warm_hand_items"), this.warmHandItems);
        this.loadMaterials(survivalConfig.getStringList("temperature.heat.desert.disallowed_helmet_items"), this.hotDisallowedHelmetItems);
    }

    /**
     * 将配置中的生物群系名称解析为 {@link Biome} 并加入目标集合，无法解析时记录警告。
     */
    private void loadBiomes(List<String> list, Set<Biome> out) {
        for (String value : list) {
            if (value != null && !value.isBlank()) {
                String raw = value.trim().toLowerCase(Locale.ROOT);
                NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : NamespacedKey.minecraft(raw);
                if (key != null) {
                    Biome biome = (Biome) Registry.BIOME.get(key);
                    if (biome != null) {
                        out.add(biome);
                    } else {
                        this.plugin.getLogger().warning("[Thermo] Bioma inválido en survival.yml: " + value);
                    }
                }
            }
        }

    }

    /**
     * 将配置中的材质名称解析为 {@link Material} 并加入目标集合，无法解析时静默忽略。
     */
    private void loadMaterials(List<String> list, Set<Material> out) {
        for (String value : list) {
            if (value != null && !value.isBlank()) {
                try {
                    out.add(Material.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

    }

    /**
     * 每个检查周期遍历在线玩家更新温度，并清理已离线玩家的温度读数。
     */
    @Override
    public void run() {
        if (this.enabled) {
            Set<UUID> onlineIds = new HashSet();

            for (Player player : Bukkit.getOnlinePlayers()) {
                onlineIds.add(player.getUniqueId());
                this.updatePlayer(player);
            }

            this.temperatureReadings.keySet().retainAll(onlineIds);
        }
    }

    /**
     * 计算并应用单个玩家的温度：更新读数、动作栏、冷热伤害与沙暴效果，并维护解冻。
     */
    private void updatePlayer(Player player) {
        ThermoPlayerState state = (ThermoPlayerState) this.states.computeIfAbsent(player.getUniqueId(), (id) -> new ThermoPlayerState());
        state.tickDown(this.checkPeriodTicks);
        if (player.isOnline() && !player.isDead()) {
            World world = player.getWorld();
            if (world != null && !this.disabledWorlds.contains(world.getName()) && !this.plugin.isWorldDisabled(world)) {
                Location loc = player.getLocation();
                Biome biome = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                TemperatureZone zone = this.resolveZone(biome);
                PlayerClimateContext ctx = this.buildContext(player, zone, state);
                int targetC = this.computeTargetC(zone, ctx);
                this.temperatureReadings.put(player.getUniqueId(), new TemperatureReading(targetC, this.displayUnit));
                if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                    boolean stable = this.isStable(targetC);
                    this.updateActionbar(player, state, targetC, stable);
                    boolean heatDanger = this.heatEnabled && zone == TemperatureZone.HOT && this.isHeatDanger(ctx);
                    boolean coldDanger = this.coldEnabled && (zone == TemperatureZone.SNOWY || zone == TemperatureZone.FROZEN) && this.isColdDanger(zone, ctx);
                    if (heatDanger) {
                        this.handleHeat(player, state, ctx);
                    } else {
                        state.heatExposureTicks = 0;
                    }

                    if (coldDanger) {
                        this.handleCold(player, state, zone, ctx);
                    } else {
                        state.coldExposureTicks = 0;
                        this.thawPlayer(player);
                    }

                    if (this.hotStormEnabled && zone == TemperatureZone.HOT && world.hasStorm()) {
                        this.handleHotStorm(player, state, ctx);
                    }

                } else {
                    this.thawPlayer(player);
                    state.resetDanger();
                }
            } else {
                this.temperatureReadings.remove(player.getUniqueId());
                this.thawPlayer(player);
                state.resetDanger();
            }
        } else {
            this.temperatureReadings.remove(player.getUniqueId());
            this.thawPlayer(player);
        }
    }

    /**
     * 根据生物群系解析温度区域（冰冻优先级最高，其次寒冷、炎热，其余为普通）。
     */
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

    /**
     * 收集计算温度所需的玩家环境上下文（位置、遮蔽、热源、涉水、护甲等）。
     */
    private PlayerClimateContext buildContext(Player player, TemperatureZone zone, ThermoPlayerState state) {
        PlayerClimateContext ctx = new PlayerClimateContext();
        ctx.player = player;
        ctx.zone = zone;
        ctx.state = state;
        ctx.world = player.getWorld();
        ctx.loc = player.getLocation();
        ctx.block = ctx.loc.getBlock();
        ctx.canSeeSky = this.canSeeSky(ctx.block);
        ctx.underRoof = !ctx.canSeeSky || this.hasSolidRoof(ctx.block);
        ctx.nearHeatSource = this.hasHeatBlockNearby(ctx.block, this.heatSourceRadius);
        ctx.inWater = player.isInWater() || player.getLocation().getBlock().isLiquid();
        ctx.wetRelief = state.wetReliefTicks > 0;
        ctx.holdingWarmItem = this.isHoldingWarmItem(player);
        ctx.nonLeatherHelmetSafe = this.hotHelmetSafeEnabled && this.hasNonLeatherHelmet(player);
        ctx.armorPieces = this.countArmorPieces(player.getInventory());
        ctx.chainmailPieces = this.countChainmailPieces(player.getInventory());
        ctx.fullArmor = ctx.armorPieces >= 4;
        return ctx;
    }

    /**
     * 按温区基准值叠加季节偏移并夹取到区间，再根据涉水/头盔遮蔽/室内/保暖条件修正目标温度。
     */
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

    /**
     * 根据世界当前的季节返回对应的温度偏移（摄氏度）。
     */
    private int getSeasonOffsetC(World world) {
        Season season = this.seasons.getStateCopy(world).season;
        if (season == null) {
            return 0;
        } else {
            int offset;
            switch (season) {
                case SPRING -> offset = this.springOffsetC;
                case SUMMER -> offset = this.summerOffsetC;
                case AUTUMN -> offset = this.autumnOffsetC;
                case WINTER -> offset = this.winterOffsetC;
                default -> throw new MatchException((String) null, (Throwable) null);
            }

            return offset;
        }
    }

    /**
     * 判断温度是否处于稳定区间（用于动作栏显示策略）。
     */
    private boolean isStable(int tempC) {
        return tempC >= this.stableMinC && tempC <= this.stableMaxC;
    }

    /**
     * 平滑更新并显示动作栏温度：逐步逼近目标值，不稳定或仍在展示窗口内时发送提示。
     */
    private void updateActionbar(Player player, ThermoPlayerState state, int targetC, boolean stable) {
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
                String msg = this.plugin.lang.trf(player, key, Map.of("temp", state.displayedTemp));
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
            }
        }
    }

    /**
     * 判断玩家在炎热区域是否处于危险状态（非涉水/无缓解/无遮蔽/未戴安全头盔时，
     * 锁甲不足或护甲过多都会导致受热）。
     */
    private boolean isHeatDanger(PlayerClimateContext ctx) {
        if (!ctx.inWater && !ctx.wetRelief && !ctx.underRoof && !ctx.nonLeatherHelmetSafe) {
            return ctx.chainmailPieces < this.hotMinChainmailPieces || ctx.armorPieces > this.hotMaxSafeArmorPieces;
        } else {
            return false;
        }
    }

    /**
     * 判断玩家在寒冷/冰冻区域是否处于危险状态（无遮蔽/热源/保暖物时按护甲件数判定，
     * 全套护甲在开启 full_armor_safe 时视为安全）。
     */
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

    /**
     * 结算炎热效果：减速/饥饿/反胃，周期掉饥饿值，并在暴露足够久后周期性造成伤害。
     */
    private void handleHeat(Player player, ThermoPlayerState state, PlayerClimateContext ctx) {
        state.heatExposureTicks += this.checkPeriodTicks;
        int extraArmor = Math.max(0, ctx.armorPieces - this.hotMaxSafeArmorPieces);
        int slowLevel = Math.max(1, this.hotSlownessLevel + extraArmor);
        this.addShortEffect(player, PotionEffectType.SLOWNESS, 40, slowLevel);
        if (this.hotHungerEffectLevel > 0) {
            this.addShortEffect(player, PotionEffectType.HUNGER, 40, this.hotHungerEffectLevel);
        }

        if (state.heatExposureTicks >= this.hotHallucinationAfterTicks && this.hotNauseaLevel > 0) {
            this.addShortEffect(player, PotionEffectType.NAUSEA, 80, this.hotNauseaLevel);
        }

        if (state.hungerCooldownTicks <= 0) {
            player.setFoodLevel(Math.max(0, player.getFoodLevel() - this.hotHungerDrain));
            player.setSaturation(0.0F);
            state.hungerCooldownTicks = this.hotHungerEveryTicks;
        }

        if (state.heatExposureTicks >= this.hotDamageAfterTicks && state.heatDamageCooldownTicks <= 0) {
            player.damage(this.hotDamageAmount);
            state.heatDamageCooldownTicks = this.hotDamageEveryTicks;
        }

    }

    /**
     * 结算寒冷效果：减速、冻结（经 {@link FreezeOverlay} 抬升），并按温区周期性造成伤害。
     */
    private void handleCold(Player player, ThermoPlayerState state, TemperatureZone zone, PlayerClimateContext ctx) {
        state.coldExposureTicks += this.checkPeriodTicks;
        this.addShortEffect(player, PotionEffectType.SLOWNESS, 40, this.coldSlownessLevel);
        this.freezeOverlay.hold(player, this.coldFreezePerCycle);
        if (zone == TemperatureZone.FROZEN) {
            if (state.coldDamageCooldownTicks <= 0) {
                player.damage(this.frozenDamageAmount);
                state.coldDamageCooldownTicks = this.frozenDamageEveryTicks;
            }
        } else if (state.coldDamageCooldownTicks <= 0) {
            player.damage(this.snowyDamageAmount);
            state.coldDamageCooldownTicks = this.snowyDamageEveryTicks;
        }

    }

    /**
     * 解除冻结遮罩并逐步降低玩家冻结 tick。
     */
    private void thawPlayer(Player player) {
        this.freezeOverlay.release(player);
        int current = player.getFreezeTicks();
        if (current > 0) {
            player.setFreezeTicks(Math.max(0, current - this.coldThawPerCycle));
        }

    }

    /**
     * 结算炎热区域的沙暴效果：暴露于天空下时施加失明、生成沙尘粒子并播放音效。
     */
    private void handleHotStorm(Player player, ThermoPlayerState state, PlayerClimateContext ctx) {
        if (ctx.canSeeSky) {
            int blindnessLevel = this.hotStormBlindnessLevel;
            if (ctx.holdingWarmItem) {
                blindnessLevel = Math.max(0, blindnessLevel - 1);
            }

            if (blindnessLevel > 0) {
                this.addShortEffect(player, PotionEffectType.BLINDNESS, this.hotStormBlindnessDurationTicks, blindnessLevel);
            }

            Location eye = player.getEyeLocation();
            player.getWorld().spawnParticle(Particle.DUST, eye, 8, 0.45, (double) 0.25F, 0.45, (double) 0.0F, new Particle.DustOptions(Color.fromRGB(214, 193, 156), 1.1F));
            if (state.stormSoundCooldownTicks <= 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_SAND_HIT, 0.35F, 0.75F);
                state.stormSoundCooldownTicks = 40;
            }

        }
    }

    /**
     * 施加一个短时药水效果（等级从 1 起，转换为 0 基的 amplifier）。
     */
    private void addShortEffect(Player player, PotionEffectType type, int duration, int level) {
        if (type != null && level > 0) {
            int amplifier = Math.max(0, level - 1);
            player.addPotionEffect(new PotionEffect(type, duration, amplifier, true, false, false));
        }
    }

    /**
     * 判断玩家主手或副手是否持有保暖物品。
     */
    private boolean isHoldingWarmItem(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return main != null && this.warmHandItems.contains(main.getType()) || off != null && this.warmHandItems.contains(off.getType());
    }

    /**
     * 判断玩家是否戴着非禁用材质的头盔（用于炎热区域的头盔安全判定）。
     */
    private boolean hasNonLeatherHelmet(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() != Material.AIR) {
            return !this.hotDisallowedHelmetItems.contains(helmet.getType());
        } else {
            return false;
        }
    }

    /**
     * 统计玩家身上真实穿戴的护甲件数（排除空槽与空气）。
     */
    private int countArmorPieces(PlayerInventory inventory) {
        int count = 0;
        if (this.isRealArmor(inventory.getHelmet())) {
            ++count;
        }

        if (this.isRealArmor(inventory.getChestplate())) {
            ++count;
        }

        if (this.isRealArmor(inventory.getLeggings())) {
            ++count;
        }

        if (this.isRealArmor(inventory.getBoots())) {
            ++count;
        }

        return count;
    }

    /**
     * 统计玩家身上锁甲类护甲的件数。
     */
    private int countChainmailPieces(PlayerInventory inventory) {
        int count = 0;
        if (this.isChainmail(inventory.getHelmet())) {
            ++count;
        }

        if (this.isChainmail(inventory.getChestplate())) {
            ++count;
        }

        if (this.isChainmail(inventory.getLeggings())) {
            ++count;
        }

        if (this.isChainmail(inventory.getBoots())) {
            ++count;
        }

        return count;
    }

    /**
     * 判断物品槽是否为真实护甲（非 null 且非空气）。
     */
    private boolean isRealArmor(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    /**
     * 判断物品是否为锁甲类护甲。
     */
    private boolean isChainmail(ItemStack item) {
        if (item == null) {
            return false;
        } else {
            boolean isChainmailItem;
            switch (item.getType()) {
                case CHAINMAIL_HELMET:
                case CHAINMAIL_CHESTPLATE:
                case CHAINMAIL_LEGGINGS:
                case CHAINMAIL_BOOTS:
                    isChainmailItem = true;
                    break;
                default:
                    isChainmailItem = false;
            }

            return isChainmailItem;
        }
    }

    /**
     * 判断玩家所站方块是否能直接看到天空（其 y 不低于该列最高方块减一）。
     */
    private boolean canSeeSky(Block base) {
        World world = base.getWorld();
        int highest = world.getHighestBlockYAt(base.getX(), base.getZ());
        return base.getY() >= highest - 1;
    }

    /**
     * 判断玩家上方一定高度内是否存在实心方块（即处于有顶遮蔽之下）。
     */
    private boolean hasSolidRoof(Block base) {
        World world = base.getWorld();
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();

        for (int dy = 1; dy <= this.roofScanHeight; ++dy) {
            Block up = world.getBlockAt(x, y + dy, z);
            if (up.getType().isSolid()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 在水平半径内（已加载区块中、垂直 ±2 格）查找热源方块，需判断点燃状态的方块需处于点燃态。
     */
    private boolean hasHeatBlockNearby(Block base, int radius) {
        World world = base.getWorld();
        int baseX = base.getX();
        int baseY = base.getY();
        int baseZ = base.getZ();
        int radiusSq = radius * radius;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (dx * dx + dz * dz <= radiusSq) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        for (int dy = -2; dy <= 2; ++dy) {
                            Block block = world.getBlockAt(x, baseY + dy, z);
                            Material type = block.getType();
                            if (this.heatSourceBlocks.contains(type)) {
                                if (!this.litOnlyHeatBlocks.contains(type)) {
                                    return true;
                                }

                                BlockData data = block.getBlockData();
                                if (!(data instanceof Lightable)) {
                                    return true;
                                }

                                Lightable lightable = (Lightable) data;
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

    /**
     * 将数值夹取到 [min, max] 区间。
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 玩家饮下普通水瓶时，给予一定时长的降温缓解。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onDrinkWater(PlayerItemConsumeEvent event) {
        if (this.enabled && this.heatEnabled) {
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.POTION) {
                ItemMeta itemMeta = item.getItemMeta();
                if (itemMeta instanceof PotionMeta) {
                    PotionMeta meta = (PotionMeta) itemMeta;
                    if (meta.getBasePotionType() == PotionType.WATER) {
                        ThermoPlayerState state = (ThermoPlayerState) this.states.computeIfAbsent(event.getPlayer().getUniqueId(), (id) -> new ThermoPlayerState());
                        state.wetReliefTicks = Math.max(state.wetReliefTicks, this.hotDrinkReliefTicks);
                    }
                }
            }
        }
    }

    /**
     * 水瓶溅射命中玩家时，按溅射强度给予降温缓解。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onWaterSplash(PotionSplashEvent event) {
        if (this.enabled && this.heatEnabled) {
            ItemMeta itemMeta = event.getPotion().getItem().getItemMeta();
            if (itemMeta instanceof PotionMeta) {
                PotionMeta meta = (PotionMeta) itemMeta;
                if (meta.getBasePotionType() == PotionType.WATER) {
                    for (LivingEntity affected : event.getAffectedEntities()) {
                        if (affected instanceof Player) {
                            Player player = (Player) affected;
                            if (!(event.getIntensity(affected) <= 0.05)) {
                                ThermoPlayerState state = (ThermoPlayerState) this.states.computeIfAbsent(player.getUniqueId(), (id) -> new ThermoPlayerState());
                                state.wetReliefTicks = Math.max(state.wetReliefTicks, this.hotSplashReliefTicks);
                            }
                        }
                    }

                }
            }
        }
    }

    /**
     * 玩家温度读数：以摄氏度原始值保存，并提供按单位换算后的数值与符号。
     */
    public record TemperatureReading(int celsius, TemperatureUnit unit) {
        public TemperatureReading {
        }

        public int value() {
            return this.unit.fromCelsiusRounded(this.celsius);
        }

        public String symbol() {
            return this.unit == TemperatureUnit.FAHRENHEIT ? "°F" : "°C";
        }
    }

    /**
     * 单次温度计算所需的玩家气候上下文快照，避免重复查询世界/背包状态。
     */
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
