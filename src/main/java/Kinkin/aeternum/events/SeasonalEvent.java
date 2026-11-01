package Kinkin.aeternum.events;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import org.bukkit.event.Listener;

public interface SeasonalEvent extends Listener {
    String getId();

    String getDisplayName();

    boolean isSeasonAllowed(Season var1);

    int getMinDurationDays();

    int getMaxDurationDays();

    boolean canStartToday(CalendarState var1, EventContext var2);

    void onStart(CalendarState var1, EventContext var2);

    void onEnd(CalendarState var1, EventContext var2);

    void onDayTick(CalendarState var1, EventContext var2);

    void onTick(CalendarState var1, EventContext var2);
}
