package Kinkin.aeternum;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.events.SeasonalEvent;
import Kinkin.aeternum.events.SeasonalEventService;
import Kinkin.aeternum.lang.LanguageManager;
import Kinkin.aeternum.temperature.TemperatureUnit;
import Kinkin.aeternum.temperature.ThermoService;
import java.util.LinkedHashSet;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AeternumPlaceholders extends PlaceholderExpansion {
    private final AeternumSeasonsPlugin plugin;

    public AeternumPlaceholders(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public @NotNull String getIdentifier() {
        return "aeternum";
    }

    public @NotNull String getAuthor() {
        return String.join(", ", this.plugin.getDescription().getAuthors());
    }

    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public boolean canRegister() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        } else {
            switch (params.toLowerCase(Locale.ROOT)) {
                case "temperature":
                case "temperature_value":
                case "temperature_unit":
                case "temperature_celsius":
                case "temperature_fahrenheit":
                    ThermoService thermo = this.plugin.getThermo();
                    ThermoService.TemperatureReading reading = thermo == null ? null : thermo.getTemperatureReading(player.getUniqueId());
                    if (reading == null) {
                        return "";
                    }

                    String var10000;
                    switch (params) {
                        case "temperature_value" -> var10000 = String.valueOf(reading.value());
                        case "temperature_unit" -> var10000 = reading.symbol();
                        case "temperature_celsius" -> var10000 = String.valueOf(reading.celsius());
                        case "temperature_fahrenheit" -> var10000 = String.valueOf(TemperatureUnit.FAHRENHEIT.fromCelsiusRounded(reading.celsius()));
                        default -> var10000 = reading.value() + " " + reading.symbol();
                    }

                    return var10000;
                default:
                    SeasonService seasons = this.plugin.getSeasons();
                    LanguageManager lang = this.plugin.lang;
                    CalendarState state = null;
                    World world = player.getWorld();
                    if (seasons != null) {
                        try {
                            state = seasons.getStateCopy(world);
                        } catch (Throwable var17) {
                        }
                    }

                    switch (params) {
                        case "day":
                        case "seasons_day":
                            if (state == null) {
                                return "";
                            }

                            return String.valueOf(state.day);
                        case "year":
                        case "seasons_year":
                            if (state == null) {
                                return "";
                            }

                            return String.valueOf(state.year);
                        case "season":
                        case "seasons_season":
                            if (state != null && lang != null) {
                                Season season = state.season;
                                if (season == null) {
                                    return "";
                                }

                                return lang.tr(player, "season." + season.name());
                            }

                            return "";
                        case "realm":
                        case "dimension":
                        case "seasons_realm":
                            return this.resolveRealmName(player, world);
                        case "world_name":
                            return world.getName();
                        case "event_active":
                            try {
                                SeasonalEventService events = this.plugin.getEventService();
                                if (events == null) {
                                    return "";
                                } else {
                                    SeasonalEvent active = events.getActive();
                                    if (active == null) {
                                        return this.trOrDefault(player, "clock.use.no_active_event", "None");
                                    }

                                    return this.trEventName(player, active);
                                }
                            } catch (Throwable var16) {
                                return "";
                            }
                        case "event_active_days_left":
                            try {
                                SeasonalEventService events = this.plugin.getEventService();
                                if (events == null) {
                                    return "";
                                } else {
                                    SeasonalEvent active = events.getActive();
                                    if (active == null) {
                                        return "";
                                    }

                                    return String.valueOf(events.getDaysRemaining());
                                }
                            } catch (Throwable var15) {
                                return "";
                            }
                        case "event_next":
                            try {
                                SeasonalEventService events = this.plugin.getEventService();
                                if (events == null) {
                                    return "";
                                } else {
                                    SeasonalEvent active = events.getActive();
                                    if (active != null) {
                                        return this.trOrDefault(player, "clock.use.next_events.blocked_by_active", "Event running");
                                    } else {
                                        SeasonalEvent queued = events.getQueuedTomorrow();
                                        if (queued == null) {
                                            return this.trOrDefault(player, "clock.use.next_events.none", "None");
                                        }

                                        return this.trEventName(player, queued);
                                    }
                                }
                            } catch (Throwable var14) {
                                return "";
                            }
                        case "event_next_in":
                            try {
                                SeasonalEventService events = this.plugin.getEventService();
                                if (events == null) {
                                    return "";
                                } else {
                                    SeasonalEvent active = events.getActive();
                                    if (active != null) {
                                        return this.trOrDefault(player, "clock.use.next_events.blocked_by_active", "Event running");
                                    } else {
                                        SeasonalEvent queued = events.getQueuedTomorrow();
                                        if (queued == null) {
                                            return this.trOrDefault(player, "clock.use.next_events.none", "None");
                                        }

                                        return "1d";
                                    }
                                }
                            } catch (Throwable var13) {
                                return "";
                            }
                        default:
                            return null;
                    }
            }
        }
    }

    private String resolveRealmName(Player p, World w) {
        String name = w.getName();
        if (this.plugin.lang == null) {
            return name;
        } else if (name.equalsIgnoreCase("aeternum_frost")) {
            return this.plugin.lang.tr(p, "realm.frost");
        } else if (name.equalsIgnoreCase("aeternum_heat")) {
            return this.plugin.lang.tr(p, "realm.heat");
        } else {
            String key = "realm.overworld";
            String val = this.plugin.lang.tr(p, key);
            if (val.equals(key)) {
                val = this.plugin.lang.tr(p, "realm.overworld_title");
            }

            return val;
        }
    }

    private String trOrDefault(Player p, String key, String fallback) {
        if (this.plugin.lang == null) {
            return fallback;
        } else {
            String v = this.plugin.lang.tr(p, key);
            return v != null && !v.isEmpty() && !v.equals(key) ? v : fallback;
        }
    }

    private String trEventName(Player p, SeasonalEvent ev) {
        if (ev == null) {
            return "";
        } else {
            String id = ev.getId().toLowerCase(Locale.ROOT);
            LinkedHashSet<String> bases = new LinkedHashSet();
            bases.add("event." + id);
            if (id.equals("season_festival")) {
                bases.add("event.festival");
            }

            if (id.endsWith("_festival")) {
                String var10001 = id.substring(0, id.length() - "_festival".length());
                bases.add("event." + var10001);
            }

            if (id.endsWith("_blessing")) {
                String var9 = id.substring(0, id.length() - "_blessing".length());
                bases.add("event." + var9);
            }

            for(String base : bases) {
                String v = this.plugin.lang.tr(p, base + ".name");
                if (v != null && !v.equals(base + ".name")) {
                    return v;
                }

                v = this.plugin.lang.tr(p, base + ".title");
                if (v != null && !v.equals(base + ".title")) {
                    return v;
                }
            }

            return ev.getDisplayName();
        }
    }
}
