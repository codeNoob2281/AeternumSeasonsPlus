package kinkin.aeternum.crafting;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * 傀儡升级（Golem Upgrades）。
 *
 * <p>本类提供两种可合成的“核心”物品，用于升级雪傀儡与铁傀儡，并周期性维护其属性：
 * <ul>
 *     <li>注册霜核（积雪球）与守卫核心（海晶碎片/紫水晶）的合成配方，并在合成预览时写入名称、描述与持久化标签；</li>
 *     <li>玩家手持对应核心右击雪傀儡/铁傀儡即可将其升级为“霜缚/守卫”傀儡（受每区块数量上限约束）；</li>
 *     <li>{@link #run()} 定时按季节调整已升级傀儡的最大生命值；冬季更耐打；</li>
 *     <li>监听伤害事件：守卫傀儡冬季伤害更高、夏季附加点燃；霜缚傀儡免疫环境伤害、雪球攻击附带减速。</li>
 * </ul>
 * 依赖 {@link AeternumSeasonsPlugin} 提供语言文本与配置，{@link SeasonService} 提供当前季节。
 * 由于季节 API 存在多个版本，{@link #resolveCurrentSeason()} 通过反射兼容多种方法名。</p>
 */
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

    /**
     * 向服务器注册事件监听器与合成配方，并启动周期任务。
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registerRecipes();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, 40L);
    }

    /**
     * 注销事件监听器、停止周期任务并移除已注册的合成配方。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        if (this.task != null) {
            this.task.cancel();
        }

        Bukkit.removeRecipe(this.frostCoreRecipeKey);
        Bukkit.removeRecipe(this.guardianCoreRecipeKey);
    }

    /**
     * 通过反射解析当前季节，兼容 getCurrentSeason/getSeason/getState/currentState 等多种 API；
     * 无法解析时回退为春季。
     */
    private Season resolveCurrentSeason() {
        if (this.seasons == null) {
            return Season.SPRING;
        } else {
            try {
                try {
                    Method method = this.seasons.getClass().getMethod("getCurrentSeason");
                    Object result = method.invoke(this.seasons);
                    if (result instanceof Season) {
                        Season season = (Season) result;
                        return season;
                    }
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    Method method = this.seasons.getClass().getMethod("getSeason");
                    Object result = method.invoke(this.seasons);
                    if (result instanceof Season) {
                        Season season = (Season) result;
                        return season;
                    }
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    Method method = this.seasons.getClass().getMethod("getState");
                    Object result = method.invoke(this.seasons);
                    if (result instanceof CalendarState) {
                        CalendarState state = (CalendarState) result;
                        if (state.season != null) {
                            return state.season;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    Method method = this.seasons.getClass().getMethod("currentState");
                    Object result = method.invoke(this.seasons);
                    if (result instanceof CalendarState) {
                        CalendarState state = (CalendarState) result;
                        if (state.season != null) {
                            return state.season;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }
            } catch (Throwable throwable) {
                this.plugin.getLogger().warning("[Golems] No se pudo resolver la estación actual: " + throwable.getMessage());
            }

            return Season.SPRING;
        }
    }

    /**
     * 注册霜核与守卫核心的合成配方。
     */
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

    /**
     * 监听合成预览事件：当配方为霜核或守卫核心时，
     * 为产物写入对应名称、描述并打上持久化标签。
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe) {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                NamespacedKey recipeKey = shaped.getKey();
                boolean frost = this.frostCoreRecipeKey.equals(recipeKey);
                boolean guardian = this.guardianCoreRecipeKey.equals(recipeKey);
                if (frost || guardian) {
                    ItemStack[] matrix = event.getInventory().getMatrix();
                    if (matrix != null) {
                        for (ItemStack stack : matrix) {
                            if (stack != null && stack.getType() == Material.AIR) {
                            }
                        }

                        Player crafter = null;
                        HumanEntity viewer = event.getView().getPlayer();
                        if (viewer instanceof Player) {
                            Player player = (Player) viewer;
                            crafter = player;
                        }

                        ItemStack result = event.getInventory().getResult();
                        if (result == null) {
                            result = frost ? new ItemStack(Material.SNOWBALL) : new ItemStack(Material.PRISMARINE_CRYSTALS);
                        }

                        ItemMeta meta = result.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            if (frost) {
                                pdc.set(this.frostCoreTagKey, PersistentDataType.BYTE, (byte) 1);
                                String name = this.plugin.lang.tr(crafter, "item.frost_core.name");
                                String lore1 = this.plugin.lang.tr(crafter, "item.frost_core.lore1");
                                String lore2 = this.plugin.lang.tr(crafter, "item.frost_core.lore2");
                                String lore3 = this.plugin.lang.tr(crafter, "item.frost_core.lore3");
                                String lore4 = this.plugin.lang.tr(crafter, "item.frost_core.lore4");
                                String lore5 = this.plugin.lang.tr(crafter, "item.frost_core.lore5");
                                meta.setDisplayName(name);
                                meta.setLore(Arrays.asList(lore1, lore2, lore3, lore4, lore5));
                            } else if (guardian) {
                                pdc.set(this.guardianCoreTagKey, PersistentDataType.BYTE, (byte) 1);
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

                        event.getInventory().setResult(result);
                    }
                }
            }
        }
    }

    /**
     * 判断物品是否为霜核（带对应持久化标签）。
     */
    private boolean isFrostCore(ItemStack stack) {
        if (stack != null && stack.hasItemMeta()) {
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            Byte flag = (Byte) pdc.get(this.frostCoreTagKey, PersistentDataType.BYTE);
            return flag != null && flag == 1;
        } else {
            return false;
        }
    }

    /**
     * 判断物品是否为守卫核心（带对应持久化标签）。
     */
    private boolean isGuardianCore(ItemStack stack) {
        if (stack != null && stack.hasItemMeta()) {
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            Byte flag = (Byte) pdc.get(this.guardianCoreTagKey, PersistentDataType.BYTE);
            return flag != null && flag == 1;
        } else {
            return false;
        }
    }

    /**
     * 监听玩家右键实体：手持霜核/守卫核心右击对应傀儡时执行升级并消耗核心。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onInteractGolem(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            Player player = event.getPlayer();
            Entity clicked = event.getRightClicked();
            ItemStack item = player.getInventory().getItemInMainHand();
            boolean frostCore = this.isFrostCore(item);
            boolean guardianCore = this.isGuardianCore(item);
            if (frostCore || guardianCore) {
                if (clicked instanceof Snowman) {
                    Snowman snow = (Snowman) clicked;
                    if (frostCore) {
                        if (!this.canUpgradeInChunk(snow.getLocation())) {
                            player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.too_many").replace("{max}", String.valueOf(this.getMaxPerChunk())));
                            return;
                        }

                        this.upgradeSnowGolem(player, snow);
                        this.consumeOne(player, item);
                        player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.frost_ok"));
                        event.setCancelled(true);
                        return;
                    }
                }

                if (clicked instanceof IronGolem) {
                    IronGolem iron = (IronGolem) clicked;
                    if (guardianCore) {
                        if (!this.canUpgradeInChunk(iron.getLocation())) {
                            player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.too_many").replace("{max}", String.valueOf(this.getMaxPerChunk())));
                            return;
                        }

                        this.upgradeIronGolem(player, iron);
                        this.consumeOne(player, item);
                        player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.guardian_ok"));
                        event.setCancelled(true);
                        return;
                    }
                }

                player.sendMessage(this.plugin.lang.tr(player, "golem.upgrade.wrong_target"));
            }
        }
    }

    /**
     * 从玩家主手中消耗一个指定物品。
     */
    private void consumeOne(Player player, ItemStack stack) {
        if (stack != null) {
            int amount = stack.getAmount();
            if (amount <= 1) {
                player.getInventory().setItemInMainHand((ItemStack) null);
            } else {
                stack.setAmount(amount - 1);
            }

            player.updateInventory();
        }
    }

    /**
     * 读取配置中每区块允许的已升级傀儡数量上限。
     */
    private int getMaxPerChunk() {
        return this.plugin.getConfig().getInt("golems.max_per_chunk", 3);
    }

    /**
     * 判断指定位置所在区块是否仍可再升级一只傀儡（未达到上限）。
     */
    private boolean canUpgradeInChunk(Location loc) {
        int max = this.getMaxPerChunk();
        if (max <= 0) {
            return true;
        } else {
            Chunk chunk = loc.getChunk();
            int count = 0;

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Golem) {
                    GolemType type = this.getGolemType(entity);
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

    /**
     * 将傀儡的升级类型写入其持久化数据。
     */
    private void setGolemType(Entity entity, GolemType type) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(this.golemTypeKey, PersistentDataType.STRING, type.name());
    }

    /**
     * 读取傀儡的升级类型；无标签或值非法时返回 null。
     */
    private GolemType getGolemType(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String storedType = (String) pdc.get(this.golemTypeKey, PersistentDataType.STRING);
        if (storedType == null) {
            return null;
        } else {
            try {
                return GolemUpgrades.GolemType.valueOf(storedType);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /**
     * 将雪傀儡升级为霜缚傀儡：写入类型、设置最大生命值与自定义名称。
     */
    private void upgradeSnowGolem(Player owner, Snowman snow) {
        this.setGolemType(snow, GolemUpgrades.GolemType.FROSTBOUND);
        double baseHealth = this.plugin.getConfig().getDouble("golems.frostbound.base_health", (double) 30.0F);
        this.setMaxHealth(snow, baseHealth);
        snow.setHealth(baseHealth);
        String name = this.plugin.lang.tr(owner, "golem.frostbound.name");
        snow.setCustomName(name);
        snow.setCustomNameVisible(true);
    }

    /**
     * 将铁傀儡升级为守卫傀儡：写入类型、设置最大生命值、名称与伤害倍率。
     */
    private void upgradeIronGolem(Player owner, IronGolem iron) {
        this.setGolemType(iron, GolemUpgrades.GolemType.GUARDIAN);
        double baseHealth = this.plugin.getConfig().getDouble("golems.guardian.base_health", (double) 120.0F);
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

    /**
     * 设置生物的最大生命值（下限为 1）。
     */
    private void setMaxHealth(LivingEntity entity, double value) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(Math.max((double) 1.0F, value));
        }

    }

    /**
     * 周期任务入口：刷新所有已升级傀儡的属性。
     */
    @Override
    public void run() {
        this.tickGolems();
    }

    /**
     * 遍历所有世界中的已升级傀儡，按当前季节刷新其最大生命值并修正溢出生命。
     */
    private void tickGolems() {
        Season season = this.resolveCurrentSeason();
        double frostBase = this.plugin.getConfig().getDouble("golems.frostbound.base_health", (double) 30.0F);
        double frostWinMul = this.plugin.getConfig().getDouble("golems.frostbound.winter_health_multiplier", (double) 2.0F);
        double frostOtherMul = this.plugin.getConfig().getDouble("golems.frostbound.other_seasons_health_multiplier", 1.4);
        double guardBase = this.plugin.getConfig().getDouble("golems.guardian.base_health", (double) 120.0F);
        double guardWinMul = this.plugin.getConfig().getDouble("golems.guardian.winter_health_multiplier", (double) 1.5F);

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                GolemType type = this.getGolemType(entity);
                if (type != null) {
                    if (type == GolemUpgrades.GolemType.FROSTBOUND && entity instanceof Snowman) {
                        Snowman snow = (Snowman) entity;
                        double mul = season == Season.WINTER ? frostWinMul : frostOtherMul;
                        this.setMaxHealth(snow, frostBase * mul);
                        AttributeInstance max = snow.getAttribute(Attribute.MAX_HEALTH);
                        if (max != null && snow.getHealth() > max.getBaseValue()) {
                            snow.setHealth(max.getBaseValue());
                        }
                    } else if (type == GolemUpgrades.GolemType.GUARDIAN && entity instanceof IronGolem) {
                        IronGolem iron = (IronGolem) entity;
                        double mul = season == Season.WINTER ? guardWinMul : (double) 1.0F;
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

    /**
     * 监听傀儡攻击：守卫傀儡在冬季提升伤害，夏季命中时点燃目标。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onGolemAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity) {
            LivingEntity damager = (LivingEntity) event.getDamager();
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

                    event.setDamage(event.getDamage() * mult);
                    if (season == Season.SUMMER) {
                        int fireTicks = this.plugin.getConfig().getInt("golems.guardian.summer_fire_ticks_on_hit", 60);
                        Entity entity = event.getEntity();
                        if (entity instanceof LivingEntity) {
                            LivingEntity target = (LivingEntity) entity;
                            if (fireTicks > 0) {
                                target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * 监听霜缚傀儡的雪球命中：设置伤害并对目标附加减速。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onSnowballHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Snowball snowball) {
            ProjectileSource src = snowball.getShooter();
            if (src instanceof Snowman snow) {
                GolemType type = this.getGolemType(snow);
                if (type == GolemUpgrades.GolemType.FROSTBOUND) {
                    Entity entity = event.getEntity();
                    if (entity instanceof LivingEntity) {
                        LivingEntity target = (LivingEntity) entity;
                        double damage = this.plugin.getConfig().getDouble("golems.frostbound.snowball_damage", (double) 1.0F);
                        event.setDamage(damage);
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

    /**
     * 监听傀儡受伤：霜缚傀儡免疫火焰/熔岩/溺水/冰冻等环境伤害；
     * 守卫傀儡在夏季（若开启火焰免疫）免疫火焰类伤害。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onGolemDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        GolemType type = this.getGolemType(entity);
        if (type != null) {
            Season season = this.resolveCurrentSeason();
            if (entity instanceof Snowman) {
                Snowman snow = (Snowman) entity;
                if (type == GolemUpgrades.GolemType.FROSTBOUND) {
                    EnumSet<EntityDamageEvent.DamageCause> envCauses = EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR, DamageCause.DROWNING, DamageCause.FREEZE, DamageCause.MELTING);
                    if (envCauses.contains(event.getCause())) {
                        event.setCancelled(true);
                        snow.setFireTicks(0);
                        return;
                    }

                    return;
                }
            }

            if (entity instanceof IronGolem) {
                IronGolem iron = (IronGolem) entity;
                if (type == GolemUpgrades.GolemType.GUARDIAN) {
                    if (season != Season.SUMMER) {
                        return;
                    }

                    boolean fireImmune = this.plugin.getConfig().getBoolean("golems.guardian.summer_fire_immunity", true);
                    if (!fireImmune) {
                        return;
                    }

                    EnumSet<EntityDamageEvent.DamageCause> fireCauses = EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR);
                    if (fireCauses.contains(event.getCause())) {
                        event.setCancelled(true);
                        iron.setFireTicks(0);
                    }

                    return;
                }
            }

        }
    }

    /**
     * 参与升级的傀儡类型：FROSTBOUND 为霜缚雪傀儡，GUARDIAN 为守卫铁傀儡。
     */
    private enum GolemType {
        FROSTBOUND,
        GUARDIAN;

        GolemType() {
        }

        // $FF: synthetic method
        private static GolemType[] $values() {
            return new GolemType[]{FROSTBOUND, GUARDIAN};
        }
    }
}
