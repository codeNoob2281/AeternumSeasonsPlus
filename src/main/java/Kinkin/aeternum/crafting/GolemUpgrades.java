package Kinkin.aeternum.crafting;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

public final class GolemUpgrades implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final NamespacedKey frostCoreRecipeKey;
    private final NamespacedKey frostCoreTagKey;
    private final NamespacedKey guardianCoreRecipeKey;
    private final NamespacedKey guardianCoreTagKey;
    private final NamespacedKey golemTypeKey;
    private BukkitTask task;

    public GolemUpgrades(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.frostCoreRecipeKey = new NamespacedKey(plugin, "frost_core");
        this.frostCoreTagKey = new NamespacedKey(plugin, "frost_core");
        this.guardianCoreRecipeKey = new NamespacedKey(plugin, "guardian_core");
        this.guardianCoreTagKey = new NamespacedKey(plugin, "guardian_core");
        this.golemTypeKey = new NamespacedKey(plugin, "season_golem_type");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipes();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 40L);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        if (this.task != null) {
            this.task.cancel();
        }

        Bukkit.removeRecipe(this.frostCoreRecipeKey);
        Bukkit.removeRecipe(this.guardianCoreRecipeKey);
    }

    private Season resolveCurrentSeason() {
        if (this.seasons == null) {
            return Season.SPRING;
        } else {
            try {
                try {
                    Method m = this.seasons.getClass().getMethod("getCurrentSeason");
                    Object res = m.invoke(this.seasons);
                    if (res instanceof Season) {
                        Season s = (Season)res;
                        return s;
                    }
                } catch (NoSuchMethodException var7) {
                }

                try {
                    Method m = this.seasons.getClass().getMethod("getSeason");
                    Object res = m.invoke(this.seasons);
                    if (res instanceof Season) {
                        Season s = (Season)res;
                        return s;
                    }
                } catch (NoSuchMethodException var6) {
                }

                try {
                    Method m = this.seasons.getClass().getMethod("getState");
                    Object res = m.invoke(this.seasons);
                    if (res instanceof CalendarState) {
                        CalendarState cs = (CalendarState)res;
                        if (cs.season != null) {
                            return cs.season;
                        }
                    }
                } catch (NoSuchMethodException var5) {
                }

                try {
                    Method m = this.seasons.getClass().getMethod("currentState");
                    Object res = m.invoke(this.seasons);
                    if (res instanceof CalendarState) {
                        CalendarState cs = (CalendarState)res;
                        if (cs.season != null) {
                            return cs.season;
                        }
                    }
                } catch (NoSuchMethodException var4) {
                }
            } catch (Throwable t) {
                this.plugin.getLogger().warning("[Golems] No se pudo resolver la estación actual: " + t.getMessage());
            }

            return Season.SPRING;
        }
    }

    private void registerRecipes() {
        ItemStack dummy = new ItemStack(Material.SNOWBALL);
        ShapedRecipe recipe = new ShapedRecipe(this.frostCoreRecipeKey, dummy);
        recipe.shape(new String[]{"SIS", "IBI", "SIS"});
        recipe.setIngredient('S', Material.SNOW_BLOCK);
        recipe.setIngredient('I', Material.PACKED_ICE);
        recipe.setIngredient('B', Material.HEART_OF_THE_SEA);
        Bukkit.addRecipe(recipe);
        dummy = new ItemStack(Material.PRISMARINE_CRYSTALS);
        recipe = new ShapedRecipe(this.guardianCoreRecipeKey, dummy);
        recipe.shape(new String[]{"IBI", "BGB", "IBI"});
        recipe.setIngredient('I', Material.IRON_BLOCK);
        recipe.setIngredient('B', Material.IRON_INGOT);
        recipe.setIngredient('G', Material.AMETHYST_SHARD);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() != null) {
            Recipe var3 = e.getRecipe();
            if (var3 instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe)var3;
                NamespacedKey var17 = shaped.getKey();
                boolean frost = this.frostCoreRecipeKey.equals(var17);
                boolean guardian = this.guardianCoreRecipeKey.equals(var17);
                if (frost || guardian) {
                    ItemStack[] matrix = e.getInventory().getMatrix();
                    if (matrix != null) {
                        for(ItemStack stack : matrix) {
                            if (stack != null && stack.getType() == Material.AIR) {
                            }
                        }

                        Player crafter = null;
                        HumanEntity var21 = e.getView().getPlayer();
                        if (var21 instanceof Player) {
                            Player p = (Player)var21;
                            crafter = p;
                        }

                        ItemStack result = e.getInventory().getResult();
                        if (result == null) {
                            result = frost ? new ItemStack(Material.SNOWBALL) : new ItemStack(Material.PRISMARINE_CRYSTALS);
                        }

                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            if (frost) {
                                pdc.set(this.frostCoreTagKey, PersistentDataType.BYTE, (byte)1);
                                String name = this.plugin.lang.tr(crafter, "item.frost_core.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.frost_core.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.frost_core.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.frost_core.lore3");
                                String lore4 = this.plugin.lang.tr(crafter, "item.frost_core.lore4");
                                String lore5 = this.plugin.lang.tr(crafter, "item.frost_core.lore5");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3, lore4, lore5));
                            } else if (guardian) {
                                pdc.set(this.guardianCoreTagKey, PersistentDataType.BYTE, (byte)1);
                                String name = this.plugin.lang.tr(crafter, "item.guardian_core.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.guardian_core.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.guardian_core.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.guardian_core.lore3");
                                String lore4 = this.plugin.lang.tr(crafter, "item.guardian_core.lore4");
                                String lore5 = this.plugin.lang.tr(crafter, "item.guardian_core.lore5");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3, lore4, lore5));
                            }

                            result.setItemMeta(meta);
                        }

                        e.getInventory().setResult(result);
                    }
                }
            }
        }
    }

    private boolean isFrostCore(ItemStack stack) {
        if (stack != null && stack.hasItemMeta()) {
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            Byte flag = (Byte)pdc.get(this.frostCoreTagKey, PersistentDataType.BYTE);
            return flag != null && flag == 1;
        } else {
            return false;
        }
    }

    private boolean isGuardianCore(ItemStack stack) {
        if (stack != null && stack.hasItemMeta()) {
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            Byte flag = (Byte)pdc.get(this.guardianCoreTagKey, PersistentDataType.BYTE);
            return flag != null && flag == 1;
        } else {
            return false;
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onInteractGolem(PlayerInteractEntityEvent e) {
        if (e.getHand() == EquipmentSlot.HAND) {
            Player player = e.getPlayer();
            Entity clicked = e.getRightClicked();
            ItemStack item = player.getInventory().getItemInMainHand();
            boolean frostCore = this.isFrostCore(item);
            boolean guardianCore = this.isGuardianCore(item);
            if (frostCore || guardianCore) {
                if (clicked instanceof Snowman) {
                    Snowman snow = (Snowman)clicked;
                    if (frostCore) {
                        if (!this.canUpgradeInChunk(snow.getLocation())) {
                            player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.too_many").replace("{max}", String.valueOf(this.getMaxPerChunk())));
                            return;
                        }

                        this.upgradeSnowGolem(player, snow);
                        this.consumeOne(player, item);
                        player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.frost_ok"));
                        e.setCancelled(true);
                        return;
                    }
                }

                if (clicked instanceof IronGolem) {
                    IronGolem iron = (IronGolem)clicked;
                    if (guardianCore) {
                        if (!this.canUpgradeInChunk(iron.getLocation())) {
                            player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.too_many").replace("{max}", String.valueOf(this.getMaxPerChunk())));
                            return;
                        }

                        this.upgradeIronGolem(player, iron);
                        this.consumeOne(player, item);
                        player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.guardian_ok"));
                        e.setCancelled(true);
                        return;
                    }
                }

                player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.wrong_target"));
            }
        }
    }

    private void consumeOne(Player player, ItemStack stack) {
        if (stack != null) {
            int amt = stack.getAmount();
            if (amt <= 1) {
                player.getInventory().setItemInMainHand((ItemStack)null);
            } else {
                stack.setAmount(amt - 1);
            }

            player.updateInventory();
        }
    }

    private int getMaxPerChunk() {
        return this.plugin.getConfig().getInt("golems.max_per_chunk", 3);
    }

    private boolean canUpgradeInChunk(Location loc) {
        int max = this.getMaxPerChunk();
        if (max <= 0) {
            return true;
        } else {
            Chunk chunk = loc.getChunk();
            int count = 0;

            for(Entity e : chunk.getEntities()) {
                if (e instanceof Golem) {
                    GolemType type = this.getGolemType(e);
                    if (type != null) {
                        ++count;
                        if (count >= max) {
                            return false;
                        }
                    }
                }
            }

            return true;
        }
    }

    private void setGolemType(Entity e, GolemType type) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(this.golemTypeKey, PersistentDataType.STRING, type.name());
    }

    private GolemType getGolemType(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        String v = (String)pdc.get(this.golemTypeKey, PersistentDataType.STRING);
        if (v == null) {
            return null;
        } else {
            try {
                return GolemUpgrades.GolemType.valueOf(v);
            } catch (IllegalArgumentException var5) {
                return null;
            }
        }
    }

    private void upgradeSnowGolem(Player owner, Snowman snow) {
        this.setGolemType(snow, GolemUpgrades.GolemType.FROSTBOUND);
        double baseHealth = this.plugin.getConfig().getDouble("golems.frostbound.base_health", (double)30.0F);
        this.setMaxHealth(snow, baseHealth);
        snow.setHealth(baseHealth);
        String name = this.plugin.lang.tr(owner, "golem.frostbound.name");
        snow.setCustomName(name);
        snow.setCustomNameVisible(true);
    }

    private void upgradeIronGolem(Player owner, IronGolem iron) {
        this.setGolemType(iron, GolemUpgrades.GolemType.GUARDIAN);
        double baseHealth = this.plugin.getConfig().getDouble("golems.guardian.base_health", (double)120.0F);
        this.setMaxHealth(iron, baseHealth);
        iron.setHealth(baseHealth);
        String name = this.plugin.lang.tr(owner, "golem.guardian.name");
        iron.setCustomName(name);
        iron.setCustomNameVisible(true);
        AttributeInstance dmg = iron.getAttribute(Attribute.MAX_HEALTH);
        if (dmg != null) {
            double vanilla = dmg.getBaseValue();
            double mult = this.plugin.getConfig().getDouble("golems.guardian.base_damage_multiplier", 1.2);
            dmg.setBaseValue(vanilla * mult);
        }

    }

    private void setMaxHealth(LivingEntity ent, double value) {
        AttributeInstance max = ent.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(Math.max((double)1.0F, value));
        }

    }

    public void run() {
        this.tickGolems();
    }

    private void tickGolems() {
        Season season = this.resolveCurrentSeason();
        double frostBase = this.plugin.getConfig().getDouble("golems.frostbound.base_health", (double)30.0F);
        double frostWinMul = this.plugin.getConfig().getDouble("golems.frostbound.winter_health_multiplier", (double)2.0F);
        double frostOtherMul = this.plugin.getConfig().getDouble("golems.frostbound.other_seasons_health_multiplier", 1.4);
        double guardBase = this.plugin.getConfig().getDouble("golems.guardian.base_health", (double)120.0F);
        double guardWinMul = this.plugin.getConfig().getDouble("golems.guardian.winter_health_multiplier", (double)1.5F);

        for(World w : Bukkit.getWorlds()) {
            for(LivingEntity ent : w.getLivingEntities()) {
                GolemType type = this.getGolemType(ent);
                if (type != null) {
                    if (type == GolemUpgrades.GolemType.FROSTBOUND && ent instanceof Snowman) {
                        Snowman snow = (Snowman)ent;
                        double mul = season == Season.WINTER ? frostWinMul : frostOtherMul;
                        this.setMaxHealth(snow, frostBase * mul);
                        AttributeInstance max = snow.getAttribute(Attribute.MAX_HEALTH);
                        if (max != null && snow.getHealth() > max.getBaseValue()) {
                            snow.setHealth(max.getBaseValue());
                        }
                    } else if (type == GolemUpgrades.GolemType.GUARDIAN && ent instanceof IronGolem) {
                        IronGolem iron = (IronGolem)ent;
                        double mul = season == Season.WINTER ? guardWinMul : (double)1.0F;
                        this.setMaxHealth(iron, guardBase * mul);
                        AttributeInstance max = iron.getAttribute(Attribute.MAX_HEALTH);
                        if (max != null && iron.getHealth() > max.getBaseValue()) {
                            iron.setHealth(max.getBaseValue());
                        }
                    }
                }
            }
        }

    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onGolemAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof LivingEntity) {
            LivingEntity damager = (LivingEntity)e.getDamager();
            GolemType type = this.getGolemType(damager);
            if (type != null) {
                Season season = this.resolveCurrentSeason();
                if (type == GolemUpgrades.GolemType.GUARDIAN && damager instanceof IronGolem) {
                    double baseMult = this.plugin.getConfig().getDouble("golems.guardian.base_damage_multiplier", 1.2);
                    double winterMult = this.plugin.getConfig().getDouble("golems.guardian.winter_damage_multiplier", 1.8);
                    double mult = baseMult;
                    if (season == Season.WINTER) {
                        mult = baseMult * winterMult;
                    }

                    e.setDamage(e.getDamage() * mult);
                    if (season == Season.SUMMER) {
                        int fireTicks = this.plugin.getConfig().getInt("golems.guardian.summer_fire_ticks_on_hit", 60);
                        Entity var13 = e.getEntity();
                        if (var13 instanceof LivingEntity) {
                            LivingEntity le = (LivingEntity)var13;
                            if (fireTicks > 0) {
                                le.setFireTicks(Math.max(le.getFireTicks(), fireTicks));
                            }
                        }
                    }
                }

            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onSnowballHit(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Snowball snowball) {
            ProjectileSource src = snowball.getShooter();
            if (src instanceof Snowman snow) {
                GolemType type = this.getGolemType(snow);
                if (type == GolemUpgrades.GolemType.FROSTBOUND) {
                    Entity var7 = e.getEntity();
                    if (var7 instanceof LivingEntity) {
                        LivingEntity target = (LivingEntity)var7;
                        double var12 = this.plugin.getConfig().getDouble("golems.frostbound.snowball_damage", (double)1.0F);
                        e.setDamage(var12);
                        int slowTicks = this.plugin.getConfig().getInt("golems.frostbound.snowball_slow_ticks", 60);
                        int slowLvl = this.plugin.getConfig().getInt("golems.frostbound.snowball_slow_level", 1);
                        if (slowTicks > 0) {
                            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, Math.max(0, slowLvl - 1), true, true, true));
                        }

                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onGolemDamage(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        GolemType type = this.getGolemType(ent);
        if (type != null) {
            Season season = this.resolveCurrentSeason();
            if (ent instanceof Snowman) {
                Snowman snow = (Snowman)ent;
                if (type == GolemUpgrades.GolemType.FROSTBOUND) {
                    EnumSet<EntityDamageEvent.DamageCause> envCauses = EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR, DamageCause.DROWNING, DamageCause.FREEZE, DamageCause.MELTING);
                    if (envCauses.contains(e.getCause())) {
                        e.setCancelled(true);
                        snow.setFireTicks(0);
                        return;
                    }

                    return;
                }
            }

            if (ent instanceof IronGolem) {
                IronGolem iron = (IronGolem)ent;
                if (type == GolemUpgrades.GolemType.GUARDIAN) {
                    if (season != Season.SUMMER) {
                        return;
                    }

                    boolean fireImmune = this.plugin.getConfig().getBoolean("golems.guardian.summer_fire_immunity", true);
                    if (!fireImmune) {
                        return;
                    }

                    EnumSet<EntityDamageEvent.DamageCause> fireCauses = EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR);
                    if (fireCauses.contains(e.getCause())) {
                        e.setCancelled(true);
                        iron.setFireTicks(0);
                    }

                    return;
                }
            }

        }
    }

    private static enum GolemType {
        FROSTBOUND,
        GUARDIAN;

        private GolemType() {
        }

        // $FF: synthetic method
        private static GolemType[] $values() {
            return new GolemType[]{FROSTBOUND, GUARDIAN};
        }
    }
}
