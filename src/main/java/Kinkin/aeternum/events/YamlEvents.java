package kinkin.aeternum.events;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * {@code events.yml} 的加载与缓存工具。
 *
 * <p>首次调用 {@link #get(AeternumSeasonsPlugin)} 时会从插件数据目录读取（不存在则
 * 先释放内置默认资源），并缓存 {@link FileConfiguration} 供各事件复用；
 * {@link #reload(AeternumSeasonsPlugin)} 会清空缓存以便重新加载。</p>
 */
public final class YamlEvents {
    /**
     * 已加载的配置缓存，null 表示尚未加载。
     */
    private static FileConfiguration cached;

    private YamlEvents() {
        super();
    }

    /**
     * 返回 events.yml 的配置对象（按需加载并缓存）。
     */
    public static FileConfiguration get(AeternumSeasonsPlugin plugin) {
        if (cached != null) {
            return cached;
        } else {
            File file = new File(plugin.getDataFolder(), "events.yml");
            if (!file.exists()) {
                try {
                    plugin.saveResource("events.yml", false);
                } catch (IllegalArgumentException ignored) {
                }
            }

            cached = YamlConfiguration.loadConfiguration(file);
            return cached;
        }
    }

    /**
     * 清空缓存并重新加载 events.yml。
     */
    public static void reload(AeternumSeasonsPlugin plugin) {
        cached = null;
        get(plugin);
    }
}
