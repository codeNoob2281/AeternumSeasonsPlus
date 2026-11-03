package kinkin.aeternum.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * YAML 默认值合并工具。
 *
 * <p>确保数据目录下的配置文件存在，并把插件 jar 内资源中的默认键补全到现有文件（不覆盖已有值）。
 * 对 climate.yml 还包含若干历史配置迁移（键迁移、过时键清理、默认值修正）。</p>
 *
 * <p>工具类：私有构造，方法均为静态。</p>
 */
public final class YamlDefaults {
    private YamlDefaults() {
        super();
    }

    /**
     * 合并指定文件的默认值：文件不存在则直接从 jar 释放；存在则读取 jar 内默认值，
     * 补全缺失键、执行 climate.yml 的历史迁移，必要时写回文件。
     */
    public static void merge(JavaPlugin plugin, String fileName) {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            File outFile = new File(plugin.getDataFolder(), fileName);
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (!outFile.exists()) {
                plugin.saveResource(fileName, false);
                return;
            }

            YamlConfiguration current = YamlConfiguration.loadConfiguration(outFile);
            InputStream in = plugin.getResource(fileName);
            if (in == null) {
                return;
            }

            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);

            YamlConfiguration def;
            try {
                def = YamlConfiguration.loadConfiguration(r);
            } catch (Throwable loadFailure) {
                try {
                    r.close();
                } catch (Throwable closeFailure) {
                    loadFailure.addSuppressed(closeFailure);
                }

                throw loadFailure;
            }

            r.close();
            boolean changed = false;
            if ("climate.yml".equals(fileName) && !current.contains("biome_spoof.client_refresh.use_biome_packets") && current.getInt("biome_spoof.client_refresh.interval_ticks", 3) == 3 && current.getInt("biome_spoof.client_refresh.chunks_per_interval", 1) == 1) {
                current.set("biome_spoof.client_refresh.interval_ticks", 1);
                current.set("biome_spoof.client_refresh.chunks_per_interval", 12);
                changed = true;
            }

            if ("climate.yml".equals(fileName)) {
                for (String obsolete : new String[]{"autumn_foliage", "autumn_foilage"}) {
                    if (current.contains(obsolete)) {
                        current.set(obsolete, (Object) null);
                        changed = true;
                    }
                }

                String oldAutumn = current.getString("biome_spoof.seasons.AUTUMN", "");
                if ("WINDSWEPT_SAVANNA".equalsIgnoreCase(oldAutumn)) {
                    current.set("biome_spoof.seasons.AUTUMN", "AUTO");
                    changed = true;
                }

                if (!current.contains("biome_spoof.pre_transition.enabled")) {
                    current.set("biome_spoof.pre_transition.enabled", true);
                    current.set("biome_spoof.pre_transition.affect_oceans", false);
                    changed = true;
                }

                if (!current.getBoolean("seasonal_flora.rules.bamboo.enabled", false) && current.getBoolean("seasonal_flora.rules.bamboo.purge_when_disabled", false)) {
                    current.set("seasonal_flora.rules.bamboo.purge_when_disabled", false);
                    changed = true;
                }

                if (!current.contains("villager_type_overrides.appearance.override_chance") && current.contains("villager_type_overrides.override_chance")) {
                    current.set("villager_type_overrides.appearance.override_chance", current.getDouble("villager_type_overrides.override_chance", (double) 1.0F));
                    changed = true;
                }

                if (!current.contains("villager_type_overrides.profession_rotation.show_lazy_tag") && current.contains("villager_type_overrides.show_lazy_tag")) {
                    current.set("villager_type_overrides.profession_rotation.show_lazy_tag", current.getBoolean("villager_type_overrides.show_lazy_tag", true));
                    changed = true;
                }
            }

            for (String key : def.getKeys(true)) {
                if (!current.contains(key)) {
                    current.set(key, def.get(key));
                    changed = true;
                }
            }

            if (changed) {
                current.save(outFile);
                plugin.getLogger().info("[AeternumSeasons] Updated defaults merged into " + fileName);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[AeternumSeasons] Could not merge defaults for " + fileName + ": " + ex.getMessage());
        }

    }
}
