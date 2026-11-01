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

public final class EventContext {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final CalendarChannel channel;
    private volatile Set<String> disabledSeasonFxWorlds = Set.of();

    public EventContext(AeternumSeasonsPlugin plugin, SeasonService seasons, CalendarChannel channel) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.channel = channel;
    }

    public AeternumSeasonsPlugin plugin() {
        return this.plugin;
    }

    public SeasonService seasons() {
        return this.seasons;
    }

    public CalendarChannel channel() {
        return this.channel;
    }

    public List<World> channelWorlds() {
        return this.seasons.getWorlds(this.channel);
    }

    public void setDisabledSeasonFxWorlds(Collection<String> worlds) {
        if (worlds != null && !worlds.isEmpty()) {
            this.disabledSeasonFxWorlds = (Set)worlds.stream().filter(Objects::nonNull).map((s) -> s.trim().toLowerCase(Locale.ROOT)).filter((s) -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        } else {
            this.disabledSeasonFxWorlds = Set.of();
        }
    }

    public boolean isSeasonFxEnabled(World w) {
        if (w == null) {
            return false;
        } else if (this.seasons.resolveChannel(w) != this.channel) {
            return false;
        } else {
            return !this.disabledSeasonFxWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
        }
    }

    public boolean isSeasonFxEnabled(Player p) {
        return p != null && this.isSeasonFxEnabled(p.getWorld());
    }

    public List<Player> getEligiblePlayers() {
        return (List)Bukkit.getOnlinePlayers().stream().filter(this::isSeasonFxEnabled).collect(Collectors.toList());
    }

    public List<World> overworlds() {
        return this.channelWorlds();
    }

    public List<World> worlds() {
        return this.channelWorlds();
    }
}
