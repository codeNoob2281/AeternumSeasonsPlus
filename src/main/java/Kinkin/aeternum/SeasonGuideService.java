package Kinkin.aeternum;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 季节指南首次登录推送服务。
 *
 * <p>负责持久化“玩家是否已经看过季节指南”的标记，并在玩家首次加入服务器时，
 * 延迟一小段时间后自动向其发送一次 {@link SeasonGuide#sendGuide} 指南。</p>
 *
 * <p>数据保存在插件数据目录下的 {@code data/players.yml} 中，键为
 * {@code players.<uuid>.seen_guide}。本类同时是一个 Bukkit 事件监听器，
 * 由主类在启动时通过 {@link #register()} 注册、关闭时通过 {@link #unregister()} 注销。</p>
 */
public final class SeasonGuideService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    /**
     * 构造服务并加载（或创建）玩家数据文件。
     *
     * <p>若父目录或文件不存在则先创建，避免后续保存失败。</p>
     */
    public SeasonGuideService(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/players.yml");
        if (!this.file.getParentFile().exists()) {
            this.file.getParentFile().mkdirs();
        }

        if (!this.file.exists()) {
            this.data = new YamlConfiguration();
            this.save();
        } else {
            this.data = YamlConfiguration.loadConfiguration(this.file);
        }

    }

    /** 把本服务注册为 Bukkit 事件监听器。 */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /** 注销所有事件监听并落盘玩家数据。 */
    public void unregister() {
        HandlerList.unregisterAll(this);
        this.save();
    }

    /** 判断指定玩家是否已经看过季节指南。 */
    private boolean hasSeenGuide(UUID id) {
        return this.data.getBoolean("players." + String.valueOf(id) + ".seen_guide", false);
    }

    /** 标记指定玩家已经看过季节指南并立即保存。 */
    private void markSeenGuide(UUID id) {
        this.data.set("players." + String.valueOf(id) + ".seen_guide", true);
        this.save();
    }

    /** 将玩家数据写入磁盘，失败时仅记录警告，不影响主流程。 */
    private void save() {
        try {
            this.data.save(this.file);
        } catch (IOException exception) {
            this.plugin.getLogger().warning("No se pudo guardar players.yml: " + exception.getMessage());
        }

    }

    /**
     * 监听玩家加入：若该玩家尚未看过指南，则先记录标记，再延迟 60 tick（约 3 秒）后发送。
     *
     * <p>延迟发送可避免与其它登录提示抢占聊天栏；发送前会再次检查玩家是否仍在线。</p>
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (!this.hasSeenGuide(player.getUniqueId())) {
            this.markSeenGuide(player.getUniqueId());
            (new BukkitRunnable() {
                public void run() {
                    if (player.isOnline()) {
                        SeasonGuide.sendGuide(player, SeasonGuideService.this.plugin);
                    }
                }
            }).runTaskLater(this.plugin, 60L);
        }
    }
}
