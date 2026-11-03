package kinkin.aeternum.farming;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 季节作物物品描述监听器。
 *
 * <p>在物品生成、被拾取、合成、战利品生成与玩家加入等时机，调用 {@link CropLoreService}
 * 为受季节管理的作物物品补充季节信息描述；同时协调 {@link VillagerCropCompatibility}
 * 处理村民交易物品的显示。</p>
 */
public final class SeasonalCropLoreListener implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonalCropConfig config;
    private final CropLoreService lore;
    private final VillagerCropCompatibility villagerCompatibility;

    public SeasonalCropLoreListener(AeternumSeasonsPlugin plugin, SeasonalCropConfig config) {
        super();
        this.plugin = plugin;
        this.config = config;
        this.lore = new CropLoreService(plugin, config);
        this.villagerCompatibility = new VillagerCropCompatibility(plugin, this.lore);
    }

    public void register() {
        if (this.config.isEnabled()) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            this.villagerCompatibility.register();
        }
    }

    public void unregister() {
        this.villagerCompatibility.unregister();
        ItemSpawnEvent.getHandlerList().unregister(this);
        EntityPickupItemEvent.getHandlerList().unregister(this);
        CraftItemEvent.getHandlerList().unregister(this);
        LootGenerateEvent.getHandlerList().unregister(this);
        PlayerJoinEvent.getHandlerList().unregister(this);
    }

    /**
     * 物品生成时为其补充季节描述。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onItemSpawn(ItemSpawnEvent e) {
        if (this.config.isEnabled()) {
            Item itemEntity = e.getEntity();
            ItemStack stack = itemEntity.getItemStack();
            if (this.lore.apply(stack, (Player) null)) {
                itemEntity.setItemStack(stack);
            }

        }
    }

    /**
     * 玩家拾取物品时按玩家语言补充季节描述，并交由村民兼容逻辑处理。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onPickup(EntityPickupItemEvent e) {
        if (this.config.isEnabled()) {
            LivingEntity entity = e.getEntity();
            if (entity instanceof Player) {
                Player player = (Player) entity;
                ItemStack stack = e.getItem().getItemStack();
                if (this.lore.apply(stack, player)) {
                    e.getItem().setItemStack(stack);
                }

                this.villagerCompatibility.queueItem(e.getItem());
            }
        }
    }

    /**
     * 玩家合成物品时为产物补充季节描述。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onCraft(CraftItemEvent e) {
        if (this.config.isEnabled()) {
            HumanEntity clicker = e.getWhoClicked();
            if (clicker instanceof Player) {
                Player player = (Player) clicker;
                ItemStack itemStack = e.getCurrentItem();
                this.lore.apply(itemStack, player);
            }
        }
    }

    /**
     * 战利品生成时为每个物品补充季节描述。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onLoot(LootGenerateEvent e) {
        if (this.config.isEnabled()) {
            e.getLoot().forEach((stack) -> this.lore.apply(stack, (Player) null));
        }
    }

    /**
     * 玩家加入时刷新其背包内物品的季节描述。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (this.config.isEnabled()) {
            Player player = e.getPlayer();

            for (ItemStack stack : player.getInventory().getContents()) {
                this.lore.apply(stack, player);
            }

        }
    }
}
