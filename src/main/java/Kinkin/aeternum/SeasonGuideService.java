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

public final class SeasonGuideService implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

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

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        this.save();
    }

    private boolean hasSeenGuide(UUID id) {
        return this.data.getBoolean("players." + String.valueOf(id) + ".seen_guide", false);
    }

    private void markSeenGuide(UUID id) {
        this.data.set("players." + String.valueOf(id) + ".seen_guide", true);
        this.save();
    }

    private void save() {
        try {
            this.data.save(this.file);
        } catch (IOException e) {
            this.plugin.getLogger().warning("No se pudo guardar players.yml: " + e.getMessage());
        }

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!this.hasSeenGuide(p.getUniqueId())) {
            this.markSeenGuide(p.getUniqueId());
            (new BukkitRunnable() {
                public void run() {
                    if (p.isOnline()) {
                        SeasonGuide.sendGuide(p, SeasonGuideService.this.plugin);
                    }
                }
            }).runTaskLater(this.plugin, 60L);
        }
    }
}
