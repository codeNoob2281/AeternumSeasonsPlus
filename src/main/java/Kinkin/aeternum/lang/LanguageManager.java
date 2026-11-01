package Kinkin.aeternum.lang;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;

public final class LanguageManager implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final Set<String> enabled = new LinkedHashSet();
    private String def;
    private final Map<String, FileConfiguration> bundles = new HashMap();

    public LanguageManager(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        this.loadIndex();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    private void loadIndex() {
        File idx = new File(this.plugin.getDataFolder(), "lang.yml");
        if (!idx.exists()) {
            this.plugin.saveResource("lang.yml", false);
        }

        FileConfiguration y = YamlConfiguration.loadConfiguration(idx);
        this.enabled.clear();
        this.enabled.addAll(y.getStringList("enabled"));
        this.def = y.getString("default", "auto");
        this.bundles.clear();

        for(String code : this.enabled) {
            File f = new File(this.plugin.getDataFolder(), "lang/" + code + ".yml");
            if (!f.exists()) {
                this.plugin.saveResource("lang/" + code + ".yml", false);
            }

            this.bundles.put(code, YamlConfiguration.loadConfiguration(f));
        }

        this.bundles.computeIfAbsent("en_US", (k) -> {
            File f = new File(this.plugin.getDataFolder(), "lang/en_US.yml");
            if (!f.exists()) {
                this.plugin.saveResource("lang/en_US.yml", false);
            }

            return YamlConfiguration.loadConfiguration(f);
        });
    }

    public String resolve(Player p) {
        if (p == null) {
            if (this.enabled.contains("en_US")) {
                return "en_US";
            } else {
                return !this.enabled.isEmpty() ? (String)this.enabled.iterator().next() : "en_US";
            }
        } else {
            String raw = p.getLocale();
            String wanted = this.safe(raw);
            if (wanted != null && this.enabled.contains(wanted)) {
                return wanted;
            } else {
                if (wanted != null) {
                    String lang = wanted.substring(0, 2).toLowerCase(Locale.ROOT);

                    for(String code : this.enabled) {
                        if (code.toLowerCase(Locale.ROOT).startsWith(lang + "_")) {
                            return code;
                        }
                    }
                }

                if (!"auto".equalsIgnoreCase(this.def) && this.enabled.contains(this.def)) {
                    return this.def;
                } else {
                    return this.enabled.contains("en_US") ? "en_US" : (String)this.enabled.stream().findFirst().orElse("en_US");
                }
            }
        }
    }

    private String safe(String s) {
        if (s == null) {
            return "en_US";
        } else {
            String tmp = s.replace('-', '_');
            if (tmp.contains("_")) {
                String[] parts = tmp.split("_", 2);
                String lang = parts[0].toLowerCase(Locale.ROOT);
                String country = parts[1].toUpperCase(Locale.ROOT);
                return lang + "_" + country;
            } else {
                return tmp.toLowerCase(Locale.ROOT);
            }
        }
    }

    public String tr(Player p, String key) {
        String code = this.resolve(p);
        FileConfiguration b = (FileConfiguration)this.bundles.getOrDefault(code, (FileConfiguration)this.bundles.get("en_US"));
        String v = b.getString(key);
        if (v == null || v.isBlank()) {
            String en = ((FileConfiguration)this.bundles.get("en_US")).getString(key);
            if (en != null && !en.isBlank()) {
                v = en;
            } else {
                v = key;
            }
        }

        return ChatColor.translateAlternateColorCodes('&', v);
    }

    public String trOr(Player p, String key, String fallback) {
        String v = this.tr(p, key);
        return v != null && !v.isBlank() && !v.equals(key) ? v : org.bukkit.ChatColor.translateAlternateColorCodes('&', fallback);
    }

    public String trServer(String key) {
        String code = this.resolve((Player)null);
        FileConfiguration b = (FileConfiguration)this.bundles.getOrDefault(code, (FileConfiguration)this.bundles.get("en_US"));
        String v = b.getString(key);
        if (v == null || v.isBlank()) {
            String en = ((FileConfiguration)this.bundles.get("en_US")).getString(key);
            if (en != null && !en.isBlank()) {
                v = en;
            } else {
                v = key;
            }
        }

        return ChatColor.translateAlternateColorCodes('&', v);
    }

    public Set<String> getAllTranslations(String key) {
        Set<String> translations = new HashSet();

        for(FileConfiguration b : this.bundles.values()) {
            String v = b.getString(key);
            if (v != null && !v.isBlank()) {
                String coloredTag = ChatColor.translateAlternateColorCodes('&', v);
                translations.add(coloredTag);
                translations.add(ChatColor.stripColor(coloredTag));
            }
        }

        return translations;
    }

    public void replaceLoreTag(List<String> lore, String key, Player p) {
        if (lore != null) {
            Set<String> all = this.getAllTranslations(key);
            lore.removeIf((line) -> {
                if (line == null) {
                    return false;
                } else {
                    String stripped = ChatColor.stripColor(line);
                    return all.contains(line) || all.contains(stripped);
                }
            });
            lore.add(this.tr(p, key));
        }
    }

    public String trf(Player p, String key, Map<String, Object> vars) {
        String rawT = this.tr(p, key);
        if (vars != null && !vars.isEmpty()) {
            String out = rawT;

            for(Map.Entry<String, Object> e : vars.entrySet()) {
                out = out.replace("{" + (String)e.getKey() + "}", String.valueOf(e.getValue()));
            }

            return out;
        } else {
            return rawT;
        }
    }

    public void reload() {
        this.loadIndex();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
    }

    @EventHandler
    public void onLocale(PlayerLocaleChangeEvent e) {
    }
}
