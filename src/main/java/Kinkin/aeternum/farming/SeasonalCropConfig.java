package kinkin.aeternum.farming;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.Season;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * 季节作物配置。
 *
 * <p>负责从 {@code crops.yml} 读取季节性作物相关配置，包括生长速度修正、
 * 温室识别参数、调试工具以及每种作物允许种植的季节集合。</p>
 * <p>实例通过 {@link #reload()} 重新读取磁盘配置；其余方法仅暴露读取结果。</p>
 */
public final class SeasonalCropConfig {
    private final AeternumSeasonsPlugin plugin;
    private boolean enabled;
    private double rainBonus;
    private double winterGreenhouseBonus;
    private double undergroundMultiplier;
    private double lowLightMultiplier;
    private int requiredLight;
    private double offSeasonMultiplier;
    private boolean greenhouseEnabled;
    private Set<Material> greenhouseBlocks;
    private int maxRoofHeight;
    private int greenhouseRadius;
    private int greenhouseMinGlass;
    private boolean greenhouseRequireCore;
    private Material greenhouseCoreBlock;
    private boolean debugEnabled;
    private Material debugStickMaterial;
    private final Map<Material, EnumSet<Season>> cropSeasons = new HashMap();

    public SeasonalCropConfig(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.reload();
    }

    /**
     * 从 crops.yml 重新读取全部配置项；文件不存在时会先尝试导出默认资源。
     */
    public void reload() {
        File file = new File(this.plugin.getDataFolder(), "crops.yml");
        if (!file.exists()) {
            try {
                this.plugin.saveResource("crops.yml", false);
            } catch (IllegalArgumentException ignored) {
            }
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        this.enabled = cfg.getBoolean("seasonal_crops.enabled", true);
        this.rainBonus = cfg.getDouble("seasonal_crops.rain_growth_bonus", (double) 1.0F);
        this.winterGreenhouseBonus = cfg.getDouble("seasonal_crops.greenhouse.winter_bonus", (double) 1.0F);
        this.offSeasonMultiplier = cfg.getDouble("seasonal_crops.off_season_growth_chance", (double) 0.0F);
        this.undergroundMultiplier = cfg.getDouble("seasonal_crops.underground_slow_multiplier", 0.3);
        this.lowLightMultiplier = cfg.getDouble("seasonal_crops.low_light_multiplier", (double) 0.5F);
        this.requiredLight = cfg.getInt("seasonal_crops.required_light", 9);
        this.greenhouseEnabled = cfg.getBoolean("seasonal_crops.greenhouse.enabled", true);
        this.maxRoofHeight = cfg.getInt("seasonal_crops.greenhouse.max_roof_height", 8);
        this.greenhouseRadius = cfg.getInt("seasonal_crops.greenhouse.radius", 7);
        this.greenhouseMinGlass = cfg.getInt("seasonal_crops.greenhouse.min_glass_count", 12);
        this.greenhouseRequireCore = cfg.getBoolean("seasonal_crops.greenhouse.require_core", false);
        String coreId = cfg.getString("seasonal_crops.greenhouse.core_block", "CARTOGRAPHY_TABLE");

        try {
            this.greenhouseCoreBlock = Material.valueOf(coreId.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            this.greenhouseCoreBlock = null;
        }

        this.greenhouseBlocks = new HashSet();

        for (String id : cfg.getStringList("seasonal_crops.greenhouse.block_types")) {
            try {
                this.greenhouseBlocks.add(Material.valueOf(id.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        this.debugEnabled = cfg.getBoolean("seasonal_crops.greenhouse.debug.enabled", true);
        String stickId = cfg.getString("seasonal_crops.greenhouse.debug.stick", "STICK");

        try {
            this.debugStickMaterial = Material.valueOf(stickId.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            this.debugStickMaterial = Material.STICK;
        }

        this.cropSeasons.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("seasonal_crops.crops");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase(Locale.ROOT));
                    List<String> allowed = sec.getStringList(key + ".allowed_seasons");
                    EnumSet<Season> set = EnumSet.noneOf(Season.class);

                    for (String s : allowed) {
                        try {
                            set.add(Season.valueOf(s.toUpperCase(Locale.ROOT)));
                        } catch (Exception ignored) {
                        }
                    }

                    this.cropSeasons.put(mat, set);
                } catch (Exception ignored) {
                    this.plugin.getLogger().warning("[Crops] Tipo de cultivo no reconocido: " + key);
                }
            }
        }

    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public double getRainBonus() {
        return this.rainBonus;
    }

    public double getWinterGreenhouseBonus() {
        return this.winterGreenhouseBonus;
    }

    public double getUndergroundMultiplier() {
        return this.undergroundMultiplier;
    }

    public double getLowLightMultiplier() {
        return this.lowLightMultiplier;
    }

    public int getRequiredLight() {
        return this.requiredLight;
    }

    public boolean isGreenhouseEnabled() {
        return this.greenhouseEnabled;
    }

    public Set<Material> getGreenhouseBlocks() {
        return this.greenhouseBlocks;
    }

    public int getMaxRoofHeight() {
        return this.maxRoofHeight;
    }

    public int getGreenhouseRadius() {
        return this.greenhouseRadius;
    }

    public int getGreenhouseMinGlass() {
        return this.greenhouseMinGlass;
    }

    public boolean isGreenhouseRequireCore() {
        return this.greenhouseRequireCore;
    }

    public Material getGreenhouseCoreBlock() {
        return this.greenhouseCoreBlock;
    }

    public boolean isDebugEnabled() {
        return this.debugEnabled;
    }

    public Material getDebugStickMaterial() {
        return this.debugStickMaterial;
    }

    /**
     * 返回指定作物允许的季节集合；若该作物不受管理则返回 null。
     */
    public EnumSet<Season> getAllowedSeasons(Material mat) {
        return (EnumSet) this.cropSeasons.get(mat);
    }

    /**
     * 判断指定作物是否受本配置管理。
     */
    public boolean isManagedCrop(Material mat) {
        return this.cropSeasons.containsKey(mat);
    }

    public double getOffSeasonMultiplier() {
        return this.offSeasonMultiplier;
    }
}
