package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 季节作物物品描述服务。
 *
 * <p>为可种植作物物品添加“生长季节/温室光照”等 Lore 信息，并在需要时移除旧 Lore；
 * 同时为村民交易生成规范化的物品模板。所有标记通过物品持久化数据键
 * （{@code seasonal_crop_lore*}）记录，以支持重复应用时的去重与回滚。</p>
 * <p>当服务器安装了 CustomCrops 插件时，为避免冲突会跳过疑似自定义物品。</p>
 */
public final class CropLoreService {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonalCropConfig config;
    private final NamespacedKey KEY_MARK;
    private final NamespacedKey KEY_LINE1;
    private final NamespacedKey KEY_LINE2;
    private final boolean compatibilityMode;
    private final Map<Material, ItemStack> villagerTemplates = new ConcurrentHashMap();
    private static final Set<Material> VILLAGER_CROPS;

    public CropLoreService(AeternumSeasonsPlugin plugin, SeasonalCropConfig config) {
        super();
        this.plugin = plugin;
        this.config = config;
        this.KEY_MARK = new NamespacedKey(plugin, "seasonal_crop_lore");
        this.KEY_LINE1 = new NamespacedKey(plugin, "seasonal_crop_lore_line1");
        this.KEY_LINE2 = new NamespacedKey(plugin, "seasonal_crop_lore_line2");
        this.compatibilityMode = Bukkit.getPluginManager().isPluginEnabled("CustomCrops");
    }

