package kinkin.aeternum.command;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarChannel;
import kinkin.aeternum.events.SeasonalEvent;
import kinkin.aeternum.events.SeasonalEventService;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /event 命令执行器。
 *
 * <p>面向管理员的季节事件控制命令，支持：
 * <ul>
 *     <li>{@code list} —— 列出各日历频道已注册的事件；</li>
 *     <li>{@code info} —— 查看各频道当前进行中的事件及剩余天数；</li>
 *     <li>{@code start <id> [days]} —— 强制开始指定事件，可指定持续天数；</li>
 *     <li>{@code stop [channel]} —— 强制结束指定频道（默认玩家所在频道）的事件。</li>
 * </ul>
 * 权限为 {@code aeternum.events}；控制台发送者会回退到英文提示。</p>
 */
public final class EventCommand implements CommandExecutor, TabCompleter {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonalEventService events;

    public EventCommand(AeternumSeasonsPlugin plugin, SeasonalEventService events) {
        super();
        this.plugin = plugin;
        this.events = events;
    }

    /**
     * 处理 /event 命令：校验权限后按子命令分发。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        if (!sender.hasPermission("aeternum.events")) {
            if (player != null) {
                sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.no_permission"));
            } else {
                sender.sendMessage("[Aeternum] No permission.");
            }

            return true;
        } else if (args.length == 0) {
            this.sendUsage(sender, player, label);
            return true;
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("list".equals(sub)) {
                this.handleList(sender, player, label);
                return true;
            } else if ("info".equals(sub)) {
                this.handleInfo(sender, player, args, label);
                return true;
            } else if ("stop".equals(sub)) {
                CalendarChannel channel = player == null ? CalendarChannel.OVERWORLD : this.plugin.getSeasons().resolveChannel(player.getWorld());
                if (channel == null) {
                    channel = CalendarChannel.OVERWORLD;
                }

                if (args.length >= 2) {
                    try {
                        channel = CalendarChannel.valueOf(args[1].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        sender.sendMessage("Usage: /" + label + " stop [OVERWORLD|NETHER]");
                        return true;
                    }
                }

                this.events.forceStop(channel);
                if (player != null) {
                    sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.stopped"));
                } else {
                    sender.sendMessage("[Aeternum] Active event stopped.");
                }

                return true;
            } else if ("start".equals(sub)) {
                if (args.length < 2) {
                    this.sendUsage(sender, player, label);
                    return true;
                } else {
                    String id = args[1].toLowerCase(Locale.ROOT);
                    Integer days = null;
                    if (args.length >= 3) {
                        try {
                            days = Integer.parseInt(args[2]);
                        } catch (NumberFormatException exception) {
                        }
                    }

                    boolean ok = this.events.forceStart(id, days);
                    if (!ok) {
                        boolean known = false;

                        // 判断该 id 是否至少在某频道注册过，以便给出更准确的失败原因。
                        for (CalendarChannel channel : CalendarChannel.values()) {
                            known |= this.events.getEventById(channel, id) != null;
                        }

                        if (known) {
                            sender.sendMessage("[Aeternum] Could not start " + id + ". Check events.enabled, calendar channel enabled, and the server log.");
                        } else if (player != null) {
                            sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.no_such_event").replace("{id}", id));
                        } else {
                            sender.sendMessage("[Aeternum] Unknown event: " + id);
                        }

                        return true;
                    } else {
                        if (player != null) {
                            sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.started").replace("{id}", id));
                        } else {
                            sender.sendMessage("[Aeternum] Forced start of event: " + id);
                        }

                        return true;
                    }
                }
            } else {
                this.sendUsage(sender, player, label);
                return true;
            }
        }
    }

    /**
     * 发送命令用法提示（玩家走本地化文本，控制台走英文）。
     */
    private void sendUsage(CommandSender sender, Player player, String label) {
        if (player != null) {
            sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.usage").replace("{cmd}", "/" + label));
        } else {
            sender.sendMessage("Usage: /" + label + " <list|info|start|stop> [id] [days]");
        }

    }

