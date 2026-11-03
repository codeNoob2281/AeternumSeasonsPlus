package kinkin.aeternum.heat;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * 炎热世界战利品监听器。
 *
 * <p>当炎热世界（默认 {@code aeternum_heat}，可通过 {@code heat_loot.worlds} 配置）中的容器
 * 生成战利品时，清空原版战利品并替换为高等级附魔装备/材料等自定义战利品。
 * 掉落数量、装备池与附魔等级等均由配置项控制，并复用全局附魔等级上限设置。</p>
 */
public final class HeatLootListener implements Listener {
    private static final String DEFAULT_HEAT_WORLD = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final Random random = new Random();

    public HeatLootListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 监听战利品生成，将炎热世界容器的战利品替换为自定义战利品。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGHEST
    )
    public void onLoot(LootGenerateEvent event) {
        if (this.plugin.getConfig().getBoolean("heat_loot.enabled", true)) {
            Location location = event.getLootContext().getLocation();
            if (location != null) {
                World world = location.getWorld();
                if (world != null) {
                    if (this.isHeatWorld(world.getName())) {
                        if (event.getInventoryHolder() instanceof Container) {
                            List<ItemStack> loot = event.getLoot();
                            loot.clear();
                            int minItems = this.plugin.getConfig().getInt("heat_loot.items_per_chest.min", 3);
                            int maxItems = this.plugin.getConfig().getInt("heat_loot.items_per_chest.max", 6);
                            int items = this.randBetween(minItems, maxItems);

                            for (int i = 0; i < items; ++i) {
                                loot.add(this.generateHeatLoot());
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 判断世界名是否属于配置的炎热世界集合（未配置时回退默认世界名）。
     */
    private boolean isHeatWorld(String worldName) {
        List<String> worlds = this.plugin.getConfig().getStringList("heat_loot.worlds");
        if (worlds != null && !worlds.isEmpty()) {
            for (String worldEntry : worlds) {
                if (worldEntry != null && !worldEntry.isBlank() && worldName.equalsIgnoreCase(worldEntry.trim())) {
                    return true;
                }
            }

            return false;
        } else {
            return "aeternum_heat".equalsIgnoreCase(worldName);
        }
    }

    /**
     * 从配置的装备池中随机生成一件（或附魔书）战利品。
     */
    private ItemStack generateHeatLoot() {
        List<String> poolCfg = this.plugin.getConfig().getStringList("heat_loot.gear_pool");
        List<Material> pool = new ArrayList();
        if (poolCfg != null && !poolCfg.isEmpty()) {
            for (String entry : poolCfg) {
                if (entry != null) {
                    try {
                        pool.add(Material.valueOf(entry.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        if (pool.isEmpty()) {
            pool = Arrays.asList(Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_HELMET, Material.DIAMOND_BOOTS, Material.NETHERITE_SCRAP, Material.NETHERITE_INGOT, Material.ENCHANTED_GOLDEN_APPLE, Material.BOOK);
        }

        Material material = (Material) pool.get(this.random.nextInt(pool.size()));
        ItemStack item;
        if (material == Material.BOOK) {
            item = this.createCrazyBook();
        } else {
            item = new ItemStack(material);
            if (material == Material.NETHERITE_SCRAP || material == Material.NETHERITE_INGOT || material == Material.ENCHANTED_GOLDEN_APPLE) {
                return item;
            }

            this.addHighLevelEnchants(item);
        }

        return item;
    }

    /**
     * 依装备类型为其添加若干高等级附魔（受配置数量与等级上限约束）。
     */
    private void addHighLevelEnchants(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Material type = item.getType();
            List<Enchantment> pool = new ArrayList();
            if (type == Material.DIAMOND_SWORD) {
                pool.add(Enchantment.SHARPNESS);
                pool.add(Enchantment.LOOTING);
                pool.add(Enchantment.FIRE_ASPECT);
                pool.add(Enchantment.UNBREAKING);
                pool.add(Enchantment.MENDING);
            } else if (type != Material.DIAMOND_PICKAXE && type != Material.DIAMOND_AXE && type != Material.DIAMOND_SHOVEL && type != Material.DIAMOND_HOE) {
                if (type == Material.DIAMOND_BOOTS) {
                    pool.add(Enchantment.PROTECTION);
                    pool.add(Enchantment.FIRE_PROTECTION);
                    pool.add(Enchantment.FEATHER_FALLING);
                    pool.add(Enchantment.DEPTH_STRIDER);
                    pool.add(Enchantment.SOUL_SPEED);
                    pool.add(Enchantment.UNBREAKING);
                    pool.add(Enchantment.MENDING);
                } else if (type != Material.DIAMOND_HELMET && type != Material.DIAMOND_CHESTPLATE && type != Material.DIAMOND_LEGGINGS) {
                    pool.add(Enchantment.UNBREAKING);
                    pool.add(Enchantment.MENDING);
                } else {
                    pool.add(Enchantment.PROTECTION);
                    pool.add(Enchantment.FIRE_PROTECTION);
                    pool.add(Enchantment.UNBREAKING);
                    pool.add(Enchantment.MENDING);
                    pool.add(Enchantment.THORNS);
                }
            } else {
                pool.add(Enchantment.EFFICIENCY);
                pool.add(Enchantment.FORTUNE);
                pool.add(Enchantment.SILK_TOUCH);
                pool.add(Enchantment.UNBREAKING);
                pool.add(Enchantment.MENDING);
            }

            if (!pool.isEmpty()) {
                int minEnchants = this.plugin.getConfig().getInt("heat_loot.enchants_per_item.min", 1);
                int maxEnchants = this.plugin.getConfig().getInt("heat_loot.enchants_per_item.max", 3);
                int target = this.randBetween(minEnchants, maxEnchants);
                int minLevel = this.plugin.getConfig().getInt("heat_loot.enchant_level.min", 6);
                int maxLevel = this.plugin.getConfig().getInt("heat_loot.enchant_level.max", 10);
                Set<Enchantment> used = new HashSet();
                int added = 0;
                int attempts = 0;

                while (added < target && attempts++ < 30) {
                    Enchantment ench = (Enchantment) pool.get(this.random.nextInt(pool.size()));
                    if (!used.contains(ench)) {
                        int desired = this.randBetween(minLevel, maxLevel);
                        int level = this.capEnchantLevel(ench, desired);
                        if (level <= 0) {
                            used.add(ench);
                        } else {
                            meta.addEnchant(ench, level, true);
                            used.add(ench);
                            ++added;
                        }
                    }
                }

                item.setItemMeta(meta);
            }
        }
    }

    /**
     * 生成一本附带多条高等级附魔的“疯狂附魔书”。
     */
    private ItemStack createCrazyBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta rawMeta = book.getItemMeta();
        if (!(rawMeta instanceof EnchantmentStorageMeta meta)) {
            return book;
        } else {
            List possible = Arrays.asList(Enchantment.SHARPNESS, Enchantment.EFFICIENCY, Enchantment.PROJECTILE_PROTECTION, Enchantment.LOOTING, Enchantment.FORTUNE, Enchantment.MENDING, Enchantment.SILK_TOUCH);
            int minEnchants = this.plugin.getConfig().getInt("heat_loot.book_enchants.min", 2);
            int maxEnchants = this.plugin.getConfig().getInt("heat_loot.book_enchants.max", 4);
            int target = this.randBetween(minEnchants, maxEnchants);
            int minLevel = this.plugin.getConfig().getInt("heat_loot.enchant_level.min", 6);
            int maxLevel = this.plugin.getConfig().getInt("heat_loot.enchant_level.max", 10);
            HashSet used = new HashSet();
            int added = 0;
            int attempts = 0;

            while (added < target && attempts++ < 40) {
                Enchantment ench = (Enchantment) possible.get(this.random.nextInt(possible.size()));
                if (!used.contains(ench)) {
                    int desired = this.randBetween(minLevel, maxLevel);
                    int level = this.capEnchantLevel(ench, desired);
                    if (level <= 0) {
                        used.add(ench);
                    } else {
                        meta.addStoredEnchant(ench, level, true);
                        used.add(ench);
                        ++added;
                    }
                }
            }

            book.setItemMeta(meta);
            return book;
        }
    }

    /**
     * 按全局附魔上限（含按附魔键覆盖）裁剪期望等级。
     */
    private int capEnchantLevel(Enchantment ench, int desired) {
        if (!this.plugin.getConfig().getBoolean("enchants.caps.enabled", true)) {
            return desired;
        } else {
            int cap = this.plugin.getConfig().getInt("enchants.caps.default_max", 10);
            if (this.plugin.getConfig().getBoolean("enchants.caps.clamp_to_vanilla", false)) {
                cap = Math.min(cap, ench.getMaxLevel());
            }

            Integer override = this.readOverrideCap(ench);
            if (override != null) {
                cap = override;
            }

            return cap <= 0 ? 0 : Math.min(desired, cap);
        }
    }

    /**
     * 读取某附魔的覆盖上限配置（支持完整键、小写简单键与大写简单键）。
     */
    private Integer readOverrideCap(Enchantment ench) {
        ConfigurationSection sec = this.plugin.getConfig().getConfigurationSection("enchants.caps.overrides");
        if (sec == null) {
            return null;
        } else {
            String key = ench.getKey().toString();
            if (sec.contains(key)) {
                return sec.getInt(key);
            } else {
                String simpleLower = ench.getKey().getKey().toLowerCase(Locale.ROOT);
                String simpleUpper = ench.getKey().getKey().toUpperCase(Locale.ROOT);
                if (sec.contains(simpleLower)) {
                    return sec.getInt(simpleLower);
                } else {
                    return sec.contains(simpleUpper) ? sec.getInt(simpleUpper) : null;
                }
            }
        }
    }

    /**
     * 返回 [min, max] 区间内的随机整数（会自动纠正颠倒的边界）。
     */
    private int randBetween(int min, int max) {
        if (max < min) {
            int temp = min;
            min = max;
            max = temp;
        }

        return min == max ? min : min + this.random.nextInt(max - min + 1);
    }
}
