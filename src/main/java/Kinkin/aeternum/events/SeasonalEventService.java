package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarChannel;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.calendar.SeasonUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 季节随机事件的调度服务。
 *
 * <p>按 {@link CalendarChannel}（如主世界、下界）分别维护事件注册表、当前激活事件、
 * 剩余天数与“明日队列”。它监听 {@link SeasonUpdateEvent} 在游戏日推进时结算事件
 * 生命周期，并通过定时任务（每秒）驱动 {@link SeasonalEvent#onTick}；同时向命令层
 * 暴露查询与强制启停接口。事件配置来自 {@link YamlEvents} 读取的 {@code events.yml}。</p>
 */
public final class SeasonalEventService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final EnumMap<CalendarChannel, EventContext> contexts = new EnumMap(CalendarChannel.class);
    private final EnumMap<CalendarChannel, Map<String, SeasonalEvent>> registries = new EnumMap(CalendarChannel.class);
    private final EnumMap<CalendarChannel, SeasonalEvent> activeEvents = new EnumMap(CalendarChannel.class);
    private final EnumMap<CalendarChannel, Integer> daysRemaining = new EnumMap(CalendarChannel.class);
    private final EnumMap<CalendarChannel, QueueEntry> queues = new EnumMap(CalendarChannel.class);
    private BukkitTask tickTask;
    private boolean enabled;
    /**
     * 是否要求在线玩家数达到门槛才触发随机事件。
     */
    private boolean requirePlayers;
    /**
     * 随机事件的在线玩家数门槛。
     */
    private int minPlayers;
    private static final double RANDOM_EVENT_CHANCE = 0.6;
    /**
     * 关闭季节特效的世界名集合（小写）。
     */
    private Set<String> disabledSeasonFxWorlds = Set.of();

    public SeasonalEventService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;

        for (CalendarChannel channel : CalendarChannel.values()) {
            this.contexts.put(channel, new EventContext(plugin, seasons, channel));
            this.registries.put(channel, new LinkedHashMap());
            this.daysRemaining.put(channel, 0);
        }

        YamlEvents.reload(plugin);
        this.reloadFromConfig();
        this.registerDefaultEvents();
    }

    /**
     * 从事件配置与主配置重新读取开关、玩家门槛与禁用特效世界列表。
     */
    private void reloadFromConfig() {
        FileConfiguration config = YamlEvents.get(this.plugin);
        this.enabled = config.getBoolean("events.enabled", true);
        this.requirePlayers = config.getBoolean("events.require_players.enabled", false);
        this.minPlayers = Math.max(0, config.getInt("events.require_players.players", 3));
        List<String> disabledWorldsList = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
        this.disabledSeasonFxWorlds = (disabledWorldsList == null ? List.<String>of() : disabledWorldsList).stream().filter(Objects::nonNull).map((entry) -> entry.trim().toLowerCase(Locale.ROOT)).filter((entry) -> !entry.isEmpty()).collect(Collectors.toUnmodifiableSet());

        for (EventContext context : this.contexts.values()) {
            context.setDisabledSeasonFxWorlds(this.disabledSeasonFxWorlds);
        }

    }

    /**
     * 清空并重新注册所有内置事件到对应的日历通道。
     */
    private void registerDefaultEvents() {
        this.registries.values().forEach(Map::clear);
        this.registerEvent(CalendarChannel.OVERWORLD, new BloodMoonEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new HeatWaveEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new WinterFreezeEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new MagicStormEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new SeasonFestivalEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new FishingFestivalEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new MiningBlessingEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new TornadoEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new RestfulSleepEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new HolidayEvent(this.plugin));
        this.registerEvent(CalendarChannel.OVERWORLD, new NewYearEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new NetherFishingDerbyEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new PiglinMarketEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new QuartzRushEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new FungusBloomEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new BlazeSurgeEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new MagmaTidesEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new GhastAlertEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new WitherLooseEvent(this.plugin));
        this.registerEvent(CalendarChannel.NETHER, new WitherSkeletonSwarmEvent(this.plugin));
    }

    /**
     * 将单个事件按其小写 id 注册到指定通道。
     */
    private void registerEvent(CalendarChannel channel, SeasonalEvent event) {
        ((Map) this.registries.get(channel)).put(event.getId().toLowerCase(Locale.ROOT), event);
    }

    /**
     * 注册监听器、初始化各通道的明日队列并启动每秒调度任务。
     */
    public void register() {
        if (this.enabled) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);

            for (CalendarChannel channel : CalendarChannel.values()) {
                if (this.seasons.isChannelEnabled(channel)) {
                    this.updateTomorrowQueue(channel, this.seasons.getStateCopy(channel));
                }
            }

            if (this.tickTask != null) {
                this.tickTask.cancel();
            }

            this.tickTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 20L);
        }
    }

    /**
     * 取消调度任务、注销监听器并停止所有通道的激活事件。
     */
    public void unregister() {
        if (this.tickTask != null) {
            this.tickTask.cancel();
        }

        HandlerList.unregisterAll(this);

        for (CalendarChannel channel : CalendarChannel.values()) {
            this.stopActive(channel, "plugin_disable");
            this.queues.remove(channel);
        }

    }

    /**
     * 定时任务主体：对每个通道的激活事件调用 {@link SeasonalEvent#onTick}。
     */
    @Override
    public void run() {
        if (this.enabled) {
            for (CalendarChannel channel : CalendarChannel.values()) {
                SeasonalEvent active = (SeasonalEvent) this.activeEvents.get(channel);
                if (active != null) {
                    CalendarState state = this.seasons.getStateCopy(channel);
                    active.onTick(state, (EventContext) this.contexts.get(channel));
                }
            }

        }
    }

    /**
     * 监听季节更新事件：在游戏日推进时结算激活事件剩余天数，并尝试启动排队或新事件。
     */
    @EventHandler
    public void onSeasonUpdate(SeasonUpdateEvent event) {
        if (this.enabled) {
            if (event.isDayAdvanced()) {
                CalendarChannel channel = event.getChannel();
                if (channel != null) {
                    CalendarState state = event.getState();
                    SeasonalEvent active = (SeasonalEvent) this.activeEvents.get(channel);
                    if (active != null) {
                        active.onDayTick(state, (EventContext) this.contexts.get(channel));
                        int remaining = Math.max(0, (Integer) this.daysRemaining.getOrDefault(channel, 0) - 1);
                        this.daysRemaining.put(channel, remaining);
                        if (remaining <= 0) {
                            this.stopActive(channel, "duration_ended");
                        }
                    }

                    if (this.activeEvents.get(channel) == null) {
                        QueueEntry queue = (QueueEntry) this.queues.get(channel);
                        if (queue != null && queue.matches(state)) {
                            SeasonalEvent queued = queue.event();
                            if (queued == null) {
                                this.queues.remove(channel);
                            } else if (queued.isSeasonAllowed(state.season) && (!this.isForcedCalendarEvent(queued) || queued.canStartToday(state, (EventContext) this.contexts.get(channel)))) {
                                if (this.isForcedCalendarEvent(queued)) {
                                    this.queues.remove(channel);
                                    int duration = this.randomBetween(queued.getMinDurationDays(), queued.getMaxDurationDays());
                                    this.startEvent(channel, queued, duration, state, "queued_forced_calendar");
                                } else if (!this.hasEnoughPlayersOnline(channel)) {
                                    CalendarState tomorrow = this.seasons.peekTomorrowState(channel);
                                    if (tomorrow == null || !queued.isSeasonAllowed(tomorrow.season) || this.isForcedCalendarEvent(queued) && !queued.canStartToday(tomorrow, (EventContext) this.contexts.get(channel))) {
                                        this.queues.remove(channel);
                                    } else {
                                        this.queues.put(channel, new QueueEntry(queued, tomorrow));
                                    }
                                } else {
                                    this.queues.remove(channel);
                                    int duration = this.randomBetween(queued.getMinDurationDays(), queued.getMaxDurationDays());
                                    this.startEvent(channel, queued, duration, state, "queued_auto");
                                }
                            } else {
                                this.queues.remove(channel);
                            }
                        } else {
                            this.tryStartNewEvent(channel, state);
                        }
                    }

                    this.updateTomorrowQueue(channel, state);
                }
            }
        }
    }

    /**
     * 判断事件是否为受日历强制约束的事件（节日/新年）。
     */
    private boolean isForcedCalendarEvent(SeasonalEvent event) {
        return event instanceof HolidayEvent || event instanceof NewYearEvent;
    }

    /**
     * 在指定通道尝试随机启动一个新事件（强制事件优先）。
     */
    private void tryStartNewEvent(CalendarChannel channel, CalendarState state) {
        List<SeasonalEvent> candidates = new ArrayList();
        SeasonalEvent forcedEvent = null;

        for (SeasonalEvent event : ((Map<String, SeasonalEvent>) this.registries.get(channel)).values()) {
            if (event.isSeasonAllowed(state.season) && event.canStartToday(state, (EventContext) this.contexts.get(channel))) {
                if (this.isForcedCalendarEvent(event)) {
                    forcedEvent = event;
                    break;
                }

                candidates.add(event);
            }
        }

        if (forcedEvent != null) {
            this.startEvent(channel, forcedEvent, this.randomBetween(forcedEvent.getMinDurationDays(), forcedEvent.getMaxDurationDays()), state, "forced_priority");
        } else if (this.hasEnoughPlayersOnline(channel)) {
            if (!candidates.isEmpty()) {
                if (!(ThreadLocalRandom.current().nextDouble() > 0.6)) {
                    SeasonalEvent event = candidates.size() == 1 ? (SeasonalEvent) candidates.get(0) : (SeasonalEvent) candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                    int duration = this.randomBetween(event.getMinDurationDays(), event.getMaxDurationDays());
                    this.startEvent(channel, event, duration, state, "rng");
                }
            }
        }
    }

    /**
     * 启动指定事件：先停止旧事件、注册监听器、回调 onStart 并记录剩余天数。
     */
    private void startEvent(CalendarChannel channel, SeasonalEvent event, int duration, CalendarState state, String reason) {
        this.stopActive(channel, "replace_before_start");

        try {
            Bukkit.getPluginManager().registerEvents(event, this.plugin);
            event.onStart(state, (EventContext) this.contexts.get(channel));
            this.activeEvents.put(channel, event);
            this.daysRemaining.put(channel, Math.max(1, duration));
        } catch (Throwable t) {
            Logger logger = this.plugin.getLogger();
            String channelName = channel.name();
            logger.warning("[Events/" + channelName + "] Error al iniciar evento " + event.getId() + ": " + t.getMessage());
            HandlerList.unregisterAll(event);
            this.activeEvents.remove(channel);
            this.daysRemaining.put(channel, 0);
            return;
        }

        Logger logger = this.plugin.getLogger();
        String channelName = channel.name();
        logger.info("[Events/" + channelName + "] Evento iniciado: " + event.getId() + " por " + duration + " día(s). motivo=" + reason);
    }

    /**
     * 停止并清理指定通道的激活事件（回调 onEnd、注销监听器、清空剩余天数）。
     */
    private void stopActive(CalendarChannel channel, String reason) {
        SeasonalEvent active = (SeasonalEvent) this.activeEvents.get(channel);
        if (active != null) {
            CalendarState state = this.seasons.getStateCopy(channel);

            try {
                active.onEnd(state, (EventContext) this.contexts.get(channel));
            } catch (Throwable t) {
                Logger logger = this.plugin.getLogger();
                String channelName = channel.name();
                logger.warning("[Events/" + channelName + "] Error al terminar evento " + active.getId() + ": " + t.getMessage());
            }

            HandlerList.unregisterAll(active);
            Logger logger = this.plugin.getLogger();
            String channelName = channel.name();
            logger.info("[Events/" + channelName + "] Evento finalizado: " + active.getId() + " (" + reason + ")");
            this.activeEvents.remove(channel);
            this.daysRemaining.put(channel, 0);
        }
    }

    /**
     * 根据明天的日历状态刷新该通道的“明日队列”。
     */
    private void updateTomorrowQueue(CalendarChannel channel, CalendarState today) {
        if (this.activeEvents.get(channel) != null) {
            this.queues.remove(channel);
        } else {
            CalendarState tomorrow = this.seasons.peekTomorrowState(channel);
            if (tomorrow == null) {
                this.queues.remove(channel);
            } else {
                QueueEntry current = (QueueEntry) this.queues.get(channel);
                if (current == null || !current.matches(tomorrow)) {
                    List<SeasonalEvent> candidates = new ArrayList();
                    SeasonalEvent forcedEvent = null;

                    for (SeasonalEvent event : ((Map<String, SeasonalEvent>) this.registries.get(channel)).values()) {
                        if (event.isSeasonAllowed(tomorrow.season) && event.canStartToday(tomorrow, (EventContext) this.contexts.get(channel))) {
                            if (this.isForcedCalendarEvent(event)) {
                                forcedEvent = event;
                                break;
                            }

                            candidates.add(event);
                        }
                    }

                    if (forcedEvent != null) {
                        this.queues.put(channel, new QueueEntry(forcedEvent, tomorrow));
                    } else if (candidates.isEmpty()) {
                        this.queues.put(channel, new QueueEntry((SeasonalEvent) null, tomorrow));
                    } else if (ThreadLocalRandom.current().nextDouble() > 0.6) {
                        this.queues.put(channel, new QueueEntry((SeasonalEvent) null, tomorrow));
                    } else {
                        SeasonalEvent selected = candidates.size() == 1 ? (SeasonalEvent) candidates.get(0) : (SeasonalEvent) candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                        this.queues.put(channel, new QueueEntry(selected, tomorrow));
                    }
                }
            }
        }
    }

    /**
     * 返回主世界当前激活的事件。
     */
    public SeasonalEvent getActive() {
        return this.getActive(CalendarChannel.OVERWORLD);
    }

    /**
     * 返回指定通道当前激活的事件。
     */
    public SeasonalEvent getActive(CalendarChannel channel) {
        return (SeasonalEvent) this.activeEvents.get(channel);
    }

    /**
     * 返回主世界激活事件的剩余天数。
     */
    public int getDaysRemaining() {
        return this.getDaysRemaining(CalendarChannel.OVERWORLD);
    }

    /**
     * 返回指定通道激活事件的剩余天数。
     */
    public int getDaysRemaining(CalendarChannel channel) {
        return (Integer) this.daysRemaining.getOrDefault(channel, 0);
    }

    /**
     * 返回主世界明日队列中的事件。
     */
    public SeasonalEvent getQueuedTomorrow() {
        return this.getQueuedTomorrow(CalendarChannel.OVERWORLD);
    }

    /**
     * 返回指定通道明日队列中的事件。
     */
    public SeasonalEvent getQueuedTomorrow(CalendarChannel channel) {
        QueueEntry queue = (QueueEntry) this.queues.get(channel);
        return queue != null ? queue.event() : null;
    }

    /**
     * 返回主世界已注册事件的 id 集合。
     */
    public Set<String> getRegisteredEventIds() {
        return this.getRegisteredEventIds(CalendarChannel.OVERWORLD);
    }

    /**
     * 返回指定通道已注册事件的 id 集合（只读）。
     */
    public Set<String> getRegisteredEventIds(CalendarChannel channel) {
        return Collections.unmodifiableSet(((Map) this.registries.get(channel)).keySet());
    }

    /**
     * 按 id 在主世界查找事件。
     */
    public SeasonalEvent getEventById(String id) {
        return this.getEventById(CalendarChannel.OVERWORLD, id);
    }

    /**
     * 按 id 在指定通道查找事件（支持 fishing/mining 别名）。
     */
    public SeasonalEvent getEventById(CalendarChannel channel, String id) {
        if (id == null) {
            return null;
        } else {
            String normalized = id.toLowerCase(Locale.ROOT);
            if ("fishing".equals(normalized)) {
                normalized = "fishing_festival";
            }

            if ("mining".equals(normalized)) {
                normalized = "mining_blessing";
            }

            return (SeasonalEvent) ((Map) this.registries.get(channel)).get(normalized);
        }
    }

    /**
     * 在所有通道中按 id 强制启动事件。
     */
    public boolean forceStart(String id, Integer durationOverride) {
        for (CalendarChannel channel : CalendarChannel.values()) {
            if (this.getEventById(channel, id) != null) {
                return this.forceStart(channel, id, durationOverride);
            }
        }

        return false;
    }

    /**
     * 在指定通道按 id 强制启动事件（可覆盖持续时间）。
     */
    public boolean forceStart(CalendarChannel channel, String id, Integer durationOverride) {
        if (this.enabled && this.seasons.isChannelEnabled(channel)) {
            SeasonalEvent event = this.getEventById(channel, id);
            if (event == null) {
                return false;
            } else {
                CalendarState state = this.seasons.getStateCopy(channel);
                int duration = durationOverride != null && durationOverride > 0 ? durationOverride : this.randomBetween(event.getMinDurationDays(), event.getMaxDurationDays());
                this.startEvent(channel, event, duration, state, "forced");
                this.queues.remove(channel);
                return this.activeEvents.get(channel) == event;
            }
        } else {
            return false;
        }
    }

    /**
     * 强制停止主世界的激活事件。
     */
    public void forceStop() {
        this.forceStop(CalendarChannel.OVERWORLD);
    }

    /**
     * 强制停止指定通道的激活事件并刷新明日队列。
     */
    public void forceStop(CalendarChannel channel) {
        this.stopActive(channel, "forced_stop");
        this.queues.remove(channel);
        if (this.seasons.isChannelEnabled(channel)) {
            this.updateTomorrowQueue(channel, this.seasons.getStateCopy(channel));
        }

    }

    /**
     * 判断指定通道当前在线合格玩家数是否达到门槛。
     */
    private boolean hasEnoughPlayersOnline(CalendarChannel channel) {
        if (!this.requirePlayers) {
            return true;
        } else {
            int online = ((EventContext) this.contexts.get(channel)).getEligiblePlayers().size();
            return online >= this.minPlayers;
        }
    }

    /**
     * 判断给定世界是否关闭了季节特效。
     */
    public boolean isSeasonFxDisabledWorld(World world) {
        return world == null || this.disabledSeasonFxWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * 在 [min, max] 范围内随机取值（自动纠正 min/max 顺序）。
     */
    private int randomBetween(int min, int max) {
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }

        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * 明日队列条目：待触发事件与其目标日历状态。
     */
    private record QueueEntry(SeasonalEvent event, CalendarState target) {
        private QueueEntry {
        }

        /**
         * 判断给定日历状态是否与该条目的目标日期一致。
         */
        private boolean matches(CalendarState state) {
            if (state != null && this.target != null) {
                return state.year == this.target.year && state.day == this.target.day && state.season == this.target.season && Objects.equals(state.monthId, this.target.monthId);
            } else {
                return false;
            }
        }
    }
}
