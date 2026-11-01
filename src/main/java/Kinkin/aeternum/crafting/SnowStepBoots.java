package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Snowable;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class SnowStepBoots implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey recipeKey;
    private final NamespacedKey tagKey;

    public SnowStepBoots(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "snow_step_boots");
        this.tagKey = new NamespacedKey(plugin, "snow_step");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.LEATHER_BOOTS);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{" B ", " S ", " S "});
        List<Material> bootsList = new ArrayList();

        for(Material m : Material.values()) {
            if (m != null && m.name().endsWith("_BOOTS")) {
                bootsList.add(m);
            }
        }

        RecipeChoice bootsChoice = new RecipeChoice.MaterialChoice(bootsList);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('S', Material.POWDER_SNOW_BUCKET);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() != null) {
            Recipe var3 = e.getRecipe();
            if (var3 instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe)var3;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = e.getInventory().getMatrix();
                    ItemStack boots = null;
                    int snowBuckets = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            if (this.isBoots(stack.getType())) {
                                if (boots != null) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                boots = stack.clone();
                            } else {
                                if (stack.getType() != Material.POWDER_SNOW_BUCKET) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                snowBuckets += stack.getAmount();
                            }
                        }
                    }

                    if (boots != null && snowBuckets >= 2) {
                        Player crafter = null;
                        HumanEntity var17 = e.getView().getPlayer();
                        if (var17 instanceof Player) {
                            Player pl = (Player)var17;
                            crafter = pl;
                        }

                        ItemMeta meta = boots.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.tagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String display = this.plugin.lang.tr(crafter, "item.snow_step_boots.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.snow_step_boots.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.snow_step_boots.lore2");
                                meta.setDisplayName(display);
                                meta.setLore(Arrays.asList(lore1, lore2));
                            } catch (Throwable var12) {
                            }

                            boots.setItemMeta(meta);
                        }

                        e.getInventory().setResult(boots);
                    } else {
                        e.getInventory().setResult((ItemStack)null);
                    }
                }
            }
        }
    }

    private boolean isBoots(Material m) {
        if (m == null) {
            return false;
        } else if (m.name().endsWith("_BOOTS")) {
            return true;
        } else {
            NamespacedKey k = m.getKey();
            return k != null && "minecraft".equals(k.getNamespace()) && k.getKey().endsWith("_boots");
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null) {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
                Player p = e.getPlayer();
                if (this.hasSnowStepBoots(p)) {
                    Block feet = e.getTo().getBlock();
                    Block below = feet.getRelative(0, -1, 0);
                    this.handlePowderSnow(feet);
                    this.handlePowderSnow(below);
                }
            }
        }
    }

    private boolean hasSnowStepBoots(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            ItemMeta meta = boots.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Byte flag = (Byte)pdc.get(this.tagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        } else {
            return false;
        }
    }

    private void handlePowderSnow(Block b) {
        if (b.getType() == Material.POWDER_SNOW) {
            b.setType(Material.SNOW_BLOCK, false);
            this.clearSnowyBelow(b);
        }
    }

    private void clearSnowyBelow(Block snowBlock) {
        Block below = snowBlock.getRelative(0, -1, 0);
        BlockData data = below.getBlockData();
        if (data instanceof Snowable snowData) {
            if (snowData.isSnowy()) {
                snowData.setSnowy(false);
                below.setBlockData(snowData, false);
            }
        }

    }
}
