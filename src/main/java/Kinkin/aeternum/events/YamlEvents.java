package Kinkin.aeternum.events;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlEvents {
    private static FileConfiguration cached;

    private YamlEvents() {
        super();
    }

    public static FileConfiguration get(AeternumSeasonsPlugin plugin) {
        if (cached != null) {
            return cached;
        } else {
            File f = new File(plugin.getDataFolder(), "events.yml");
            if (!f.exists()) {
                try {
                    plugin.saveResource("events.yml", false);
                } catch (IllegalArgumentException var3) {
                }
            }

            cached = YamlConfiguration.loadConfiguration(f);
            return cached;
        }
    }

    public static void reload(AeternumSeasonsPlugin plugin) {
        cached = null;
        get(plugin);
    }
}
