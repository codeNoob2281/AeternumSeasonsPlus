package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 雪地步靴（Snow Step Boots）。
 *
 * <p>本类负责注册并维护“雪地步靴”这一自定义物品：
 * <ul>
 *     <li>注册由任意靴子 + 细雪桶合成的有序配方，并在合成预览时按材料与数量校验产物；</li>
 *     <li>为合成出的靴子写入物品名称、描述与持久化标签，作为身份标记；</li>
 *     <li>玩家穿着该靴子移动时，将脚下的细雪转化为雪块，并清除雪块下方的覆雪状态。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 提供语言文本与命名空间。</p>
 */
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

    /**
     * 向服务器注册事件监听器与合成配方。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipe();
    }

    /**
     * 注销事件监听器并移除已注册的合成配方。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        Bukkit.removeRecipe(this.recipeKey);
    }

    /**
     * 注册雪地步靴的合成配方：B 为任意靴子，S 为细雪桶。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.LEATHER_BOOTS);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{" B ", " S ", " S "});
        List<Material> bootsList = new ArrayList();

        for (Material material : Material.values()) {
            if (material != null && material.name().endsWith("_BOOTS")) {
                bootsList.add(material);
            }
        }

        RecipeChoice bootsChoice = new RecipeChoice.MaterialChoice(bootsList);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('S', Material.POWDER_SNOW_BUCKET);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为本模组的雪地步靴配方时，
     * 校验摆放的材料（恰好一双靴子与至少两个细雪桶），符合条件才显示产物。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                if (this.recipeKey.equals(shaped.getKey())) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    ItemStack boots = null;
                    int snowBuckets = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            if (this.isBoots(stack.getType())) {
                                if (boots != null) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                boots = stack.clone();
                            } else {
                                if (stack.getType() != Material.POWDER_SNOW_BUCKET) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                snowBuckets += stack.getAmount();
                            }
                        }
                    }

                    if (boots != null && snowBuckets >= 2) {
                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            crafter = player;
                        }

                        ItemMeta meta = boots.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.tagKey, PersistentDataType.BYTE, (byte) 1);

                            try {
                                String display = this.plugin.lang.tr(crafter, "item.snow_step_boots.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.snow_step_boots.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.snow_step_boots.lore2");
                                meta.setDisplayName(display);
                                meta.setLore(Arrays.asList(lore1, lore2));
                            } catch (Throwable ignored) {
                            }

                            boots.setItemMeta(meta);
                        }

                        event.getInventory().setResult(boots);
                    } else {
                        event.getInventory().setResult((ItemStack) null);
                    }
                }
            }
        }
    }

    /**
     * 判断给定材料是否为任意靴子（含原版与自定义命名空间）。
     */
    private boolean isBoots(Material material) {
        if (material == null) {
            return false;
        } else if (material.name().endsWith("_BOOTS")) {
            return true;
        } else {
            NamespacedKey key = material.getKey();
            return key != null && "minecraft".equals(key.getNamespace()) && key.getKey().endsWith("_boots");
        }
    }

    /**
     * 监听玩家移动：当玩家穿着雪地步靴且跨越方块边界时，
     * 处理其脚部及脚下方块上的细雪。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                Player player = event.getPlayer();
                if (this.hasSnowStepBoots(player)) {
                    Block feet = event.getTo().getBlock();
                    Block below = feet.getRelative(0, -1, 0);
                    this.handlePowderSnow(feet);
                    this.handlePowderSnow(below);
                }
            }
        }
    }

    /**
     * 判断玩家当前穿着的靴子是否带有雪地步靴的持久化标签。
     */
    private boolean hasSnowStepBoots(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            ItemMeta meta = boots.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Byte flag = (Byte) pdc.get(this.tagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        } else {
            return false;
        }
    }

    /**
     * 若方块为细雪，则将其替换为雪块，并清除其下方的覆雪状态。
     */
    private void handlePowderSnow(Block block) {
        if (block.getType() == Material.POWDER_SNOW) {
            block.setType(Material.SNOW_BLOCK, false);
            this.clearSnowyBelow(block);
        }
    }

    /**
     * 清除指定雪块下方方块的覆雪标记（若该方块可积雪）。
     */
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
