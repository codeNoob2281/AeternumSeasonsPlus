package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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
        recipe.shape(new String[]{"PPP", "PBP", "PPP"});
        List<Material> bootsList = new ArrayList();

        for(Material m : Material.values()) {
            if (m != null && m.name().endsWith("_BOOTS")) {
                bootsList.add(m);
            }
        }

        RecipeChoice bootsChoice = new RecipeChoice.MaterialChoice(bootsList);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('B', bootsChoice);
        recipe.setIngredient('P', Material.PINK_PETALS);
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
                    int petals = 0;

                    for(ItemStack stack : matrix) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            Material t = stack.getType();
                            if (this.isBoots(t)) {
                                if (boots != null) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                boots = stack.clone();
                            } else {
                                if (t != Material.PINK_PETALS) {
                                    e.getInventory().setResult((ItemStack)null);
                                    return;
                                }

                                petals += stack.getAmount();
                            }
                        }
                    }

                    if (boots != null && petals >= 8) {
                        Player crafter = null;
                        HumanEntity var18 = e.getView().getPlayer();
                        if (var18 instanceof Player) {
                            Player pl = (Player)var18;
                            crafter = pl;
                        }

                        ItemMeta meta = boots.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(this.tagKey, PersistentDataType.BYTE, (byte)1);

                            try {
                                String name = this.plugin.lang.tr(crafter, "item.spring_stride_boots.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.spring_stride_boots.lore3");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3));
                            } catch (Throwable var13) {
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
        return m != null && m.name().endsWith("_BOOTS");
    }

    private boolean hasSpringStrideBoots(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            ItemMeta meta = boots.getItemMeta();
            if (meta == null) {
                return false;
            } else {
                Byte flag = (Byte)meta.getPersistentDataContainer().get(this.tagKey, PersistentDataType.BYTE);
                return flag != null && flag == 1;
            }
        } else {
            return false;
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (this.hasSpringStrideBoots(p)) {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 12, 0, true, false, true));
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == DamageCause.FALL) {
            Entity var3 = e.getEntity();
            if (var3 instanceof Player) {
                Player p = (Player)var3;
                if (this.hasSpringStrideBoots(p)) {
                    Block landing = p.getLocation().getBlock();
                    landing.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add((double)0.0F, 0.1, (double)0.0F), 24, 0.8, 0.2, 0.8);
                    if (this.rnd.nextDouble() < 0.45) {
                        this.spawnFlowersAround(landing);
                    }

                }
            }
        }
    }

    private void spawnFlowersAround(Block center) {
        for(int dx = -2; dx <= 2; ++dx) {
            for(int dz = -2; dz <= 2; ++dz) {
                if (!(this.rnd.nextDouble() > 0.2)) {
                    Block ground = center.getRelative(dx, -1, dz);
                    Block air = center.getRelative(dx, 0, dz);
                    if (ground.getType() == Material.GRASS_BLOCK && air.getType() == Material.AIR) {
                        Material flower = (Material)this.FLOWERS.get(this.rnd.nextInt(this.FLOWERS.size()));
                        air.setType(flower, false);
                        air.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, air.getLocation().add((double)0.5F, 0.3, (double)0.5F), 6, 0.2, 0.2, 0.2);
                    }
                }
            }
        }

    }
}
