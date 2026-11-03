package kinkin.aeternum.weather;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.calendar.SeasonUpdateEvent;
import kinkin.aeternum.util.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 季节性天气服务。
 *
 * <p>根据当前季节为指定世界安排下雨/雷暴日程：每个季节随机挑选若干“雨天”，
 * 雨天再按概率决定是否雷暴，并按配置的时长区间维持风暴或晴天。日程按“年+季节”持久化到
 * {@code data/weather_<year>_<season>.yml}，可跨重启恢复。</p>
 *
 * <p>附带两项辅助逻辑：
 * <ul>
 *     <li>村民工作窗口保护——在配置的白天时段内强制放晴，避免影响村民工作；</li>
 *     <li>手动覆盖尊重——当玩家/控制台使用天气指令时，当天不再被自动天气覆盖。</li>
 * </ul>
 * 通过 {@link PlatformScheduler} 以全局定时任务驱动，每 40 tick 检查一次日历日期是否变化。</p>
 */
public final class SeasonalWeatherService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private boolean enabled;
    private List<String> worldIds;
    private int rainyDaysPerSeason;
    private PlatformScheduler.TaskHandle clockTask;
    /**
     * 各季节的雨天数量覆盖值。
     */
    private final EnumMap<Season, Integer> rainyDaysOverrides = new EnumMap(Season.class);
    private double thunderChance;
    private int stormMin;
    private int stormMax;
    private int clearMin;
    private int clearMax;
    private boolean reseedEachSeason;
    private boolean respectManual;
    private boolean villagerWorkWindowEnabled;
    private int villagerWorkWindowStart;
    private int villagerWorkWindowEnd;
    private static final int BASE_SEASON_LENGTH = 28;
    /**
     * 当前季节被选为雨天的日期集合。
     */
    private final Set<Integer> rainyDays = new HashSet();
    private String lastAppliedCalendarDate = null;
    private volatile boolean manualOverrideToday = false;
    private boolean scheduledRainToday;
    private boolean scheduledThunderToday;
    private int scheduledStormTicks;
    private int scheduledClearTicks;
    /**
     * 各世界在工作窗口内是否已放晴的标记。
     */
    private final Map<UUID, Boolean> clearWorkWindowByWorld = new HashMap();

    public SeasonalWeatherService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.reloadFromConfig();
        this.loadOrSeedSchedule(seasons.getStateCopy());
    }

    /**
     * 注册事件监听并启动天气应用与时钟轮询任务。
     */
    public void register() {
        if (this.enabled) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            PlatformScheduler.executeGlobal(this.plugin, this::applyForToday);
            if (this.clockTask != null) {
                this.clockTask.cancel();
            }

            this.clockTask = PlatformScheduler.runGlobalTimer(this.plugin, this::tickWorldClock, 60L, 40L);
        }
    }

    /**
     * 注销事件监听并停止时钟轮询任务。
     */
    public void unregister() {
        if (this.clockTask != null) {
            this.clockTask.cancel();
        }

        this.clockTask = null;
        HandlerList.unregisterAll(this);
    }

    /**
     * 从 climate.yml 读取季节性天气的全部配置项。
     */
    private void reloadFromConfig() {
        this.enabled = this.plugin.cfg.climate.getBoolean("seasonal_weather.enabled", true);
        this.worldIds = new ArrayList(this.plugin.cfg.climate.getStringList("seasonal_weather.worlds"));
        if (this.worldIds.isEmpty()) {
            this.worldIds = List.of("world");
        }

        this.rainyDaysPerSeason = Math.max(0, this.plugin.cfg.climate.getInt("seasonal_weather.rainy_days_per_season", 10));
        this.rainyDaysOverrides.clear();
        ConfigurationSection section = this.plugin.cfg.climate.getConfigurationSection("seasonal_weather.rainy_days");
        if (section != null) {
            for (Season season : Season.values()) {
                int value = Math.max(0, section.getInt(season.name(), this.rainyDaysPerSeason));
                this.rainyDaysOverrides.put(season, value);
            }
        } else {
            for (Season season : Season.values()) {
                this.rainyDaysOverrides.put(season, this.rainyDaysPerSeason);
            }
        }

        this.thunderChance = Math.max((double) 0.0F, Math.min((double) 1.0F, this.plugin.cfg.climate.getDouble("seasonal_weather.thunder_chance", 0.2)));
        this.stormMin = this.plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.min", 6000);
        this.stormMax = this.plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.max", 18000);
        this.clearMin = this.plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.min", 6000);
        this.clearMax = this.plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.max", 24000);
        this.reseedEachSeason = this.plugin.cfg.climate.getBoolean("seasonal_weather.reseed_each_season", true);
        this.respectManual = this.plugin.cfg.climate.getBoolean("seasonal_weather.respect_manual_commands", true);
        this.villagerWorkWindowEnabled = this.plugin.cfg.climate.getBoolean("seasonal_weather.villager_work_window.enabled", true);
        this.villagerWorkWindowStart = this.normalizeTime((long) this.plugin.cfg.climate.getInt("seasonal_weather.villager_work_window.start_tick", 2000));
        this.villagerWorkWindowEnd = this.normalizeTime((long) this.plugin.cfg.climate.getInt("seasonal_weather.villager_work_window.end_tick", 9000));
        if (this.villagerWorkWindowStart == this.villagerWorkWindowEnd) {
            this.villagerWorkWindowEnabled = false;
            this.plugin.getLogger().warning("[SeasonalWeather] Villager work window start/end are equal; protection disabled.");
        }

    }

    /**
     * 季节更新事件：进入新季节第一天时（按配置）重新生成雨天日程并立即应用。
     */
    @EventHandler
    public void onSeasonEvent(SeasonUpdateEvent event) {
        if (this.enabled) {
            PlatformScheduler.executeGlobal(this.plugin, () -> {
                CalendarState state = event.getState();
                if (state.day == 1 && (this.reseedEachSeason || this.rainyDays.isEmpty())) {
                    this.seedSchedule(state);
                    this.saveSchedule(state);
                }

                this.manualOverrideToday = false;
                this.applyForToday();
            });
        }
    }

    /**
     * 全局时钟轮询：日历日期变化时重新应用天气，否则维持村民工作窗口保护。
     */
    private void tickWorldClock() {
        if (this.enabled) {
            CalendarState state = this.seasons.getStateCopy();
            String date = this.calendarDateKey(state);
            if (!Objects.equals(date, this.lastAppliedCalendarDate)) {
                this.manualOverrideToday = false;
                this.applyForToday();
            } else {
                this.enforceVillagerWorkWindow();
            }
        }
    }

    /**
     * 计算并应用“今天”的天气（除非被手动覆盖且需尊重手动指令）。
     */
    private void applyForToday() {
        if (this.enabled) {
            CalendarState st = this.seasons.getStateCopy();
            if (!this.respectManual || !this.manualOverrideToday) {
                this.lastAppliedCalendarDate = this.calendarDateKey(st);
                this.scheduledRainToday = this.rainyDays.contains(st.day);
                ThreadLocalRandom random = ThreadLocalRandom.current();
                this.scheduledThunderToday = this.scheduledRainToday && random.nextDouble() < this.thunderChance;
                this.scheduledStormTicks = this.clampRand(this.stormMin, this.stormMax);
                this.scheduledClearTicks = this.clampRand(this.clearMin, this.clearMax);
                this.clearWorkWindowByWorld.clear();

                for (World world : this.worldsToApply()) {
                    boolean workWindowClear = this.scheduledRainToday && this.isInsideVillagerWorkWindow(world.getTime());
                    this.clearWorkWindowByWorld.put(world.getUID(), workWindowClear);
                    if (workWindowClear) {
                        this.applyClearWeather(world, Math.max(24000, this.scheduledClearTicks));
                    } else {
                        this.applyScheduledWeather(world);
                    }
                }

            }
        }
    }

    /**
     * 维持村民工作窗口保护：世界进入/离开工作窗口时切换晴天/雨天。
     */
    private void enforceVillagerWorkWindow() {
        if (this.villagerWorkWindowEnabled && this.scheduledRainToday) {
            if (!this.respectManual || !this.manualOverrideToday) {
                for (World world : this.worldsToApply()) {
                    boolean inside = this.isInsideVillagerWorkWindow(world.getTime());
                    Boolean previous = (Boolean) this.clearWorkWindowByWorld.put(world.getUID(), inside);
                    if (previous == null || previous != inside) {
                        if (inside) {
                            this.applyClearWeather(world, Math.max(24000, this.scheduledClearTicks));
                        } else {
                            this.applyScheduledWeather(world);
                        }
                    }
                }

            }
        }
    }

    /**
     * 按今日日程设置世界天气：雨天则风暴（可雷暴），否则放晴。
     */
    private void applyScheduledWeather(World world) {
        if (this.scheduledRainToday) {
            world.setStorm(true);
            world.setWeatherDuration(this.scheduledStormTicks);
            world.setThunderDuration(this.scheduledThunderToday ? this.scheduledStormTicks : 0);
            world.setThundering(this.scheduledThunderToday);
        } else {
            this.applyClearWeather(world, this.scheduledClearTicks);
        }
    }

    /**
     * 将世界设为晴天并维持指定 tick 数。
     */
    private void applyClearWeather(World world, int durationTicks) {
        world.setStorm(false);
        world.setWeatherDuration(Math.max(1, durationTicks));
        world.setThunderDuration(0);
        world.setThundering(false);
    }

    /**
     * 判断某个世界时间是否落在村民工作窗口内（支持跨午夜区间）。
     */
    private boolean isInsideVillagerWorkWindow(long rawWorldTime) {
        if (!this.villagerWorkWindowEnabled) {
            return false;
        } else {
            int time = this.normalizeTime(rawWorldTime);
            if (this.villagerWorkWindowStart < this.villagerWorkWindowEnd) {
                return time >= this.villagerWorkWindowStart && time < this.villagerWorkWindowEnd;
            } else {
                return time >= this.villagerWorkWindowStart || time < this.villagerWorkWindowEnd;
            }
        }
    }

    /**
     * 把世界时间归一到 {@code [0, 24000)} 区间。
     */
    private int normalizeTime(long time) {
        long normalized = time % 24000L;
        if (normalized < 0L) {
            normalized += 24000L;
        }

        return (int) normalized;
    }

    /**
     * 解析配置中的世界名，返回当前已加载的世界对象列表。
     */
    private List<World> worldsToApply() {
        List<World> out = new ArrayList();

        for (String id : this.worldIds) {
            World world = Bukkit.getWorld(id);
            if (world != null) {
                out.add(world);
            }
        }

        return out;
    }

    /**
     * 标记今天已被手动覆盖，后续自动天气不再改动。
     */
    public void markManualOverride() {
        this.manualOverrideToday = true;
    }

    /**
     * 监听玩家执行的天气指令：命中手动天气命令时标记手动覆盖。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.MONITOR
    )
    public void onPlayerWeatherCommand(PlayerCommandPreprocessEvent event) {
        if (this.enabled && this.respectManual) {
            if (this.isManualWeatherCommand(event.getMessage())) {
                this.markManualOverride();
            }

        }
    }

    /**
     * 监听控制台执行的天气指令：命中手动天气命令时标记手动覆盖。
     */
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onConsoleWeatherCommand(ServerCommandEvent event) {
        if (this.enabled && this.respectManual) {
            if (this.isManualWeatherCommand(event.getCommand())) {
                this.markManualOverride();
            }

        }
    }

    /**
     * 判断给定指令是否为会改变天气的手动命令（rain/storm/sun/thunder 或 weather 子命令）。
     */
    private boolean isManualWeatherCommand(String raw) {
        if (raw == null) {
            return false;
        } else {
            String command = raw.trim();
            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }

            if (command.isEmpty()) {
                return false;
            } else {
                String[] args = command.split("\\s+");
                String root = args[0].toLowerCase(Locale.ROOT);
                int namespace = root.indexOf(58);
                if (namespace >= 0) {
                    root = root.substring(namespace + 1);
                }

                if (!"rain".equals(root) && !"storm".equals(root) && !"sun".equals(root) && !"thunder".equals(root)) {
                    if ("weather".equals(root) && args.length >= 2) {
                        String action = args[1].toLowerCase(Locale.ROOT);
                        return "clear".equals(action) || "rain".equals(action) || "thunder".equals(action) || "storm".equals(action);
                    } else {
                        return false;
                    }
                } else {
                    return true;
                }
            }
        }
    }

    /**
     * 读取已保存的日程；无有效日程则重新生成并保存。
     */
    private void loadOrSeedSchedule(CalendarState st) {
        if (!this.loadSchedule(st)) {
            this.seedSchedule(st);
            this.saveSchedule(st);
        }

    }

    /**
     * 按季节长度与目标天数，用确定性随机种子生成当季的雨天日期集合。
     */
    private void seedSchedule(CalendarState st) {
        this.rainyDays.clear();
        int days = this.seasons.getDaysPerSeason();
        if (days > 0) {
            int baseTarget = (Integer) this.rainyDaysOverrides.getOrDefault(st.season, this.rainyDaysPerSeason);
            int target;
            if (days == 28) {
                target = baseTarget;
            } else {
                double factor = (double) days / (double) 28.0F;
                target = (int) Math.round((double) baseTarget * factor);
            }

            int need = Math.min(Math.max(0, target), days);
            long seed = (long) st.year * 1315423911L ^ (long) st.season.ordinal() * 2654435761L;
            Random random = new Random(seed);

            while (this.rainyDays.size() < need) {
                int day = 1 + random.nextInt(days);
                this.rainyDays.add(day);
            }

        }
    }

    /**
     * 从磁盘读取当季的雨天日程；文件不存在或无数据时返回 false。
     */
    private boolean loadSchedule(CalendarState st) {
        try {
            File file = this.scheduleFile(st);
            if (!file.exists()) {
                return false;
            } else {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                List<Integer> list = yaml.getIntegerList("rainy_days");
                this.rainyDays.clear();
                this.rainyDays.addAll(list);
                return !this.rainyDays.isEmpty();
            }
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 把当季的雨天日程写入磁盘。
     */
    private void saveSchedule(CalendarState st) {
        try {
            File file = this.scheduleFile(st);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("year", st.year);
            yaml.set("season", st.season.name());
            yaml.set("rainy_days", new ArrayList(this.rainyDays));
            yaml.save(file);
        } catch (IOException failure) {
        }

    }

    /**
     * 生成当季日程文件的路径：{@code data/weather_<year>_<season>.yml}。
     */
    private File scheduleFile(CalendarState st) {
        File dataFolder = this.plugin.getDataFolder();
        int year = st.year;
        return new File(dataFolder, "data/weather_" + year + "_" + st.season.name() + ".yml");
    }

    /**
     * 在 [min, max] 区间内取随机 tick 数（自动纠正颠倒区间与非法下界）。
     */
    private int clampRand(int min, int max) {
        if (max < min) {
            int temp = min;
            min = max;
            max = temp;
        }

        if (min < 1) {
            min = 1;
        }

        if (max < 1) {
            max = min;
        }

        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * 生成用于比较“是否换了日期”的日历键：年:季:月:日。
     */
    private String calendarDateKey(CalendarState state) {
        String month = state.monthsEnabled && state.monthId != null ? state.monthId : "";
        int year = state.year;
        return year + ":" + state.season.name() + ":" + month + ":" + state.day;
    }
}
