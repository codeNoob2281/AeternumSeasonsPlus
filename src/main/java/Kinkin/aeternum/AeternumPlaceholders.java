package kinkin.aeternum;

import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.events.SeasonalEvent;
import kinkin.aeternum.events.SeasonalEventService;
import kinkin.aeternum.lang.LanguageManager;
import kinkin.aeternum.temperature.TemperatureUnit;
import kinkin.aeternum.temperature.ThermoService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * PlaceholderAPI 占位符扩展。
 *
 * <p>向 PlaceholderAPI 暴露一整套以 {@code %aeternum_...%} 为前缀的占位符，
 * 用于在其它插件的计分板、聊天、HUD 等位置展示当前世界/玩家的季节信息。支持的占位符包括：</p>
 * <ul>
 *     <li>温度相关：{@code temperature}、{@code temperature_value}、{@code temperature_unit}、
 *     {@code temperature_celsius}、{@code temperature_fahrenheit}；</li>
 *     <li>日历相关：{@code day}、{@code year}、{@code season}（以及带 {@code seasons_} 前缀的别名）；</li>
 *     <li>世界相关：{@code realm}、{@code dimension}、{@code world_name}；</li>
 *     <li>事件相关：{@code event_active}、{@code event_active_days_left}、{@code event_next}、{@code event_next_in}。</li>
 * </ul>
 *
 * <p>所有查询都做了空值与异常保护：当服务未初始化、世界无数据或发生异常时返回空串或安全的兜底文案，
 * 避免占位符解析失败影响宿主插件。文案通过 {@link LanguageManager} 按玩家语言本地化。</p>
 */
public final class AeternumPlaceholders extends PlaceholderExpansion {
    private final AeternumSeasonsPlugin plugin;

    public AeternumPlaceholders(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    /**
     * 占位符前缀标识，对应 {@code %aeternum_...%}。
     */
    @Override
    public @NotNull String getIdentifier() {
        return "aeternum";
    }

    /**
     * 扩展作者，取自插件描述信息。
     */
    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", this.plugin.getDescription().getAuthors());
    }

    /**
     * 扩展版本，取自插件描述信息。
     */
    @Override
    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    /**
     * 保持扩展常驻，服务器重载时不会被自动反注册。
     */
    @Override
    public boolean persist() {
        return true;
    }

    /**
     * 仅当服务端已安装 PlaceholderAPI 时才允许注册。
     */
    @Override
    public boolean canRegister() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * PlaceholderAPI 的占位符解析入口。
     *
     * <p>根据 {@code params}（即占位符中 {@code aeternum_} 之后的部分，大小写不敏感）分派到温度、日历、
     * 世界或事件分支。无法识别的占位符返回 {@code null}，交由 PlaceholderAPI 按“未知占位符”处理。</p>
     *
     * @param player 请求占位符的玩家，可能为 {@code null}
     * @param params 占位符参数（已去掉前缀）
     * @return 解析后的文本，或在无数据时返回空串
     */
    @Override
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

                    // 按具体温度占位符选择数值、单位、摄氏或华氏表示；未指定时返回“数值 + 单位”。
                    String result;
                    switch (params) {
                        case "temperature_value" -> result = String.valueOf(reading.value());
                        case "temperature_unit" -> result = reading.symbol();
                        case "temperature_celsius" -> result = String.valueOf(reading.celsius());
                        case "temperature_fahrenheit" ->
                                result = String.valueOf(TemperatureUnit.FAHRENHEIT.fromCelsiusRounded(reading.celsius()));
                        default -> result = reading.value() + " " + reading.symbol();
                    }

                    return result;
                default:
                    SeasonService seasons = this.plugin.getSeasons();
                    LanguageManager lang = this.plugin.lang;
                    CalendarState state = null;
                    World world = player.getWorld();
                    if (seasons != null) {
                        try {
                            state = seasons.getStateCopy(world);
                        } catch (Throwable ignored) {
                            // 获取日历状态失败时保持 state 为 null，由后续分支返回空串。
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
                            } catch (Throwable ignored) {
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
                            } catch (Throwable ignored) {
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
                            } catch (Throwable ignored) {
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
                            } catch (Throwable ignored) {
                                return "";
                            }
                        default:
                            return null;
                    }
            }
        }
    }

    /**
     * 解析玩家所在世界的“领域/维度”显示名。
     *
     * <p>霜界、热界世界使用专用语言键；其余世界按主世界处理，优先取 {@code realm.overworld}，
     * 若缺失则回退到 {@code realm.overworld_title}。语言管理器为空时直接返回世界名。</p>
     */
    private String resolveRealmName(Player player, World world) {
        String name = world.getName();
        if (this.plugin.lang == null) {
            return name;
        } else if ("aeternum_frost".equalsIgnoreCase(name)) {
            return this.plugin.lang.tr(player, "realm.frost");
        } else if ("aeternum_heat".equalsIgnoreCase(name)) {
            return this.plugin.lang.tr(player, "realm.heat");
        } else {
            String key = "realm.overworld";
            String val = this.plugin.lang.tr(player, key);
            if (val.equals(key)) {
                val = this.plugin.lang.tr(player, "realm.overworld_title");
            }

            return val;
        }
    }

    /**
     * 带兜底的语言查询：当语言管理器为空、翻译为空或返回原键时，使用给定的 {@code fallback}。
     */
    private String trOrDefault(Player player, String key, String fallback) {
        if (this.plugin.lang == null) {
            return fallback;
        } else {
            String value = this.plugin.lang.tr(player, key);
            return value != null && !value.isEmpty() && !value.equals(key) ? value : fallback;
        }
    }

    /**
     * 解析季节事件的显示名。
     *
     * <p>按事件 id 依次尝试若干候选语言键：基础键 {@code event.<id>}，以及为兼容旧命名补充的
     * {@code festival}/{@code blessing} 变体；每个候选键先查 {@code .name} 再查 {@code .title}，
     * 均未命中时回退到事件自身的 {@link SeasonalEvent#getDisplayName()}。</p>
     */
    private String trEventName(Player player, SeasonalEvent event) {
        if (event == null) {
            return "";
        } else {
            String id = event.getId().toLowerCase(Locale.ROOT);
            LinkedHashSet<String> bases = new LinkedHashSet();
            bases.add("event." + id);
            if ("season_festival".equals(id)) {
                bases.add("event.festival");
            }

            if (id.endsWith("_festival")) {
                // 去掉 _festival 后缀得到更通用的语言键，例如 xxx_festival -> event.xxx。
                String festivalBase = id.substring(0, id.length() - "_festival".length());
                bases.add("event." + festivalBase);
            }

            if (id.endsWith("_blessing")) {
                // 去掉 _blessing 后缀得到更通用的语言键，例如 xxx_blessing -> event.xxx。
                String blessingBase = id.substring(0, id.length() - "_blessing".length());
                bases.add("event." + blessingBase);
            }

            for (String base : bases) {
                String value = this.plugin.lang.tr(player, base + ".name");
                if (value != null && !value.equals(base + ".name")) {
                    return value;
                }

                value = this.plugin.lang.tr(player, base + ".title");
                if (value != null && !value.equals(base + ".title")) {
                    return value;
                }
            }

            return event.getDisplayName();
        }
    }
}
