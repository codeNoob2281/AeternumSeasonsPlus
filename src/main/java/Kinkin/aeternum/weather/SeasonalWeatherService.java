package Kinkin.aeternum.weather;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import Kinkin.aeternum.util.PlatformScheduler;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

public final class SeasonalWeatherService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private boolean enabled;
    private List<String> worldIds;
    private int rainyDaysPerSeason;
    private PlatformScheduler.TaskHandle clockTask;
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
    private final Set<Integer> rainyDays = new HashSet();
    private String lastAppliedCalendarDate = null;
    private volatile boolean manualOverrideToday = false;
    private boolean scheduledRainToday;
    private boolean scheduledThunderToday;
    private int scheduledStormTicks;
    private int scheduledClearTicks;
    private final Map<UUID, Boolean> clearWorkWindowByWorld = new HashMap();

    public SeasonalWeatherService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.reloadFromConfig();
        this.loadOrSeedSchedule(seasons.getStateCopy());
    }

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

    public void unregister() {
        if (this.clockTask != null) {
            this.clockTask.cancel();
        }

        this.clockTask = null;
        HandlerList.unregisterAll(this);
    }

    private void reloadFromConfig() {
        this.enabled = this.plugin.cfg.climate.getBoolean("seasonal_weather.enabled", true);
        this.worldIds = new ArrayList(this.plugin.cfg.climate.getStringList("seasonal_weather.worlds"));
        if (this.worldIds.isEmpty()) {
            this.worldIds = List.of("world");
        }

        this.rainyDaysPerSeason = Math.max(0, this.plugin.cfg.climate.getInt("seasonal_weather.rainy_days_per_season", 10));
        this.rainyDaysOverrides.clear();
        ConfigurationSection sec = this.plugin.cfg.climate.getConfigurationSection("seasonal_weather.rainy_days");
        if (sec != null) {
            for(Season s : Season.values()) {
                int v = Math.max(0, sec.getInt(s.name(), this.rainyDaysPerSeason));
                this.rainyDaysOverrides.put(s, v);
            }
        } else {
            for(Season s : Season.values()) {
                this.rainyDaysOverrides.put(s, this.rainyDaysPerSeason);
            }
        }

        this.thunderChance = Math.max((double)0.0F, Math.min((double)1.0F, this.plugin.cfg.climate.getDouble("seasonal_weather.thunder_chance", 0.2)));
        this.stormMin = this.plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.min", 6000);
        this.stormMax = this.plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.max", 18000);
        this.clearMin = this.plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.min", 6000);
        this.clearMax = this.plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.max", 24000);
        this.reseedEachSeason = this.plugin.cfg.climate.getBoolean("seasonal_weather.reseed_each_season", true);
        this.respectManual = this.plugin.cfg.climate.getBoolean("seasonal_weather.respect_manual_commands", true);
        this.villagerWorkWindowEnabled = this.plugin.cfg.climate.getBoolean("seasonal_weather.villager_work_window.enabled", true);
        this.villagerWorkWindowStart = this.normalizeTime((long)this.plugin.cfg.climate.getInt("seasonal_weather.villager_work_window.start_tick", 2000));
        this.villagerWorkWindowEnd = this.normalizeTime((long)this.plugin.cfg.climate.getInt("seasonal_weather.villager_work_window.end_tick", 9000));
        if (this.villagerWorkWindowStart == this.villagerWorkWindowEnd) {
            this.villagerWorkWindowEnabled = false;
            this.plugin.getLogger().warning("[SeasonalWeather] Villager work window start/end are equal; protection disabled.");
        }

    }

    @EventHandler
    public void onSeasonEvent(SeasonUpdateEvent e) {
        if (this.enabled) {
            PlatformScheduler.executeGlobal(this.plugin, () -> {
                CalendarState st = e.getState();
                if (st.day == 1 && (this.reseedEachSeason || this.rainyDays.isEmpty())) {
                    this.seedSchedule(st);
                    this.saveSchedule(st);
                }

                this.manualOverrideToday = false;
                this.applyForToday();
            });
        }
    }

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

    private void applyForToday() {
        if (this.enabled) {
            CalendarState st = this.seasons.getStateCopy();
            if (!this.respectManual || !this.manualOverrideToday) {
                this.lastAppliedCalendarDate = this.calendarDateKey(st);
                this.scheduledRainToday = this.rainyDays.contains(st.day);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                this.scheduledThunderToday = this.scheduledRainToday && r.nextDouble() < this.thunderChance;
                this.scheduledStormTicks = this.clampRand(this.stormMin, this.stormMax);
                this.scheduledClearTicks = this.clampRand(this.clearMin, this.clearMax);
                this.clearWorkWindowByWorld.clear();

                for(World w : this.worldsToApply()) {
                    boolean workWindowClear = this.scheduledRainToday && this.isInsideVillagerWorkWindow(w.getTime());
                    this.clearWorkWindowByWorld.put(w.getUID(), workWindowClear);
                    if (workWindowClear) {
                        this.applyClearWeather(w, Math.max(24000, this.scheduledClearTicks));
                    } else {
                        this.applyScheduledWeather(w);
                    }
                }

            }
        }
    }

    private void enforceVillagerWorkWindow() {
        if (this.villagerWorkWindowEnabled && this.scheduledRainToday) {
            if (!this.respectManual || !this.manualOverrideToday) {
                for(World world : this.worldsToApply()) {
                    boolean inside = this.isInsideVillagerWorkWindow(world.getTime());
                    Boolean previous = (Boolean)this.clearWorkWindowByWorld.put(world.getUID(), inside);
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

    private void applyClearWeather(World world, int durationTicks) {
        world.setStorm(false);
        world.setWeatherDuration(Math.max(1, durationTicks));
        world.setThunderDuration(0);
        world.setThundering(false);
    }

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

    private int normalizeTime(long time) {
        long normalized = time % 24000L;
        if (normalized < 0L) {
            normalized += 24000L;
        }

        return (int)normalized;
    }

    private List<World> worldsToApply() {
        List<World> out = new ArrayList();

        for(String id : this.worldIds) {
            World w = Bukkit.getWorld(id);
            if (w != null) {
                out.add(w);
            }
        }

        return out;
    }

    public void markManualOverride() {
        this.manualOverrideToday = true;
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onPlayerWeatherCommand(PlayerCommandPreprocessEvent e) {
        if (this.enabled && this.respectManual) {
            if (this.isManualWeatherCommand(e.getMessage())) {
                this.markManualOverride();
            }

        }
    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onConsoleWeatherCommand(ServerCommandEvent e) {
        if (this.enabled && this.respectManual) {
            if (this.isManualWeatherCommand(e.getCommand())) {
                this.markManualOverride();
            }

        }
    }

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

                if (!root.equals("rain") && !root.equals("storm") && !root.equals("sun") && !root.equals("thunder")) {
                    if (root.equals("weather") && args.length >= 2) {
                        String action = args[1].toLowerCase(Locale.ROOT);
                        return action.equals("clear") || action.equals("rain") || action.equals("thunder") || action.equals("storm");
                    } else {
                        return false;
                    }
                } else {
                    return true;
                }
            }
        }
    }

    private void loadOrSeedSchedule(CalendarState st) {
        if (!this.loadSchedule(st)) {
            this.seedSchedule(st);
            this.saveSchedule(st);
        }

    }

    private void seedSchedule(CalendarState st) {
        this.rainyDays.clear();
        int days = this.seasons.getDaysPerSeason();
        if (days > 0) {
            int baseTarget = (Integer)this.rainyDaysOverrides.getOrDefault(st.season, this.rainyDaysPerSeason);
            int target;
            if (days == 28) {
                target = baseTarget;
            } else {
                double factor = (double)days / (double)28.0F;
                target = (int)Math.round((double)baseTarget * factor);
            }

            int need = Math.min(Math.max(0, target), days);
            long seed = (long)st.year * 1315423911L ^ (long)st.season.ordinal() * 2654435761L;
            Random rnd = new Random(seed);

            while(this.rainyDays.size() < need) {
                int d = 1 + rnd.nextInt(days);
                this.rainyDays.add(d);
            }

        }
    }

    private boolean loadSchedule(CalendarState st) {
        try {
            File f = this.scheduleFile(st);
            if (!f.exists()) {
                return false;
            } else {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                List<Integer> list = y.getIntegerList("rainy_days");
                this.rainyDays.clear();
                this.rainyDays.addAll(list);
                return !this.rainyDays.isEmpty();
            }
        } catch (Throwable var5) {
            return false;
        }
    }

    private void saveSchedule(CalendarState st) {
        try {
            File f = this.scheduleFile(st);
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }

            YamlConfiguration y = new YamlConfiguration();
            y.set("year", st.year);
            y.set("season", st.season.name());
            y.set("rainy_days", new ArrayList(this.rainyDays));
            y.save(f);
        } catch (IOException var4) {
        }

    }

    private File scheduleFile(CalendarState st) {
        File var10002 = this.plugin.getDataFolder();
        int var10003 = st.year;
        return new File(var10002, "data/weather_" + var10003 + "_" + st.season.name() + ".yml");
    }

    private int clampRand(int min, int max) {
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }

        if (min < 1) {
            min = 1;
        }

        if (max < 1) {
            max = min;
        }

        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private String calendarDateKey(CalendarState state) {
        String month = state.monthsEnabled && state.monthId != null ? state.monthId : "";
        int var10000 = state.year;
        return var10000 + ":" + state.season.name() + ":" + month + ":" + state.day;
    }
}
