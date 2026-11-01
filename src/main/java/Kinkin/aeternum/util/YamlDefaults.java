package Kinkin.aeternum.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class YamlDefaults {
    private YamlDefaults() {
        super();
    }

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
            } catch (Throwable var13) {
                try {
                    r.close();
                } catch (Throwable var12) {
                    var13.addSuppressed(var12);
                }

                throw var13;
            }

            r.close();
            boolean changed = false;
            if ("climate.yml".equals(fileName) && !current.contains("biome_spoof.client_refresh.use_biome_packets") && current.getInt("biome_spoof.client_refresh.interval_ticks", 3) == 3 && current.getInt("biome_spoof.client_refresh.chunks_per_interval", 1) == 1) {
                current.set("biome_spoof.client_refresh.interval_ticks", 1);
                current.set("biome_spoof.client_refresh.chunks_per_interval", 12);
                changed = true;
            }

            if ("climate.yml".equals(fileName)) {
                for(String obsolete : new String[]{"autumn_foliage", "autumn_foilage"}) {
                    if (current.contains(obsolete)) {
                        current.set(obsolete, (Object)null);
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
                    current.set("villager_type_overrides.appearance.override_chance", current.getDouble("villager_type_overrides.override_chance", (double)1.0F));
                    changed = true;
                }

                if (!current.contains("villager_type_overrides.profession_rotation.show_lazy_tag") && current.contains("villager_type_overrides.show_lazy_tag")) {
                    current.set("villager_type_overrides.profession_rotation.show_lazy_tag", current.getBoolean("villager_type_overrides.show_lazy_tag", true));
                    changed = true;
                }
            }

            for(String key : def.getKeys(true)) {
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
