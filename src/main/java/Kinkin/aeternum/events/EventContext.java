package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarChannel;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 事件运行上下文（Event Context）。
 *
 * <p>封装一次季节事件运行期间所需的依赖：插件实例、季节服务与当前日历频道；
 * 同时维护“禁用季节特效”的世界名单，并提供判断某世界/玩家是否应参与季节特效、
 * 获取本频道可用世界与合格玩家等便捷方法。</p>
 *
 * <p>由 {@code SeasonalEventService} 在调度事件时构造，并传入各个
 * {@link SeasonalEvent} 的生命周期回调。</p>
 */
public final class EventContext {
    /** 插件主实例，供事件访问语言、配置等服务。 */
    private final AeternumSeasonsPlugin plugin;
    /** 季节服务，用于按频道解析世界等。 */
    private final SeasonService seasons;
    /** 当前日历频道（如 OVERWORLD）。 */
    private final CalendarChannel channel;
    /** 在这些世界中将禁用季节特效（小写世界名集合）。 */
    private volatile Set<String> disabledSeasonFxWorlds = Set.of();

    public EventContext(AeternumSeasonsPlugin plugin, SeasonService seasons, CalendarChannel channel) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.channel = channel;
    }

    /** 返回插件主实例。 */
    public AeternumSeasonsPlugin plugin() {
        return this.plugin;
    }

    /** 返回季节服务。 */
    public SeasonService seasons() {
        return this.seasons;
    }

    /** 返回当前日历频道。 */
    public CalendarChannel channel() {
        return this.channel;
    }

    /** 返回当前频道下所有可用世界。 */
    public List<World> channelWorlds() {
        return this.seasons.getWorlds(this.channel);
    }

    /** 设置禁用季节特效的世界名单（会做去空、去重、转小写归一化）。 */
    public void setDisabledSeasonFxWorlds(Collection<String> worlds) {
        if (worlds != null && !worlds.isEmpty()) {
            this.disabledSeasonFxWorlds = (Set)worlds.stream().filter(Objects::nonNull).map((name) -> name.trim().toLowerCase(Locale.ROOT)).filter((name) -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
        } else {
            this.disabledSeasonFxWorlds = Set.of();
        }
    }

    /** 判断指定世界是否启用季节特效（属于当前频道且未被禁用）。 */
    public boolean isSeasonFxEnabled(World world) {
        if (world == null) {
            return false;
        } else if (this.seasons.resolveChannel(world) != this.channel) {
            return false;
        } else {
            return !this.disabledSeasonFxWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
        }
    }

    /** 判断指定玩家所在世界是否启用季节特效。 */
    public boolean isSeasonFxEnabled(Player player) {
        return player != null && this.isSeasonFxEnabled(player.getWorld());
    }

    /** 返回所有处于启用季节特效世界中的在线玩家。 */
    public List<Player> getEligiblePlayers() {
        return (List)Bukkit.getOnlinePlayers().stream().filter(this::isSeasonFxEnabled).collect(Collectors.toList());
    }

    /** 返回主世界列表（等价于当前频道世界）。 */
    public List<World> overworlds() {
        return this.channelWorlds();
    }

    /** 返回当前频道的世界列表。 */
    public List<World> worlds() {
        return this.channelWorlds();
    }
}
