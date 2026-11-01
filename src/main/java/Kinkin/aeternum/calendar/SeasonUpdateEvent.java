package Kinkin.aeternum.calendar;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class SeasonUpdateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SeasonService source;
    private final CalendarChannel channel;
    private final CalendarState state;
    private final boolean dayAdvanced;

    public SeasonUpdateEvent(SeasonService src, CalendarChannel channel, CalendarState st, boolean dayAdvanced) {
        super(false);
        this.source = src;
        this.channel = channel;
        this.state = st;
        this.dayAdvanced = dayAdvanced;
    }

    public SeasonService getSource() {
        return this.source;
    }

    public CalendarChannel getChannel() {
        return this.channel;
    }

    public CalendarState getState() {
        return this.state;
    }

    public boolean isDayAdvanced() {
        return this.dayAdvanced;
    }

    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
