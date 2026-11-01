package Kinkin.aeternum.fauna;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.SeasonService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class ColdExposureService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private File faunaFile;
    private FileConfiguration fauna;
    private boolean enabled;
    private int tickPeriodTicks;
    private int budgetEntitiesPerTick;
    private final Set<EntityType> vulnerable = new HashSet();
    private final Set<String> disabledWorlds = new HashSet();
    private BukkitTask task;
    private int worldIndex = 0;
    private int entityIndex = 0;
    private final NamespacedKey KEY_HYPOTHERMIA;
    private final NamespacedKey KEY_NO_LOOT;

    public ColdExposureService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.KEY_HYPOTHERMIA = new NamespacedKey(plugin, "cold_hypothermia");
        this.KEY_NO_LOOT = new NamespacedKey(plugin, "cold_no_loot");
    }

    public void register() {
        this.loadFauna();
        this.reloadFromFauna();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.task != null) {
            this.task.cancel();
        }

        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, (long)this.tickPeriodTicks);
            this.cleanupAllTrackedNow();
        }
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.cleanupAllTrackedNow();
    }

    public void reload() {
        this.loadFauna();
        this.reloadFromFauna();
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        if (this.enabled) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 40L, (long)this.tickPeriodTicks);
        }

        this.cleanupAllTrackedNow();
    }

    private void loadFauna() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }

        this.faunaFile = new File(this.plugin.getDataFolder(), "fauna.yml");
        if (!this.faunaFile.exists()) {
            try {
                this.plugin.saveResource("fauna.yml", false);
            } catch (IllegalArgumentException var4) {
                try {
                    this.faunaFile.createNewFile();
                } catch (IOException var3) {
                }
            }
        }

        this.fauna = YamlConfiguration.loadConfiguration(this.faunaFile);
    }

    private FileConfiguration fauna() {
        return this.fauna;
    }

    private void reloadFromFauna() {
        this.enabled = this.fauna().getBoolean("cold_exposure.enabled", false);
        this.tickPeriodTicks = Math.max(1, this.fauna().getInt("cold_exposure.tick_period_ticks", 40));
        this.budgetEntitiesPerTick = Math.max(1, this.fauna().getInt("cold_exposure.budget_entities_per_tick", 200));
        this.vulnerable.clear();

        for(String s : this.fauna().getStringList("cold_exposure.vulnerable")) {
            if (s != null && !s.isBlank()) {
                try {
                    this.vulnerable.add(EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException var4) {
                }
            }
        }

        if (this.vulnerable.isEmpty()) {
            this.vulnerable.add(EntityType.COW);
            this.vulnerable.add(EntityType.PIG);
            this.vulnerable.add(EntityType.CHICKEN);
            this.vulnerable.add(EntityType.SHEEP);
            this.vulnerable.add(EntityType.RABBIT);
            this.vulnerable.add(EntityType.HORSE);
        }

        this.disabledWorlds.clear();

        for(String w : this.fauna().getStringList("cold_exposure.disabled_worlds")) {
            if (w != null && !w.isBlank()) {
                this.disabledWorlds.add(w.trim());
            }
        }

        this.worldIndex = 0;
        this.entityIndex = 0;
    }

    public void run() {
        if (this.enabled) {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                int budget = this.budgetEntitiesPerTick;
                int safeGuard = worlds.size() * 3;

                while(budget > 0 && safeGuard-- > 0) {
                    if (this.worldIndex >= worlds.size()) {
                        this.worldIndex = 0;
                    }

                    World w = (World)worlds.get(this.worldIndex);
                    if (!this.disabledWorlds.contains(w.getName()) && w.getEnvironment() == Environment.NORMAL) {
                        List<Animals> list = new ArrayList(w.getEntitiesByClass(Animals.class));
                        if (list.isEmpty()) {
                            ++this.worldIndex;
                            this.entityIndex = 0;
                        } else {
                            while(budget > 0 && this.entityIndex < list.size()) {
                                Animals animal = (Animals)list.get(this.entityIndex++);
                                if (animal instanceof LivingEntity && this.vulnerable.contains(animal.getType())) {
                                    this.cleanupEntity(animal);
                                    --budget;
                                }
                            }

                            if (this.entityIndex >= list.size()) {
                                ++this.worldIndex;
                                this.entityIndex = 0;
                            }
                        }
                    } else {
                        ++this.worldIndex;
                        this.entityIndex = 0;
                    }
                }

            }
        }
    }

    private void cleanupAllTrackedNow() {
        for(World w : Bukkit.getWorlds()) {
            for(Animals animal : w.getEntitiesByClass(Animals.class)) {
                if (this.vulnerable.isEmpty() || this.vulnerable.contains(animal.getType())) {
                    this.cleanupEntity(animal);
                }
            }
        }

    }

    private void cleanupEntity(LivingEntity le) {
        this.clearHypothermiaFlags(le);
        this.clearColdPenalty(le);
    }

    private void clearColdPenalty(LivingEntity le) {
        le.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    private void clearHypothermiaFlags(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.remove(this.KEY_HYPOTHERMIA);
        pdc.remove(this.KEY_NO_LOOT);
    }

    private boolean hasLegacyColdFlag(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        Byte hyp = (Byte)pdc.get(this.KEY_HYPOTHERMIA, PersistentDataType.BYTE);
        Byte noLoot = (Byte)pdc.get(this.KEY_NO_LOOT, PersistentDataType.BYTE);
        return hyp != null && hyp == 1 || noLoot != null && noLoot == 1 || le.hasPotionEffect(PotionEffectType.SLOWNESS);
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBreed(EntityBreedEvent e) {
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent e) {
        Entity var3 = e.getEntity();
        if (var3 instanceof LivingEntity le) {
            if (this.vulnerable.contains(le.getType())) {
                if (this.hasLegacyColdFlag(le)) {
                    this.cleanupEntity(le);
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (this.vulnerable.contains(le.getType())) {
            this.cleanupEntity(le);
        }
    }
}
