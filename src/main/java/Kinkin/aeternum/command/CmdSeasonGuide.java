package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.lang.LanguageManager;
import Kinkin.aeternum.util.BookPaginator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("frost")) {
                String w = p.getWorld() != null ? p.getWorld().getName() : "";
                if (!w.equalsIgnoreCase("aeternum_frost")) {
                    return true;
                } else if (this.plugin.getFrostWorldGuide() == null) {
                    return true;
                } else {
                    this.plugin.getFrostWorldGuide().giveGuideForce(p);
                    return true;
                }
            } else {
                this.giveGuide(p, true);
                return true;
            }
        } else {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
    }

    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String pfx = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList();
            out.add("frost");
            out.removeIf((s) -> !s.startsWith(pfx));
            return out;
        } else {
            return List.of();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (this.plugin.getConfig().getBoolean("season_guide.give_on_first_join", true)) {
            Player p = e.getPlayer();
            if (!p.hasPlayedBefore()) {
                this.giveGuide(p, false);
            }

        }
    }

    private void giveGuide(Player p, boolean fromCommand) {
        Season current = this.seasonService.getStateCopy(p.getWorld()).season;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            p.sendMessage("§cNo se pudo crear el libro de guía.");
        } else {
            meta.setTitle(this.lang.tr(p, "guide.book_title"));
            meta.setAuthor(this.lang.tr(p, "guide.book_author"));
            List<String> pages = new ArrayList();
            StringBuilder sb = new StringBuilder();
            String rawSeasonName = this.lang.tr(p, "season." + current.name());
            String var10000 = this.seasonColor(current);
            String coloredSeason = var10000 + rawSeasonName + "§7";
            sb.append("§0").append(this.lang.tr(p, "guide.title")).append("\n\n");
            sb.append(this.lang.trf(p, "guide.current", Map.of("season", coloredSeason))).append("\n\n");
            sb.append("§7").append(this.lang.tr(p, "guide.intro"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.events")).append(" §7(1/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.blood_moon")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.heat_wave")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.winter_freeze")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.events")).append(" §7(2/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.magic_storm")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.festival")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.fishing")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.events")).append(" §7(3/3)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.mining")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.tornado")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.events.restful_sleep")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crops")).append(" §7(1/2)").append("\n\n");
            sb.append(this.seasonColor(Season.SPRING)).append("• ").append(this.lang.tr(p, "season.SPRING")).append("§7: ").append(this.lang.tr(p, "guide.crops.SPRING")).append("\n\n");
            sb.append(this.seasonColor(Season.SUMMER)).append("• ").append(this.lang.tr(p, "season.SUMMER")).append("§7: ").append(this.lang.tr(p, "guide.crops.SUMMER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crops")).append(" §7(2/2)").append("\n\n");
            sb.append(this.seasonColor(Season.AUTUMN)).append("• ").append(this.lang.tr(p, "season.AUTUMN")).append("§7: ").append(this.lang.tr(p, "guide.crops.AUTUMN")).append("\n\n");
            sb.append(this.seasonColor(Season.WINTER)).append("• ").append(this.lang.tr(p, "season.WINTER")).append("§7: ").append(this.lang.tr(p, "guide.crops.WINTER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.migration")).append(" §7(1/2)").append("\n\n");
            Season s = Season.SPRING;
            coloredSeason = this.seasonColor(s);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(p, "season." + s.name())).append("§7: ").append(this.lang.tr(p, "guide.migration." + s.name())).append("\n\n");
            s = Season.SUMMER;
            coloredSeason = this.seasonColor(s);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(p, "season." + s.name())).append("§7: ").append(this.lang.tr(p, "guide.migration." + s.name())).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.migration")).append(" §7(2/2)").append("\n\n");
            s = Season.AUTUMN;
            coloredSeason = this.seasonColor(s);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(p, "season." + s.name())).append("§7: ").append(this.lang.tr(p, "guide.migration." + s.name())).append("\n\n");
            s = Season.WINTER;
            coloredSeason = this.seasonColor(s);
            sb.append(coloredSeason).append("• ").append(this.lang.tr(p, "season." + s.name())).append("§7: ").append(this.lang.tr(p, "guide.migration." + s.name())).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.weather")).append(" §7(1/2)").append("\n\n");
            sb.append(this.seasonColor(Season.SPRING)).append("• ").append(this.lang.tr(p, "season.SPRING")).append("§7: ").append(this.lang.tr(p, "guide.weather.SPRING")).append("\n\n");
            sb.append(this.seasonColor(Season.SUMMER)).append("• ").append(this.lang.tr(p, "season.SUMMER")).append("§7: ").append(this.lang.tr(p, "guide.weather.SUMMER")).append("\n\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.weather")).append(" §7(2/2)").append("\n\n");
            sb.append(this.seasonColor(Season.AUTUMN)).append("• ").append(this.lang.tr(p, "season.AUTUMN")).append("§7: ").append(this.lang.tr(p, "guide.weather.AUTUMN")).append("\n\n");
            sb.append(this.seasonColor(Season.WINTER)).append("• ").append(this.lang.tr(p, "season.WINTER")).append("§7: ").append(this.lang.tr(p, "guide.weather.WINTER")).append("\n\n");
            sb.append("§7").append(this.lang.tr(p, "guide.footer"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.stables")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.stables.winter")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.stables.breeding")).append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.stables.bonus_drops")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(1/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.advanced_composter")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(2/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.harvest_hoe")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(3/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.farmer_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(4/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.lunar_lantern")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(5/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.solar_torch")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(6/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.spring_bell")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(7/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.spring_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(8/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.snow_boots")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(9/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.woodcutter_axe")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(10/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.frost_core")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(11/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.guardian_core")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(12/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.fish_trap")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(13/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.creature_generator")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.crafting")).append(" §7(14/14)").append("\n\n");
            sb.append("§7• ").append(this.lang.tr(p, "guide.crafting.Clock")).append("\n");
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.dimensions")).append(" §7(1/2)").append("\n\n");
            sb.append("§4").append(this.lang.tr(p, "guide.dimensions.snowbound.title")).append("\n\n");
            sb.append("§7").append(this.lang.tr(p, "guide.dimensions.snowbound.build")).append("\n\n");
            sb.append("§2[G] [G] [G] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [ ] [ ] [G]\n");
            sb.append("§2[G] [G] [G] [G]\n");
            sb.append("§1G = ").append(this.lang.tr(p, "guide.dimensions.snowbound.block")).append("\n");
            sb.append("§1").append(this.lang.tr(p, "guide.dimensions.snowbound.activate"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.dimensions")).append("\n\n");
            sb.append("§4").append(this.lang.tr(p, "guide.dimensions.snowbound.title"));
            sb.append("§1").append(this.lang.tr(p, "guide.dimensions.snowbound.warning_title")).append("\n\n");
            sb.append("§7").append(this.lang.tr(p, "guide.dimensions.snowbound.warning_1")).append("\n");
            sb.append("§7").append(this.lang.tr(p, "guide.dimensions.snowbound.warning_2")).append("\n");
            sb.append("§7").append(this.lang.tr(p, "guide.dimensions.snowbound.warning_3"));
            pages.add(sb.toString());
            sb = new StringBuilder();
            sb.append("§0").append(this.lang.tr(p, "guide.section.dimensions")).append(" §7(2/2)").append("\n\n");
            sb.append("§4").append(this.lang.tr(p, "guide.dimensions.emberbound.title"));
            sb.append("§7").append(this.lang.tr(p, "guide.dimensions.emberbound.build_glitched"));
            sb.append("§2[W] [W] [W] [?]\n");
            sb.append("§2[W] [ ] [ ] [W]\n");
            sb.append("§2[W] [ ]   [?]\n");
            sb.append("§2[?]   [ ]\n\n");
            sb.append("§KW = ").append(this.lang.tr(p, "guide.dimensions.emberbound.block"));
            sb.append("§4").append(this.lang.tr(p, "guide.dimensions.emberbound.glitch_warning"));
            pages.add(sb.toString());
            BookPaginator paginator = new BookPaginator();
            boolean first = true;

            for(String rawPage : pages) {
                if (!first) {
                    paginator.newPage();
                }

                first = false;
                paginator.addText(rawPage);
            }

            List<String> finalPages = paginator.build();
            meta.setPages(finalPages);
            book.setItemMeta(meta);
            p.getInventory().addItem(new ItemStack[]{book});
            if (fromCommand) {
                String var10001 = this.lang.tr(p, "guide.book_given");
                p.sendMessage("§2" + var10001);
            } else {
                String var50 = this.lang.tr(p, "guide.book_welcome");
                p.sendMessage("§2" + var50);
            }

        }
    }

    private String seasonColor(Season s) {
        String var10000;
        switch (s) {
            case SPRING -> var10000 = "§2";
            case SUMMER -> var10000 = "§6";
            case AUTUMN -> var10000 = "§6";
            case WINTER -> var10000 = "§3";
            default -> throw new MatchException((String)null, (Throwable)null);
        }

        return var10000;
    }
}