    /**
     * 列出所有日历频道下已注册的事件及其显示名。
     */
    private void handleList(CommandSender sender, Player player, String label) {
        if (player != null) {
            sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.list.header"));

            for (CalendarChannel channel : CalendarChannel.values()) {
                for (String id : this.events.getRegisteredEventIds(channel)) {
                    SeasonalEvent ev = this.events.getEventById(channel, id);
                    if (ev != null) {
                        String line = "§8[§r" + this.channelWorldName(player, channel) + "§8] "
                                + this.plugin.lang.tr(player, "cmd.event.list.item")
                                .replace("{id}", id)
                                .replace("{name}", ev.getDisplayName());
                        sender.sendMessage(this.buildClickableEventLine(player, channel, id, line, label));
                    }
                }
            }
        } else {
            sender.sendMessage("Registered events:");

            for (CalendarChannel channel : CalendarChannel.values()) {
                for (String id : this.events.getRegisteredEventIds(channel)) {
                    SeasonalEvent ev = this.events.getEventById(channel, id);
                    if (ev != null) {
                        sender.sendMessage("[" + channel.name() + "] - " + id + " (" + ev.getDisplayName() + ")");
                    }
                }
            }
        }

    }

    /**
     * 返回频道对应的世界显示名（主世界 / 下界），用于在事件列表前标注事件所属世界。
     */
    private String channelWorldName(Player player, CalendarChannel channel) {
        if (channel == CalendarChannel.OVERWORLD) {
            return this.plugin.lang.trOr(player, "realm.overworld_title", "Overworld");
        }
        return this.plugin.lang.trOr(player, "realm.nether_title", "Nether");
    }

