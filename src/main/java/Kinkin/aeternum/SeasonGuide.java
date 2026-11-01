package Kinkin.aeternum;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.lang.LanguageManager;
import java.util.Map;
import org.bukkit.entity.Player;

public final class SeasonGuide {
    private SeasonGuide() {
        super();
    }

    public static void sendGuide(Player p, AeternumSeasonsPlugin plugin) {
        LanguageManager lang = plugin.lang;
        SeasonService seasons = plugin.getSeasons();
        CalendarState st = seasons.getStateCopy(p.getWorld());
        Season current = st.season;
        String var10001 = lang.tr(p, "guide.title");
        p.sendMessage("§6§m------------§r §e" + var10001 + " §6§m------------");
        var10001 = lang.trf(p, "guide.current", Map.of("season", lang.tr(p, "season." + current.name())));
        p.sendMessage("§f" + var10001);
        p.sendMessage("");
        var10001 = lang.tr(p, "guide.section.events");
        p.sendMessage("§d" + var10001);
        var10001 = lang.tr(p, "guide.events.blood_moon");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.heat_wave");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.winter_freeze");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.magic_storm");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.festival");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.fishing");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.mining");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.tornado");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.events.restful_sleep");
        p.sendMessage("§7- " + var10001);
        p.sendMessage("");
        var10001 = lang.tr(p, "guide.section.crops");
        p.sendMessage("§a" + var10001);

        for(Season s : Season.values()) {
            String seasonName = lang.tr(p, "season." + s.name());
            String lineKey = "guide.crops." + s.name();
            p.sendMessage("§7- " + seasonName + ": §f" + lang.tr(p, lineKey));
        }

        p.sendMessage("");
        var10001 = lang.tr(p, "guide.section.migration");
        p.sendMessage("§b" + var10001);

        for(Season s : Season.values()) {
            String seasonName = lang.tr(p, "season." + s.name());
            String lineKey = "guide.migration." + s.name();
            p.sendMessage("§7- " + seasonName + ": §f" + lang.tr(p, lineKey));
        }

        p.sendMessage("");
        var10001 = lang.tr(p, "guide.section.stables");
        p.sendMessage("§6" + var10001);
        var10001 = lang.tr(p, "guide.stables.winter");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.stables.breeding");
        p.sendMessage("§7- " + var10001);
        var10001 = lang.tr(p, "guide.stables.bonus_drops");
        p.sendMessage("§7- " + var10001);
        p.sendMessage("");
        var10001 = lang.tr(p, "guide.section.weather");
        p.sendMessage("§9" + var10001);

        for(Season s : Season.values()) {
            String seasonName = lang.tr(p, "season." + s.name());
            String lineKey = "guide.weather." + s.name();
            p.sendMessage("§7- " + seasonName + ": §f" + lang.tr(p, lineKey));
        }

        p.sendMessage("");
        var10001 = lang.tr(p, "guide.footer");
        p.sendMessage("§6" + var10001);
    }
}
