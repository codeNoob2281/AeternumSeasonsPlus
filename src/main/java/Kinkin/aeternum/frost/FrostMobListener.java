package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.ArrayList;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 永霜世界怪物强化监听器。
 *
 * <p>仅在 {@code aeternum_frost} 世界生效：自然生成的怪物会被标记为“永霜变体”并强化
 * 属性（生命、攻击、移速），僵尸替换为戴冰盔的溺尸、骷髅替换为流浪者；同时为不同怪物
 * 的攻击附加冰冻与减速等效果（蜘蛛结网、末影人传送、掠夺者/卫道士、溺尸冰霜打击、骷髅
 * 冰箭），并让永霜怪物免疫燃烧、被强化苦力怕爆炸时以细雪代替破坏。依赖
 * {@link AeternumSeasonsPlugin} 完成事件注册与延迟任务调度。</p>
 */
public final class FrostMobListener implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey FROST_VARIANT_KEY;
    private final Random random = new Random();
    private static final double FROST_MAX_HEALTH_CAP = (double)1024.0F;

    public FrostMobListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.FROST_VARIANT_KEY = new NamespacedKey(plugin, "frost_variant");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 判断实体是否位于永霜世界。
     */
    private boolean inFrostWorld(Entity entity) {
        World world = entity.getWorld();
        return world != null && world.getName().equalsIgnoreCase("aeternum_frost");
    }

    /**
     * 将实体标记为永霜变体。
     */
    private void markFrost(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(this.FROST_VARIANT_KEY, PersistentDataType.BYTE, (byte)1);
    }

    /**
     * 判断实体是否已被标记为永霜变体。
     */
    private boolean isFrost(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = (Byte)pdc.get(this.FROST_VARIANT_KEY, PersistentDataType.BYTE);
        return flag != null && flag == 1;
    }

    /**
     * 处理永霜世界的怪物生成：强化并标记怪物，并按类型替换为对应的永霜变体。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (this.inFrostWorld(entity)) {
            if (entity instanceof Monster) {
                if (entity instanceof Zombie && !(entity instanceof Drowned)) {
                    World world = entity.getWorld();
                    Location loc = entity.getLocation();
                    event.setCancelled(true);
                    world.spawn(loc, Drowned.class, (drowned) -> {
                        this.markFrost(drowned);
                        this.buffMonster(drowned);
                        EntityEquipment equipment = drowned.getEquipment();
                        if (equipment != null) {
                            equipment.setHelmet(new ItemStack(Material.ICE));
                            equipment.setHelmetDropChance(0.0F);
                        }

                    });
                } else {
                    if (!this.isFrost(entity)) {
                        this.markFrost(entity);
                        this.buffMonster(entity);
                    }

                    if (entity instanceof Skeleton) {
                        Skeleton skeleton = (Skeleton)entity;
                        if (skeleton instanceof Stray) {
                            return;
                        }

                        World world = skeleton.getWorld();
                        Location loc = skeleton.getLocation();
                        skeleton.remove();
                        world.spawn(loc, Stray.class, (stray) -> {
                            this.markFrost(stray);
                            this.buffMonster(stray);
                        });
                    } else if (entity instanceof Spider) {
                        Spider spider = (Spider)entity;
                    } else if (entity instanceof Creeper) {
                        Creeper creeper = (Creeper)entity;
                    } else if (entity instanceof Enderman) {
                        Enderman enderman = (Enderman)entity;
                    } else if (!(entity instanceof Vindicator) && entity instanceof Pillager) {
                    }

                }
            }
        }
    }

    /**
     * 强化怪物属性：生命翻倍（上限 1024）、攻击提升 50%、移速提升 10%。
     */
    private void buffMonster(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            double base = maxHealth.getBaseValue();
            double newMax = base * (double)2.0F;
            if (newMax > (double)1024.0F) {
                newMax = (double)1024.0F;
            }

            if (newMax < (double)1.0F) {
                newMax = (double)1.0F;
            }

            maxHealth.setBaseValue(newMax);
            entity.setHealth(newMax);
        }

        AttributeInstance attack = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getBaseValue() * (double)1.5F);
        }

        AttributeInstance movement = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * 1.1);
        }

    }

    /**
     * 永霜蜘蛛命中目标时，在目标周围铺设蛛网（短暂后自动清除）并施加冻结。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpiderHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Spider spider) {
            if (this.inFrostWorld(spider) && this.isFrost(spider)) {
                Entity hitEntity = event.getEntity();
                if (hitEntity instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)hitEntity;
                    World world = spider.getWorld();
                    Location loc = victim.getLocation();
                    ArrayList<Block> placed = new ArrayList<>();

                    for(int dx = -1; dx <= 1; ++dx) {
                        for(int dz = -1; dz <= 1; ++dz) {
                            Location spot = loc.clone().add((double)dx, (double)0.0F, (double)dz);
                            Block block = world.getBlockAt(spot);
                            if (block.getType().isAir()) {
                                block.setType(Material.COBWEB, false);
                                placed.add(block);
                            }
                        }
                    }

                    if (!placed.isEmpty()) {
                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                            for(Block block : placed) {
                                if (block.getType() == Material.COBWEB) {
                                    block.setType(Material.AIR, false);
                                }
                            }

                        }, 60L);
                    }

                    if (victim instanceof Player) {
                        Player player = (Player)victim;
                        player.setFreezeTicks(Math.min(player.getFreezeTicks() + 40, 200));
                    }

                }
            }
        }
    }

    /**
     * 永霜苦力怕爆炸时取消方块破坏，改用范围细雪铺设并播放雪花音效/粒子。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onCreeperExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Creeper creeper) {
            if (this.inFrostWorld(creeper) && this.isFrost(creeper)) {
                World world = creeper.getWorld();
                Location center = creeper.getLocation();
                event.blockList().clear();
                int radius = 3;

                for(int dx = -radius; dx <= radius; ++dx) {
                    for(int dz = -radius; dz <= radius; ++dz) {
                        double distSq = (double)(dx * dx + dz * dz);
                        if (!(distSq > (double)(radius * radius))) {
                            int x = center.getBlockX() + dx;
                            int z = center.getBlockZ() + dz;
                            int y = center.getBlockY();
                            Block ground = world.getBlockAt(x, y - 1, z);
                            Block target = world.getBlockAt(x, y, z);
                            if (target.getType().isAir() && ground.getType().isSolid()) {
                                target.setType(Material.POWDER_SNOW, false);
                            }
                        }
                    }
                }

                world.playSound(center, Sound.BLOCK_POWDER_SNOW_FALL, 1.0F, 0.8F);
                world.spawnParticle(Particle.SNOWFLAKE, center, 80, (double)1.5F, (double)1.0F, (double)1.5F, 0.1);
            }
        }
    }

    /**
     * 永霜骷髅射出的箭命中目标时施加冻结，若目标是玩家则额外减速。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onArrowHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            if (projectile instanceof Arrow) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Skeleton) {
                    Skeleton skeleton = (Skeleton)shooter;
                    if (this.inFrostWorld(skeleton) && this.isFrost(skeleton)) {
                        Entity hitEntity = event.getEntity();
                        if (hitEntity instanceof LivingEntity) {
                            LivingEntity victim = (LivingEntity)hitEntity;
                            victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 60, 200));
                            if (victim instanceof Player) {
                                Player player = (Player)victim;
                                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, true, true));
                            }

                        }
                    }
                }
            }
        }
    }

    /**
     * 永霜末影人命中玩家时，尝试将其传送到附近开阔地面并施加冻结。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onEndermanHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Enderman enderman) {
            if (this.inFrostWorld(enderman) && this.isFrost(enderman)) {
                Entity hitEntity = event.getEntity();
                if (hitEntity instanceof Player) {
                    Player player = (Player)hitEntity;
                    Location base = player.getLocation();
                    World world = base.getWorld();
                    if (world != null) {
                        for(int tries = 0; tries < 10; ++tries) {
                            double dx = (this.random.nextDouble() - (double)0.5F) * (double)8.0F;
                            double dz = (this.random.nextDouble() - (double)0.5F) * (double)8.0F;
                            Location candidate = base.clone().add(dx, (double)0.0F, dz);
                            candidate.setY((double)(world.getHighestBlockYAt(candidate) + 1));
                            if (candidate.getBlock().getType().isAir() && candidate.clone().add((double)0.0F, (double)1.0F, (double)0.0F).getBlock().getType().isAir()) {
                                player.teleport(candidate);
                                player.setFreezeTicks(Math.min(player.getFreezeTicks() + 40, 200));
                                world.playSound(candidate, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.6F);
                                break;
                            }
                        }

                    }
                }
            }
        }
    }

    /**
     * 永霜卫道士/掠夺者命中目标时施加冻结，玩家额外获得减速与缓降。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onIllagerHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Vindicator || damager instanceof Pillager) {
            if (this.inFrostWorld(damager) && this.isFrost(damager)) {
                Entity hitEntity = event.getEntity();
                if (hitEntity instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)hitEntity;
                    victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 80, 200));
                    if (victim instanceof Player) {
                        Player player = (Player)victim;
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, true, true, true));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 1, true, true, true));
                    }

                }
            }
        }
    }

    /**
     * 永霜溺尸命中目标时施加冻结，玩家额外获得减速、挖掘疲劳与冰霜特效。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onDrownedHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Drowned drowned) {
            if (this.inFrostWorld(drowned) && this.isFrost(drowned)) {
                Entity hitEntity = event.getEntity();
                if (hitEntity instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)hitEntity;
                    victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 80, 200));
                    if (victim instanceof Player) {
                        Player player = (Player)victim;
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, true, true, true));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 0, true, true, true));
                        World world = player.getWorld();
                        world.playSound(player.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1.0F, 0.7F);
                        world.spawnParticle(Particle.SNOWFLAKE, player.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 20, 0.3, 0.6, 0.3, 0.05);
                    }

                }
            }
        }
    }

    /**
     * 永霜世界中的怪物免疫燃烧（取消燃烧事件并清零火焰 tick）。
     */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onFrostMobCombust(EntityCombustEvent event) {
        Entity entity = event.getEntity();
        if (this.inFrostWorld(entity)) {
            if (entity instanceof Monster) {
                event.setCancelled(true);
                if (entity instanceof LivingEntity) {
                    LivingEntity livingEntity = (LivingEntity)entity;
                    livingEntity.setFireTicks(0);
                }

            }
        }
    }
}
