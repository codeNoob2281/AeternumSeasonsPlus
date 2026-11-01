package Kinkin.aeternum.heat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
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

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onLoot(LootGenerateEvent e) {
        if (this.plugin.getConfig().getBoolean("heat_loot.enabled", true)) {
            Location loc = e.getLootContext().getLocation();
            if (loc != null) {
                World w = loc.getWorld();
                if (w != null) {
                    if (this.isHeatWorld(w.getName())) {
                        if (e.getInventoryHolder() instanceof Container) {
                            List<ItemStack> loot = e.getLoot();
                            loot.clear();
                            int minItems = this.plugin.getConfig().getInt("heat_loot.items_per_chest.min", 3);
                            int maxItems = this.plugin.getConfig().getInt("heat_loot.items_per_chest.max", 6);
                            int items = this.randBetween(minItems, maxItems);

                            for(int i = 0; i < items; ++i) {
                                loot.add(this.generateHeatLoot());
                            }

                        }
                    }
                }
            }
        }
    }

    private boolean isHeatWorld(String worldName) {
        List<String> worlds = this.plugin.getConfig().getStringList("heat_loot.worlds");
        if (worlds != null && !worlds.isEmpty()) {
            for(String s : worlds) {
                if (s != null && !s.isBlank() && worldName.equalsIgnoreCase(s.trim())) {
                    return true;
                }
            }

            return false;
        } else {
            return worldName.equalsIgnoreCase("aeternum_heat");
        }
    }

    private ItemStack generateHeatLoot() {
        List<String> poolCfg = this.plugin.getConfig().getStringList("heat_loot.gear_pool");
        List<Material> pool = new ArrayList();
        if (poolCfg != null && !poolCfg.isEmpty()) {
            for(String s : poolCfg) {
                if (s != null) {
                    try {
                        pool.add(Material.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException var6) {
                    }
                }
            }
        }

        if (pool.isEmpty()) {
            pool = Arrays.asList(Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_HELMET, Material.DIAMOND_BOOTS, Material.NETHERITE_SCRAP, Material.NETHERITE_INGOT, Material.ENCHANTED_GOLDEN_APPLE, Material.BOOK);
        }

        Material m = (Material)pool.get(this.random.nextInt(pool.size()));
        ItemStack item;
        if (m == Material.BOOK) {
            item = this.createCrazyBook();
        } else {
            item = new ItemStack(m);
            if (m == Material.NETHERITE_SCRAP || m == Material.NETHERITE_INGOT || m == Material.ENCHANTED_GOLDEN_APPLE) {
                return item;
            }

            this.addHighLevelEnchants(item);
        }

        return item;
    }

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

                while(added < target && attempts++ < 30) {
                    Enchantment ench = (Enchantment)pool.get(this.random.nextInt(pool.size()));
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

            while(added < target && attempts++ < 40) {
                Enchantment ench = (Enchantment)possible.get(this.random.nextInt(possible.size()));
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

    private int randBetween(int min, int max) {
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }

        return min == max ? min : min + this.random.nextInt(max - min + 1);
    }
}
