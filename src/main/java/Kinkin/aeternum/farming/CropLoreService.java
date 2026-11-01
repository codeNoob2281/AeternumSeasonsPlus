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
                                    String plain = ChatColor.stripColor(line);
                                    return prev1 != null && prev1.equals(plain) || prev2 != null && prev2.equals(plain);
                                }
                            });
                        }

                        lore = this.stripOldLoreFallback(lore);
                    }

                    String growsIn;
                    if (seasons.size() >= 4) {
                        growsIn = this.trSafe(viewer, "crop.lore.any_season", "&7Grows in any season");
                    } else {
                        String joined = (String)seasons.stream().map((s) -> this.plugin.lang.tr(viewer, "season." + s.name())).collect(Collectors.joining(", "));
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

    private boolean isProbablyCustomItem(ItemMeta meta) {
        if (meta.hasCustomModelData()) {
            return true;
        } else {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            Set<NamespacedKey> keys = pdc.getKeys();
            if (keys != null && !keys.isEmpty()) {
                String ours = this.KEY_MARK.getNamespace();

                for(NamespacedKey k : keys) {
                    if (k != null) {
                        String ns = k.getNamespace();
                        if (ns != null && !ns.equalsIgnoreCase(ours)) {
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

    private boolean startsWithAny(String plainLower, Set<String> prefixesLower) {
        if (plainLower != null && !plainLower.isEmpty()) {
            if (prefixesLower != null && !prefixesLower.isEmpty()) {
                for(String p : prefixesLower) {
                    if (p != null && !p.isEmpty() && plainLower.startsWith(p)) {
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

    private Set<String> normalizedFullLinesForKey(String key) {
        Set<String> out = new HashSet();

        for(String t : this.plugin.lang.getAllTranslations(key)) {
            if (t != null && !t.isBlank()) {
                out.add(this.norm(t));
            }
        }

        return out;
    }

    private Set<String> normalizedPrefixesForKey(String key, String placeholderName) {
        Set<String> out = new HashSet();
        String token = "{" + placeholderName.toLowerCase(Locale.ROOT) + "}";

        for(String t : this.plugin.lang.getAllTranslations(key)) {
            if (t != null && !t.isBlank()) {
                String plain = ChatColor.stripColor(t);
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

    private String norm(String s) {
        return ChatColor.stripColor(s == null ? "" : s).toLowerCase(Locale.ROOT).trim();
    }

    private String trSafe(Player p, String key, String fallback) {
        String v = this.plugin.lang.tr(p, key);
        return v != null && !v.equals(key) ? v : ChatColor.translateAlternateColorCodes('&', fallback);
    }

    private EnumSet<Season> seasonsForItem(Material itemType) {
        if (this.config.isManagedCrop(itemType)) {
            return this.config.getAllowedSeasons(itemType);
        } else {
            Material var10000;
            switch (itemType) {
                case WHEAT_SEEDS -> var10000 = Material.WHEAT;
                case BEETROOT_SEEDS -> var10000 = Material.BEETROOTS;
                case CARROT -> var10000 = Material.CARROTS;
                case POTATO -> var10000 = Material.POTATOES;
                case MELON_SEEDS -> var10000 = Material.MELON_STEM;
                case PUMPKIN_SEEDS -> var10000 = Material.PUMPKIN_STEM;
                case NETHER_WART -> var10000 = Material.NETHER_WART;
                case COCOA_BEANS -> var10000 = Material.COCOA;
                case SWEET_BERRIES -> var10000 = Material.SWEET_BERRY_BUSH;
                case BAMBOO -> var10000 = Material.BAMBOO;
                case SUGAR_CANE -> var10000 = Material.SUGAR_CANE;
                case CACTUS -> var10000 = Material.CACTUS;
                case KELP -> var10000 = Material.KELP;
                default -> var10000 = null;
            }

            Material cropType = var10000;
            return cropType == null ? null : this.config.getAllowedSeasons(cropType);
        }
    }

    static {
        VILLAGER_CROPS = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.BREAD);
    }
}
