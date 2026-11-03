package kinkin.aeternum.calendar;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 日历更新事件。
 *
 * <p>当某个频道的日历状态发生变化时由 {@link SeasonService} 触发，供其他插件模块监听。
 * 事件携带来源服务、所属频道、更新后的状态副本，以及本次更新是否推进了一天。</p>
 */
public final class SeasonUpdateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SeasonService source;
    private final CalendarChannel channel;
    private final CalendarState state;
    private final boolean dayAdvanced;

    /**
     * 创建一个日历更新事件。
     *
     * @param source      触发事件的服务
     * @param channel     发生变化的频道
     * @param state       更新后的状态（通常是副本）
     * @param dayAdvanced 本次更新是否推进了日期
     */
    public SeasonUpdateEvent(SeasonService source, CalendarChannel channel, CalendarState state, boolean dayAdvanced) {
        super(false);
        this.source = source;
        this.channel = channel;
        this.state = state;
        this.dayAdvanced = dayAdvanced;
    }

    /**
     * 返回触发本事件的服务。
     */
    public SeasonService getSource() {
        return this.source;
    }

    /**
     * 返回发生变化的日历频道。
     */
    public CalendarChannel getChannel() {
        return this.channel;
    }

    /**
     * 返回更新后的日历状态。
     */
    public CalendarState getState() {
        return this.state;
    }

    /**
     * 返回本次更新是否推进了日期。
     */
    public boolean isDayAdvanced() {
        return this.dayAdvanced;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
