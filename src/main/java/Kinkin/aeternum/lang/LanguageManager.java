package kinkin.aeternum.lang;

import kinkin.aeternum.AeternumSeasonsPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;

import java.io.File;
import java.util.*;

/**
 * 多语言管理器。
 *
 * <p>从 {@code lang.yml} 读取启用语言列表与默认语言，按需从 jar 释放并加载各语言包
 * （{@code lang/<code>.yml}）。根据玩家客户端区域设置（locale）选择最合适的语言，
 * 并在缺失翻译时回退到 en_US 或返回键名本身。</p>
 *
 * <p>同时以 {@link Listener} 形式监听玩家加入与语言变更事件（当前为空实现），
 * 并提供带占位符替换的格式化翻译方法。</p>
 */
public final class LanguageManager implements Listener {
    private final AeternumSeasonsPlugin plugin;
    /**
     * 已启用的语言代码（按配置顺序去重）。
     */
    private final Set<String> enabled = new LinkedHashSet();
    /**
     * 默认语言代码（"auto" 表示按客户端自动匹配）。
     */
    private String def;
    /**
     * 已加载的语言包：语言代码 -> 配置对象。
     */
    private final Map<String, FileConfiguration> bundles = new HashMap();

    public LanguageManager(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    /**
     * 加载语言索引并注册事件监听器。
     */
    public void register() {
        this.loadIndex();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /**
     * 读取 lang.yml 中的启用语言列表与默认语言，并加载全部语言包（确保 en_US 始终存在）。
     */
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

        for (String code : this.enabled) {
            File f = new File(this.plugin.getDataFolder(), "lang/" + code + ".yml");
            if (!f.exists()) {
                this.plugin.saveResource("lang/" + code + ".yml", false);
            }

            this.bundles.put(code, YamlConfiguration.loadConfiguration(f));
        }

        this.bundles.computeIfAbsent("en_US", (code) -> {
            File f = new File(this.plugin.getDataFolder(), "lang/en_US.yml");
            if (!f.exists()) {
                this.plugin.saveResource("lang/en_US.yml", false);
            }

            return YamlConfiguration.loadConfiguration(f);
        });
    }

    /**
     * 解析该玩家应使用的语言代码。
     *
     * <p>优先精确匹配客户端 locale；否则按主语言前缀匹配已启用语言；再否则使用配置的默认语言；
     * 最后回退到 en_US 或任意已启用语言。玩家为 null 时按服务端默认处理。</p>
     */
    public String resolve(Player player) {
        if (player == null) {
            if (this.enabled.contains("en_US")) {
                return "en_US";
            } else {
                return !this.enabled.isEmpty() ? (String) this.enabled.iterator().next() : "en_US";
            }
        } else {
            String raw = player.getLocale();
            String wanted = this.safe(raw);
            if (wanted != null && this.enabled.contains(wanted)) {
                return wanted;
            } else {
                if (wanted != null) {
                    String lang = wanted.substring(0, 2).toLowerCase(Locale.ROOT);

                    for (String code : this.enabled) {
                        if (code.toLowerCase(Locale.ROOT).startsWith(lang + "_")) {
                            return code;
                        }
                    }
                }

                if (!"auto".equalsIgnoreCase(this.def) && this.enabled.contains(this.def)) {
                    return this.def;
                } else {
                    return this.enabled.contains("en_US") ? "en_US" : (String) this.enabled.stream().findFirst().orElse("en_US");
                }
            }
        }
    }

    /**
     * 把客户端 locale 规范化为 {@code lang_COUNTRY} 或 {@code lang} 形式（缺省时返回 en_US）。
     */
    private String safe(String s) {
        if (s == null) {
            return "en_US";
        } else {
            String normalized = s.replace('-', '_');
            if (normalized.contains("_")) {
                String[] segments = normalized.split("_", 2);
                String lang = segments[0].toLowerCase(Locale.ROOT);
                String country = segments[1].toUpperCase(Locale.ROOT);
                return lang + "_" + country;
            } else {
                return normalized.toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * 按玩家语言获取翻译，并把 {@code &} 颜色代码转换为实际颜色；缺失时回退 en_US 或键名。
     */
    public String tr(Player player, String key) {
        String code = this.resolve(player);
        FileConfiguration bundle = (FileConfiguration) this.bundles.getOrDefault(code, (FileConfiguration) this.bundles.get("en_US"));
        String value = bundle.getString(key);
        if (value == null || value.isBlank()) {
            String englishValue = ((FileConfiguration) this.bundles.get("en_US")).getString(key);
            if (englishValue != null && !englishValue.isBlank()) {
                value = englishValue;
            } else {
                value = key;
            }
        }

        return ChatColor.translateAlternateColorCodes('&', value);
    }

    /**
     * 同 {@link #tr}，但在翻译缺失（返回键名或空）时改用调用方提供的 fallback。
     */
    public String trOr(Player player, String key, String fallback) {
        String value = this.tr(player, key);
        return value != null && !value.isBlank() && !value.equals(key) ? value : org.bukkit.ChatColor.translateAlternateColorCodes('&', fallback);
    }

    /**
     * 按服务端默认语言获取翻译（不针对具体玩家）。
     */
    public String trServer(String key) {
        String code = this.resolve((Player) null);
        FileConfiguration bundle = (FileConfiguration) this.bundles.getOrDefault(code, (FileConfiguration) this.bundles.get("en_US"));
        String value = bundle.getString(key);
        if (value == null || value.isBlank()) {
            String englishValue = ((FileConfiguration) this.bundles.get("en_US")).getString(key);
            if (englishValue != null && !englishValue.isBlank()) {
                value = englishValue;
            } else {
                value = key;
            }
        }

        return ChatColor.translateAlternateColorCodes('&', value);
    }

    /**
     * 收集某键在所有语言包中的翻译（同时包含带色与去色版本），供 Lore 去重匹配使用。
     */
    public Set<String> getAllTranslations(String key) {
        Set<String> translations = new HashSet();

        for (FileConfiguration bundle : this.bundles.values()) {
            String value = bundle.getString(key);
            if (value != null && !value.isBlank()) {
                String coloredTag = ChatColor.translateAlternateColorCodes('&', value);
                translations.add(coloredTag);
                translations.add(ChatColor.stripColor(coloredTag));
            }
        }

        return translations;
    }

    /**
     * 移除 Lore 中所有既有翻译行（任意语言、含去色版本），再追加当前玩家语言的翻译。
     */
    public void replaceLoreTag(List<String> lore, String key, Player player) {
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
            lore.add(this.tr(player, key));
        }
    }

    /**
     * 按玩家语言获取翻译，并替换 {@code {name}} 形式的占位符。
     */
    public String trf(Player player, String key, Map<String, Object> vars) {
        String rawT = this.tr(player, key);
        if (vars != null && !vars.isEmpty()) {
            String out = rawT;

            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                out = out.replace("{" + (String) entry.getKey() + "}", String.valueOf(entry.getValue()));
            }

            return out;
        } else {
            return rawT;
        }
    }

    /**
     * 重新加载语言索引与全部语言包。
     */
    public void reload() {
        this.loadIndex();
    }

    /**
     * 玩家加入事件（当前无需额外处理）。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
    }

    /**
     * 玩家客户端语言变更事件（当前无需额外处理）。
     */
    @EventHandler
    public void onLocale(PlayerLocaleChangeEvent event) {
    }
}
