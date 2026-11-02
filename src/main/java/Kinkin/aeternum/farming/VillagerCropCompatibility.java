package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.PlatformScheduler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 村民作物兼容处理。
 *
 * <p>追踪已加载的村民与掉落物实体，周期性（每 10 tick）在实体所属线程上：
 * <ul>
 *     <li>规范化村民交易库存中的作物物品；</li>
 *     <li>规范化掉落物物品栈，使其回到“原版”外观。</li>
 * </ul>
 * 通过反射优先使用 Paper 的实体调度器（{@code Entity#getScheduler}），
 * 不可用时退回全局调度器，以尽量避免跨线程访问实体。</p>
 */
final class VillagerCropCompatibility implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private final CropLoreService lore;
    private final Map<UUID, Villager> tracked = new ConcurrentHashMap();
    private final Queue<Villager> villagers = new ConcurrentLinkedQueue();
    private final Map<UUID, Item> queuedItems = new ConcurrentHashMap();
    private final Queue<Item> items = new ConcurrentLinkedQueue();
    private PlatformScheduler.TaskHandle task;
    private volatile boolean active;
    private volatile boolean warned;
    private static final Method ENTITY_SCHEDULER = schedulerMethod();

    VillagerCropCompatibility(AeternumSeasonsPlugin plugin, CropLoreService lore) {
        super();
        this.plugin = plugin;
        this.lore = lore;
    }

    void register() {
        this.active = true;
        Bukkit.getPluginManager().registerEvents(this, this.plugin);

        for(World world : Bukkit.getWorlds()) {
            for(Entity entity : world.getEntitiesByClasses(new Class[]{Villager.class, Item.class})) {
                this.discover(entity);
            }
        }

        this.task = PlatformScheduler.runGlobalTimer(this.plugin, this, 1L, 10L);
    }

    void unregister() {
        this.active = false;
        if (this.task != null) {
            this.task.cancel();
        }

        this.task = null;
        HandlerList.unregisterAll(this);
        this.tracked.clear();
        this.villagers.clear();
        this.queuedItems.clear();
        this.items.clear();
    }

    /** 根据实体类型登记村民或待处理物品。 */
    private void discover(Entity entity) {
        if (this.active) {
            if (entity instanceof Villager) {
                Villager villager = (Villager)entity;
                if (this.tracked.putIfAbsent(villager.getUniqueId(), villager) == null) {
                    this.villagers.add(villager);
                }
            } else if (entity instanceof Item) {
                Item item = (Item)entity;
                this.queueItem(item);
            }

        }
    }

    /** 将掉落物加入待规范化队列（去重）。 */
    void queueItem(Item item) {
        if (this.active && this.queuedItems.putIfAbsent(item.getUniqueId(), item) == null) {
            this.items.add(item);
        }

    }

    /** 监听生物生成，登记新出现的村民或物品实体。 */
    @EventHandler(
        ignoreCancelled = true,
        priority = EventPriority.MONITOR
    )
    public void onSpawn(CreatureSpawnEvent event) {
        this.discover(event.getEntity());
    }

    /** 区块实体加载时登记其中的村民与物品。 */
    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(this::discover);
    }

    /** 区块实体卸载时从跟踪集合中移除对应村民与物品。 */
    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for(Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                this.tracked.remove(entity.getUniqueId(), villager);
            }

            if (entity instanceof Item item) {
                this.queuedItems.remove(entity.getUniqueId(), item);
            }
        }

    }

    /** 定时任务入口：分批处理村民库存与掉落物的规范化。 */
    public void run() {
        if (this.active && this.plugin.isEnabled()) {
            int visits = Math.min(16, this.villagers.size());

            for(int i = 0; i < visits; ++i) {
                Villager villager = (Villager)this.villagers.poll();
                if (villager == null) {
                    break;
                }

                UUID id = villager.getUniqueId();
                if (this.tracked.get(id) == villager) {
                    Runnable retired = () -> this.tracked.remove(id, villager);
                    this.onOwner(villager, () -> {
                        if (this.tracked.get(id) == villager && villager.isValid() && !villager.isDead()) {
                            try {
                                Inventory inventory = villager.getInventory();
                                CropLoreService loreService = this.lore;
                                Objects.requireNonNull(loreService);
                                VillagerCropInventory.normalize(inventory, loreService::forVillager);
                            } finally {
                                if (this.active && this.tracked.get(id) == villager) {
                                    this.villagers.add(villager);
                                }

                            }

                        } else {
                            retired.run();
                        }
                    }, retired);
                }
            }

            for(int i = 0; i < 16; ++i) {
                Item item = (Item)this.items.poll();
                if (item == null) {
                    break;
                }

                UUID id = item.getUniqueId();
                if (this.queuedItems.get(id) == item) {
                    Runnable retired = () -> this.queuedItems.remove(id, item);
                    this.onOwner(item, () -> {
                        try {
                            if (item.isValid() && !item.isDead()) {
                                ItemStack current = item.getItemStack();
                                ItemStack canonical = this.lore.forVillager(current);
                                if (canonical != null && !canonical.equals(current)) {
                                    item.setItemStack(canonical);
                                }

                                return;
                            }
                        } finally {
                            retired.run();
                        }

                    }, retired);
                }
            }

        }
    }

    /** 探测 Paper 的实体调度器方法；不存在时返回 null。 */
    private static Method schedulerMethod() {
        try {
            return Entity.class.getMethod("getScheduler");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /**
     * 在实体所属线程上执行动作；无法调度或实体已失效时执行 retired 回退。
     *
     * @param entity 目标实体
     * @param action 需要在实体线程执行的动作
     * @param retired 放弃处理时的回退动作
     */
    private void onOwner(Entity entity, Runnable action, Runnable retired) {
        if (this.active && this.plugin.isEnabled()) {
            Runnable guarded = () -> {
                if (this.active && this.plugin.isEnabled()) {
                    action.run();
                } else {
                    retired.run();
                }

            };
            if (ENTITY_SCHEDULER == null) {
                Bukkit.getScheduler().runTask(this.plugin, guarded);
            } else {
                try {
                    Object scheduler = ENTITY_SCHEDULER.invoke(entity);
                    Method execute = ENTITY_SCHEDULER.getReturnType().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, Long.TYPE);
                    if (!Boolean.TRUE.equals(execute.invoke(scheduler, this.plugin, guarded, retired, 1L))) {
                        retired.run();
                    }
                } catch (ReflectiveOperationException error) {
                    retired.run();
                    if (!this.warned) {
                        this.warned = true;
                        this.plugin.getLogger().warning("[CropLore] Could not schedule villager compatibility: " + error.getMessage());
                    }
                }

            }
        } else {
            retired.run();
        }
    }
}
