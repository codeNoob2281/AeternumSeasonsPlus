package kinkin.aeternum.command;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.lang.LanguageManager;
import kinkin.aeternum.util.BookPaginator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 季节指南命令与首次入服引导。
 *
 * <p>负责 {@code /seasonguide}（及 {@code /guide}）命令：把当前季节、事件、作物、
 * 生物迁移、天气、马厩、合成配方、维度搭建等信息动态生成为一本成书（Written Book）
 * 交给玩家。另监听玩家首次加入，按配置自动发放指南；对霜冻世界还支持发放专用指南。</p>
 *
 * <p>依赖 {@link SeasonService} 读取当前季节、{@link LanguageManager} 做本地化、
 * {@link BookPaginator} 处理分页。</p>
 */
public final class CmdSeasonGuide implements CommandExecutor, TabCompleter, Listener {
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager lang;
    private final SeasonService seasonService;

    public CmdSeasonGuide(AeternumSeasonsPlugin plugin, LanguageManager lang, SeasonService seasonService) {
        super();
        this.plugin = plugin;
        this.lang = lang;
        this.seasonService = seasonService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 处理 /seasonguide 命令：{@code frost} 子命令发放霜冻世界专用指南，
     * 其余情况发放当前世界季节对应的通用指南。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player player) {
            if (args.length >= 1 && "frost".equalsIgnoreCase(args[0])) {
                String worldName = player.getWorld() != null ? player.getWorld().getName() : "";
                if (!"aeternum_frost".equalsIgnoreCase(worldName)) {
                    return true;
                } else if (this.plugin.getFrostWorldGuide() == null) {
                    return true;
                } else {
                    this.plugin.getFrostWorldGuide().giveGuideForce(player);
                    return true;
                }
            } else {
                this.giveGuide(player, true);
                return true;
            }
        } else {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
    }

