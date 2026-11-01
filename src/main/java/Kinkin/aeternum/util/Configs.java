package Kinkin.aeternum.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class Configs {
    private final Plugin plugin;
    public FileConfiguration calendar;
    public FileConfiguration config;
    public FileConfiguration hud;
    public FileConfiguration climate;
    public FileConfiguration survival;
    public FileConfiguration fauna;
    public FileConfiguration crops;
    public FileConfiguration events;

    public Configs(Plugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void loadAll() {
        this.calendar = this.load("calendar.yml");
        this.config = this.load("config.yml");
        this.hud = this.load("hud.yml");
        this.climate = this.load("climate.yml");
        this.survival = this.load("survival.yml");
        this.fauna = this.load("fauna.yml");
        this.crops = this.load("crops.yml");
        this.events = this.load("events.yml");
    }

    private FileConfiguration load(String name) {
        File f = new File(this.plugin.getDataFolder(), name);
        if (!f.exists()) {
            this.plugin.saveResource(name, false);
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        try {
            InputStreamReader reader = new InputStreamReader(this.plugin.getResource(name), StandardCharsets.UTF_8);

            try {
                YamlConfiguration defaultCfg = YamlConfiguration.loadConfiguration(reader);
                cfg.setDefaults(defaultCfg);
                cfg.options().copyDefaults(true);
            } catch (Throwable var8) {
                try {
                    reader.close();
                } catch (Throwable var7) {
                    var8.addSuppressed(var7);
                }

                throw var8;
            }

            reader.close();
        } catch (Exception var9) {
        }

        return cfg;
    }

    public void save(String name, FileConfiguration cfg) {
        try {
            cfg.options().copyDefaults(true);
            cfg.save(new File(this.plugin.getDataFolder(), name));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void saveAll() {
        this.save("calendar.yml", this.calendar);
        this.save("hud.yml", this.hud);
        this.save("config.yml", this.config);
        this.save("climate.yml", this.climate);
        this.save("survival.yml", this.survival);
        this.save("fauna.yml", this.fauna);
        this.save("crops.yml", this.crops);
        this.save("events.yml", this.events);
    }
}
