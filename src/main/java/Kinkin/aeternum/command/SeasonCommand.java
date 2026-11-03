package kinkin.aeternum.command;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.hud.HudService;
import kinkin.aeternum.world.BiomeSpoofAdapter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * /season 命令执行器。
 *
 * <p>管理员用于控制季节时钟与生物群系伪装的综合命令，子命令包括：
 * {@code info}（查看当前季节/天数/年份）、{@code set}（设置季节）、
 * {@code skipday}、{@code day|setday}、{@code year}、{@code reload}、
 * {@code hud}（切换玩家 HUD 显示模式）以及 {@code biomes}（开关/备份/恢复生物群系伪装）。</p>
 *
 * <p>各子命令按权限节点分别鉴权；发送者可能是玩家或控制台，因此本地化文本在
 * 无玩家上下文时会回退到英文或纯文本提示。</p>
 */
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

    /**
     * 立即把内存中的 climate.yml 配置写盘，失败时记录日志并提示发送者。
     */
    private void saveClimateNow(CommandSender sender) {
        try {
            File f = new File(this.plugin.getDataFolder(), "climate.yml");
            this.plugin.cfg.climate.save(f);
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Season] No se pudo guardar climate.yml: " + exception.getMessage());
            sender.sendMessage("§c[Season] No pude guardar climate.yml (revisa consola).");
        }

    }

    /**
     * 返回季节的本地化显示名；若翻译缺失则回退到季节自身的显示名。
     */
    private String seasonName(CommandSender sender, Season season) {
        Player player = sender instanceof Player ? (Player) sender : null;
        String key = "season." + season.name();
        String val = this.plugin.lang.tr(player, key);
        return val != null && !val.isBlank() && !val.equalsIgnoreCase(key) ? val : season.display();
    }

    /**
     * 便捷方法：按发送者类型（玩家/控制台）获取本地化文本。
     */
    private String tr(CommandSender sender, String key) {
        Player player = sender instanceof Player ? (Player) sender : null;
        return this.plugin.lang.tr(player, key);
    }

    /**
     * 便捷方法：带占位符变量的本地化文本。
     */
    private String trf(CommandSender sender, String key, Map<String, Object> vars) {
        Player player = sender instanceof Player ? (Player) sender : null;
        return this.plugin.lang.trf(player, key, vars);
    }

    /**
     * 处理 /season 命令：解析子命令并按权限分派到对应分支。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 0 && !"info".equalsIgnoreCase(args[0])) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "set":
                    if (!sender.hasPermission("aeternum.command.set")) {
                        return this.deny(sender);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = Collections.singletonMap("label", label);
                        sender.sendMessage(this.trf(sender, "cmd.season.set.usage", vars));
                        return true;
                    } else {
                        Season target;
                        try {
                            target = Season.valueOf(args[1].toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException exception) {
                            sender.sendMessage(this.tr(sender, "cmd.season.set.invalid"));
                            return true;
                        }

                        this.seasons.setSeason(target);
                        Map<String, Object> vars = Collections.singletonMap("season", this.seasonName(sender, target));
                        sender.sendMessage(this.trf(sender, "cmd.season.set.success", vars));
                        return true;
                    }
                case "skipday":
                    if (!sender.hasPermission("aeternum.command.skip")) {
                        return this.deny(sender);
                    }

                    this.seasons.nextDay();
                    sender.sendMessage(this.tr(sender, "cmd.season.skipday.done"));
                    return true;
                case "day":
                case "setday":
                    if (!sender.hasPermission("aeternum.command.day")) {
                        return this.deny(sender);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = new HashMap();
                        vars.put("label", label);
                        vars.put("max", this.seasons.getDaysPerSeason());
                        sender.sendMessage(this.trf(sender, "cmd.season.day.usage", vars));
                        return true;
                    } else {
                        int day;
                        try {
                            day = Integer.parseInt(args[1]);
                        } catch (NumberFormatException exception) {
                            sender.sendMessage(this.tr(sender, "cmd.season.day.invalid_number"));
                            return true;
                        }

                        this.seasons.setDay(day);
                        Map<String, Object> vars = Collections.singletonMap("day", day);
                        sender.sendMessage(this.trf(sender, "cmd.season.day.success", vars));
                        return true;
                    }
                case "year":
                    if (!sender.hasPermission("aeternum.command.year") && !sender.hasPermission("aeternum.command.base")) {
                        return this.deny(sender);
                    } else if (args.length < 2) {
                        Map<String, Object> vars = Collections.singletonMap("label", label);
                        sender.sendMessage(this.trf(sender, "cmd.season.year.usage", vars));
                        return true;
                    } else {
                        int year;
                        try {
                            year = Integer.parseInt(args[1]);
                        } catch (NumberFormatException exception) {
                            sender.sendMessage(this.tr(sender, "cmd.season.year.invalid_number"));
                            return true;
                        }

                        this.seasons.setYear(year);
                        Map<String, Object> vars = Collections.singletonMap("year", year);
                        sender.sendMessage(this.trf(sender, "cmd.season.year.success", vars));
                        return true;
                    }
                case "reload":
                    if (!sender.hasPermission("aeternum.command.reload")) {
                        return this.deny(sender);
                    }

                    sender.sendMessage("§e[Season] Reloading plugin...");

                    try {
                        this.plugin.reloadEverything();
                        sender.sendMessage(this.tr(sender, "cmd.season.reload.done"));
                    } catch (Throwable error) {
                        this.plugin.getLogger().log(Level.SEVERE, "[Season] Reload failed", error);
                        sender.sendMessage("§c[Season] Reload failed. Check console.");
                    }

                    return true;
                case "hud":
                    if (!sender.hasPermission("aeternum.command.base") && !sender.hasPermission("aeternum.command.hud")) {
                        return this.deny(sender);
                    } else {
                        if (sender instanceof Player) {
                            Player player = (Player) sender;
                            if (args.length < 2) {
                                HudService.HudMode current = this.hud.getPlayerMode(player);
                                String modeNameRaw = current.name();
                                String modeKey = "cmd.season.hud.mode." + modeNameRaw.toLowerCase(Locale.ROOT);
                                String modeName = this.plugin.lang.tr(player, modeKey);
                                Map<String, Object> varsUsage = Collections.singletonMap("label", label);
                                player.sendMessage(this.plugin.lang.trf(player, "cmd.season.hud.usage", varsUsage));
                                Map<String, Object> varsCurrent = Collections.singletonMap("mode", modeName);
                                player.sendMessage(this.plugin.lang.trf(player, "cmd.season.hud.current", varsCurrent));
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
                                    player.sendMessage(this.tr(player, "cmd.season.hud.invalid"));
                                    return true;
                            }

                            this.hud.setPlayerMode(player, mode);
                            switch (mode) {
                                case FIXED -> player.sendMessage(this.tr(player, "cmd.season.hud.set.fixed"));
                                case VARIABLE -> player.sendMessage(this.tr(player, "cmd.season.hud.set.variable"));
                                case OFF -> player.sendMessage(this.tr(player, "cmd.season.hud.set.off"));
                            }

                            return true;
                        }

                        sender.sendMessage(this.tr(sender, "cmd.season.hud.player_only"));
                        return true;
                    }
                case "biomes":
                    if (!sender.hasPermission("aeternum.command.biomes") && !sender.hasPermission("aeternum.command.base")) {
                        return this.deny(sender);
                    } else if (args.length < 2) {
                        sender.sendMessage("§eUso: /season biomes <on|off|restore|backup>");
                        return true;
                    } else {
                        switch (args[1].toLowerCase(Locale.ROOT)) {
                            case "off":
                                this.biomeSpoof.setEnabled(false);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", false);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable error) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + error.getMessage());
                                }

                                this.saveClimateNow(sender);
                                sender.sendMessage("§a[Season] Biome painting OFF");
                                break;
                            case "on":
                                this.biomeSpoof.setEnabled(true);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", true);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable error) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + error.getMessage());
                                }

                                this.saveClimateNow(sender);
                                sender.sendMessage("§a[Season] Biome painting ON");
                                break;
                            case "backup":
                                if (args.length < 3) {
                                    sender.sendMessage("§eUso: /season biomes backup <on|off>");
                                    return true;
                                }

                                String value = args[2].toLowerCase(Locale.ROOT);
                                boolean enable;
                                if (!"on".equals(value) && !"true".equals(value) && !"enable".equals(value)) {
                                    if (!"off".equals(value) && !"false".equals(value) && !"disable".equals(value)) {
                                        sender.sendMessage("§eUso: /season biomes backup <on|off>");
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
                                } catch (Throwable error) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + error.getMessage());
                                }

                                this.saveClimateNow(sender);
                                sender.sendMessage(enable ? "§a[Season] Biome backups DISK ON" : "§a[Season] Biome backups DISK OFF");
                                break;
                            case "restore":
                                this.biomeSpoof.setEnabled(false);
                                this.plugin.cfg.climate.set("biome_spoof.enabled", false);

                                try {
                                    this.plugin.cfg.saveAll();
                                } catch (Throwable error) {
                                    this.plugin.getLogger().warning("[Season] cfg.saveAll() falló: " + error.getMessage());
                                }

                                this.saveClimateNow(sender);
                                int budget = Math.max(1, this.plugin.cfg.climate.getInt("biome_spoof.restore_budget_chunks_per_tick", 6));
                                this.biomeSpoof.getDiskBackups().startRestoreAll(sender, budget);
                                sender.sendMessage("§a[Season] Restaurando biomas con budget " + budget + "/tick.");
                                break;
                            default:
                                sender.sendMessage("§eUso: /season biomes <on|off|restore|backup>");
                        }

                        return true;
                    }
                default:
                    Map<String, Object> vars = Collections.singletonMap("label", label);
                    sender.sendMessage(this.trf(sender, "cmd.season.help", vars));
                    return true;
            }
        } else if (!sender.hasPermission("aeternum.command.info") && !sender.hasPermission("aeternum.command.base")) {
            return this.deny(sender);
        } else {
            CalendarState st = this.seasons.getStateCopy();
            Map<String, Object> vars = new HashMap();
            vars.put("season", this.seasonName(sender, st.season));
            vars.put("day", st.day);
            vars.put("max", this.seasons.getDaysPerSeason());
            vars.put("year", st.year);
            sender.sendMessage(this.trf(sender, "cmd.season.info.line", vars));
            return true;
        }
    }

    /**
     * 发送无权限提示并返回 true（供命令处理器直接返回）。
     */
    private boolean deny(CommandSender sender) {
        sender.sendMessage(this.tr(sender, "cmd.season.no_permission"));
        return true;
    }

    /**
     * 按子命令与权限补全参数。
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) {
            if (args.length == 2 && "set".equalsIgnoreCase(args[0])) {
                return !sender.hasPermission("aeternum.command.set") && !sender.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("SPRING", "SUMMER", "AUTUMN", "WINTER");
            } else if (args.length == 2 && "hud".equalsIgnoreCase(args[0])) {
                return !sender.hasPermission("aeternum.command.hud") && !sender.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("fixed", "variable", "off");
            } else if (args.length == 2 && "biomes".equalsIgnoreCase(args[0])) {
                return !sender.hasPermission("aeternum.command.biomes") && !sender.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("on", "off", "restore", "backup");
            } else if (args.length == 3 && "biomes".equalsIgnoreCase(args[0]) && "backup".equalsIgnoreCase(args[1])) {
                return !sender.hasPermission("aeternum.command.biomes") && !sender.hasPermission("aeternum.command.base") ? Collections.emptyList() : Arrays.asList("on", "off");
            } else {
                return Collections.emptyList();
            }
        } else {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList();
            if (sender.hasPermission("aeternum.command.info") || sender.hasPermission("aeternum.command.base")) {
                out.add("info");
            }

            if (sender.hasPermission("aeternum.command.hud") || sender.hasPermission("aeternum.command.base")) {
                out.add("hud");
            }

            if (sender.hasPermission("aeternum.command.set") || sender.hasPermission("aeternum.command.base")) {
                out.add("set");
            }

            if (sender.hasPermission("aeternum.command.skip") || sender.hasPermission("aeternum.command.base")) {
                out.add("skipday");
            }

            if (sender.hasPermission("aeternum.command.day") || sender.hasPermission("aeternum.command.base")) {
                out.add("day");
            }

            if (sender.hasPermission("aeternum.command.reload") || sender.hasPermission("aeternum.command.base")) {
                out.add("reload");
            }

            if (sender.hasPermission("aeternum.command.biomes") || sender.hasPermission("aeternum.command.base")) {
                out.add("biomes");
            }

            if (sender.hasPermission("aeternum.command.year") || sender.hasPermission("aeternum.command.base")) {
                out.add("year");
            }

            out.removeIf((sub) -> !sub.startsWith(prefix));
            return out;
        }
    }
}
