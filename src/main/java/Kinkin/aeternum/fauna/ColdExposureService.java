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

/**
 * 寒冷暴露（冷伤害）清理服务。
 *
 * <p>早期版本会给受寒的动物打上“低温”持久化标记、禁止掉落并以缓慢效果作为惩罚；
 * 现版本已改为在动物出生/交互时即时处理，因此本服务负责清理这些历史遗留状态：
 * <ul>
 *     <li>以固定周期分批扫描各主世界中的易受寒动物；</li>
 *     <li>清除动物身上的低温标记、禁止掉落标记以及缓慢药水效果。</li>
 * </ul>
 * 扫描预算由配置决定，逐世界、逐实体推进游标以避免单 tick 开销过大。
 * 依赖 {@link AeternumSeasonsPlugin} 提供数据目录与调度器，以及 {@link SeasonService}
 * 作为季节上下文。</p>
 */
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

    /** 加载配置、注册事件监听并在启用时启动周期清理任务，随后立即全量清理一次。 */
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

    /** 取消周期任务、注销事件监听并做一次全量清理。 */
    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.cleanupAllTrackedNow();
    }

    /** 重新加载配置并重启周期任务，随后立即全量清理一次。 */
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

    /** 确保数据目录存在，并在 fauna.yml 缺失时尝试落盘默认资源或创建空文件。 */
    private void loadFauna() {
        if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
        }

        this.faunaFile = new File(this.plugin.getDataFolder(), "fauna.yml");
        if (!this.faunaFile.exists()) {
            try {
                this.plugin.saveResource("fauna.yml", false);
            } catch (IllegalArgumentException ignored) {
                try {
                    this.faunaFile.createNewFile();
                } catch (IOException ignoredToo) {
                }
            }
        }

        this.fauna = YamlConfiguration.loadConfiguration(this.faunaFile);
    }

    /** 返回当前加载的 fauna.yml 配置。 */
    private FileConfiguration fauna() {
        return this.fauna;
    }

    /** 从 fauna.yml 读取寒冷暴露相关配置：开关、周期、预算、易受寒类型与被禁世界。 */
    private void reloadFromFauna() {
        this.enabled = this.fauna().getBoolean("cold_exposure.enabled", false);
        this.tickPeriodTicks = Math.max(1, this.fauna().getInt("cold_exposure.tick_period_ticks", 40));
        this.budgetEntitiesPerTick = Math.max(1, this.fauna().getInt("cold_exposure.budget_entities_per_tick", 200));
        this.vulnerable.clear();

        for(String s : this.fauna().getStringList("cold_exposure.vulnerable")) {
            if (s != null && !s.isBlank()) {
                try {
                    this.vulnerable.add(EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
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

    /** 周期任务：在预算内按世界/实体游标分批扫描主世界中的易受寒动物并清理其历史冷状态。 */
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

    /** 立即遍历所有世界的动物并清理其历史冷状态（用于启停与重载时的收敛）。 */
    private void cleanupAllTrackedNow() {
        for(World w : Bukkit.getWorlds()) {
            for(Animals animal : w.getEntitiesByClass(Animals.class)) {
                if (this.vulnerable.isEmpty() || this.vulnerable.contains(animal.getType())) {
                    this.cleanupEntity(animal);
                }
            }
        }

    }

    /** 清除单个动物的全部历史冷状态（低温标记、禁止掉落标记与缓慢效果）。 */
    private void cleanupEntity(LivingEntity le) {
        this.clearHypothermiaFlags(le);
        this.clearColdPenalty(le);
    }

    /** 移除动物身上残留的缓慢药水效果。 */
    private void clearColdPenalty(LivingEntity le) {
        le.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /** 移除动物持久化数据中的低温和禁止掉落标记。 */
    private void clearHypothermiaFlags(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.remove(this.KEY_HYPOTHERMIA);
        pdc.remove(this.KEY_NO_LOOT);
    }

    /** 判断动物是否仍带有旧版冷状态（低温标记、禁止掉落标记或缓慢效果）。 */
    private boolean hasLegacyColdFlag(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        Byte hyp = (Byte)pdc.get(this.KEY_HYPOTHERMIA, PersistentDataType.BYTE);
        Byte noLoot = (Byte)pdc.get(this.KEY_NO_LOOT, PersistentDataType.BYTE);
        return hyp != null && hyp == 1 || noLoot != null && noLoot == 1 || le.hasPotionEffect(PotionEffectType.SLOWNESS);
    }

    /** 繁殖事件监听：当前不做处理，保留钩子以便未来扩展。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onBreed(EntityBreedEvent e) {
    }

    /** 受伤事件监听：若易受寒动物仍带旧版冷状态，则就地清理。 */
    @EventHandler(
        ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof LivingEntity le) {
            if (this.vulnerable.contains(le.getType())) {
                if (this.hasLegacyColdFlag(le)) {
                    this.cleanupEntity(le);
                }
            }
        }
    }

    /** 死亡事件监听：易受寒动物死亡时清理其残留冷状态。 */
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
