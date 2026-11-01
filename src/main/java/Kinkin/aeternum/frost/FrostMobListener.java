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

    private boolean inFrostWorld(Entity e) {
        World w = e.getWorld();
        return w != null && w.getName().equalsIgnoreCase("aeternum_frost");
    }

    private void markFrost(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(this.FROST_VARIANT_KEY, PersistentDataType.BYTE, (byte)1);
    }

    private boolean isFrost(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        Byte b = (Byte)pdc.get(this.FROST_VARIANT_KEY, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity ent = e.getEntity();
        if (this.inFrostWorld(ent)) {
            if (ent instanceof Monster) {
                if (ent instanceof Zombie && !(ent instanceof Drowned)) {
                    World w = ent.getWorld();
                    Location loc = ent.getLocation();
                    e.setCancelled(true);
                    w.spawn(loc, Drowned.class, (drowned) -> {
                        this.markFrost(drowned);
                        this.buffMonster(drowned);
                        EntityEquipment eq = drowned.getEquipment();
                        if (eq != null) {
                            eq.setHelmet(new ItemStack(Material.ICE));
                            eq.setHelmetDropChance(0.0F);
                        }

                    });
                } else {
                    if (!this.isFrost(ent)) {
                        this.markFrost(ent);
                        this.buffMonster(ent);
                    }

                    if (ent instanceof Skeleton) {
                        Skeleton skeleton = (Skeleton)ent;
                        if (skeleton instanceof Stray) {
                            return;
                        }

                        World w = skeleton.getWorld();
                        Location loc = skeleton.getLocation();
                        skeleton.remove();
                        w.spawn(loc, Stray.class, (stray) -> {
                            this.markFrost(stray);
                            this.buffMonster(stray);
                        });
                    } else if (ent instanceof Spider) {
                        Spider spider = (Spider)ent;
                    } else if (ent instanceof Creeper) {
                        Creeper creeper = (Creeper)ent;
                    } else if (ent instanceof Enderman) {
                        Enderman enderman = (Enderman)ent;
                    } else if (!(ent instanceof Vindicator) && ent instanceof Pillager) {
                    }

                }
            }
        }
    }

    private void buffMonster(LivingEntity ent) {
        AttributeInstance maxHealth = ent.getAttribute(Attribute.MAX_HEALTH);
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
            ent.setHealth(newMax);
        }

        AttributeInstance attack = ent.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getBaseValue() * (double)1.5F);
        }

        AttributeInstance movement = ent.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * 1.1);
        }

    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpiderHit(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Spider spider) {
            if (this.inFrostWorld(spider) && this.isFrost(spider)) {
                Entity var4 = e.getEntity();
                if (var4 instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)var4;
                    World var12 = spider.getWorld();
                    Location loc = victim.getLocation();
                    ArrayList<Block> placed = new ArrayList<>();

                    for(int dx = -1; dx <= 1; ++dx) {
                        for(int dz = -1; dz <= 1; ++dz) {
                            Location spot = loc.clone().add((double)dx, (double)0.0F, (double)dz);
                            Block b = var12.getBlockAt(spot);
                            if (b.getType().isAir()) {
                                b.setType(Material.COBWEB, false);
                                placed.add(b);
                            }
                        }
                    }

                    if (!placed.isEmpty()) {
                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                            for(Block b : placed) {
                                if (b.getType() == Material.COBWEB) {
                                    b.setType(Material.AIR, false);
                                }
                            }

                        }, 60L);
                    }

                    if (victim instanceof Player) {
                        Player p = (Player)victim;
                        p.setFreezeTicks(Math.min(p.getFreezeTicks() + 40, 200));
                    }

                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGH
    )
    public void onCreeperExplode(EntityExplodeEvent e) {
        Entity var3 = e.getEntity();
        if (var3 instanceof Creeper creeper) {
            if (this.inFrostWorld(creeper) && this.isFrost(creeper)) {
                World w = creeper.getWorld();
                Location center = creeper.getLocation();
                e.blockList().clear();
                int radius = 3;

                for(int dx = -radius; dx <= radius; ++dx) {
                    for(int dz = -radius; dz <= radius; ++dz) {
                        double distSq = (double)(dx * dx + dz * dz);
                        if (!(distSq > (double)(radius * radius))) {
                            int x = center.getBlockX() + dx;
                            int z = center.getBlockZ() + dz;
                            int y = center.getBlockY();
                            Block ground = w.getBlockAt(x, y - 1, z);
                            Block target = w.getBlockAt(x, y, z);
                            if (target.getType().isAir() && ground.getType().isSolid()) {
                                target.setType(Material.POWDER_SNOW, false);
                            }
                        }
                    }
                }

                w.playSound(center, Sound.BLOCK_POWDER_SNOW_FALL, 1.0F, 0.8F);
                w.spawnParticle(Particle.SNOWFLAKE, center, 80, (double)1.5F, (double)1.0F, (double)1.5F, 0.1);
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onArrowHit(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Projectile proj) {
            if (proj instanceof Arrow) {
                ProjectileSource var4 = proj.getShooter();
                if (var4 instanceof Skeleton) {
                    Skeleton sk = (Skeleton)var4;
                    if (this.inFrostWorld(sk) && this.isFrost(sk)) {
                        Entity var5 = e.getEntity();
                        if (var5 instanceof LivingEntity) {
                            LivingEntity victim = (LivingEntity)var5;
                            victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 60, 200));
                            if (victim instanceof Player) {
                                Player p = (Player)victim;
                                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, true, true));
                            }

                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onEndermanHit(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Enderman enderman) {
            if (this.inFrostWorld(enderman) && this.isFrost(enderman)) {
                Entity var4 = e.getEntity();
                if (var4 instanceof Player) {
                    Player p = (Player)var4;
                    Location base = p.getLocation();
                    World w = base.getWorld();
                    if (w != null) {
                        for(int tries = 0; tries < 10; ++tries) {
                            double dx = (this.random.nextDouble() - (double)0.5F) * (double)8.0F;
                            double dz = (this.random.nextDouble() - (double)0.5F) * (double)8.0F;
                            Location candidate = base.clone().add(dx, (double)0.0F, dz);
                            candidate.setY((double)(w.getHighestBlockYAt(candidate) + 1));
                            if (candidate.getBlock().getType().isAir() && candidate.clone().add((double)0.0F, (double)1.0F, (double)0.0F).getBlock().getType().isAir()) {
                                p.teleport(candidate);
                                p.setFreezeTicks(Math.min(p.getFreezeTicks() + 40, 200));
                                w.playSound(candidate, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.6F);
                                break;
                            }
                        }

                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onIllagerHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Vindicator || damager instanceof Pillager) {
            if (this.inFrostWorld(damager) && this.isFrost(damager)) {
                Entity var4 = e.getEntity();
                if (var4 instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)var4;
                    victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 80, 200));
                    if (victim instanceof Player) {
                        Player p = (Player)victim;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, true, true, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 1, true, true, true));
                    }

                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onDrownedHit(EntityDamageByEntityEvent e) {
        Entity var3 = e.getDamager();
        if (var3 instanceof Drowned drowned) {
            if (this.inFrostWorld(drowned) && this.isFrost(drowned)) {
                Entity var4 = e.getEntity();
                if (var4 instanceof LivingEntity) {
                    LivingEntity victim = (LivingEntity)var4;
                    victim.setFreezeTicks(Math.min(victim.getFreezeTicks() + 80, 200));
                    if (victim instanceof Player) {
                        Player p = (Player)victim;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, true, true, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 0, true, true, true));
                        World w = p.getWorld();
                        w.playSound(p.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1.0F, 0.7F);
                        w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add((double)0.0F, (double)1.0F, (double)0.0F), 20, 0.3, 0.6, 0.3, 0.05);
                    }

                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.HIGHEST
    )
    public void onFrostMobCombust(EntityCombustEvent e) {
        Entity ent = e.getEntity();
        if (this.inFrostWorld(ent)) {
            if (ent instanceof Monster) {
                e.setCancelled(true);
                if (ent instanceof LivingEntity) {
                    LivingEntity le = (LivingEntity)ent;
                    le.setFireTicks(0);
                }

            }
        }
    }
}
