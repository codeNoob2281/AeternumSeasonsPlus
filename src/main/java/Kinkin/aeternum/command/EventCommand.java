package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarChannel;
import Kinkin.aeternum.events.SeasonalEvent;
import Kinkin.aeternum.events.SeasonalEventService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class EventCommand implements CommandExecutor, TabCompleter {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonalEventService events;

    public EventCommand(AeternumSeasonsPlugin plugin, SeasonalEventService events) {
        super();
        this.plugin = plugin;
        this.events = events;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player p = sender instanceof Player ? (Player)sender : null;
        if (!sender.hasPermission("aeternum.events")) {
            if (p != null) {
                sender.sendMessage(this.plugin.lang.tr(p, "cmd.event.no_permission"));
            } else {
                sender.sendMessage("[Aeternum] No permission.");
            }

            return true;
        } else if (args.length == 0) {
            this.sendUsage(sender, p, label);
            return true;
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) {
                this.handleList(sender, p);
                return true;
            } else if (sub.equals("info")) {
                this.handleInfo(sender, p);
                return true;
            } else if (sub.equals("stop")) {
                CalendarChannel channel = p == null ? CalendarChannel.OVERWORLD : this.plugin.getSeasons().resolveChannel(p.getWorld());
                if (channel == null) {
                    channel = CalendarChannel.OVERWORLD;
                }

                if (args.length >= 2) {
                    try {
                        channel = CalendarChannel.valueOf(args[1].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException var15) {
                        sender.sendMessage("Usage: /" + label + " stop [OVERWORLD|NETHER]");
                        return true;
                    }
                }

                this.events.forceStop(channel);
                if (p != null) {
                    sender.sendMessage(this.plugin.lang.tr(p, "cmd.event.stopped"));
                } else {
                    sender.sendMessage("[Aeternum] Active event stopped.");
                }

                return true;
            } else if (sub.equals("start")) {
                if (args.length < 2) {
                    this.sendUsage(sender, p, label);
                    return true;
                } else {
                    String id = args[1].toLowerCase(Locale.ROOT);
                    Integer days = null;
                    if (args.length >= 3) {
                        try {
                            days = Integer.parseInt(args[2]);
                        } catch (NumberFormatException var16) {
                        }
                    }

                    boolean ok = this.events.forceStart(id, days);
                    if (!ok) {
                        boolean known = false;

                        for(CalendarChannel channel : CalendarChannel.values()) {
                            known |= this.events.getEventById(channel, id) != null;
                        }

                        if (known) {
                            sender.sendMessage("[Aeternum] Could not start " + id + ". Check events.enabled, calendar channel enabled, and the server log.");
                        } else if (p != null) {
                            sender.sendMessage(this.plugin.lang.tr(p, "cmd.event.no_such_event").replace("{id}", id));
                        } else {
                            sender.sendMessage("[Aeternum] Unknown event: " + id);
                        }

                        return true;
                    } else {
                        if (p != null) {
                            sender.sendMessage(this.plugin.lang.tr(p, "cmd.event.started").replace("{id}", id));
                        } else {
                            sender.sendMessage("[Aeternum] Forced start of event: " + id);
                        }

                        return true;
                    }
                }
            } else {
                this.sendUsage(sender, p, label);
                return true;
            }
        }
    }

    private void sendUsage(CommandSender sender, Player p, String label) {
        if (p != null) {
            sender.sendMessage(this.plugin.lang.tr(p, "cmd.event.usage").replace("{cmd}", "/" + label));
        } else {
            sender.sendMessage("Usage: /" + label + " <list|info|start|stop> [id] [days]");
        }

    }

    private void handleList(CommandSender sender, Player p) {
        String header = p != null ? this.plugin.lang.tr(p, "cmd.event.list.header") : "Registered events:";
        sender.sendMessage(header);

        for(CalendarChannel channel : CalendarChannel.values()) {
            for(String id : this.events.getRegisteredEventIds(channel)) {
                SeasonalEvent ev = this.events.getEventById(channel, id);
                String line;
                if (p != null) {
                    line = this.plugin.lang.tr(p, "cmd.event.list.item").replace("{id}", id).replace("{name}", ev.getDisplayName());
                } else {
                    line = "- " + id + " (" + ev.getDisplayName() + ")";
                }

                String var10001 = channel.name();
                sender.sendMessage("[" + var10001 + "] " + line);
            }
        }

    }

    private void handleInfo(CommandSender sender, Player p) {
        for(CalendarChannel channel : CalendarChannel.values()) {
            SeasonalEvent ev = this.events.getActive(channel);
            if (ev == null) {
                if (p != null) {
                    String var10001 = channel.name();
                    sender.sendMessage("[" + var10001 + "] " + this.plugin.lang.tr(p, "cmd.event.no_active"));
                } else {
                    sender.sendMessage("[Aeternum/" + channel.name() + "] No active event.");
                }
            } else {
                int days = this.events.getDaysRemaining(channel);
                if (p != null) {
                    String msg = this.plugin.lang.tr(p, "cmd.event.info").replace("{id}", ev.getId()).replace("{name}", ev.getDisplayName()).replace("{days}", String.valueOf(days));
                    String var10 = channel.name();
                    sender.sendMessage("[" + var10 + "] " + msg);
                } else {
                    String var11 = channel.name();
                    sender.sendMessage("[Aeternum/" + var11 + "] Active: " + ev.getId() + " (" + ev.getDisplayName() + "), days remaining=" + days);
                }
            }
        }

    }

    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);

            for(String s : new String[]{"list", "info", "start", "stop"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }

            return out;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            String p = args[1].toLowerCase(Locale.ROOT);

            for(CalendarChannel channel : CalendarChannel.values()) {
                for(String id : this.events.getRegisteredEventIds(channel)) {
                    if (id.startsWith(p)) {
                        out.add(id);
                    }
                }
            }

            return out;
        } else {
            if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
                for(CalendarChannel channel : CalendarChannel.values()) {
                    if (channel.name().startsWith(args[1].toUpperCase(Locale.ROOT))) {
                        out.add(channel.name());
                    }
                }
            }

            return out;
        }
    }
}