    /**
     * 补全 {@code frost} 子命令。
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList();
            out.add("frost");
            out.removeIf((candidate) -> !candidate.startsWith(prefix));
            return out;
        } else {
            return List.of();
        }
    }

    /**
     * 玩家首次加入时，按配置自动发放季节指南。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.plugin.getConfig().getBoolean("season_guide.give_on_first_join", true)) {
            Player player = event.getPlayer();
            if (!player.hasPlayedBefore()) {
                this.giveGuide(player, false);
            }

        }
    }

    /**
     * 构建并发放季节指南成书。
     *
     * <p>逐页拼装书名、当前季节、事件列表、作物、迁移、天气、马厩、合成与维度说明，
     * 再用 {@link BookPaginator} 分页写入成书并交给玩家。{@code fromCommand}
     * 用于区分“命令主动索取”和“首次入服赠送”时不同的提示文案。</p>
     */
    private void giveGuide(Player player, boolean fromCommand) {
        Season current = this.seasonService.getStateCopy(player.getWorld()).season;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            player.sendMessage("§cNo se pudo crear el libro de guía.");
        } else {
            meta.setTitle(this.lang.tr(player, "guide.book_title"));
            meta.setAuthor(this.lang.tr(player, "guide.book_author"));
            List<String> pages = new ArrayList();
            StringBuilder sb = new StringBuilder();
            String rawSeasonName = this.lang.tr(player, "season." + current.name());
            String seasonColorCode = this.seasonColor(current);
            String coloredSeason = seasonColorCode + rawSeasonName + "§7";
            sb.append("§0").append(this.lang.tr(player, "guide.title")).append("\n\n");
            sb.append(this.lang.trf(player, "guide.current", Map.of("season", coloredSeason))).append("\n\n");
            sb.append("§7").append(this.lang.tr(player, "guide.intro"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.events")).append(" §7(1/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.blood_moon")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.heat_wave")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.winter_freeze")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.events")).append(" §7(2/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.magic_storm")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.festival")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.fishing")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.events")).append(" §7(3/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.mining")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.tornado")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.events.restful_sleep")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crops")).append(" §7(1/2)").append("\n\n");
            sb.append(this.seasonColor(Season.SPRING)).append("• ").append(this.lang.tr(player, "season.SPRING")).append("§7: ").append(this.lang.tr(player, "guide.crops.SPRING")).append("\n\n");
            sb.append(this.seasonColor(Season.SUMMER)).append("• ").append(this.lang.tr(player, "season.SUMMER")).append("§7: ").append(this.lang.tr(player, "guide.crops.SUMMER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crops")).append(" §7(2/2)").append("\n\n");
            sb.append(this.seasonColor(Season.AUTUMN)).append("• ").append(this.lang.tr(player, "season.AUTUMN")).append("§7: ").append(this.lang.tr(player, "guide.crops.AUTUMN")).append("\n\n");
            sb.append(this.seasonColor(Season.WINTER)).append("• ").append(this.lang.tr(player, "season.WINTER")).append("§7: ").append(this.lang.tr(player, "guide.crops.WINTER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.migration")).append(" §7(1/2)").append("\n\n");
            Season season = Season.SPRING;
            coloredSeason = this.seasonColor(season);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(player, "season." + season.name())).append("§7: ").append(this.lang.tr(player, "guide.migration." + season.name())).append("\n\n");
            season = Season.SUMMER;
            coloredSeason = this.seasonColor(season);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(player, "season." + season.name())).append("§7: ").append(this.lang.tr(player, "guide.migration." + season.name())).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.migration")).append(" §7(2/2)").append("\n\n");
            season = Season.AUTUMN;
            coloredSeason = this.seasonColor(season);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(player, "season." + season.name())).append("§7: ").append(this.lang.tr(player, "guide.migration." + season.name())).append("\n\n");
            season = Season.WINTER;
            coloredSeason = this.seasonColor(season);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(player, "season." + season.name())).append("§7: ").append(this.lang.tr(player, "guide.migration." + season.name())).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.weather")).append(" §7(1/2)").append("\n\n");
            sb.append(this.seasonColor(Season.SPRING)).append("• ").append(this.lang.tr(player, "season.SPRING")).append("§7: ").append(this.lang.tr(player, "guide.weather.SPRING")).append("\n\n");
            sb.append(this.seasonColor(Season.SUMMER)).append("• ").append(this.lang.tr(player, "season.SUMMER")).append("§7: ").append(this.lang.tr(player, "guide.weather.SUMMER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.weather")).append(" §7(2/2)").append("\n\n");
            sb.append(this.seasonColor(Season.AUTUMN)).append("• ").append(this.lang.tr(player, "season.AUTUMN")).append("§7: ").append(this.lang.tr(player, "guide.weather.AUTUMN")).append("\n\n");
            sb.append(this.seasonColor(Season.WINTER)).append("• ").append(this.lang.tr(player, "season.WINTER")).append("§7: ").append(this.lang.tr(player, "guide.weather.WINTER")).append("\n\n");
            sb.append("§7").append(this.lang.tr(player, "guide.footer"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.stables")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.stables.winter")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.stables.breeding")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.stables.bonus_drops")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(1/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.advanced_composter")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(2/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.harvest_hoe")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(3/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.farmer_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(4/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.lunar_lantern")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(5/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.solar_torch")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(6/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.spring_bell")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(7/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.spring_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(8/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.snow_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(9/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.woodcutter_axe")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(10/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.frost_core")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(11/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.guardian_core")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(12/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.fish_trap")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(13/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.creature_generator")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.crafting")).append(" §7(14/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(player, "guide.crafting.Clock")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.dimensions")).append(" §7(1/2)").append("\n\n");
            sb.append("§4").append(this.lang.tr(player, "guide.dimensions.snowbound.title")).append("\n\n");
            sb.append("§7").append(this.lang.tr(player, "guide.dimensions.snowbound.build")).append("\n\n");
            sb.append("§2[G] [G] [G] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [G] [G] [G]\n");
            sb.append("§1G = ").append(this.lang.tr(player, "guide.dimensions.snowbound.block")).append("\n");
            sb.append("§1").append(this.lang.tr(player, "guide.dimensions.snowbound.activate"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.dimensions")).append("\n\n");
            sb.append("§4").append(this.lang.tr(player, "guide.dimensions.snowbound.title"));
            sb.append("§1").append(this.lang.tr(player, "guide.dimensions.snowbound.warning_title")).append("\n\n");
            sb.append("§7").append(this.lang.tr(player, "guide.dimensions.snowbound.warning_1")).append("\n");
            sb.append("§7").append(this.lang.tr(player, "guide.dimensions.snowbound.warning_2")).append("\n");
            sb.append("§7").append(this.lang.tr(player, "guide.dimensions.snowbound.warning_3"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(player, "guide.section.dimensions")).append(" §7(2/2)").append("\n\n");
            sb.append("§4").append(this.lang.tr(player, "guide.dimensions.emberbound.title"));
            sb.append("§7").append(this.lang.tr(player, "guide.dimensions.emberbound.build_glitched"));
            sb.append("§2[W] [W] [W] [?]\n");
            sb.append("§2[W] [ ] [ ] [W]\n");
            sb.append("§2[W] [ ]   [?]\n");
            sb.append("§2[?]   [ ]\n\n");
            sb.append("§KW = ").append(this.lang.tr(player, "guide.dimensions.emberbound.block"));
            sb.append("§4").append(this.lang.tr(player, "guide.dimensions.emberbound.glitch_warning"));
            pages.add(sb.toString());
            BookPaginator paginator = new BookPaginator();
            boolean first = true;

            for (String rawPage : pages) {
                if (!first) {
                    paginator.newPage();
                }

                first = false;
                paginator.addText(rawPage);
            }

            List<String> finalPages = paginator.build();
            meta.setPages(finalPages);
            book.setItemMeta(meta);
            player.getInventory().addItem(new ItemStack[]{book});
            if (fromCommand) {
                String givenMessage = this.lang.tr(player, "guide.book_given");
                player.sendMessage("§2" + givenMessage);
            } else {
                String welcomeMessage = this.lang.tr(player, "guide.book_welcome");
                player.sendMessage("§2" + welcomeMessage);
            }

        }
    }

    /**
     * 返回某季节对应的颜色代码（春季绿、夏秋金、冬季青）。
     */
    private String seasonColor(Season season) {
        String color;
        switch (season) {
            case SPRING -> color = "§a";
            case SUMMER -> color = "§e";
            case AUTUMN -> color = "§6";
            case WINTER -> color = "§b";
            default -> throw new MatchException((String) null, (Throwable) null);
        }

        return color;
    }
}
