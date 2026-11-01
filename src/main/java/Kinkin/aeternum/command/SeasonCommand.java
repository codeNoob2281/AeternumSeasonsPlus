package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.hud.HudService;
import Kinkin.aeternum.world.BiomeSpoofAdapter;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class SeasonCommand implements CommandExecutor, TabCompleter {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final HudService hud;
    private final BiomeSpoofAdapter biomeSpoof;

    public SeasonCommand(AeternumSeasonsPlugin plugin, SeasonService seasons, HudService hud, BiomeSpoofAdapter biomeSpoof) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.hud = hud;
        this.biomeSpoof = biomeSpoof;
    }

    private void saveClimateNow(CommandSender s) {
        try {
            File f = new File(this.plugin.getDataFolder(), "climate.yml");
            this.plugin.cfg.climate.save(f);
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Season] No se pudo guardar climate.yml: " + e.getMessage());
            s.sendMessage("§c[Season] No pude guardar climate.yml (revisa consola).");
        }

    }

    private String seasonName(CommandSender s, Season season) {
        Player p = s instanceof Player ? (Player)s : null;
        String key = "season." + season.name();
        String val = this.plugin.lang.tr(p, key);
        return val != null && !val.isBlank() && !val.equalsIgnoreCase(key) ? val : season.display();
    }

    private String tr(CommandSender s, String key) {
        Player p = s instanceof Player ? (Player)s : null;
        return this.plugin.lang.tr(p, key);
    }

    private String trf(CommandSender s, String key, Map<String, Object> vars) {
        Player p = s instanceof Player ? (Player)s : null;
        return this.plugin.lang.trf(p, key, vars);
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length != 0 && !args[0].equalsIgnoreCase("info")) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "set":
                    if (!s.hasPermission("aeternum.command.set")) {
                        return this.deny(s);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = Collections.singletonMap("label", label);
                        s.sendMessage(this.trf(s, "cmd.season.set.usage", vars));
                        return true;
                    } else {
                        Season target;
                        try {
                            target = Season.valueOf(args[1].toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException var21) {
                            s.sendMessage(this.tr(s, "cmd.season.set.invalid"));
                            return true;
                        }

                        this.seasons.setSeason(target);
                        Map<String, Object> vars = Collections.singletonMap("season", this.seasonName(s, target));
                        s.sendMessage(this.trf(s, "cmd.season.set.success", vars));
                        return true;
                    }
                case "skipday":
                    if (!s.hasPermission("aeternum.command.skip")) {
                        return this.deny(s);
                    }

                    this.seasons.nextDay();
                    s.sendMessage(this.tr(s, "cmd.season.skipday.done"));
                    return true;
                case "day":
                case "setday":
                    if (!s.hasPermission("aeternum.command.day")) {
                        return this.deny(s);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = new HashMap();
                        vars.put("label", label);
                        vars.put("max", this.seasons.getDaysPerSeason());
                        s.sendMessage(this.trf(s, "cmd.season.day.usage", vars));
                        return true;
                    } else {
                        int n;
                        try {
                            n = Integer.parseInt(args[1]);
                        } catch (NumberFormatException var20) {
                            s.sendMessage(this.tr(s, "cmd.season.day.invalid_number"));
                            return true;
                        }

                        this.seasons.setDay(n);
                        Map<String, Object> vars = Collections.singletonMap("day", n);
                        s.sendMessage(this.trf(s, "cmd.season.day.success", vars));
                        return true;
                    }
                case "year":
                    if (!s.hasPermission("aeternum.command.year") && !s.hasPermission("aeternum.command.base")) {
                        return this.deny(s);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = Collections.singletonMap("label", label);
                        s.sendMessage(this.trf(s, "cmd.season.year.usage", vars));
                        return true;
                    } else {
                        int year;
                        try {
                            year = Integer.parseInt(args[1]);
                        } catch (NumberFormatException var19) {
                            s.sendMessage(this.tr(s, "cmd.season.year.invalid_number"));
                            return true;
                        }

                        this.seasons.setYear(year);
                        Map<String, Object> vars = Collections.singletonMap("year", year);
                        s.sendMessage(this.trf(s, "cmd.season.year.success", vars));
                        return true;
                    }
                case "reload":
                    if (!s.hasPermission("aeternum.command.reload")) {
                        return this.deny(s);
                    }

                    s.sendMessage("§e[Season] Reloading plugin...");

                    try {
                        this.plugin.reloadEverything();
                        s.sendMessage(this.tr(s, "cmd.season.reload.done"));
                    } catch (Throwable t) {
                        this.plugin.getLogger().severe("[Season] Reload falló: " + t.getMessage());
                        t.printStackTrace();
                        s.sendMessage("§c[Season] Reload falló. Revisa consola.");
                    }

                    return true;
                case "hud":
                    if (!s.hasPermission("aeternum.command.base") && !s.hasPermission("aeternum.command.hud")) {
                        return this.deny(s);
                    } else {
                        if (s instanceof Player) {
                            Player p = (Player)s;
                            if (args.length < 2) {
                                HudService.HudMode current = this.hud.getPlayerMode(p);
                                String var10000 = current.name();
                                String modeKey = "cmd.season.hud.mode." + var10000.toLowerCase(Locale.ROOT);
                                String modeName = this.plugin.lang.tr(p, modeKey);
                                Map<String, Object> varsUsage = Collections.singletonMap("label", label);
                                p.sendMessage(this.plugin.lang.trf(p, "cmd.season.hud.usage", varsUsage));
                                Map<String, Object> varsCurrent = Collections.singletonMap("mode", modeName);
                                p.sendMessage(this.plugin.lang.trf(p, "cmd.season.hud.current", varsCurrent));
                                return true;
                            }

                            HudService.HudMode mode;
                            switch (args[1].toLowerCase(Locale.ROOT)) {
                                case "fixed":
                                case "fijo":
                                    mode = HudService.HudMode.FIXED;
                                    break;
                                case "variable":
                                case "var":
                                    mode = HudService.HudMode.VARIABLE;
                                    break;
                                case "off":
                                case "apagado":
                                case "disable":
                                case "disabled":
                                    mode = HudService.HudMode.OFF;
                                    break;
                                default:
                                    p.sendMessage(this.tr(p, "cmd.season.hud.invalid"));
                                    return true;
                            }

                            this.hud.setPlayerMode(p, mode);
                            switch (mode) {
                                case FIXED -> p.sendMessage(this.tr(p, "cmd.season.hud.set.fixed"));
                                case VARIABLE -> p.sendMessage(this.tr(p, "cmd.season.hud.set.variable"));
                                case OFF -> p.sendMessage(this.tr(p, "cmd.season.hud.set.off"));
                            }

                            return true;
                        }

                        s.sendMessage(this.tr(s, "cmd.season.hud.player_only"));
                        return true;
                    }
                case "biomes":
                    if (!s.hasPermission("aeternum.command.biomes") && !s.hasPermission("aeternum.command.base")) {
                        return this.deny(s);
                    } else if (args.length < 2) {
                        s.sendMessage("§eUso: /season biomes <on|off|restore|backup>");
                        return true;
                    } else {
                        switch (args[1].toLowerCase(Locale.ROOT)) {
                            case "off":
                                this.biomeSpoof.setEnabled(false);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", false);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable t) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + t.getMessage());
                                }

                                this.saveClimateNow(s);
                                s.sendMessage("§a[Season] Biome painting OFF");
                                break;
                            case "on":
                                this.biomeSpoof.setEnabled(true);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", true);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable t) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + t.getMessage());
                                }

                                this.saveClimateNow(s);
                                s.sendMessage("§a[Season] Biome painting ON");
                                break;
                            case "backup":
                                if (args.length < 3) {
                                    s.sendMessage("§eUso: /season biomes backup <on|off>");
                                    return true;
                                }

                                String v = args[2].toLowerCase(Locale.ROOT);
                                boolean enable;
                                if (!"on".equals(v) && !"true".equals(v) && !"enable".equals(v)) {
                                    if (!"off".equals(v) && !"false".equals(v) && !"disable".equals(v)) {
                                        s.sendMessage("§eUso: /season biomes backup <on|off>");
                                        return true;
                                    }

                                    enable = false;
                                } else {
                                    enable = true;
                                }

                                this.biomeSpoof.setDiskBackupEnabled(enable);
                                this.plugin.cfg.climate.set("biome_spoof.disk_backup.enabled", enable);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable t) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + t.getMessage());
                                }

                                this.saveClimateNow(s);
                                s.sendMessage(enable ? "§a[Season] Biome backups DISK ON" : "§a[Season] Biome backups DISK OFF");
                                break;
                            case "restore":
                                this.biomeSpoof.setEnabled(false);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", false);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable t) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + t.getMessage());
                                }

                                this.saveClimateNow(s);
                                int budget = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.restore_budget_chunks_per_tick", 6));
                                this.biomeSpoof.getDiskBackups().startRestoreAll(s, budget);
                                s.sendMessage("§a[Season] Restaurando biomas con budget " + budget + "/tick.");
                                break;
                            default:
                                s.sendMessage("§eUso: /season biomes <on|off|restore|backup>");
                        }

                        return true;
                    }
                default:
                    Map<String, Object> vars = Collections.singletonMap("label", label);
                    s.sendMessage(this.trf(s, "cmd.season.help", vars));
                    return true;
            }
        } else if (!s.hasPermission("aeternum.command.info") && !s.hasPermission("aeternum.command.base")) {
            return this.deny(s);
        } else {
            CalendarState st = this.seasons.getStateCopy();
            Map<String, Object> vars = new HashMap();
            vars.put("season", this.seasonName(s, st.season));
            vars.put("day", st.day);
            vars.put("max", this.seasons.getDaysPerSeason());
            vars.put("year", st.year);
            s.sendMessage(this.trf(s, "cmd.season.info.line", vars));
            return true;
        }
    }

    private boolean deny(CommandSender s) {
        s.sendMessage(this.tr(s, "cmd.season.no_permission"));
        return true;
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length != 1) {
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return !s.hasPermission("aeternum.command.set") && !s.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("SPRING", "SUMMER", "AUTUMN", "WINTER");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("hud")) {
                return !s.hasPermission("aeternum.command.hud") && !s.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("fixed", "variable", "off");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("biomes")) {
                return !s.hasPermission("aeternum.command.biomes") && !s.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("on", "off", "restore", "backup");
            } else if (args.length == 3 && args[0].equalsIgnoreCase("biomes") && args[1].equalsIgnoreCase("backup")) {
                return !s.hasPermission("aeternum.command.biomes") && !s.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("on", "off");
            } else {
                return Collections.emptyList();
            }
        } else {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList();
            if (s.hasPermission("aeternum.command.info") || s.hasPermission("aeternum.command.base")) {
                out.add("info");
            }

            if (s.hasPermission("aeternum.command.hud") || s.hasPermission("aeternum.command.base")) {
                out.add("hud");
            }

            if (s.hasPermission("aeternum.command.set") || s.hasPermission("aeternum.command.base")) {
                out.add("set");
            }

            if (s.hasPermission("aeternum.command.skip") || s.hasPermission("aeternum.command.base")) {
                out.add("skipday");
            }

            if (s.hasPermission("aeternum.command.day") || s.hasPermission("aeternum.command.base")) {
                out.add("day");
            }

            if (s.hasPermission("aeternum.command.reload") || s.hasPermission("aeternum.command.base")) {
                out.add("reload");
            }

            if (s.hasPermission("aeternum.command.biomes") || s.hasPermission("aeternum.command.base")) {
                out.add("biomes");
            }

            if (s.hasPermission("aeternum.command.year") || s.hasPermission("aeternum.command.base")) {
                out.add("year");
            }

            out.removeIf((sub) -> !sub.startsWith(prefix));
            return out;
        }
    }
}
