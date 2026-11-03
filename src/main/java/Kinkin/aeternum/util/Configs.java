package kinkin.aeternum.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * 配置文件集合管理器。
 *
 * <p>统一负责本插件各个 YAML 配置（calendar、config、hud、climate、survival、fauna、crops、events）
 * 的加载、默认值合并与保存。加载时会从插件 jar 内的同名资源读取默认值，作为缺失键的兜底。</p>
 *
 * <p>依赖：Bukkit {@link YamlConfiguration}。各配置对象以 public 字段暴露供其他服务直接读取。</p>
 */
public final class Configs {
    private final Plugin plugin;
    /**
     * 日历状态配置（calendar.yml）。
     */
    public FileConfiguration calendar;
    /**
     * 主配置（config.yml）。
     */
    public FileConfiguration config;
    /**
     * HUD 配置（hud.yml）。
     */
    public FileConfiguration hud;
    /**
     * 气候配置（climate.yml）。
     */
    public FileConfiguration climate;
    /**
     * 生存配置（survival.yml）。
     */
    public FileConfiguration survival;
    /**
     * 动物配置（fauna.yml）。
     */
    public FileConfiguration fauna;
    /**
     * 作物配置（crops.yml）。
     */
    public FileConfiguration crops;
    /**
     * 事件配置（events.yml）。
     */
    public FileConfiguration events;

    public Configs(Plugin plugin) {
        super();
        this.plugin = plugin;
    }

    /**
     * 加载全部配置文件（不存在时先从 jar 释放默认文件）。
     */
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

    /**
     * 加载单个 YAML 文件，并把 jar 内的默认值合并为兜底（copyDefaults）。
     */
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
            } catch (Throwable loadFailure) {
                try {
                    reader.close();
                } catch (Throwable closeFailure) {
                    loadFailure.addSuppressed(closeFailure);
                }

                throw loadFailure;
            }

            reader.close();
        } catch (Exception ignored) {
        }

        return cfg;
    }

    /**
     * 将指定配置保存到数据目录下的同名文件。
     */
    public void save(String name, FileConfiguration cfg) {
        try {
            cfg.options().copyDefaults(true);
            cfg.save(new File(this.plugin.getDataFolder(), name));
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to save config: " + name, e);
        }

    }

    /**
     * 保存全部配置文件。
     */
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