    /**
     * 为物品栈补充季节 Lore（已存在则先移除旧 Lore 再重新写入）。
     *
     * @param stack 目标物品栈
     * @param viewer 语言上下文玩家，可为 null
     * @return 若成功修改物品则返回 true
     */
    public boolean apply(ItemStack stack, Player viewer) {
        if (stack != null && !stack.getType().isAir()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            } else if (this.compatibilityMode && this.isProbablyCustomItem(meta)) {
                return false;
            } else {
                EnumSet<Season> seasons = this.seasonsForItem(stack.getType());
                if (seasons != null && !seasons.isEmpty()) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    boolean alreadyMarked = pdc.has(this.KEY_MARK, PersistentDataType.BYTE);
                    List<String> lore = meta.hasLore() ? new ArrayList(meta.getLore()) : new ArrayList();
                    if (alreadyMarked) {
                        String prev1 = (String)pdc.get(this.KEY_LINE1, PersistentDataType.STRING);
                        String prev2 = (String)pdc.get(this.KEY_LINE2, PersistentDataType.STRING);
                        if (prev1 != null && !prev1.isBlank() || prev2 != null && !prev2.isBlank()) {
                            lore.removeIf((line) -> {
                                if (line == null) {
                                    return false;
                                } else {
                                    String plainLine = ChatColor.stripColor(line);
                                    return prev1 != null && prev1.equals(plainLine) || prev2 != null && prev2.equals(plainLine);
                                }
                            });
                        }

                        lore = this.stripOldLoreFallback(lore);
                    }

                    String growsIn;
                    if (seasons.size() >= 4) {
                        growsIn = this.trSafe(viewer, "crop.lore.any_season", "&7Grows in any season");
                    } else {
                        String joined = (String)seasons.stream().map((season) -> this.plugin.lang.tr(viewer, "season." + season.name())).collect(Collectors.joining(", "));
                        growsIn = this.trSafe(viewer, "crop.lore.grows_in", "&7Grows in: {seasons}").replace("{seasons}", joined);
                    }

                    lore.add(growsIn);
                    int reqLight = this.config.getRequiredLight();
                    String lightLine = this.trSafe(viewer, "crop.lore.greenhouse_light", "&8Underground greenhouse: light {light}+").replace("{light}", String.valueOf(reqLight));
                    lore.add(lightLine);
                    meta.setLore(lore);
                    pdc.set(this.KEY_MARK, PersistentDataType.BYTE, (byte)1);
                    pdc.set(this.KEY_LINE1, PersistentDataType.STRING, ChatColor.stripColor(growsIn));
                    pdc.set(this.KEY_LINE2, PersistentDataType.STRING, ChatColor.stripColor(lightLine));
                    stack.setItemMeta(meta);
                    return true;
                } else {
                    return false;
                }
            }
        } else {
            return false;
        }
    }

    /**
     * 生成村民交易用的“原版”物品模板：移除本服务添加的季节 Lore 与标记后，
     * 再套用该材质的标准模板。
     *
     * @param original 原始物品栈
     * @return 规范化后的物品栈；若不是村民作物或无法规范化则返回 null
     */
    ItemStack forVillager(ItemStack original) {
        if (original != null && VILLAGER_CROPS.contains(original.getType())) {
            EnumSet<Season> allowed = this.seasonsForItem(original.getType());
            if (allowed != null && !allowed.isEmpty()) {
                ItemStack plain = original.clone();
                ItemMeta meta = plain.getItemMeta();
                if (meta == null) {
                    return null;
                } else {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    if (pdc.has(this.KEY_MARK, PersistentDataType.BYTE)) {
                        List<String> lines = meta.hasLore() ? new ArrayList(meta.getLore()) : new ArrayList();
                        String previous1 = (String)pdc.get(this.KEY_LINE1, PersistentDataType.STRING);
                        String previous2 = (String)pdc.get(this.KEY_LINE2, PersistentDataType.STRING);
                        lines.removeIf((line) -> {
                            String stripped = ChatColor.stripColor(line);
                            return stripped != null && (stripped.equals(previous1) || stripped.equals(previous2));
                        });
                        List<String> template = this.stripOldLoreFallback(lines);
                        meta.setLore(template.isEmpty() ? null : template);
                        pdc.remove(this.KEY_MARK);
                        pdc.remove(this.KEY_LINE1);
                        pdc.remove(this.KEY_LINE2);
                        plain.setItemMeta(meta);
                    }

                    if (!plain.isSimilar(new ItemStack(original.getType()))) {
                        return null;
                    } else {
                        ItemStack template = (ItemStack)this.villagerTemplates.computeIfAbsent(original.getType(), (material) -> {
                            ItemStack item = new ItemStack(material);
                            this.apply(item, (Player)null);
                            return item;
                        });
                        ItemStack result = template.clone();
                        result.setAmount(original.getAmount());
                        return result;
                    }
                }
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /** 粗略判断物品是否为第三方自定义物品（含自定义模型数据或其它命名空间的 PDC 键）。 */
    private boolean isProbablyCustomItem(ItemMeta meta) {
        if (meta.hasCustomModelData()) {
            return true;
        } else {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            Set<NamespacedKey> keys = pdc.getKeys();
            if (keys != null && !keys.isEmpty()) {
                String ours = this.KEY_MARK.getNamespace();

                for(NamespacedKey key : keys) {
                    if (key != null) {
                        String namespace = key.getNamespace();
                        if (namespace != null && !namespace.equalsIgnoreCase(ours)) {
                            return true;
                        }
                    }
                }

                return false;
            } else {
                return false;
            }
        }
    }

    /** 兜底移除所有语言文件中“任意季节/生长季节/温室光照”相关 Lore 行。 */
    private List<String> stripOldLoreFallback(List<String> lore) {
        if (lore != null && !lore.isEmpty()) {
            Set<String> anySeasonLines = this.normalizedFullLinesForKey("crop.lore.any_season");
            Set<String> growsPrefixes = this.normalizedPrefixesForKey("crop.lore.grows_in", "seasons");
            Set<String> lightPrefixes = this.normalizedPrefixesForKey("crop.lore.greenhouse_light", "light");
            List<String> out = new ArrayList(lore.size());

            for(String line : lore) {
                if (line != null) {
                    String plain = this.norm(line);
                    if (!anySeasonLines.contains(plain) && !this.startsWithAny(plain, growsPrefixes) && !this.startsWithAny(plain, lightPrefixes)) {
                        out.add(line);
                    }
                }
            }

            return out;
        } else {
            return new ArrayList();
        }
    }

    /** 判断小写文本是否以给定前缀集合中的任一前缀开头。 */
    private boolean startsWithAny(String plainLower, Set<String> prefixesLower) {
        if (plainLower != null && !plainLower.isEmpty()) {
            if (prefixesLower != null && !prefixesLower.isEmpty()) {
                for(String prefix : prefixesLower) {
                    if (prefix != null && !prefix.isEmpty() && plainLower.startsWith(prefix)) {
                        return true;
                    }
                }

                return false;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    /** 汇集某语言键所有翻译的规范化全文。 */
    private Set<String> normalizedFullLinesForKey(String key) {
        Set<String> out = new HashSet();

        for(String translation : this.plugin.lang.getAllTranslations(key)) {
            if (translation != null && !translation.isBlank()) {
                out.add(this.norm(translation));
            }
        }

        return out;
    }

    /** 汇集某语言键所有翻译中、占位符之前的规范化前缀。 */
    private Set<String> normalizedPrefixesForKey(String key, String placeholderName) {
        Set<String> out = new HashSet();
        String token = "{" + placeholderName.toLowerCase(Locale.ROOT) + "}";

        for(String translation : this.plugin.lang.getAllTranslations(key)) {
            if (translation != null && !translation.isBlank()) {
                String plain = ChatColor.stripColor(translation);
                if (plain != null) {
                    String low = plain.toLowerCase(Locale.ROOT);
                    int idx = low.indexOf(token);
                    String prefix = idx >= 0 ? low.substring(0, idx).trim() : low.trim();
                    if (!prefix.isEmpty()) {
                        out.add(prefix);
                    }
                }
            }
        }

        return out;
    }

    /** 去除颜色代码并转为小写、去空白后的规范化文本。 */
    private String norm(String s) {
        return ChatColor.stripColor(s == null ? "" : s).toLowerCase(Locale.ROOT).trim();
    }

    /** 安全翻译：若语言键无对应翻译则使用带颜色代码的兜底文本。 */
    private String trSafe(Player player, String key, String fallback) {
        String v = this.plugin.lang.tr(player, key);
        return v != null && !v.equals(key) ? v : ChatColor.translateAlternateColorCodes('&', fallback);
    }

    /** 将种子/物品映射到对应作物方块材质并返回其允许季节；无法映射时返回 null。 */
    private EnumSet<Season> seasonsForItem(Material itemType) {
        if (this.config.isManagedCrop(itemType)) {
            return this.config.getAllowedSeasons(itemType);
        } else {
            Material cropMaterial;
            switch (itemType) {
                case WHEAT_SEEDS -> cropMaterial = Material.WHEAT;
                case BEETROOT_SEEDS -> cropMaterial = Material.BEETROOTS;
                case CARROT -> cropMaterial = Material.CARROTS;
                case POTATO -> cropMaterial = Material.POTATOES;
                case MELON_SEEDS -> cropMaterial = Material.MELON_STEM;
                case PUMPKIN_SEEDS -> cropMaterial = Material.PUMPKIN_STEM;
                case NETHER_WART -> cropMaterial = Material.NETHER_WART;
                case COCOA_BEANS -> cropMaterial = Material.COCOA;
                case SWEET_BERRIES -> cropMaterial = Material.SWEET_BERRY_BUSH;
                case BAMBOO -> cropMaterial = Material.BAMBOO;
                case SUGAR_CANE -> cropMaterial = Material.SUGAR_CANE;
                case CACTUS -> cropMaterial = Material.CACTUS;
                case KELP -> cropMaterial = Material.KELP;
                default -> cropMaterial = null;
            }

            Material cropType = cropMaterial;
            return cropType == null ? null : this.config.getAllowedSeasons(cropType);
        }
    }

    static {
        VILLAGER_CROPS = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.BREAD);
    }
}
