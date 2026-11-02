package Kinkin.aeternum.heat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 炎热维度怪物变种监听器。
 *
 * <p>在炎热世界 {@code aeternum_heat} 中，为敌对生物打上 {@code heat_variant} 标签（“变种”），
 * 并为其赋予对应的元素强化：</p>
 * <ul>
 *     <li>骷髅射出的火焰箭会点燃地面并灼烧目标；</li>
 *     <li>僵尸/猪灵等近战命中附加基础火焰与范围爆炸点燃；</li>
 *     <li>蜘蛛命中生成火焰、末影人命中附带灼烧，苦力怕爆炸会留下岩浆与火焰，末影人传送会引燃周围玩家。</li>
 * </ul>
 * <p>所有变种识别均依赖持久化数据键，确保只强化一次。</p>
 */
public final class HeatVariantListener implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey VARIANT_KEY;
    private final NamespacedKey ARROW_KEY;
    private final Random random = new Random();

    public HeatVariantListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.VARIANT_KEY = new NamespacedKey(plugin, "heat_variant");
        this.ARROW_KEY = new NamespacedKey(plugin, "heat_arrow");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** 判断实体是否位于炎热世界。 */
    private boolean inHeatWorld(Entity entity) {
        World world = entity.getWorld();
        return world != null && world.getName().equalsIgnoreCase("aeternum_heat");
    }

    /** 判断生物是否为敌对生物（怪物、史莱姆或幻翼）。 */
    private boolean isHostile(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom;
    }

    /** 为生物打上变种标签。 */
    private void markVariant(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(this.VARIANT_KEY, PersistentDataType.BYTE, (byte)1);
    }

    /** 判断实体是否为变种生物。 */
    private boolean isVariant(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = (Byte)pdc.get(this.VARIANT_KEY, PersistentDataType.BYTE);
        return flag != null && flag == 1;
    }

    /** 监听生物生成：在炎热世界中标记新出现的敌对生物并保证其基础生命值。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity entity = e.getEntity();
        if (this.inHeatWorld(entity)) {
            if (this.isHostile(entity)) {
                if (!this.isVariant(entity)) {
                    this.markVariant(entity);
                    entity.getWorld().spawnParticle(Particle.FLAME, entity.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 16, 0.4, 0.4, 0.4, 0.02);
                }

                AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null && maxHealth.getBaseValue() < (double)1.0F) {
                    maxHealth.setBaseValue((double)20.0F);
                    entity.setHealth((double)20.0F);
                }

            }
        }
    }

    /** 监听骷髅射箭：变种骷髅的箭矢会被点燃并打上标记。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onShoot(EntityShootBowEvent e) {
        LivingEntity entity = e.getEntity();
        if (entity instanceof Skeleton sk) {
            if (this.inHeatWorld(sk) && this.isVariant(sk)) {
                Entity projectile = e.getProjectile();
                if (projectile instanceof Arrow) {
                    Arrow arrow = (Arrow)projectile;
                    arrow.setFireTicks(200);
                    arrow.setCritical(true);
                    PersistentDataContainer pdc = arrow.getPersistentDataContainer();
                    pdc.set(this.ARROW_KEY, PersistentDataType.BYTE, (byte)1);
                }
            }
        }
    }

    /** 监听箭矢命中：被标记的火焰箭会点燃落点并灼烧命中的生物。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onArrowHit(ProjectileHitEvent e) {
        Projectile projectile = e.getEntity();
        if (projectile instanceof Arrow arrow) {
            PersistentDataContainer pdc = arrow.getPersistentDataContainer();
            Byte tag = (Byte)pdc.get(this.ARROW_KEY, PersistentDataType.BYTE);
            if (tag != null && tag == 1) {
                World world = arrow.getWorld();
                Location hitLoc = arrow.getLocation();
                Block block = world.getBlockAt(hitLoc);
                if (block.getType().isAir() || block.isPassable()) {
                    block.setType(Material.FIRE, false);
                }

                Entity hitEntity = e.getHitEntity();
                if (hitEntity instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)hitEntity;
                    victim.setFireTicks(Math.max(victim.getFireTicks(), 100));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, true, true));
                }

                world.spawnParticle(Particle.FLAME, hitLoc, 24, 0.4, 0.4, 0.4, 0.02);
            }
        }
    }

    /** 监听变种生物命中玩家/生物，按怪物种类施加对应的元素效果。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onHit(EntityDamageByEntityEvent e) {
        LivingEntity attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof LivingEntity livingDamager) {
            attacker = livingDamager;
        } else {
            damager = e.getDamager();
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof LivingEntity shooterLiving) {
                    attacker = shooterLiving;
                }
            }
        }

        if (attacker != null) {
            if (this.inHeatWorld(attacker) && this.isVariant(attacker)) {
                Entity entity = e.getEntity();
                if (entity instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)entity;
                    this.applyBaseFireHit(attacker, victim, e);
                    if (!(attacker instanceof Zombie) && !(attacker instanceof PigZombie) && !(attacker instanceof Piglin) && !(attacker instanceof PiglinBrute)) {
                        if (!(attacker instanceof Spider) && !(attacker instanceof CaveSpider)) {
                            if (attacker instanceof Enderman) {
                                this.applyEmberEnderman(victim);
                            }
                        } else {
                            this.applyMagmaSpider(victim);
                        }
                    } else {
                        this.applyBruteIgnis(victim, attacker);
                    }

                }
            }
        }
    }

    /** 基础火焰命中：点燃目标并放大伤害（创造/旁观玩家除外）。 */
    private void applyBaseFireHit(LivingEntity attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
        if (victim instanceof Player pl) {
            GameMode gm = pl.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                return;
            }
        }

        victim.setFireTicks(Math.max(victim.getFireTicks(), 80));
        e.setDamage(e.getDamage() * (double)1.25F);
        victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 12, 0.3, 0.3, 0.3, 0.01);
    }

    /** 烈焰近战：以目标为中心引爆并点燃周围除攻击者外的生物。 */
    private void applyBruteIgnis(LivingEntity victim, LivingEntity attacker) {
        World world = victim.getWorld();
        Location loc = victim.getLocation();
        double radius = (double)1.5F;
        world.spawnParticle(Particle.EXPLOSION, loc.clone().add((double)0.0F, (double)0.5F, (double)0.0F), 1, (double)0.0F, (double)0.0F, (double)0.0F, (double)0.0F);

        for(Entity nearby : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (nearby instanceof LivingEntity le) {
                if (!le.equals(attacker)) {
                    if (nearby instanceof Player) {
                        Player pl = (Player)nearby;
                        GameMode gm = pl.getGameMode();
                        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                            continue;
                        }
                    }

                    le.setFireTicks(Math.max(le.getFireTicks(), 60));
                }
            }
        }

    }

    /** 岩浆蜘蛛：在目标周围随机生成火焰并播放岩浆粒子。 */
    private void applyMagmaSpider(LivingEntity victim) {
        World world = victim.getWorld();
        Location loc = victim.getLocation();
        int count = 6 + this.random.nextInt(5);

        for(int i = 0; i < count; ++i) {
            double dx = (this.random.nextDouble() - (double)0.5F) * (double)2.0F;
            double dz = (this.random.nextDouble() - (double)0.5F) * (double)2.0F;
            Location spot = loc.clone().add(dx, (double)0.0F, dz);
            Block block = world.getBlockAt(spot);
            if (block.getType().isAir() || block.isPassable()) {
                block.setType(Material.FIRE, false);
            }
        }

        world.spawnParticle(Particle.LAVA, loc.add((double)0.0F, 0.2, (double)0.0F), 12, 0.4, 0.2, 0.4, 0.02);
    }

    /** 余烬末影人：点燃目标、造成额外伤害并播放火焰粒子。 */
    private void applyEmberEnderman(LivingEntity victim) {
        World world = victim.getWorld();
        Location loc = victim.getLocation();
        victim.setFireTicks(Math.max(victim.getFireTicks(), 80));
        victim.damage((double)0.5F);
        world.spawnParticle(Particle.FLAME, loc.add((double)0.0F, (double)1.0F, (double)0.0F), 24, 0.6, 0.6, 0.6, 0.02);
    }

    /** 监听苦力怕爆炸：变种苦力怕改为生成岩浆与火焰而非破坏方块。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof Creeper creeper) {
            if (this.inHeatWorld(creeper) && this.isVariant(creeper)) {
                Location loc = creeper.getLocation();
                World world = loc.getWorld();
                e.blockList().clear();
                double radius = (double)3.0F;

                for(double x = -radius; x <= radius; ++x) {
                    for(double z = -radius; z <= radius; ++z) {
                        if (!(x * x + z * z > radius * radius)) {
                            Location spot = loc.clone().add(x, (double)0.0F, z);
                            Block block = world.getBlockAt(spot);
                            if (block.getType().isAir() || block.isPassable()) {
                                if (this.random.nextDouble() < (double)0.25F) {
                                    block.setType(Material.MAGMA_BLOCK, false);
                                } else {
                                    block.setType(Material.FIRE, false);
                                }
                            }
                        }
                    }
                }

                world.spawnParticle(Particle.LAVA, loc.add((double)0.0F, (double)0.5F, (double)0.0F), 40, 1.2, 0.6, 1.2, 0.05);
            }
        }
    }

    /** 监听末影人传送：变种末影人传送时留下烟雾并在落点引燃周围玩家。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onTeleport(EntityTeleportEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof Enderman ender) {
            if (this.inHeatWorld(ender) && this.isVariant(ender)) {
                World world = ender.getWorld();
                Location from = e.getFrom();
                Location to = e.getTo();
                if (from != null) {
                    world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, from.clone().add((double)0.0F, (double)1.0F, (double)0.0F), 20, (double)0.5F, (double)0.5F, (double)0.5F, 0.02);
                }

                if (to != null) {
                    world.spawnParticle(Particle.FLAME, to.clone().add((double)0.0F, (double)1.0F, (double)0.0F), 30, 0.6, 0.6, 0.6, 0.02);
                    double radius = (double)3.0F;

                    for(Entity ent : world.getNearbyEntities(to, radius, radius, radius)) {
                        if (ent instanceof Player) {
                            Player p = (Player)ent;
                            if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                                p.setFireTicks(Math.max(p.getFireTicks(), 80));
                                p.damage((double)1.0F);
                            }
                        }
                    }
                }

            }
        }
    }
}
