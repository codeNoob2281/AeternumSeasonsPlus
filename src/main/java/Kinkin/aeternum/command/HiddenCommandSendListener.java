package Kinkin.aeternum.command;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

public final class HiddenCommandSendListener implements Listener {
    private static final String CMD = "seasonbiomefix";

    public HiddenCommandSendListener() {
        super();
    }

    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent e) {
        e.getCommands().remove("seasonbiomefix");
        e.getCommands().removeIf((s) -> s.equalsIgnoreCase("seasonbiomefix") || s.toLowerCase().endsWith(":seasonbiomefix"));
    }
}
