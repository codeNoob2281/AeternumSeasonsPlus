package Kinkin.aeternum.calendar;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.compat.WorldCompat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 季节与日历服务。
 *
 * <p>本类是日历系统的核心：按频道（主世界 / 下界）维护独立的日历状态，并根据配置在世界时间、
 * 自定义时钟与多种模式之间推进日期。主要职责包括：</p>
 * <ul>
 *     <li>从配置构建并缓存每个频道的运行时 {@link ChannelRuntime}，包括世界映射、季节天数、
 *         月份定义与推进策略；</li>
 *     <li>通过 Bukkit 调度任务驱动两种时钟：跟随世界时间的“世界时钟”和按现实时间自转的“自定义时钟”；</li>
 *     <li>监听玩家睡觉、时间跳过、命令等事件，把外部时间变化转换为日历推进，并在状态变化时触发
 *         {@link SeasonUpdateEvent}；</li>
 *     <li>将日历状态持久化到 {@code data/calendars/<channel>.yml}，并在启动/重载时恢复。</li>
 * </ul>
 * <p>依赖 {@link AeternumSeasonsPlugin} 提供配置、日志与语言支持，依赖 {@link WorldCompat}
 * 处理跨版本的世界时间与游戏规则差异。</p>
 */
public final class SeasonService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    /** 永久冬季世界的名称。 */
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    /** 下界对应的“热”世界名称。 */
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    /** 自定义时钟的调度周期（tick）。 */
    private static final long CUSTOM_CLOCK_UPDATE_PERIOD_TICKS = 20L;
    /** 每个频道的运行时，按频道索引。 */
    private final EnumMap<CalendarChannel, ChannelRuntime> runtimes = new EnumMap(CalendarChannel.class);
    /** 世界名（小写）到显式指定频道的映射。 */
    private final Map<String, CalendarChannel> explicitWorldChannels = new HashMap();
    /** 是否已向 Bukkit 注册事件监听。 */
    private boolean registered;
    private long lastFrostManualAdvanceMs = 0L;
    /** 玩家进入床时记录的时间快照，用于离开床时判断是否应推进日期。 */
    private final Map<UUID, SleepSnapshot> sleepSnapshots = new HashMap();
    /** 已排队、等待确认的时间命令，按世界 UUID 索引。 */
    private final Map<UUID, ManualTimeCommand> pendingManualTimeCommands = new HashMap();

    public SeasonService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.rebuildFromConfig();
    }

    /** 注册事件监听（幂等）并为所有已启用的频道启动时钟任务。 */
    public void register() {
        if (!this.registered) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            this.registered = true;
        }

        for(ChannelRuntime rt : this.runtimes.values()) {
            rt.registerTasks();
        }

    }

    /** 停止所有频道任务、清空临时状态并注销事件监听。 */
    public void unregister() {
        for(ChannelRuntime rt : this.runtimes.values()) {
            rt.unregisterTasks();
        }

        this.sleepSnapshots.clear();
        this.pendingManualTimeCommands.clear();
        HandlerList.unregisterAll(this);
        this.registered = false;
    }

    /** 重新从配置构建所有频道；若此前已注册，则重启其时钟任务。 */
    public synchronized void reloadCalendarSettings() {
        boolean wasRegistered = this.registered;
        if (wasRegistered) {
            for(ChannelRuntime rt : this.runtimes.values()) {
                rt.unregisterTasks();
            }
        }

        this.rebuildFromConfig();
        if (wasRegistered) {
            for(ChannelRuntime rt : this.runtimes.values()) {
                rt.registerTasks();
            }
        }

    }

    /** 清空并按当前配置重建两个频道的运行时，然后刷新世界到频道的映射。 */
    private void rebuildFromConfig() {
        this.runtimes.clear();
        this.explicitWorldChannels.clear();
        ChannelRuntime overworld = this.buildRuntime(CalendarChannel.OVERWORLD);
        ChannelRuntime nether = this.buildRuntime(CalendarChannel.NETHER);
        this.runtimes.put(CalendarChannel.OVERWORLD, overworld);
        this.runtimes.put(CalendarChannel.NETHER, nether);
        this.indexWorldMappings(overworld);
        this.indexWorldMappings(nether);
    }

    /** 将一个运行时中配置的世界名登记到显式频道映射表。 */
    private void indexWorldMappings(ChannelRuntime rt) {
        if (rt != null && rt.enabled) {
            for(String name : rt.worldNames) {
                if (name != null && !name.isBlank()) {
                    this.explicitWorldChannels.put(name.trim().toLowerCase(Locale.ROOT), rt.channel);
                }
            }

        }
    }

    /**
     * 依据配置为一个频道构建运行时对象。
     *
     * <p>读取该频道的 enabled、mode、worlds、季节天数、推进策略与月份定义，并加载其持久化状态。
     * 对旧版仅主世界配置做兼容回退（无频道配置段时按主世界处理）。</p>
     */
    private ChannelRuntime buildRuntime(CalendarChannel channel) {
        ConfigurationSection section = this.getChannelSection(channel);
        boolean legacyOverworld = channel == CalendarChannel.OVERWORLD && section == null;
        boolean enabled = legacyOverworld || this.getBoolean(section, "enabled", channel == CalendarChannel.OVERWORLD);
        CalendarMode mode = CalendarMode.fromString(this.getString(section, "mode", "SEASONS"), CalendarMode.SEASONS);
        Set<String> worlds = new LinkedHashSet();

        for(String worldName : this.getStringList(section, "worlds")) {
            if (worldName != null && !worldName.isBlank()) {
                worlds.add(worldName.trim());
            }
        }

        if (legacyOverworld && worlds.isEmpty()) {
            worlds.add("world");
        }

        int daysPerSeason = Math.max(4, this.getInt(section, "days_per_season", 28));
        EnumMap<Season, Integer> bySeason = new EnumMap(Season.class);
        ConfigurationSection bySeasonSec = this.getSubSection(section, "days_per_season_by_season");
        if (legacyOverworld && bySeasonSec == null) {
            bySeasonSec = this.readLegacySection("days_per_season_by_season", "calendar.days_per_season_by_season");
        }

        if (bySeasonSec != null) {
            for(String key : bySeasonSec.getKeys(false)) {
                Season season;
                try {
                    season = Season.valueOf(key.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException invalidSeason) {
                    continue;
                }

                bySeason.put(season, Math.max(4, bySeasonSec.getInt(key, daysPerSeason)));
            }
        }

        ConfigurationSection advance = this.getSubSection(section, "advance");
        if (legacyOverworld && advance == null) {
            advance = this.readLegacySection("advance", "calendar.advance");
        }

        boolean followWorldTime = this.getBoolean(advance, "follow_overworld_time", legacyOverworld);
        boolean advanceOnSleep = this.getBoolean(advance, "on_sleep", true);
        int seasonDaylightMinutes = Math.max(0, this.getInt(advance, "daylight_minutes", 0));
        int seasonNightMinutes = Math.max(0, this.getInt(advance, "night_minutes", 0));
        boolean requirePlayersOnServer = this.getBoolean(advance, "require_players_on_server", false);
        String timeAnchorWorld = this.getString(advance, "time_anchor_world", channel == CalendarChannel.NETHER ? "aeternum_heat" : "world");
        int maxCatchupDays = Math.max(0, this.getInt(advance, "max_catchup_days", 2));
        List<CalendarMonthDefinition> months = new ArrayList();
        ConfigurationSection monthsSection = this.getSubSection(section, "months");
        boolean monthsEnabled = false;
        if (monthsSection != null && monthsSection.getBoolean("enabled", false)) {
            monthsEnabled = true;
            List<String> order = monthsSection.getStringList("order");
            ConfigurationSection defs = monthsSection.getConfigurationSection("definitions");
            if (defs != null) {
                for(String monthId : order) {
                    if (monthId != null && !monthId.isBlank()) {
                        ConfigurationSection monthDef = defs.getConfigurationSection(monthId);
                        if (monthDef != null) {
                            Season season;
                            try {
                                season = Season.valueOf(monthDef.getString("season", "SPRING").toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException invalidSeason) {
                                season = Season.SPRING;
                            }

                            String displayName = monthDef.getString("display_name", monthId);
                            int days = Math.max(1, monthDef.getInt("days", 30));
                            int realTimeMinutesPerDay = Math.max(0, monthDef.getInt("real_time_minutes_per_day", 0));
                            int daylight = Math.max(0, monthDef.getInt("daylight_minutes", 0));
                            int night = Math.max(0, monthDef.getInt("night_minutes", 0));
                            months.add(new CalendarMonthDefinition(monthId, displayName, season, days, realTimeMinutesPerDay, daylight, night));
                        }
                    }
                }
            }
        }

        if (monthsEnabled && months.isEmpty()) {
            monthsEnabled = false;
        }

        if (mode == CalendarMode.MONTHS && !monthsEnabled) {
            mode = CalendarMode.SEASONS;
        }

        List<CalendarMonthDefinition> effectiveMonths = monthsEnabled ? months : List.of();
        File stateFile = new File(this.plugin.getDataFolder(), "data/calendars/" + channel.id() + ".yml");
        CalendarState state = this.loadState(channel, mode, effectiveMonths, stateFile);
        return new ChannelRuntime(channel, enabled, mode, worlds, daysPerSeason, bySeason, advanceOnSleep, seasonDaylightMinutes, seasonNightMinutes, followWorldTime, requirePlayersOnServer, timeAnchorWorld, maxCatchupDays, effectiveMonths, stateFile, state);
    }

    /** 读取某个频道在配置中的 section。 */
    private ConfigurationSection getChannelSection(CalendarChannel channel) {
        ConfigurationSection root = this.plugin.cfg.calendar.getConfigurationSection("calendar.channels");
        return root == null ? null : root.getConfigurationSection(channel.name());
    }

    /** 读取旧版平铺路径或新版 calendar.* 路径下的 section，优先使用平铺路径。 */
    private ConfigurationSection readLegacySection(String plainPath, String calendarPath) {
        if (this.plugin.cfg.calendar.isConfigurationSection(plainPath)) {
            return this.plugin.cfg.calendar.getConfigurationSection(plainPath);
        } else {
            return this.plugin.cfg.calendar.isConfigurationSection(calendarPath) ? this.plugin.cfg.calendar.getConfigurationSection(calendarPath) : null;
        }
    }

    /** 安全地获取子 section（父 section 为 null 时返回 null）。 */
    private ConfigurationSection getSubSection(ConfigurationSection root, String path) {
        return root == null ? null : root.getConfigurationSection(path);
    }

    /** 从 section 读取字符串，section 为 null 时返回默认值。 */
    private String getString(ConfigurationSection section, String path, String defaultValue) {
        return section == null ? defaultValue : section.getString(path, defaultValue);
    }

    /** 从 section 读取整数，section 为 null 时返回默认值。 */
    private int getInt(ConfigurationSection section, String path, int defaultValue) {
        return section == null ? defaultValue : section.getInt(path, defaultValue);
    }

    /** 从 section 读取布尔值，section 为 null 时返回默认值。 */
    private boolean getBoolean(ConfigurationSection section, String path, boolean defaultValue) {
        return section == null ? defaultValue : section.getBoolean(path, defaultValue);
    }

    /** 从 section 读取字符串列表，section 为 null 时返回空列表。 */
    private List<String> getStringList(ConfigurationSection section, String path) {
        return section == null ? List.of() : section.getStringList(path);
    }

    /**
     * 加载某个频道的日历状态。
     *
     * <p>状态文件不存在时按默认值创建（月份模式取首个月份；季节模式主世界从春季开始、
     * 下界从夏季开始）。文件存在时读取并校正越界字段。</p>
     */
    private CalendarState loadState(CalendarChannel channel, CalendarMode mode, List<CalendarMonthDefinition> months, File file) {
        if (!file.exists()) {
            if (mode == CalendarMode.MONTHS && !months.isEmpty()) {
                CalendarMonthDefinition first = (CalendarMonthDefinition)months.get(0);
                CalendarState state = new CalendarState(1, 1, first.getSeason());
                state.monthsEnabled = true;
                state.monthId = first.getId();
                state.monthDisplayName = first.getDisplayName();
                state.monthIndex = 0;
                state.daysInMonth = first.getDays();
                return state;
            } else {
                return new CalendarState(1, 1, channel == CalendarChannel.NETHER ? Season.SUMMER : Season.SPRING);
            }
        } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            int year = Math.max(1, yaml.getInt("year", 1));
            int day = Math.max(1, yaml.getInt("day", 1));

            Season season;
            try {
                season = Season.valueOf(yaml.getString("season", channel == CalendarChannel.NETHER ? "SUMMER" : "SPRING"));
            } catch (IllegalArgumentException invalidSeason) {
                season = channel == CalendarChannel.NETHER ? Season.SUMMER : Season.SPRING;
            }

            CalendarState state = new CalendarState(year, day, season);
            if (mode == CalendarMode.MONTHS && !months.isEmpty()) {
                String monthId = yaml.getString("month", ((CalendarMonthDefinition)months.get(0)).getId());
                int monthIndex = this.findMonthIndex(months, monthId);
                if (monthIndex < 0) {
                    monthIndex = 0;
                }

                CalendarMonthDefinition monthDef = (CalendarMonthDefinition)months.get(monthIndex);
                state.monthsEnabled = true;
                state.monthId = monthDef.getId();
                state.monthDisplayName = monthDef.getDisplayName();
                state.monthIndex = monthIndex;
                state.daysInMonth = monthDef.getDays();
                state.season = monthDef.getSeason();
                if (state.day > monthDef.getDays()) {
                    state.day = monthDef.getDays();
                }
            }

            return state;
        }
    }

    /**
     * 解析世界所属的日历频道。
     *
     * <p>依次尝试：显式世界映射、特殊世界名（aeternum_heat）、世界环境（下界 / 主世界），
     * 均不匹配或对应频道未启用时返回 {@code null}。</p>
     */
    public CalendarChannel resolveChannel(World world) {
        if (world == null) {
            return null;
        } else {
            CalendarChannel explicit = (CalendarChannel)this.explicitWorldChannels.get(world.getName().toLowerCase(Locale.ROOT));
            if (explicit != null && this.isChannelEnabled(explicit)) {
                return explicit;
            } else if (world.getName().equalsIgnoreCase("aeternum_heat") && this.isChannelEnabled(CalendarChannel.NETHER)) {
                return CalendarChannel.NETHER;
            } else if (world.getEnvironment() == Environment.NETHER && this.isChannelEnabled(CalendarChannel.NETHER)) {
                return CalendarChannel.NETHER;
            } else {
                return world.getEnvironment() == Environment.NORMAL && this.isChannelEnabled(CalendarChannel.OVERWORLD) ? CalendarChannel.OVERWORLD : null;
            }
        }
    }

    /** 判断世界是否为永久冬季世界（aeternum_frost）。 */
    public boolean isPermanentWinterWorld(World world) {
        return world != null && world.getName().equalsIgnoreCase("aeternum_frost");
    }

    /** 判断指定频道的运行时是否存在且已启用。 */
    public boolean isChannelEnabled(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null && rt.enabled;
    }

    /** 返回当前解析到指定频道的所有已加载世界。 */
    public List<World> getWorlds(CalendarChannel channel) {
        return (List)Bukkit.getWorlds().stream().filter((world) -> this.resolveChannel(world) == channel).collect(Collectors.toList());
    }

    /** 世界加载时标记频道世界缓存为脏，促使下次访问时重建。 */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        this.markRuntimeWorldCachesDirty();
    }

    /** 世界卸载时从各频道缓存中驱逐该世界。 */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        for(ChannelRuntime runtime : this.runtimes.values()) {
            runtime.evictCachedWorld(event.getWorld());
        }

    }

    /** 将所有频道的世界缓存标记为脏。 */
    private void markRuntimeWorldCachesDirty() {
        for(ChannelRuntime runtime : this.runtimes.values()) {
            runtime.markWorldCacheDirty();
        }

    }

    /** 返回主世界频道状态的副本。 */
    public synchronized CalendarState getStateCopy() {
        return this.getStateCopy(CalendarChannel.OVERWORLD);
    }

    /** 返回指定频道状态的副本；频道不存在时返回默认春季状态。 */
    public synchronized CalendarState getStateCopy(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt == null ? new CalendarState(1, 1, Season.SPRING) : rt.state.copy();
    }

    /** 返回世界对应频道状态的副本；永久冬季世界强制为冬季。 */
    public synchronized CalendarState getStateCopy(World world) {
        CalendarChannel channel = this.resolveChannel(world);
        CalendarState state = channel != null ? this.getStateCopy(channel) : this.getStateCopy(CalendarChannel.OVERWORLD);
        if (this.isPermanentWinterWorld(world)) {
            state.season = Season.WINTER;
        }

        return state;
    }

    /** 预演明天的状态并返回副本，不影响实际状态。 */
    public synchronized CalendarState peekTomorrowState(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt == null ? null : rt.peekTomorrow();
    }

    /** 返回主世界频道配置的默认每季天数。 */
    public int getDefaultDaysPerSeason() {
        return this.getDefaultDaysPerSeason(CalendarChannel.OVERWORLD);
    }

    /** 返回指定频道配置的默认每季天数。 */
    public int getDefaultDaysPerSeason(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.daysPerSeason : 28;
    }

    /** 返回主世界频道当前周期的天数。 */
    public int getDaysPerSeason() {
        return this.getDaysPerSeason(CalendarChannel.OVERWORLD);
    }

    /** 返回指定频道当前周期的天数（月份模式下为当月天数）。 */
    public int getDaysPerSeason(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.getCurrentPeriodLength() : 28;
    }

    /** 返回指定频道当前周期的天数（月份模式下为当月天数）。 */
    public int getCurrentPeriodLength(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.getCurrentPeriodLength() : 28;
    }

    /** 返回指定频道当前月份的标识；未启用月份模式时为 null。 */
    public String getCurrentMonthId(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.state.monthId : null;
    }

    /** 返回指定频道当前月份的显示名；未启用月份模式时为 null。 */
    public String getCurrentMonthDisplayName(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.state.monthDisplayName : null;
    }

    /** 判断指定频道是否处于月份模式。 */
    public boolean isMonthsEnabled(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null && rt.mode == CalendarMode.MONTHS;
    }

    /** 返回指定频道的日历模式；频道不存在时默认为季节模式。 */
    public CalendarMode getMode(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        return rt != null ? rt.mode : CalendarMode.SEASONS;
    }

    /** 将主世界频道推进一天。 */
    public synchronized void nextDay() {
        this.nextDay(CalendarChannel.OVERWORLD);
    }

    /** 将指定频道推进一天。 */
    public synchronized void nextDay(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        if (rt != null) {
            rt.nextDay(false);
        }

    }

    /** 设置主世界频道的季节。 */
    public synchronized void setSeason(Season season) {
        this.setSeason(CalendarChannel.OVERWORLD, season);
    }

    /** 设置指定频道的季节。 */
    public synchronized void setSeason(CalendarChannel channel, Season season) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        if (rt != null) {
            rt.setSeason(season);
        }

    }

    /** 设置主世界频道的日期。 */
    public synchronized void setDay(int day) {
        this.setDay(CalendarChannel.OVERWORLD, day);
    }

    /** 设置指定频道的日期（会自动限制在当前周期范围内）。 */
    public synchronized void setDay(CalendarChannel channel, int day) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        if (rt != null) {
            rt.setDay(day);
        }

    }

    /** 设置主世界频道的年份。 */
    public synchronized void setYear(int year) {
        this.setYear(CalendarChannel.OVERWORLD, year);
    }

    /** 设置指定频道的年份。 */
    public synchronized void setYear(CalendarChannel channel, int year) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        if (rt != null) {
            rt.setYear(year);
        }

    }

    /** 判断当前是否有任意玩家在线。 */
    private boolean hasAnyOnlinePlayer() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    /** 判断给定的世界时间（tick）是否处于夜晚区间。 */
    private boolean isNightTime(long worldTime) {
        long normalizedTime = worldTime % 24000L;
        if (normalizedTime < 0L) {
            normalizedTime += 24000L;
        }

        return normalizedTime >= 12541L || normalizedTime <= 1000L;
    }

    /**
     * 反射调用时间跳过事件的 {@code getSkipReason()} 并返回其名称。
     *
     * <p>该访问器在不同服务端版本中可能不存在，故使用反射并吞掉异常，失败时返回空串。</p>
     */
    private static String getCompatSkipReasonName(TimeSkipEvent event) {
        if (event == null) {
            return "";
        } else {
            try {
                Object reason = event.getClass().getMethod("getSkipReason").invoke(event);
                if (reason instanceof Enum) {
                    return ((Enum)reason).name();
                } else {
                    return reason != null ? String.valueOf(reason) : "";
                }
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /**
     * 将玩家/控制台输入的时间命令解析为内部的时间命令对象。
     *
     * <p>支持 {@code day}、{@code night} 以及 {@code time add <值>} / {@code time set <值>}
     * 等写法；解析失败或无法识别时返回 {@code null}。命令中的命名空间前缀（{@code namespace:}）
     * 会被忽略。</p>
     */
    private ManualTimeCommand parseManualTimeCommand(String rawCommand, long currentWorldTime) {
        if (rawCommand == null) {
            return null;
        } else {
            String cmd = rawCommand.trim();
            if (cmd.isEmpty()) {
                return null;
            } else {
                if (cmd.charAt(0) == '/') {
                    cmd = cmd.substring(1).trim();
                }

                if (cmd.isEmpty()) {
                    return null;
                } else {
                    String[] split = cmd.split("\\s+");
                    if (split.length == 0) {
                        return null;
                    } else {
                        String root = split[0].toLowerCase(Locale.ROOT);
                        if (root.contains(":")) {
                            root = root.substring(root.indexOf(58) + 1);
                        }

                        if (root.equals("day")) {
                            return new ManualTimeCommand(0L, 1);
                        } else if (root.equals("night")) {
                            long current = this.normalizeTime(currentWorldTime);
                            return new ManualTimeCommand(14000L, 14000L < current ? 1 : 0);
                        } else if (root.equals("time") && split.length >= 3) {
                            String action = split[1].toLowerCase(Locale.ROOT);
                            String value = split[2].toLowerCase(Locale.ROOT);
                            if (value.contains(":")) {
                                value = value.substring(value.indexOf(58) + 1);
                            }

                            if (action.equals("add")) {
                                Long ticks = this.parseTimeTicks(value);
                                if (ticks == null) {
                                    return null;
                                } else {
                                    long current = this.normalizeTime(currentWorldTime);

                                    long total;
                                    try {
                                        total = Math.addExact(current, ticks);
                                    } catch (ArithmeticException overflow) {
                                        return null;
                                    }

                                    long crossings = ticks > 0L ? Math.floorDiv(total, 24000L) : 0L;
                                    int days = (int)Math.min(2147483647L, Math.max(0L, crossings));
                                    return new ManualTimeCommand(Math.floorMod(total, 24000L), days);
                                }
                            } else if (!action.equals("set")) {
                                return null;
                            } else {
                                long current = this.normalizeTime(currentWorldTime);
                                ManualTimeCommand command;
                                switch (value) {
                                    case "day":
                                    case "morning":
                                        command = this.manualSetCommand(current, 1000L);
                                        break;
                                    case "night":
                                        command = this.manualSetCommand(current, 13000L);
                                        break;
                                    case "noon":
                                        command = this.manualSetCommand(current, 6000L);
                                        break;
                                    case "midnight":
                                        command = this.manualSetCommand(current, 18000L);
                                        break;
                                    default:
                                        Long ticks = this.parseTimeTicks(value);
                                        command = ticks != null ? this.manualSetCommand(current, Math.floorMod(ticks, 24000L)) : null;
                                }

                                return command;
                            }
                        } else {
                            return null;
                        }
                    }
                }
            }
        }
    }

    /** 构造一条“设置到某时刻”的命令，并在跨越午夜时标记需推进的天数。 */
    private ManualTimeCommand manualSetCommand(long currentWorldTime, long targetWorldTime) {
        long current = this.normalizeTime(currentWorldTime);
        long target = this.normalizeTime(targetWorldTime);
        return new ManualTimeCommand(target, target < current ? 1 : 0);
    }

    /** 把时间（tick）归一化到 [0, 24000) 区间。 */
    private long normalizeTime(long time) {
        long normalized = time % 24000L;
        return normalized < 0L ? normalized + 24000L : normalized;
    }

    /**
     * 解析带单位后缀的时间数值为 tick 数。
     *
     * <p>支持后缀 {@code d}（天，24000 tick）、{@code s}（秒，20 tick）、{@code t}（tick，默认）。
     * 数值非法或非有限时返回 {@code null}。</p>
     */
    private Long parseTimeTicks(String rawValue) {
        if (rawValue != null && !rawValue.isBlank()) {
            String value = rawValue.trim().toLowerCase(Locale.ROOT);
            double multiplier = (double)1.0F;
            char suffix = value.charAt(value.length() - 1);
            if (suffix == 'd' || suffix == 's' || suffix == 't') {
                value = value.substring(0, value.length() - 1);
                multiplier = suffix == 'd' ? (double)24000.0F : (suffix == 's' ? (double)20.0F : (double)1.0F);
            }

            try {
                double amount = Double.parseDouble(value);
                return !Double.isFinite(amount) ? null : Math.round(amount * multiplier);
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * 将时间命令排入下一个主线程 tick 执行。
     *
     * <p>执行时再次确认该命令仍未被替换，且其关联的跳过事件未被取消，然后依据世界所属频道
     * 应用手动时间命令。</p>
     */
    private void scheduleManualTimeCommand(World world, ManualTimeCommand cmd) {
        if (world != null && cmd != null) {
            UUID worldId = world.getUID();
            this.pendingManualTimeCommands.put(worldId, cmd);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (this.pendingManualTimeCommands.remove(worldId, cmd)) {
                    if (cmd.appliedEvent != null && !cmd.appliedEvent.isCancelled() && this.plugin.isEnabled()) {
                        CalendarChannel channel = this.resolveChannel(world);
                        if (channel != null) {
                            ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
                            if (rt != null && rt.enabled) {
                                if (!rt.requirePlayersOnServer || this.hasAnyOnlinePlayer()) {
                                    if (world.getEnvironment() == Environment.NORMAL) {
                                        rt.applyManualCommand(cmd.targetWorldTime, cmd.daysToAdvance);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /** 返回控制台命令默认作用的世界：优先取主世界频道的锚世界，否则回退到名为 world 的世界。 */
    private World getConsoleDefaultWorld() {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(CalendarChannel.OVERWORLD);
        if (rt != null) {
            World anchor = rt.getAnchorWorld();
            if (anchor != null) {
                return anchor;
            }
        }

        return Bukkit.getWorld("world");
    }

    /** 监听玩家执行命令：若命令是时间操作，则解析并排队应用。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        World world = event.getPlayer().getWorld();
        ManualTimeCommand cmd = this.parseManualTimeCommand(event.getMessage(), world.getTime());
        if (cmd != null) {
            this.scheduleManualTimeCommand(world, cmd);
        }
    }

    /** 监听控制台命令：若命令是时间操作，则解析并在默认世界排队应用。 */
    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onServerCommand(ServerCommandEvent event) {
        World world = this.getConsoleDefaultWorld();
        if (world != null) {
            ManualTimeCommand cmd = this.parseManualTimeCommand(event.getCommand(), world.getTime());
            if (cmd != null) {
                this.scheduleManualTimeCommand(world, cmd);
            }
        }
    }

    /** 监听玩家上床：记录入睡时的时间快照，供离床时判断是否推进。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == BedEnterResult.OK) {
            Player player = event.getPlayer();
            World world = player.getWorld();
            this.sleepSnapshots.put(player.getUniqueId(), new SleepSnapshot(world.getUID(), System.currentTimeMillis(), world.getTime()));
        }
    }

    /**
     * 监听世界时间跳过事件（睡觉或命令导致的时间跳变）。
     *
     * <p>仅对属于已启用频道的主世界生效，内部时间写入会被忽略以避免自触发。睡觉造成的跳夜
     * 交由 {@link #scheduleConfirmedTimeSkip} 处理；命令造成的跳变则换算为需推进的天数后同样排队应用。</p>
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onTimeSkip(TimeSkipEvent event) {
        World world = event.getWorld();
        CalendarChannel channel = this.resolveChannel(world);
        if (channel != null) {
            ChannelRuntime runtime = (ChannelRuntime)this.runtimes.get(channel);
            if (runtime != null && runtime.enabled) {
                if (!runtime.internalTimeWrite) {
                    if (!runtime.requirePlayersOnServer || this.hasAnyOnlinePlayer()) {
                        if (world.getEnvironment() == Environment.NORMAL) {
                            String reason = getCompatSkipReasonName(event);
                            long currentTime = world.getTime();
                            long skip = event.getSkipAmount();
                            long newTime = (currentTime + skip) % 24000L;
                            if (newTime < 0L) {
                                newTime += 24000L;
                            }

                            if ("NIGHT_SKIP".equals(reason)) {
                                if (runtime.advanceOnSleep) {
                                    if (!runtime.followWorldTime || runtime.hasCustomClock()) {
                                        long now = System.currentTimeMillis();
                                        if (now - runtime.lastOverworldSleepRecoveryMs >= 1200L) {
                                            runtime.lastOverworldSleepRecoveryMs = now;
                                            this.scheduleConfirmedTimeSkip(world, new ManualTimeCommand(0L, 1), event);
                                        }
                                    }
                                }
                            } else {
                                if ("COMMAND".equals(reason) || "CUSTOM".equals(reason)) {
                                    ManualTimeCommand pending = (ManualTimeCommand)this.pendingManualTimeCommands.get(world.getUID());
                                    if (pending != null) {
                                        pending.appliedEvent = event;
                                        return;
                                    }
                                }

                                if ("COMMAND".equals(reason) || "CUSTOM".equals(reason)) {
                                    long total;
                                    try {
                                        total = Math.addExact(this.normalizeTime(currentTime), skip);
                                    } catch (ArithmeticException overflow) {
                                        return;
                                    }

                                    long crossings = skip > 0L ? Math.floorDiv(total, 24000L) : (newTime < this.normalizeTime(currentTime) ? 1L : 0L);
                                    int days = (int)Math.min(2147483647L, Math.max(0L, crossings));
                                    this.scheduleConfirmedTimeSkip(world, new ManualTimeCommand(newTime, days), event);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 把跳过事件绑定到时间命令上并排队，以便执行前确认事件未被取消。 */
    private void scheduleConfirmedTimeSkip(World world, ManualTimeCommand command, TimeSkipEvent event) {
        command.appliedEvent = event;
        this.scheduleManualTimeCommand(world, command);
    }

    /**
     * 监听玩家离开床：根据入睡快照判断是否应推进一天。
     *
     * <p>仅对已启用且开启“睡觉推进”的主世界频道生效，并带 1200ms 去重。只有在确认玩家
     * 睡够时长，且满足自定义时钟 / 非跟随世界时间 / 世界时间已到清晨等条件时才推进。</p>
     */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        SleepSnapshot snapshot = (SleepSnapshot)this.sleepSnapshots.remove(player.getUniqueId());
        CalendarChannel channel = this.resolveChannel(world);
        if (channel != null) {
            ChannelRuntime runtime = (ChannelRuntime)this.runtimes.get(channel);
            if (runtime != null && runtime.enabled) {
                if (runtime.advanceOnSleep) {
                    if (!runtime.requirePlayersOnServer || this.hasAnyOnlinePlayer()) {
                        if (world.getEnvironment() == Environment.NORMAL) {
                            long now = System.currentTimeMillis();
                            if (now - runtime.lastOverworldSleepRecoveryMs >= 1200L) {
                                boolean sleptLongEnough = snapshot != null && snapshot.worldId.equals(world.getUID()) && now - snapshot.enteredAtMs >= 1500L;
                                boolean enteredAtNight = snapshot != null && this.isNightTime(snapshot.enteredWorldTime);
                                if (runtime.hasCustomClock() && sleptLongEnough && enteredAtNight) {
                                    runtime.lastOverworldSleepRecoveryMs = now;
                                    runtime.lastWorldDayIdx = world.getFullTime() / 24000L;
                                    runtime.applyManualCommand(0L, 1);
                                } else if (!runtime.followWorldTime) {
                                    runtime.lastOverworldSleepRecoveryMs = now;
                                    runtime.applyManualCommand(0L, 1);
                                } else if (world.getTime() <= 1000L) {
                                    World anchor = runtime.getAnchorWorld();
                                    if (anchor != null) {
                                        if (anchor.getUID().equals(world.getUID())) {
                                            runtime.lastOverworldSleepRecoveryMs = now;
                                            runtime.lastWorldDayIdx = world.getFullTime() / 24000L;
                                            runtime.applyManualCommand(0L, 1);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 在月份列表中查找指定月份标识的下标（忽略大小写）；找不到返回 -1。 */
    private int findMonthIndex(List<CalendarMonthDefinition> months, String monthId) {
        if (monthId == null) {
            return -1;
        } else {
            for(int index = 0; index < months.size(); ++index) {
                if (((CalendarMonthDefinition)months.get(index)).getId().equalsIgnoreCase(monthId)) {
                    return index;
                }
            }

            return -1;
        }
    }

    /** 立即将所有频道的日历状态写入磁盘。 */
    public synchronized void persistNow() {
        for(ChannelRuntime rt : this.runtimes.values()) {
            if (rt != null) {
                rt.persistNow();
            }
        }

    }

    /** 立即将指定频道的日历状态写入磁盘。 */
    public synchronized void persistNow(CalendarChannel channel) {
        ChannelRuntime rt = (ChannelRuntime)this.runtimes.get(channel);
        if (rt != null) {
            rt.persistNow();
        }

    }

    /** 玩家入睡时的时间快照，用于离床时判断是否满足推进条件。 */
    private static final class SleepSnapshot {
        private final UUID worldId;
        private final long enteredAtMs;
        private final long enteredWorldTime;

        private SleepSnapshot(UUID worldId, long enteredAtMs, long enteredWorldTime) {
            super();
            this.worldId = worldId;
            this.enteredAtMs = enteredAtMs;
            this.enteredWorldTime = enteredWorldTime;
        }
    }

    /** 一次手动时间修改命令：目标世界时间（当天 tick）、需推进的天数，以及可选的关联跳过事件。 */
    private static final class ManualTimeCommand {
        private final long targetWorldTime;
        private final int daysToAdvance;
        private TimeSkipEvent appliedEvent;

        private ManualTimeCommand(long targetWorldTime, int daysToAdvance) {
            super();
            this.targetWorldTime = targetWorldTime;
            this.daysToAdvance = Math.max(0, daysToAdvance);
        }
    }

    /**
     * 单个日历频道的运行时。
     *
     * <p>封装该频道的日历状态、配置（季节天数、月份、推进策略）以及调度任务与各类缓存，
     * 并负责具体的时钟推进、时间写入、状态持久化与 {@link SeasonUpdateEvent} 触发。</p>
     */
    private final class ChannelRuntime {
        private boolean internalTimeWrite;
        private final CalendarChannel channel;
        private final boolean enabled;
        private final CalendarMode mode;
        private final Set<String> worldNames;
        private final int daysPerSeason;
        private final EnumMap<Season, Integer> daysPerSeasonBySeason;
        private final boolean advanceOnSleep;
        private final int seasonDaylightMinutes;
        private final int seasonNightMinutes;
        private final boolean followWorldTime;
        private final boolean requirePlayersOnServer;
        private final String timeAnchorWorldName;
        private final int maxCatchupDays;
        private final List<CalendarMonthDefinition> months;
        private final File stateFile;
        private CalendarState state;
        private BukkitTask worldClockTask;
        private BukkitTask customClockTask;
        private long clockStartMs = Long.MIN_VALUE;
        private long customClockPausedAtMs = Long.MIN_VALUE;
        private String clockPhaseKey = null;
        private final Map<UUID, Long> customClockWorldDays = new HashMap();
        private final Map<UUID, Boolean> previousDaylightCycleValues = new HashMap();
        private boolean customClockControlsDaylightCycle;
        private List<World> cachedManualTimeWorlds = List.of();
        private World cachedAnchorWorld;
        private boolean worldCacheDirty = true;
        private long lastWorldDayIdx = Long.MIN_VALUE;
        private long lastDayAdvanceMs = 0L;
        private long lastOverworldSleepRecoveryMs = 0L;
        private long lastManualCommandSyncMs = 0L;
        private long lastManualCommandTarget = Long.MIN_VALUE;
        private int lastManualCommandDaysAdvanced = 0;

        /** 构造频道运行时并校正初始状态。 */
        private ChannelRuntime(CalendarChannel channel, boolean enabled, CalendarMode mode, Set<String> worldNames, int daysPerSeason, EnumMap<Season, Integer> daysPerSeasonBySeason, boolean advanceOnSleep, int seasonDaylightMinutes, int seasonNightMinutes, boolean followWorldTime, boolean requirePlayersOnServer, String timeAnchorWorldName, int maxCatchupDays, List<CalendarMonthDefinition> months, File stateFile, CalendarState state) {
            super();
            this.channel = channel;
            this.enabled = enabled;
            this.mode = mode;
            this.worldNames = worldNames;
            this.daysPerSeason = daysPerSeason;
            this.daysPerSeasonBySeason = daysPerSeasonBySeason;
            this.advanceOnSleep = advanceOnSleep;
            this.seasonDaylightMinutes = seasonDaylightMinutes;
            this.seasonNightMinutes = seasonNightMinutes;
            this.followWorldTime = followWorldTime;
            this.requirePlayersOnServer = requirePlayersOnServer;
            this.timeAnchorWorldName = timeAnchorWorldName;
            this.maxCatchupDays = maxCatchupDays;
            this.months = months;
            this.stateFile = stateFile;
            this.state = state;
            this.clampState();
        }

        /** 根据配置的时钟类型启动相应调度任务（有自定义时钟时优先，否则按需启动世界时钟）。 */
        private void registerTasks() {
            this.unregisterTasks();
            if (this.enabled) {
                if (this.hasCustomClock()) {
                    this.clockStartMs = Long.MIN_VALUE;
                    this.customClockPausedAtMs = Long.MIN_VALUE;
                    this.clockPhaseKey = null;
                    this.customClockWorldDays.clear();
                    this.customClockControlsDaylightCycle = true;
                    this.refreshWorldCache();
                    long initialDelay = this.channel == CalendarChannel.NETHER ? 3L : 1L;
                    this.customClockTask = Bukkit.getScheduler().runTaskTimer(SeasonService.this.plugin, this::tickCustomClock, initialDelay, 20L);
                } else {
                    if (this.followWorldTime) {
                        this.lastWorldDayIdx = Long.MIN_VALUE;
                        this.worldClockTask = Bukkit.getScheduler().runTaskTimer(SeasonService.this.plugin, this::tickWorldClock, 40L, 10L);
                    }

                }
            }
        }

        /** 取消所有调度任务，释放昼夜循环接管，并重置时钟与缓存状态。 */
        private void unregisterTasks() {
            if (this.worldClockTask != null) {
                this.worldClockTask.cancel();
            }

            if (this.customClockTask != null) {
                this.customClockTask.cancel();
            }

            this.customClockControlsDaylightCycle = false;
            this.releaseDaylightCycleControl();
            this.worldClockTask = null;
            this.customClockTask = null;
            this.clockStartMs = Long.MIN_VALUE;
            this.customClockPausedAtMs = Long.MIN_VALUE;
            this.clockPhaseKey = null;
            this.customClockWorldDays.clear();
            this.cachedManualTimeWorlds = List.of();
            this.cachedAnchorWorld = null;
            this.worldCacheDirty = true;
        }

        /** 返回当前生效的白天现实分钟数（月份模式取当月定义，否则取季节配置）。 */
        private int getEffectiveDaylightMinutes() {
            if (this.mode == CalendarMode.MONTHS) {
                CalendarMonthDefinition monthDef = this.getCurrentMonth();
                return monthDef != null ? monthDef.getDaylightMinutes() : 0;
            } else {
                return this.seasonDaylightMinutes;
            }
        }

        /** 返回当前生效的夜晚现实分钟数（月份模式取当月定义，否则取季节配置）。 */
        private int getEffectiveNightMinutes() {
            if (this.mode == CalendarMode.MONTHS) {
                CalendarMonthDefinition monthDef = this.getCurrentMonth();
                return monthDef != null ? monthDef.getNightMinutes() : 0;
            } else {
                return this.seasonNightMinutes;
            }
        }

        /** 判断是否需要使用自定义时钟（昼夜时长均大于 0，且与默认 10/10 的跟随世界时间配置不同）。 */
        private boolean hasCustomClock() {
            int daylight = this.getEffectiveDaylightMinutes();
            int night = this.getEffectiveNightMinutes();
            if (daylight > 0 && night > 0) {
                return !this.followWorldTime || daylight != 10 || night != 10;
            } else {
                return false;
            }
        }

        /** 返回指定季节配置的每季天数；未单独配置时回退到默认值。 */
        private int getDaysPerSeasonFor(Season season) {
            Integer configuredDays = (Integer)this.daysPerSeasonBySeason.get(season);
            return configuredDays != null ? configuredDays : this.daysPerSeason;
        }

        /** 返回当前周期的天数（月份模式下为当月天数，否则为当前季节的天数）。 */
        private int getCurrentPeriodLength() {
            if (this.mode == CalendarMode.MONTHS) {
                CalendarMonthDefinition monthDef = this.getCurrentMonth();
                return monthDef != null ? monthDef.getDays() : 1;
            } else {
                return this.getDaysPerSeasonFor(this.state.season);
            }
        }

        /** 返回当前月份定义；未启用月份模式或没有月份时返回 null。 */
        private CalendarMonthDefinition getCurrentMonth() {
            if (this.mode == CalendarMode.MONTHS && !this.months.isEmpty()) {
                int monthIndex = this.state.monthIndex;
                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = SeasonService.this.findMonthIndex(this.months, this.state.monthId);
                }

                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = 0;
                }

                return (CalendarMonthDefinition)this.months.get(monthIndex);
            } else {
                return null;
            }
        }

        /** 返回当前时钟阶段键（月份模式为 MONTH_&lt;id&gt;，否则为 SEASON_&lt;季节&gt;），用于检测阶段切换。 */
        private String getCurrentClockPhaseKey() {
            return this.mode == CalendarMode.MONTHS && this.getCurrentMonth() != null ? "MONTH_" + this.getCurrentMonth().getId() : "SEASON_" + this.state.season.name();
        }

        /** 把世界时间归一化到 [0, 24000) 区间。 */
        private long normalizeWorldTime(long time) {
            long normalizedTime = time % 24000L;
            if (normalizedTime < 0L) {
                normalizedTime += 24000L;
            }

            return normalizedTime;
        }

        /** 将世界时间（tick）按昼夜比例换算为从当天开始经过的现实毫秒数。 */
        private long worldTimeToElapsedMs(long worldTime, int daylightMinutes, int nightMinutes) {
            long normalizedTime = this.normalizeWorldTime(worldTime);
            long dayMs = (long)daylightMinutes * 60000L;
            long nightMs = (long)nightMinutes * 60000L;
            if (normalizedTime < 12000L) {
                return Math.round((double)normalizedTime / (double)12000.0F * (double)dayMs);
            } else {
                long nightTicks = normalizedTime - 12000L;
                return dayMs + Math.round((double)nightTicks / (double)12000.0F * (double)nightMs);
            }
        }

        /** 将从当天开始的现实毫秒数按昼夜比例换算回世界时间（tick）。 */
        private long elapsedMsToWorldTime(long elapsedMs, int daylightMinutes, int nightMinutes) {
            long dayMs = (long)daylightMinutes * 60000L;
            long nightMs = (long)nightMinutes * 60000L;
            if (elapsedMs < dayMs) {
                return Math.max(0L, Math.min(11999L, Math.round((double)elapsedMs / (double)dayMs * (double)12000.0F)));
            } else {
                long nightElapsed = elapsedMs - dayMs;
                return 12000L + Math.max(0L, Math.min(11999L, Math.round((double)nightElapsed / (double)nightMs * (double)12000.0F)));
            }
        }

        /** 标记世界缓存为脏，下次使用时重建。 */
        private void markWorldCacheDirty() {
            this.worldCacheDirty = true;
        }

        /** 从各类缓存中移除某个世界（卸载时调用）。 */
        private void evictCachedWorld(World world) {
            if (world != null) {
                UUID worldId = world.getUID();
                this.cachedManualTimeWorlds = this.cachedManualTimeWorlds.stream().filter((cached) -> !cached.getUID().equals(worldId)).toList();
                if (this.cachedAnchorWorld != null && this.cachedAnchorWorld.getUID().equals(worldId)) {
                    this.cachedAnchorWorld = null;
                }

                this.customClockWorldDays.remove(worldId);
                this.worldCacheDirty = true;
            }
        }

        /** 若世界缓存为脏则重建。 */
        private void ensureWorldCache() {
            if (this.worldCacheDirty) {
                this.refreshWorldCache();
            }

        }

        /** 重建缓存：收集本频道的世界、可手动控制时间的世界，并确定锚世界。 */
        private void refreshWorldCache() {
            List<World> channelWorlds = new ArrayList();
            List<World> manualTimeWorlds = new ArrayList();

            for(World world : Bukkit.getWorlds()) {
                if (SeasonService.this.resolveChannel(world) == this.channel) {
                    channelWorlds.add(world);
                    if (WorldCompat.supportsManualTime(world)) {
                        manualTimeWorlds.add(world);
                    }
                }
            }

            World anchor = Bukkit.getWorld(this.timeAnchorWorldName);
            if (anchor == null && !channelWorlds.isEmpty()) {
                anchor = (World)channelWorlds.get(0);
            }

            if (anchor == null) {
                anchor = Bukkit.getWorld(this.channel == CalendarChannel.NETHER ? "aeternum_heat" : "world");
            }

            this.cachedManualTimeWorlds = List.copyOf(manualTimeWorlds);
            this.cachedAnchorWorld = anchor;
            this.worldCacheDirty = false;
            if (this.customClockControlsDaylightCycle) {
                this.acquireDaylightCycleControl(manualTimeWorlds);
            }

        }

        /** 接管频道世界的昼夜循环：记录并关闭自动昼夜循环，改由自定义时钟控制时间。 */
        private void acquireDaylightCycleControl(List<World> worlds) {
            GameRule<Boolean> rule = WorldCompat.daylightCycleRule();
            if (rule != null) {
                for(World world : worlds) {
                    UUID worldId = world.getUID();
                    if (!this.previousDaylightCycleValues.containsKey(worldId)) {
                        try {
                            Boolean previous = (Boolean)world.getGameRuleValue(rule);
                            if (WorldCompat.disableDaylightCycle(world, SeasonService.this.plugin)) {
                                this.previousDaylightCycleValues.put(worldId, previous == null || previous);
                            }
                        } catch (Throwable failure) {
                            Logger logger = SeasonService.this.plugin.getLogger();
                            String worldName = world.getName();
                            logger.warning("[Calendar] Could not take clock control in " + worldName + ": " + failure.getMessage());
                        }
                    }
                }

            }
        }

        /** 释放对昼夜循环的接管，恢复此前记录的原值。 */
        private void releaseDaylightCycleControl() {
            if (!this.previousDaylightCycleValues.isEmpty()) {
                GameRule<Boolean> rule = WorldCompat.daylightCycleRule();
                if (rule == null) {
                    this.previousDaylightCycleValues.clear();
                } else {
                    for(Map.Entry<UUID, Boolean> entry : this.previousDaylightCycleValues.entrySet()) {
                        World world = Bukkit.getWorld((UUID)entry.getKey());
                        if (world != null) {
                            try {
                                world.setGameRule(rule, (Boolean)entry.getValue());
                            } catch (Throwable failure) {
                                Logger logger = SeasonService.this.plugin.getLogger();
                                String worldName = world.getName();
                                logger.warning("[Calendar] Could not restore daylight cycle in " + worldName + ": " + failure.getMessage());
                            }
                        }
                    }

                    this.previousDaylightCycleValues.clear();
                }
            }
        }

        /** 将归一化后的世界时间应用到该频道的所有可手动控制世界。 */
        private void applyWorldTimeToChannelWorlds(long worldTime) {
            this.applyWorldTimeToChannelWorlds(worldTime, false);
        }

        /**
         * 将归一化后的世界时间应用到该频道的所有可手动控制世界。
         *
         * @param worldTime  目标当天时间（tick）
         * @param advanceDay 是否同时把目标世界日推后一天
         */
        private void applyWorldTimeToChannelWorlds(long worldTime, boolean advanceDay) {
            long normalizedTime = this.normalizeWorldTime(worldTime);
            this.ensureWorldCache();

            for(World world : this.cachedManualTimeWorlds) {
                UUID worldId = world.getUID();
                boolean controlledByCustomClock = this.previousDaylightCycleValues.containsKey(worldId);
                if (controlledByCustomClock || WorldCompat.isTimeAdvancementEnabled(world)) {
                    long currentFullTime = world.getFullTime();
                    long currentRelativeTime = Math.floorMod(currentFullTime, 24000L);
                    long currentWorldDay = Math.floorDiv(currentFullTime, 24000L);
                    long targetWorldDay = (Long)this.customClockWorldDays.computeIfAbsent(worldId, (ignored) -> currentWorldDay);
                    if (advanceDay) {
                        ++targetWorldDay;
                        this.customClockWorldDays.put(worldId, targetWorldDay);
                    }

                    long targetFullTime = targetWorldDay * 24000L + normalizedTime;
                    if (targetFullTime != currentFullTime) {
                        if (!advanceDay && targetWorldDay == currentWorldDay && normalizedTime > currentRelativeTime) {
                            this.writeWorldTime(world, normalizedTime, false);
                        } else {
                            this.writeWorldTime(world, targetFullTime, true);
                        }
                    }
                }
            }

        }

        /** 将自定义时钟对齐到锚世界的当前时间。 */
        private void realignCustomClockToCurrentWorldTime() {
            if (!this.hasCustomClock()) {
                this.clockStartMs = Long.MIN_VALUE;
                this.clockPhaseKey = null;
            } else {
                World anchor = this.getAnchorWorld();
                if (anchor != null) {
                    int daylight = this.getEffectiveDaylightMinutes();
                    int night = this.getEffectiveNightMinutes();
                    if (daylight > 0 && night > 0) {
                        long elapsedMs = this.worldTimeToElapsedMs(anchor.getTime(), daylight, night);
                        this.clockStartMs = System.currentTimeMillis() - elapsedMs;
                        this.clockPhaseKey = this.getCurrentClockPhaseKey();
                    }
                }
            }
        }

        /** 把自定义时钟重置到黎明（新一天开始）。 */
        private void resetCustomClockToDawn() {
            if (!this.hasCustomClock()) {
                this.clockStartMs = Long.MIN_VALUE;
                this.clockPhaseKey = null;
            } else {
                this.clockStartMs = System.currentTimeMillis();
                this.clockPhaseKey = this.getCurrentClockPhaseKey();
                this.applyWorldTimeToChannelWorlds(0L, true);
            }
        }

        /** 直接把归一化后的世界时间写入该频道的所有可手动控制世界（不推进世界日）。 */
        private void setWorldTimeToChannelWorlds(long worldTime) {
            long normalizedTime = this.normalizeWorldTime(worldTime);
            this.ensureWorldCache();

            for(World world : this.cachedManualTimeWorlds) {
                this.writeWorldTime(world, normalizedTime, false);
            }

        }

        /** 写入世界时间；fullTime 为 true 时写完整时间（含天数），否则只写当天相对时间。 */
        private void writeWorldTime(World world, long value, boolean fullTime) {
            this.internalTimeWrite = true;

            try {
                if (fullTime) {
                    WorldCompat.trySetFullTime(world, value, SeasonService.this.plugin);
                } else {
                    WorldCompat.trySetTime(world, value, SeasonService.this.plugin);
                }
            } finally {
                this.internalTimeWrite = false;
            }

        }

        /** 将自定义时钟对齐到指定的手动世界时间。 */
        private void realignCustomClockToManualWorldTime(long requestedWorldTime) {
            if (!this.hasCustomClock()) {
                this.clockStartMs = Long.MIN_VALUE;
                this.clockPhaseKey = null;
            } else {
                int daylight = this.getEffectiveDaylightMinutes();
                int night = this.getEffectiveNightMinutes();
                if (daylight > 0 && night > 0) {
                    long target = this.normalizeWorldTime(requestedWorldTime);
                    long elapsedMs = this.worldTimeToElapsedMs(target, daylight, night);
                    this.clockStartMs = System.currentTimeMillis() - elapsedMs;
                    this.clockPhaseKey = this.getCurrentClockPhaseKey();
                }
            }
        }

        /** 应用一条手动时间命令；带 250ms 去重，按需要推进的天数逐日处理。 */
        private synchronized void applyManualCommand(long requestedWorldTime, int daysToAdvance) {
            long target = this.normalizeWorldTime(requestedWorldTime);
            long now = System.currentTimeMillis();
            int days = Math.max(0, daysToAdvance);
            if (now - this.lastManualCommandSyncMs >= 250L || target != this.lastManualCommandTarget || days != this.lastManualCommandDaysAdvanced) {
                this.lastManualCommandSyncMs = now;
                this.lastManualCommandTarget = target;
                this.lastManualCommandDaysAdvanced = days;
                if (days <= 0) {
                    this.handleManualTimeSet(target);
                } else {
                    for(int dayOffset = 0; dayOffset < days; ++dayOffset) {
                        this.handleManualSetToDay(target);
                    }

                }
            }
        }

        /** 处理“手动设置到某天”的情况：推进一天并同步时钟/世界时间。 */
        private synchronized void handleManualSetToDay(long requestedWorldTime) {
            long target = this.normalizeWorldTime(requestedWorldTime);
            long now = System.currentTimeMillis();
            this.advance(this.state);
            if (this.hasCustomClock()) {
                int daylight = this.getEffectiveDaylightMinutes();
                int night = this.getEffectiveNightMinutes();
                if (daylight > 0 && night > 0) {
                    long elapsedMs = this.worldTimeToElapsedMs(target, daylight, night);
                    this.clockStartMs = now - elapsedMs;
                    this.clockPhaseKey = this.getCurrentClockPhaseKey();
                    this.applyWorldTimeToChannelWorlds(target, true);
                }
            } else {
                this.setWorldTimeToChannelWorlds(target);
            }

            if (this.followWorldTime && !this.hasCustomClock()) {
                World anchor = this.getAnchorWorld();
                if (anchor != null) {
                    this.lastWorldDayIdx = Math.floorDiv(anchor.getFullTime(), 24000L);
                }
            }

            this.lastDayAdvanceMs = now;
            this.persistNow();
            Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), true));
        }

        /** 处理“手动设置时刻”的情况：对齐自定义时钟或直接写入世界时间。 */
        private synchronized void handleManualTimeSet(long requestedWorldTime) {
            long target = this.normalizeWorldTime(requestedWorldTime);
            if (this.hasCustomClock()) {
                this.realignCustomClockToManualWorldTime(target);
                this.applyWorldTimeToChannelWorlds(target);
            } else {
                this.setWorldTimeToChannelWorlds(target);
            }

        }

        /** 自定义时钟的周期回调：按现实流逝时间推进日历，并把目标时间同步到频道世界。 */
        private void tickCustomClock() {
            int daylight = this.getEffectiveDaylightMinutes();
            int night = this.getEffectiveNightMinutes();
            if (daylight > 0 && night > 0) {
                long now = System.currentTimeMillis();
                if (this.requirePlayersOnServer && !SeasonService.this.hasAnyOnlinePlayer()) {
                    if (this.customClockPausedAtMs == Long.MIN_VALUE) {
                        this.customClockPausedAtMs = now;
                    }

                } else {
                    World anchor = this.getAnchorWorld();
                    if (anchor != null) {
                        if (!this.hasPendingTimeSync()) {
                            boolean controlledByCustomClock = this.previousDaylightCycleValues.containsKey(anchor.getUID());
                            if (!controlledByCustomClock && !WorldCompat.isTimeAdvancementEnabled(anchor)) {
                                if (this.customClockPausedAtMs == Long.MIN_VALUE) {
                                    this.customClockPausedAtMs = now;
                                }

                            } else {
                                if (this.customClockPausedAtMs != Long.MIN_VALUE) {
                                    if (this.clockStartMs != Long.MIN_VALUE) {
                                        this.clockStartMs += now - this.customClockPausedAtMs;
                                    }

                                    this.customClockPausedAtMs = Long.MIN_VALUE;
                                }

                                String phaseKey = this.getCurrentClockPhaseKey();
                                if (this.clockStartMs == Long.MIN_VALUE || !Objects.equals(this.clockPhaseKey, phaseKey)) {
                                    long elapsedMs = this.worldTimeToElapsedMs(anchor.getTime(), daylight, night);
                                    this.clockStartMs = now - elapsedMs;
                                    this.clockPhaseKey = phaseKey;
                                }

                                long totalMs = ((long)daylight + (long)night) * 60000L;

                                long elapsedMs;
                                for(elapsedMs = now - this.clockStartMs; elapsedMs >= totalMs; this.clockStartMs = now - elapsedMs) {
                                    elapsedMs -= totalMs;
                                    this.nextDay(true);
                                    daylight = this.getEffectiveDaylightMinutes();
                                    night = this.getEffectiveNightMinutes();
                                    if (daylight <= 0 || night <= 0) {
                                        return;
                                    }

                                    totalMs = ((long)daylight + (long)night) * 60000L;
                                    this.clockPhaseKey = this.getCurrentClockPhaseKey();
                                }

                                long targetWorldTime = this.elapsedMsToWorldTime(elapsedMs, daylight, night);
                                this.applyWorldTimeToChannelWorlds(targetWorldTime);
                            }
                        }
                    }
                }
            }
        }

        /** 返回明天状态的副本（不修改实际状态）。 */
        private CalendarState peekTomorrow() {
            CalendarState copy = this.state.copy();
            this.advance(copy);
            return copy;
        }

        /** 世界时钟的周期回调：跟随锚世界时间的前进，按跨过的天数推进日历。 */
        private void tickWorldClock() {
            World anchor = this.getAnchorWorld();
            if (anchor != null) {
                if (!this.hasPendingTimeSync()) {
                    long worldDayIndex = anchor.getFullTime() / 24000L;
                    if (this.lastWorldDayIdx == Long.MIN_VALUE) {
                        this.lastWorldDayIdx = worldDayIndex;
                    } else if (worldDayIndex < this.lastWorldDayIdx) {
                        this.lastWorldDayIdx = worldDayIndex;
                    } else {
                        long daysElapsed = worldDayIndex - this.lastWorldDayIdx;
                        if (daysElapsed != 0L) {
                            if (this.requirePlayersOnServer && !SeasonService.this.hasAnyOnlinePlayer()) {
                                this.lastWorldDayIdx = worldDayIndex;
                            } else if ((this.maxCatchupDays != 0 || daysElapsed <= 1L) && (this.maxCatchupDays <= 0 || daysElapsed <= (long)this.maxCatchupDays)) {
                                this.lastWorldDayIdx = worldDayIndex;

                                for(int dayOffset = 0; (long)dayOffset < daysElapsed; ++dayOffset) {
                                    this.nextDay(true);
                                }

                            } else {
                                this.lastWorldDayIdx = worldDayIndex;
                            }
                        }
                    }
                }
            }
        }

        /** 返回锚世界（用于读取/驱动世界时间的参照世界）。 */
        private World getAnchorWorld() {
            this.ensureWorldCache();
            return this.cachedAnchorWorld;
        }

        /** 判断是否存在尚未确认生效的时间同步命令（避免时钟与其冲突）。 */
        private boolean hasPendingTimeSync() {
            this.ensureWorldCache();

            for(World world : this.cachedManualTimeWorlds) {
                ManualTimeCommand pending = (ManualTimeCommand)SeasonService.this.pendingManualTimeCommands.get(world.getUID());
                if (pending != null && pending.appliedEvent != null && !pending.appliedEvent.isCancelled()) {
                    return true;
                }
            }

            return false;
        }

        /** 将频道日期推进一天；非绕过去抖时会做 500ms 去重，并触发更新事件。 */
        private synchronized void nextDay(boolean bypassDebounce) {
            long now = System.currentTimeMillis();
            if (!bypassDebounce) {
                if (now - this.lastDayAdvanceMs < 500L) {
                    return;
                }

                this.lastDayAdvanceMs = now;
            }

            this.advance(this.state);
            this.resetCustomClockToDawn();
            this.persistNow();
            Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), true));
        }

        /** 设置季节；月份模式下会定位到该季节对应的第一个月份并对齐状态。 */
        private synchronized void setSeason(Season season) {
            if (this.mode == CalendarMode.MONTHS) {
                for(int index = 0; index < this.months.size(); ++index) {
                    CalendarMonthDefinition monthDef = (CalendarMonthDefinition)this.months.get(index);
                    if (monthDef.getSeason() == season) {
                        this.state.season = monthDef.getSeason();
                        this.state.monthId = monthDef.getId();
                        this.state.monthDisplayName = monthDef.getDisplayName();
                        this.state.monthIndex = index;
                        this.state.daysInMonth = monthDef.getDays();
                        if (this.state.day > monthDef.getDays()) {
                            this.state.day = monthDef.getDays();
                        }

                        if (this.state.day < 1) {
                            this.state.day = 1;
                        }

                        this.realignCustomClockToCurrentWorldTime();
                        this.persistNow();
                        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), false));
                        return;
                    }
                }
            }

            this.state.season = season;
            this.clampState();
            this.realignCustomClockToCurrentWorldTime();
            this.persistNow();
            Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), false));
        }

        /** 设置日期并限制在当前周期范围内。 */
        private synchronized void setDay(int day) {
            int maxDay = this.getCurrentPeriodLength();
            if (day < 1) {
                day = 1;
            }

            if (day > maxDay) {
                day = maxDay;
            }

            this.state.day = day;
            this.realignCustomClockToCurrentWorldTime();
            this.persistNow();
            Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), false));
        }

        /** 设置年份（最小为 1）。 */
        private synchronized void setYear(int year) {
            if (year < 1) {
                year = 1;
            }

            this.state.year = year;
            this.realignCustomClockToCurrentWorldTime();
            this.persistNow();
            Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(SeasonService.this, this.channel, this.state.copy(), false));
        }

        /** 校正状态字段：保证年份/日期合法，并同步月份模式下的月份相关字段。 */
        private void clampState() {
            if (this.state.year < 1) {
                this.state.year = 1;
            }

            if (this.state.day < 1) {
                this.state.day = 1;
            }

            if (this.mode == CalendarMode.MONTHS && !this.months.isEmpty()) {
                int monthIndex = this.state.monthIndex;
                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = SeasonService.this.findMonthIndex(this.months, this.state.monthId);
                }

                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = 0;
                }

                CalendarMonthDefinition monthDef = (CalendarMonthDefinition)this.months.get(monthIndex);
                this.state.monthsEnabled = true;
                this.state.monthId = monthDef.getId();
                this.state.monthDisplayName = monthDef.getDisplayName();
                this.state.monthIndex = monthIndex;
                this.state.daysInMonth = monthDef.getDays();
                this.state.season = monthDef.getSeason();
                if (this.state.day > monthDef.getDays()) {
                    this.state.day = monthDef.getDays();
                }
            } else {
                this.state.monthsEnabled = false;
                this.state.monthId = null;
                this.state.monthDisplayName = null;
                this.state.monthIndex = -1;
                this.state.daysInMonth = 0;
                int maxDay = this.getDaysPerSeasonFor(this.state.season);
                if (this.state.day > maxDay) {
                    this.state.day = maxDay;
                }
            }

        }

        /** 将目标状态推进一天（跨月/跨季时自动进位，可能跨年）。 */
        private void advance(CalendarState target) {
            if (this.mode == CalendarMode.MONTHS && !this.months.isEmpty()) {
                int monthIndex = target.monthIndex;
                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = SeasonService.this.findMonthIndex(this.months, target.monthId);
                }

                if (monthIndex < 0 || monthIndex >= this.months.size()) {
                    monthIndex = 0;
                }

                CalendarMonthDefinition current = (CalendarMonthDefinition)this.months.get(monthIndex);
                target.monthsEnabled = true;
                target.monthId = current.getId();
                target.monthDisplayName = current.getDisplayName();
                target.monthIndex = monthIndex;
                target.daysInMonth = current.getDays();
                target.season = current.getSeason();
                ++target.day;
                if (target.day > current.getDays()) {
                    ++monthIndex;
                    if (monthIndex >= this.months.size()) {
                        monthIndex = 0;
                        ++target.year;
                    }

                    CalendarMonthDefinition next = (CalendarMonthDefinition)this.months.get(monthIndex);
                    target.day = 1;
                    target.season = next.getSeason();
                    target.monthId = next.getId();
                    target.monthDisplayName = next.getDisplayName();
                    target.monthIndex = monthIndex;
                    target.daysInMonth = next.getDays();
                }

            } else {
                ++target.day;
                int maxDay = this.getDaysPerSeasonFor(target.season);
                if (target.day > maxDay) {
                    target.day = 1;
                    target.season = this.nextSeason(target.season);
                    if (target.season == Season.SPRING) {
                        ++target.year;
                    }
                }

                target.monthsEnabled = false;
                target.monthId = null;
                target.monthDisplayName = null;
                target.monthIndex = -1;
                target.daysInMonth = 0;
            }
        }

        /** 将当前日历状态写入该频道的状态文件（必要时创建父目录）。 */
        private void persistNow() {
            try {
                if (!this.stateFile.getParentFile().exists()) {
                    this.stateFile.getParentFile().mkdirs();
                }

                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("year", this.state.year);
                yaml.set("day", this.state.day);
                yaml.set("season", this.state.season.name());
                yaml.set("mode", this.mode.name());
                if (this.mode == CalendarMode.MONTHS) {
                    yaml.set("month", this.state.monthId);
                }

                yaml.save(this.stateFile);
            } catch (IOException ex) {
                Logger logger = SeasonService.this.plugin.getLogger();
                String channelName = this.channel.name();
                logger.warning("No se pudo guardar calendario de canal " + channelName + ": " + ex.getMessage());
            }

        }

        /** 返回给定季节的下一个季节（冬季之后回到春季）。 */
        private Season nextSeason(Season season) {
            Season next;
            switch (season) {
                case SPRING -> next = Season.SUMMER;
                case SUMMER -> next = Season.AUTUMN;
                case AUTUMN -> next = Season.WINTER;
                case WINTER -> next = Season.SPRING;
                default -> throw new MatchException((String)null, (Throwable)null);
            }

            return next;
        }
    }
}
