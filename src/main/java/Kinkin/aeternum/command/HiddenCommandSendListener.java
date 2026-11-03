package kinkin.aeternum.command;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

/**
 * 隐藏命令发送监听器。
 *
 * <p>用于把 {@code /seasonbiomefix} 从客户端可见的命令列表中移除，避免玩家在
 * 补全下拉里看到这个内部维护命令。仅作用于命令建议列表（{@link PlayerCommandSendEvent}），
 * 不影响命令本身的注册与执行。</p>
 */
public final class HiddenCommandSendListener implements Listener {
    /**
     * 需要隐藏的命令名。
     */
    private static final String CMD = "seasonbiomefix";

    public HiddenCommandSendListener() {
        super();
    }

    /**
     * 玩家收到命令列表时，剔除本插件隐藏命令（同时兼容带命名空间前缀的形式）。
     */
    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().remove("seasonbiomefix");
        event.getCommands().removeIf((command) -> "seasonbiomefix".equalsIgnoreCase(command) || command.toLowerCase().endsWith(":seasonbiomefix"));
    }
}
