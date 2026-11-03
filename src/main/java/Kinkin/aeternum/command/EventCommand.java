package kinkin.aeternum.command;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarChannel;
import kinkin.aeternum.events.SeasonalEvent;
import kinkin.aeternum.events.SeasonalEventService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                this.handleList(sender, player);
                return true;
            } else if ("info".equals(sub)) {
                this.handleInfo(sender, player);
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
    private void handleList(CommandSender sender, Player player) {
        String header = player != null ? this.plugin.lang.tr(player, "cmd.event.list.header") : "Registered events:";
        sender.sendMessage(header);

        for (CalendarChannel channel : CalendarChannel.values()) {
            for (String id : this.events.getRegisteredEventIds(channel)) {
                SeasonalEvent ev = this.events.getEventById(channel, id);
                String line;
                if (player != null) {
                    line = this.plugin.lang.tr(player, "cmd.event.list.item").replace("{id}", id).replace("{name}", ev.getDisplayName());
                } else {
                    line = "- " + id + " (" + ev.getDisplayName() + ")";
                }

                String channelName = channel.name();
                sender.sendMessage("[" + channelName + "] " + line);
            }
        }

    }

    /**
     * 输出每个频道当前活跃事件的信息（含剩余天数）。
     */
    private void handleInfo(CommandSender sender, Player player) {
        for (CalendarChannel channel : CalendarChannel.values()) {
            SeasonalEvent ev = this.events.getActive(channel);
            if (ev == null) {
                if (player != null) {
                    String channelName = channel.name();
                    sender.sendMessage("[" + channelName + "] " + this.plugin.lang.tr(player, "cmd.event.no_active"));
                } else {
                    sender.sendMessage("[Aeternum/" + channel.name() + "] No active event.");
                }
            } else {
                int days = this.events.getDaysRemaining(channel);
                if (player != null) {
                    String msg = this.plugin.lang.tr(player, "cmd.event.info").replace("{id}", ev.getId()).replace("{name}", ev.getDisplayName()).replace("{days}", String.valueOf(days));
                    String channelName = channel.name();
                    sender.sendMessage("[" + channelName + "] " + msg);
                } else {
                    String channelName = channel.name();
                    sender.sendMessage("[Aeternum/" + channelName + "] Active: " + ev.getId() + " (" + ev.getDisplayName() + "), days remaining=" + days);
                }
            }
        }

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
