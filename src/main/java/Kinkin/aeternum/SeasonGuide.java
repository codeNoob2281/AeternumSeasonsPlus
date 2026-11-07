package kinkin.aeternum;

import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.lang.LanguageManager;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * 季节指南（Season Guide）文本输出工具。
 *
 * <p>负责把一个玩家当前季节下的“游戏指南”以聊天栏消息的形式打印出来，
 * 内容涵盖当前季节、季节事件、各季节作物、动物迁徙、马厩规则与天气等章节。
 * 全部文案通过 {@link LanguageManager} 从语言文件读取，因此本类只做排版与拼接，
 * 不包含任何游戏逻辑；所有文本均在调用时按玩家语言实时查询。</p>
 *
 * <p>本类为纯静态工具类，不可实例化。</p>
 */
public final class SeasonGuide {
    /**
     * 指南事件章节按序展示的事件 id（与各事件 {@code getId()} 及 {@code guide.events.*} 键一致）。
     */
    public static final String[] EVENT_IDS = new String[]{
            "blood_moon", "heat_wave", "winter_freeze", "magic_storm", "festival",
            "fishing_festival", "mining_blessing", "tornado", "restful_sleep",
            "holiday", "new_year", "nether_fishing_derby", "piglin_market",
            "quartz_rush", "fungus_bloom", "blaze_surge", "magma_tides",
            "ghast_alert", "wither_loose", "wither_skeleton_swarm"
    };

    private SeasonGuide() {
        super();
    }

    /**
     * 向指定玩家发送完整的季节指南。
     *
     * <p>依次输出标题、当前季节、事件列表、各季节的作物/迁徙/天气信息以及页脚；
     * 每个章节的分隔与颜色由代码中的固定格式串控制，章节标题与条目正文则来自语言文件。</p>
     *
     * @param player 接收指南的玩家
     * @param plugin 插件主类，用于访问语言管理器与季节服务
     */
    public static void sendGuide(Player player, AeternumSeasonsPlugin plugin) {
        LanguageManager lang = plugin.lang;
        SeasonService seasons = plugin.getSeasons();
        CalendarState state = seasons.getStateCopy(player.getWorld());
        Season current = state.season;
        String text = lang.tr(player, "guide.title");
        player.sendMessage("§6§m------------§r §e" + text + " §6§m------------");
        text = lang.trf(player, "guide.current", Map.of("season", lang.tr(player, "season." + current.name())));
        player.sendMessage("§f" + text);
        player.sendMessage("");
        text = lang.tr(player, "guide.section.events");
        player.sendMessage("§d" + text);

        for (String eventId : EVENT_IDS) {
            player.sendMessage("§7- " + lang.tr(player, "guide.events." + eventId));
        }
        player.sendMessage("");
        text = lang.tr(player, "guide.section.crops");
        player.sendMessage("§a" + text);

        for (Season season : Season.values()) {
            String seasonName = lang.tr(player, "season." + season.name());
            String lineKey = "guide.crops." + season.name();
            player.sendMessage("§7- " + seasonName + ": §f" + lang.tr(player, lineKey));
        }

        player.sendMessage("");
        text = lang.tr(player, "guide.section.migration");
        player.sendMessage("§b" + text);

        for (Season season : Season.values()) {
            String seasonName = lang.tr(player, "season." + season.name());
            String lineKey = "guide.migration." + season.name();
            player.sendMessage("§7- " + seasonName + ": §f" + lang.tr(player, lineKey));
        }

        player.sendMessage("");
        text = lang.tr(player, "guide.section.stables");
        player.sendMessage("§6" + text);
        text = lang.tr(player, "guide.stables.winter");
        player.sendMessage("§7- " + text);
        text = lang.tr(player, "guide.stables.breeding");
        player.sendMessage("§7- " + text);
        text = lang.tr(player, "guide.stables.bonus_drops");
        player.sendMessage("§7- " + text);
        player.sendMessage("");
        text = lang.tr(player, "guide.section.weather");
        player.sendMessage("§9" + text);

        for (Season season : Season.values()) {
            String seasonName = lang.tr(player, "season." + season.name());
            String lineKey = "guide.weather." + season.name();
            player.sendMessage("§7- " + seasonName + ": §f" + lang.tr(player, lineKey));
        }

        player.sendMessage("");
        text = lang.tr(player, "guide.footer");
        player.sendMessage("§6" + text);
    }
}
