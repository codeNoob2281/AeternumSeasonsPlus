package kinkin.aeternum.heat;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 炎热维度怪物强化器。
 *
 * <p>当敌对生物在炎热世界 {@code aeternum_heat} 中生成时，为其打上 {@code heat_mob} 标签，
 * 并提升其最大生命值与移动速度；被标记的怪物对玩家造成的伤害也会放大。
 * 标签保证同一生物只强化一次。</p>
 */
public final class HeatMobScaler implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey TAG_KEY;

    public HeatMobScaler(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.TAG_KEY = new NamespacedKey(plugin, "heat_mob");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 判断实体是否位于炎热世界。
     */
    private boolean inHeatWorld(Entity entity) {
        World world = entity.getWorld();
        return world != null && "aeternum_heat".equalsIgnoreCase(world.getName());
    }

    /**
     * 判断生物是否为敌对生物（怪物、史莱姆或幻翼）。
     */
    private boolean isHostile(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom;
    }

    /**
     * 监听生物生成：在炎热世界中强化尚未标记的敌对生物。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.NORMAL
    )
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (this.inHeatWorld(entity)) {
            if (this.isHostile(entity)) {
                PersistentDataContainer pdc = entity.getPersistentDataContainer();
                if (!pdc.has(this.TAG_KEY, PersistentDataType.BYTE)) {
                    pdc.set(this.TAG_KEY, PersistentDataType.BYTE, (byte) 1);
                    AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealth != null) {
                        maxHealth.setBaseValue(maxHealth.getBaseValue() * (double) 4.0F);
                        entity.setHealth(maxHealth.getBaseValue());
                    }

                    AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (speed != null) {
                        speed.setBaseValue(speed.getBaseValue() * 1.2);
                    }

                }
            }
        }
    }

    /**
     * 监听实体伤害：被标记的炎热怪物对玩家造成的伤害放大为四倍。
     */
    @EventHandler(
            ignoreCancelled = true,
            priority = EventPriority.NORMAL
    )
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damagedEntity = event.getEntity();
        if (damagedEntity instanceof Player victim) {
            LivingEntity attacker = null;
            Entity damager = event.getDamager();
            if (damager instanceof LivingEntity livingDamager) {
                attacker = livingDamager;
            } else {
                damager = event.getDamager();
                if (damager instanceof Projectile projectile) {
                    ProjectileSource shooter = projectile.getShooter();
                    if (shooter instanceof LivingEntity shooterLiving) {
                        attacker = shooterLiving;
                    }
                }
            }

            if (attacker != null) {
                if (this.inHeatWorld(attacker)) {
                    if (this.isHostile(attacker)) {
                        PersistentDataContainer pdc = attacker.getPersistentDataContainer();
                        if (pdc.has(this.TAG_KEY, PersistentDataType.BYTE)) {
                            event.setDamage(event.getDamage() * (double) 4.0F);
                        }
                    }
                }
            }
        }
    }
}
