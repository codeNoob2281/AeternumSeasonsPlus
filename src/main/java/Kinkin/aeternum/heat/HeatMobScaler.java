package Kinkin.aeternum.heat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

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

    private boolean inHeatWorld(Entity e) {
        World w = e.getWorld();
        return w != null && w.getName().equalsIgnoreCase("aeternum_heat");
    }

    private boolean isHostile(LivingEntity e) {
        return e instanceof Monster || e instanceof Slime || e instanceof Phantom;
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onSpawn(CreatureSpawnEvent e) {
        LivingEntity le = e.getEntity();
        if (this.inHeatWorld(le)) {
            if (this.isHostile(le)) {
                PersistentDataContainer pdc = le.getPersistentDataContainer();
                if (!pdc.has(this.TAG_KEY, PersistentDataType.BYTE)) {
                    pdc.set(this.TAG_KEY, PersistentDataType.BYTE, (byte)1);
                    AttributeInstance maxHp = le.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHp != null) {
                        maxHp.setBaseValue(maxHp.getBaseValue() * (double)4.0F);
                        le.setHealth(maxHp.getBaseValue());
                    }

                    AttributeInstance speed = le.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (speed != null) {
                        speed.setBaseValue(speed.getBaseValue() * 1.2);
                    }

                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.NORMAL
    )
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity var3 = e.getEntity();
        if (var3 instanceof Player victim) {
            LivingEntity var8 = null;
            Entity var7 = e.getDamager();
            if (var7 instanceof LivingEntity le) {
                var8 = le;
            } else {
                var7 = e.getDamager();
                if (var7 instanceof Projectile proj) {
                    ProjectileSource var11 = proj.getShooter();
                    if (var11 instanceof LivingEntity le2) {
                        var8 = le2;
                    }
                }
            }

            if (var8 != null) {
                if (this.inHeatWorld(var8)) {
                    if (this.isHostile(var8)) {
                        PersistentDataContainer pdc = var8.getPersistentDataContainer();
                        if (pdc.has(this.TAG_KEY, PersistentDataType.BYTE)) {
                            e.setDamage(e.getDamage() * (double)4.0F);
                        }
                    }
                }
            }
        }
    }
}
