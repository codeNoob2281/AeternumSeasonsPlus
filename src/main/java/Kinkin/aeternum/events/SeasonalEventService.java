package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarChannel;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

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
    private boolean requirePlayers;
    private int minPlayers;
    private static final double RANDOM_EVENT_CHANCE = 0.6;
    private Set<String> disabledSeasonFxWorlds = Set.of();

    public SeasonalEventService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;

        for(CalendarChannel channel : CalendarChannel.values()) {
            this.contexts.put(channel, new EventContext(plugin, seasons, channel));
            this.registries.put(channel, new LinkedHashMap());
            this.daysRemaining.put(channel, 0);
        }

        YamlEvents.reload(plugin);
        this.reloadFromConfig();
        this.registerDefaultEvents();
    }

    private void reloadFromConfig() {
        FileConfiguration cfg = YamlEvents.get(this.plugin);
        this.enabled = cfg.getBoolean("events.enabled", true);
        this.requirePlayers = cfg.getBoolean("events.require_players.enabled", false);
        this.minPlayers = Math.max(0, cfg.getInt("events.require_players.players", 3));
        List<String> list = this.plugin.getConfig().getStringList("worlds.disabled_season_fx");
        this.disabledSeasonFxWorlds = (list == null ? List.<String>of() : list).stream().filter(Objects::nonNull).map((s) -> s.trim().toLowerCase(Locale.ROOT)).filter((s) -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());

        for(EventContext ctx : this.contexts.values()) {
            ctx.setDisabledSeasonFxWorlds(this.disabledSeasonFxWorlds);
        }

    }

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

    private void registerEvent(CalendarChannel channel, SeasonalEvent ev) {
        ((Map)this.registries.get(channel)).put(ev.getId().toLowerCase(Locale.ROOT), ev);
    }

    public void register() {
        if (this.enabled) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);

            for(CalendarChannel channel : CalendarChannel.values()) {
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

    public void unregister() {
        if (this.tickTask != null) {
            this.tickTask.cancel();
        }

        HandlerList.unregisterAll(this);

        for(CalendarChannel channel : CalendarChannel.values()) {
            this.stopActive(channel, "plugin_disable");
            this.queues.remove(channel);
        }

    }

    public void run() {
        if (this.enabled) {
            for(CalendarChannel channel : CalendarChannel.values()) {
                SeasonalEvent active = (SeasonalEvent)this.activeEvents.get(channel);
                if (active != null) {
                    CalendarState st = this.seasons.getStateCopy(channel);
                    active.onTick(st, (EventContext)this.contexts.get(channel));
                }
            }

        }
    }

    @EventHandler
    public void onSeasonUpdate(SeasonUpdateEvent e) {
        if (this.enabled) {
            if (e.isDayAdvanced()) {
                CalendarChannel channel = e.getChannel();
                if (channel != null) {
                    CalendarState st = e.getState();
                    SeasonalEvent active = (SeasonalEvent)this.activeEvents.get(channel);
                    if (active != null) {
                        active.onDayTick(st, (EventContext)this.contexts.get(channel));
                        int rem = Math.max(0, (Integer)this.daysRemaining.getOrDefault(channel, 0) - 1);
                        this.daysRemaining.put(channel, rem);
                        if (rem <= 0) {
                            this.stopActive(channel, "duration_ended");
                        }
                    }

                    if (this.activeEvents.get(channel) == null) {
                        QueueEntry queue = (QueueEntry)this.queues.get(channel);
                        if (queue != null && queue.matches(st)) {
                            SeasonalEvent queued = queue.event();
                            if (queued == null) {
                                this.queues.remove(channel);
                            } else if (queued.isSeasonAllowed(st.season) && (!this.isForcedCalendarEvent(queued) || queued.canStartToday(st, (EventContext)this.contexts.get(channel)))) {
                                if (this.isForcedCalendarEvent(queued)) {
                                    this.queues.remove(channel);
                                    int dur = this.randomBetween(queued.getMinDurationDays(), queued.getMaxDurationDays());
                                    this.startEvent(channel, queued, dur, st, "queued_forced_calendar");
                                } else if (!this.hasEnoughPlayersOnline(channel)) {
                                    CalendarState tomorrow = this.seasons.peekTomorrowState(channel);
                                    if (tomorrow == null || !queued.isSeasonAllowed(tomorrow.season) || this.isForcedCalendarEvent(queued) && !queued.canStartToday(tomorrow, (EventContext)this.contexts.get(channel))) {
                                        this.queues.remove(channel);
                                    } else {
                                        this.queues.put(channel, new QueueEntry(queued, tomorrow));
                                    }
                                } else {
                                    this.queues.remove(channel);
                                    int dur = this.randomBetween(queued.getMinDurationDays(), queued.getMaxDurationDays());
                                    this.startEvent(channel, queued, dur, st, "queued_auto");
                                }
                            } else {
                                this.queues.remove(channel);
                            }
                        } else {
                            this.tryStartNewEvent(channel, st);
                        }
                    }

                    this.updateTomorrowQueue(channel, st);
                }
            }
        }
    }

    private boolean isForcedCalendarEvent(SeasonalEvent ev) {
        return ev instanceof HolidayEvent || ev instanceof NewYearEvent;
    }

    private void tryStartNewEvent(CalendarChannel channel, CalendarState st) {
        List<SeasonalEvent> candidates = new ArrayList();
        SeasonalEvent forcedEvent = null;

        for(SeasonalEvent ev : ((Map<String, SeasonalEvent>)this.registries.get(channel)).values()) {
            if (ev.isSeasonAllowed(st.season) && ev.canStartToday(st, (EventContext)this.contexts.get(channel))) {
                if (this.isForcedCalendarEvent(ev)) {
                    forcedEvent = ev;
                    break;
                }

                candidates.add(ev);
            }
        }

        if (forcedEvent != null) {
            this.startEvent(channel, forcedEvent, this.randomBetween(forcedEvent.getMinDurationDays(), forcedEvent.getMaxDurationDays()), st, "forced_priority");
        } else if (this.hasEnoughPlayersOnline(channel)) {
            if (!candidates.isEmpty()) {
                if (!(ThreadLocalRandom.current().nextDouble() > 0.6)) {
                    SeasonalEvent ev = candidates.size() == 1 ? (SeasonalEvent)candidates.get(0) : (SeasonalEvent)candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                    int dur = this.randomBetween(ev.getMinDurationDays(), ev.getMaxDurationDays());
                    this.startEvent(channel, ev, dur, st, "rng");
                }
            }
        }
    }

    private void startEvent(CalendarChannel channel, SeasonalEvent ev, int duration, CalendarState st, String reason) {
        this.stopActive(channel, "replace_before_start");

        try {
            Bukkit.getPluginManager().registerEvents(ev, this.plugin);
            ev.onStart(st, (EventContext)this.contexts.get(channel));
            this.activeEvents.put(channel, ev);
            this.daysRemaining.put(channel, Math.max(1, duration));
        } catch (Throwable t) {
            Logger var10000 = this.plugin.getLogger();
            String var10001 = channel.name();
            var10000.warning("[Events/" + var10001 + "] Error al iniciar evento " + ev.getId() + ": " + t.getMessage());
            HandlerList.unregisterAll(ev);
            this.activeEvents.remove(channel);
            this.daysRemaining.put(channel, 0);
            return;
        }

        Logger var8 = this.plugin.getLogger();
        String var9 = channel.name();
        var8.info("[Events/" + var9 + "] Evento iniciado: " + ev.getId() + " por " + duration + " día(s). motivo=" + reason);
    }

    private void stopActive(CalendarChannel channel, String reason) {
        SeasonalEvent active = (SeasonalEvent)this.activeEvents.get(channel);
        if (active != null) {
            CalendarState st = this.seasons.getStateCopy(channel);

            try {
                active.onEnd(st, (EventContext)this.contexts.get(channel));
            } catch (Throwable t) {
                Logger var10000 = this.plugin.getLogger();
                String var10001 = channel.name();
                var10000.warning("[Events/" + var10001 + "] Error al terminar evento " + active.getId() + ": " + t.getMessage());
            }

            HandlerList.unregisterAll(active);
            Logger var7 = this.plugin.getLogger();
            String var8 = channel.name();
            var7.info("[Events/" + var8 + "] Evento finalizado: " + active.getId() + " (" + reason + ")");
            this.activeEvents.remove(channel);
            this.daysRemaining.put(channel, 0);
        }
    }

    private void updateTomorrowQueue(CalendarChannel channel, CalendarState today) {
        if (this.activeEvents.get(channel) != null) {
            this.queues.remove(channel);
        } else {
            CalendarState tomorrow = this.seasons.peekTomorrowState(channel);
            if (tomorrow == null) {
                this.queues.remove(channel);
            } else {
                QueueEntry current = (QueueEntry)this.queues.get(channel);
                if (current == null || !current.matches(tomorrow)) {
                    List<SeasonalEvent> candidates = new ArrayList();
                    SeasonalEvent forcedEvent = null;

                    for(SeasonalEvent ev : ((Map<String, SeasonalEvent>)this.registries.get(channel)).values()) {
                        if (ev.isSeasonAllowed(tomorrow.season) && ev.canStartToday(tomorrow, (EventContext)this.contexts.get(channel))) {
                            if (this.isForcedCalendarEvent(ev)) {
                                forcedEvent = ev;
                                break;
                            }

                            candidates.add(ev);
                        }
                    }

                    if (forcedEvent != null) {
                        this.queues.put(channel, new QueueEntry(forcedEvent, tomorrow));
                    } else if (candidates.isEmpty()) {
                        this.queues.put(channel, new QueueEntry((SeasonalEvent)null, tomorrow));
                    } else if (ThreadLocalRandom.current().nextDouble() > 0.6) {
                        this.queues.put(channel, new QueueEntry((SeasonalEvent)null, tomorrow));
                    } else {
                        SeasonalEvent selected = candidates.size() == 1 ? (SeasonalEvent)candidates.get(0) : (SeasonalEvent)candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                        this.queues.put(channel, new QueueEntry(selected, tomorrow));
                    }
                }
            }
        }
    }

    public SeasonalEvent getActive() {
        return this.getActive(CalendarChannel.OVERWORLD);
    }

    public SeasonalEvent getActive(CalendarChannel channel) {
        return (SeasonalEvent)this.activeEvents.get(channel);
    }

    public int getDaysRemaining() {
        return this.getDaysRemaining(CalendarChannel.OVERWORLD);
    }

    public int getDaysRemaining(CalendarChannel channel) {
        return (Integer)this.daysRemaining.getOrDefault(channel, 0);
    }

    public SeasonalEvent getQueuedTomorrow() {
        return this.getQueuedTomorrow(CalendarChannel.OVERWORLD);
    }

    public SeasonalEvent getQueuedTomorrow(CalendarChannel channel) {
        QueueEntry queue = (QueueEntry)this.queues.get(channel);
        return queue != null ? queue.event() : null;
    }

    public Set<String> getRegisteredEventIds() {
        return this.getRegisteredEventIds(CalendarChannel.OVERWORLD);
    }

    public Set<String> getRegisteredEventIds(CalendarChannel channel) {
        return Collections.unmodifiableSet(((Map)this.registries.get(channel)).keySet());
    }

    public SeasonalEvent getEventById(String id) {
        return this.getEventById(CalendarChannel.OVERWORLD, id);
    }

    public SeasonalEvent getEventById(CalendarChannel channel, String id) {
        if (id == null) {
            return null;
        } else {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (normalized.equals("fishing")) {
                normalized = "fishing_festival";
            }

            if (normalized.equals("mining")) {
                normalized = "mining_blessing";
            }

            return (SeasonalEvent)((Map)this.registries.get(channel)).get(normalized);
        }
    }

    public boolean forceStart(String id, Integer durationOverride) {
        for(CalendarChannel channel : CalendarChannel.values()) {
            if (this.getEventById(channel, id) != null) {
                return this.forceStart(channel, id, durationOverride);
            }
        }

        return false;
    }

    public boolean forceStart(CalendarChannel channel, String id, Integer durationOverride) {
        if (this.enabled && this.seasons.isChannelEnabled(channel)) {
            SeasonalEvent ev = this.getEventById(channel, id);
            if (ev == null) {
                return false;
            } else {
                CalendarState st = this.seasons.getStateCopy(channel);
                int dur = durationOverride != null && durationOverride > 0 ? durationOverride : this.randomBetween(ev.getMinDurationDays(), ev.getMaxDurationDays());
                this.startEvent(channel, ev, dur, st, "forced");
                this.queues.remove(channel);
                return this.activeEvents.get(channel) == ev;
            }
        } else {
            return false;
        }
    }

    public void forceStop() {
        this.forceStop(CalendarChannel.OVERWORLD);
    }

    public void forceStop(CalendarChannel channel) {
        this.stopActive(channel, "forced_stop");
        this.queues.remove(channel);
        if (this.seasons.isChannelEnabled(channel)) {
            this.updateTomorrowQueue(channel, this.seasons.getStateCopy(channel));
        }

    }

    private boolean hasEnoughPlayersOnline(CalendarChannel channel) {
        if (!this.requirePlayers) {
            return true;
        } else {
            int online = ((EventContext)this.contexts.get(channel)).getEligiblePlayers().size();
            return online >= this.minPlayers;
        }
    }

    public boolean isSeasonFxDisabledWorld(World w) {
        return w == null ? true : this.disabledSeasonFxWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    private int randomBetween(int min, int max) {
        if (max < min) {
            int tmp = min;
            min = max;
            max = tmp;
        }

        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static record QueueEntry(SeasonalEvent event, CalendarState target) {
        private QueueEntry {
        }

        private boolean matches(CalendarState st) {
            if (st != null && this.target != null) {
                return st.year == this.target.year && st.day == this.target.day && st.season == this.target.season && Objects.equals(st.monthId, this.target.monthId);
            } else {
                return false;
            }
        }
    }
}