    /**
     * 构造一条可点击的事件列表项（悬停提示 + 点击执行 info 子命令查看详情）。
     */
    private BaseComponent[] buildClickableEventLine(Player player, CalendarChannel channel, String id, String line, String label) {
        TextComponent main = new TextComponent(line);
        String hover = this.plugin.lang.trOr(player, "cmd.event.list.click_hint", "&8(Click for details)");
        main.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hover).create()));
        main.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + label + " info " + channel.name() + " " + id));
        return new BaseComponent[]{main};
    }

    /**
     * 输出每个频道当前活跃事件的信息（含剩余天数）；若给定事件 id 则输出该事件详情。
     */
    private void handleInfo(CommandSender sender, Player player, String[] args, String label) {
        CalendarChannel filter = null;
        String targetId = null;
        if (args.length >= 3) {
            CalendarChannel ch = CalendarChannel.fromString(args[1]);
            if (ch != null) {
                filter = ch;
                targetId = args[2].toLowerCase(Locale.ROOT);
            } else {
                targetId = args[1].toLowerCase(Locale.ROOT);
            }
        } else if (args.length == 2) {
            String first = args[1];
            CalendarChannel ch = CalendarChannel.fromString(first);
            if (ch != null) {
                filter = ch;
            } else {
                targetId = first.toLowerCase(Locale.ROOT);
            }
        }

        if (targetId != null) {
            this.sendEventDetail(sender, player, filter, targetId, label);
            return;
        }

        for (CalendarChannel channel : CalendarChannel.values()) {
            if (filter == null || filter == channel) {
                SeasonalEvent ev = this.events.getActive(channel);
                if (ev == null) {
                    if (player != null) {
                        sender.sendMessage("[" + channel.name() + "] " + this.plugin.lang.tr(player, "cmd.event.no_active"));
                    } else {
                        sender.sendMessage("[Aeternum/" + channel.name() + "] No active event.");
                    }
                } else {
                    int days = this.events.getDaysRemaining(channel);
                    if (player != null) {
                        String msg = this.plugin.lang.tr(player, "cmd.event.info").replace("{id}", ev.getId()).replace("{name}", ev.getDisplayName()).replace("{days}", String.valueOf(days));
                        sender.sendMessage("[" + channel.name() + "] " + msg);
                    } else {
                        sender.sendMessage("[Aeternum/" + channel.name() + "] Active: " + ev.getId() + " (" + ev.getDisplayName() + "), days remaining=" + days);
                    }
                }
            }
        }

    }

    /**
     * 输出单个事件的详细信息（支持按频道或事件 id 过滤）。
     */
    private void sendEventDetail(CommandSender sender, Player player, CalendarChannel channel, String targetId, String label) {
        String normalized = targetId;
        if (targetId.equals("fishing")) {
            normalized = "fishing_festival";
        } else if (targetId.equals("mining")) {
            normalized = "mining_blessing";
        }

        CalendarChannel foundChannel = null;
        SeasonalEvent ev = null;
        CalendarChannel[] channels = channel != null ? new CalendarChannel[]{channel} : CalendarChannel.values();

        for (CalendarChannel ch : channels) {
            SeasonalEvent candidate = this.events.getEventById(ch, normalized);
            if (candidate != null) {
                foundChannel = ch;
                ev = candidate;
                break;
            }
        }

        if (ev == null) {
            if (player != null) {
                sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.no_such_event").replace("{id}", targetId));
            } else {
                sender.sendMessage("[Aeternum] Unknown event: " + targetId);
            }
            return;
        }

        String name = ev.getDisplayName();
        String channelId = foundChannel == null ? "?" : foundChannel.name();
        SeasonalEvent active = foundChannel == null ? null : this.events.getActive(foundChannel);
        boolean isActive = active != null && active.getId().equalsIgnoreCase(ev.getId());
        String daysStr = isActive ? String.valueOf(this.events.getDaysRemaining(foundChannel)) : "-";

        if (player == null) {
            sender.sendMessage("[Aeternum/" + channelId + "] " + ev.getId() + " (" + name + ")"
                    + " seasons=" + this.seasonList(ev)
                    + " duration=" + ev.getMinDurationDays() + "-" + ev.getMaxDurationDays() + "d"
                    + " active=" + isActive + (isActive ? " daysRemaining=" + daysStr : ""));
            return;
        }

        sender.sendMessage(this.plugin.lang.trf(player, "cmd.event.detail.header", Map.of(
                "name", name,
                "id", ev.getId(),
                "channel", channelId
        )));
        sender.sendMessage(this.plugin.lang.trf(player, "cmd.event.detail.seasons", Map.of(
                "seasons", this.seasonListTranslated(player, ev)
        )));
        sender.sendMessage(this.plugin.lang.trf(player, "cmd.event.detail.duration", Map.of(
                "min", String.valueOf(ev.getMinDurationDays()),
                "max", String.valueOf(ev.getMaxDurationDays())
        )));
        sender.sendMessage(this.plugin.lang.trf(player, "cmd.event.detail.desc", Map.of(
                "desc", this.plugin.lang.tr(player, "guide.events." + ev.getId())
        )));
        if (isActive) {
            sender.sendMessage(this.plugin.lang.trf(player, "cmd.event.detail.active", Map.of("days", daysStr)));
        } else {
            sender.sendMessage(this.plugin.lang.tr(player, "cmd.event.detail.inactive"));
        }
    }

    /**
     * 返回事件允许的季节列表（英文名，用于控制台展示）。
     */
    private String seasonList(SeasonalEvent ev) {
        List<String> out = new ArrayList();
        for (kinkin.aeternum.calendar.Season s : kinkin.aeternum.calendar.Season.values()) {
            if (ev.isSeasonAllowed(s)) {
                out.add(s.name());
            }
        }
        return String.join("/", out);
    }

    /**
     * 返回事件允许的季节列表（本地化名称，用于玩家展示）。
     */
    private String seasonListTranslated(Player player, SeasonalEvent ev) {
        List<String> out = new ArrayList();
        for (kinkin.aeternum.calendar.Season s : kinkin.aeternum.calendar.Season.values()) {
            if (ev.isSeasonAllowed(s)) {
                out.add(this.plugin.lang.trOr(player, "season." + s.name(), s.name()));
            }
        }
        // 季节名已由 trOr 转成实际颜色码；分隔符也必须用实际颜色码（§），
        // 否则 trf 只翻译模板、不翻译此处拼接的 & 代码，会原样显示 "&7, &f"。
        return String.join("§7, §f", out);
    }

    /**
     * 按子命令补全参数：子命令名、事件 id 或频道名。
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            for (String subName : new String[]{"list", "info", "start", "stop"}) {
                if (subName.startsWith(prefix)) {
                    out.add(subName);
                }
            }

            return out;
        } else if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);

            for (CalendarChannel channel : CalendarChannel.values()) {
                for (String id : this.events.getRegisteredEventIds(channel)) {
                    if (id.startsWith(prefix)) {
                        out.add(id);
                    }
                }
            }

            return out;
        } else if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);

            for (CalendarChannel channel : CalendarChannel.values()) {
                for (String id : this.events.getRegisteredEventIds(channel)) {
                    if (id.startsWith(prefix)) {
                        out.add(id);
                    }
                }
            }

            for (CalendarChannel channel : CalendarChannel.values()) {
                if (channel.name().toLowerCase(Locale.ROOT).startsWith(prefix) && !out.contains(channel.name())) {
                    out.add(channel.name());
                }
            }

            return out;
        } else {
            if (args.length == 2 && "stop".equalsIgnoreCase(args[0])) {
                for (CalendarChannel channel : CalendarChannel.values()) {
                    if (channel.name().startsWith(args[1].toUpperCase(Locale.ROOT))) {
                        out.add(channel.name());
                    }
                }
            }

            return out;
        }
    }
}
