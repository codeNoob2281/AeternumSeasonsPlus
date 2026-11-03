package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 春行靴（Spring Stride Boots）。
 *
 * <p>本类负责注册并维护“春行靴”自定义物品：
 * <ul>
 *     <li>注册由任意靴子 + 粉色花瓣合成的有序配方，并在合成预览时校验材料与数量；</li>
 *     <li>为合成产物写入名称、描述和持久化标签；</li>
 *     <li>玩家穿着该靴子移动时获得持续短暂的跳跃提升；</li>
 *     <li>玩家穿着该靴子落地时生成粒子，并有概率在周围草地上催生花朵。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 提供语言文本与命名空间。</p>
 */
public final class SpringStrideBoots implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey recipeKey;
    private final NamespacedKey tagKey;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private final List<Material> FLOWERS;

    public SpringStrideBoots(AeternumSeasonsPlugin plugin) {
        super();
        this.FLOWERS = Arrays.asList(Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.PINK_TULIP, Material.WHITE_TULIP);
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "spring_stride_boots");
        this.tagKey = new NamespacedKey(plugin, "spring_stride_boots");
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
     * 注册春行靴的合成配方：B 为任意靴子，P 为粉色花瓣。
     */
    private void registerRecipe() {
        ItemStack dummy = new ItemStack(Material.LEATHER_BOOTS);
        ShapedRecipe recipe = new ShapedRecipe(this.recipeKey, dummy);
        recipe.shape(new String[]{"PPP", "PBP", "PPP"});
        List<Material> bootsList = new ArrayList();

        for (Material material : Material.values()) {
            if (material != null && material.name().endsWith("_BOOTS")) {
                bootsList.add(material);
            }
        }

        RecipeChoice bootsChoice = new RecipeChoice.MaterialChoice(bootsList);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('P', Material.PINK_PETALS);
        Bukkit.addRecipe(recipe);
    }

    /**
     * 监听合成预览事件：当配方为春行靴配方时，
     * 校验摆放的材料（恰好一双靴子与至少 8 个粉色花瓣），符合条件才显示产物。
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
                    int petals = 0;

                    for (ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material type = stack.getType();
                            if (this.isBoots(type)) {
                                if (boots != null) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                boots = stack.clone();
                            } else {
                                if (type != Material.PINK_PETALS) {
                                    event.getInventory().setResult((ItemStack) null);
                                    return;
                                }

                                petals += stack.getAmount();
                            }
                        }
                    }

                    if (boots != null && petals >= 8) {
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
                                String name = this.plugin.lang.tr(crafter, "item.spring_stride_boots.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore3");
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
     * 判断给定材料是否为任意靴子。
     */
    private boolean isBoots(Material material) {
        return material != null && material.name().endsWith("_BOOTS");
    }

    /**
     * 判断玩家当前穿着的靴子是否带有春行靴的持久化标签。
     */
    private boolean hasSpringStrideBoots(Player player) {
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
     * 监听玩家移动：穿着春行靴跨越方块边界时，为其附加短暂的跳跃提升。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (this.hasSpringStrideBoots(player)) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 12, 0, true, false, true));
            }
        }
    }

    /**
     * 监听玩家摔落伤害：穿着春行靴落地时生成粒子，
     * 并有 45% 概率在其周围草地上催生花朵。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            Entity entity = event.getEntity();
            if (entity instanceof Player) {
                Player player = (Player) entity;
                if (this.hasSpringStrideBoots(player)) {
                    Block landing = player.getLocation().getBlock();
                    landing.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add((double) 0.0F, 0.1, (double) 0.0F), 24, 0.8, 0.2, 0.8);
                    if (this.rnd.nextDouble() < 0.45) {
                        this.spawnFlowersAround(landing);
                    }

                }
            }
        }
    }

    /**
     * 在指定中心方块周围 5x5 范围内，按概率于草方块上生成随机花朵。
     */
    private void spawnFlowersAround(Block center) {
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if (!(this.rnd.nextDouble() > 0.2)) {
                    Block ground = center.getRelative(dx, -1, dz);
                    Block air = center.getRelative(dx, 0, dz);
                    if (ground.getType() == Material.GRASS_BLOCK && air.getType() == Material.AIR) {
                        Material flower = (Material) this.FLOWERS.get(this.rnd.nextInt(this.FLOWERS.size()));
                        air.setType(flower, false);
                        air.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, air.getLocation().add((double) 0.5F, 0.3, (double) 0.5F), 6, 0.2, 0.2, 0.2);
                    }
                }
            }
        }

    }
}
