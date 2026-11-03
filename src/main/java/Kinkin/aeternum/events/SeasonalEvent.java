package kinkin.aeternum.events;

import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import org.bukkit.event.Listener;

/**
 * 季节随机事件（Seasonal Event）的统一契约。
 *
 * <p>每一种季节事件（如血月、热浪、龙卷风等）都实现本接口。{@code SeasonalEventService}
 * 负责按日历季节、持续天数与每日概率挑选并驱动这些事件；各实现只描述自身的
 * 元信息、触发条件与生命周期回调，不关心调度细节。</p>
 *
 * <p>生命周期顺序通常为：
 * {@link #canStartToday(CalendarState, EventContext)} 判定是否开始 →
 * {@link #onStart(CalendarState, EventContext)} → 每个游戏日 {@link #onDayTick(CalendarState, EventContext)}
 * 与每个 tick {@link #onTick(CalendarState, EventContext)} → {@link #onEnd(CalendarState, EventContext)}。
 * 各实现同时可注册为 Bukkit {@link Listener} 以监听原版事件（{@code extends Listener}）。</p>
 */
public interface SeasonalEvent extends Listener {
    /**
     * 事件唯一标识（用于配置键与注册表，建议使用小写下划线风格）。
     */
    String getId();

    /**
     * 事件的展示名称（用于提示信息、调试或占位符）。
     */
    String getDisplayName();

    /**
     * 判断该事件是否允许在指定季节发生。
     *
     * @param season 当前季节
     * @return 允许返回 true，否则 false
     */
    boolean isSeasonAllowed(Season season);

    /**
     * 事件持续的最短天数。
     */
    int getMinDurationDays();

    /**
     * 事件持续的最长天数。
     */
    int getMaxDurationDays();

    /**
     * 判断今天是否满足开始条件（例如有玩家处于对应维度、通过随机概率等）。
     *
     * @param state   当前日历状态
     * @param context 事件运行上下文（提供插件、季节服务、可用世界/玩家等）
     * @return 可以开始返回 true
     */
    boolean canStartToday(CalendarState state, EventContext context);

    /**
     * 事件开始时的回调。
     *
     * @param state   当前日历状态
     * @param context 事件运行上下文
     */
    void onStart(CalendarState state, EventContext context);

    /**
     * 事件结束时的回调（清理状态、通知玩家等）。
     *
     * @param state   当前日历状态
     * @param context 事件运行上下文
     */
    void onEnd(CalendarState state, EventContext context);

    /**
     * 每经过一个游戏日的回调。
     *
     * @param state   当前日历状态
     * @param context 事件运行上下文
     */
    void onDayTick(CalendarState state, EventContext context);

    /**
     * 每个调度 tick 的回调（周期由事件服务控制，注意保持轻量）。
     *
     * @param state   当前日历状态
     * @param context 事件运行上下文
     */
    void onTick(CalendarState state, EventContext context);
}
