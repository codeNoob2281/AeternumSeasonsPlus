package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 拉布拉多靴（Labrador Boots）。
 *
 * <p>本类负责注册并维护“拉布拉多靴”自定义物品：
 * <ul>
 *     <li>注册由任意靴子 + 小麦 + 任意铲子合成的有序配方，并在合成预览时校验材料与数量；</li>
 *     <li>为合成产物写入名称、描述和持久化标签；</li>
 *     <li>玩家穿着该靴子时不会踩踏农田，且在农田上行走会获得短暂速度提升；</li>
 *     <li>玩家穿着该靴子收割成熟作物时，有概率额外掉落对应的种子/作物。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 提供语言文本与命名空间。</p>
 */
public final class LabradorBoots implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey recipeKey;
    private final NamespacedKey tagKey;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private static final Set<Material> SEED_CROPS;

    public LabradorBoots(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "labrador_boots");
        this.tagKey = new NamespacedKey(plugin, "labrador_boots");
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
     * 注册拉布拉多靴的合成配方：B 任意靴子、W 小麦、S 任意铲子。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.LEATHER_BOOTS);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"WBW", "WSW", "WWW"});
        List<Material> bootsList = new ArrayList();

        for (Material material : Material.values()) {
            if (material != null && material.name().endsWith("_BOOTS")) {
                bootsList.add(material);
            }
        }

        RecipeChoice bootsChoice = new RecipeChoice.MaterialChoice(bootsList);
        List<Material> shovelList = new ArrayList();

        for (Material material : Material.values()) {
            if (material != null && material.name().endsWith("_SHOVEL")) {
                shovelList.add(material);
            }
        }

        RecipeChoice shovelChoice = new RecipeChoice.MaterialChoice(shovelList);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('W', Material.WHEAT);
        recipe.setIngredient('S', shovelChoice);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为拉布拉多靴配方时，
     * 校验摆放的材料（一双靴子、一把铲子与至少 6 个小麦），符合条件才显示产物。
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
                    int wheatCount = 0;
                    boolean hasShovel = false;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (this.isBoots(type)) {
                                if (boots != null) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                boots = stack.clone();
                            } else if (type == Material.WHEAT) {
                                wheatCount += stack.getAmount();
                            } else {
                                if (!this.isShovel(type)) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                if (hasShovel) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                hasShovel = true;
                            }
                        }
                    }

                    if (boots != null && hasShovel && wheatCount >= 6) {
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
                                String name = this.plugin.lang.tr(crafter, "item.labrador_boots.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.labrador_boots.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.labrador_boots.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.labrador_boots.lore3");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3));
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
     * 判断给定材料是否为任意铲子（含原版与自定义命名空间）。
     */
    private boolean isShovel(Material material) {
        if (material == null) {
            return false;
        } else if (material.name().endsWith("_SHOVEL")) {
            return true;
        } else {
            NamespacedKey key = material.getKey();
            return key != null && "minecraft".equals(key.getNamespace()) && key.getKey().endsWith("_shovel");
        }
    }

    /**
     * 判断玩家当前穿着的靴子是否带有拉布拉多靴的持久化标签。
     */
    private boolean hasLabradorBoots(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            ItemMeta meta = boots.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte) meta.getPersistentDataContainer().get(this.tagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        } else {
            return false;
        }
    }

    /**
     * 监听农田被踩踏（玩家踩踏耕地方块）：穿着拉布拉多靴时取消踩踏并播放粒子。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.HIGH
    )
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            if (event.getBlock().getType() == Material.FARMLAND) {
                if (this.hasLabradorBoots(player)) {
                    event.setCancelled(true);
                    event.getBlock().getWorld().spawnParticle(Particle.HAPPY_VILLAGER, event.getBlock().getLocation().add((double) 0.5F, (double) 1.0F, (double) 0.5F), 6, 0.3, 0.2, 0.3);
                }
            }
        }
    }

    /**
     * 监听玩家移动：穿着拉布拉多靴走在农田上时，为其附加短暂的速度提升。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                Player player = event.getPlayer();
                if (this.hasLabradorBoots(player)) {
                    Block below = event.getTo().clone().subtract((double) 0.0F, 0.1, (double) 0.0F).getBlock();
                    if (below.getType() == Material.FARMLAND) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, 0, true, false, true));
                    }

                }
            }
        }
    }

    /**
     * 监听方块破坏：穿着拉布拉多靴收割成熟作物时，有概率额外掉落对应的种子/作物。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onCropBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (this.hasLabradorBoots(player)) {
            Block block = event.getBlock();
            Material type = block.getType();
            if (SEED_CROPS.contains(type)) {
                BlockData blockData = block.getBlockData();
                if (blockData instanceof Ageable) {
                    Ageable ageable = (Ageable) blockData;
                    if (ageable.getAge() >= ageable.getMaximumAge()) {
                        if (this.rnd.nextDouble() < 0.35) {
                            int extra = this.rnd.nextInt(1, 3);
                            Material seedMaterial;
                            switch (type) {
                                case WHEAT -> seedMaterial = Material.WHEAT_SEEDS;
                                case CARROTS -> seedMaterial = Material.CARROT;
                                case POTATOES -> seedMaterial = Material.POTATO;
                                case BEETROOTS -> seedMaterial = Material.BEETROOT_SEEDS;
                                default -> seedMaterial = null;
                            }

                            Material seedType = seedMaterial;
                            if (seedType != null) {
                                ItemStack extraSeeds = new ItemStack(seedType, extra);
                                block.getWorld().dropItemNaturally(block.getLocation().add((double) 0.5F, 0.2, (double) 0.5F), extraSeeds);
                            }
                        }

                    }
                }
            }
        }
    }

    static {
        SEED_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS);
    }
}
