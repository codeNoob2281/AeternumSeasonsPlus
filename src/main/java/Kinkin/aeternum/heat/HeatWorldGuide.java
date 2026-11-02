package Kinkin.aeternum.heat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.BookPaginator;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 炎热世界引导书发放器。
 *
 * <p>当玩家进入、加入或重生在炎热世界 {@code aeternum_heat} 时，若尚未领取过，则赠送一本
 * 说明书（内容来自语言文件 {@code guide_heat.*}）；领取状态通过玩家持久化数据键
 * {@code heat_guide_given} 记录。</p>
 */
public final class HeatWorldGuide implements Listener {
    private static final String HEAT_WORLD_NAME = "aeternum_heat";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey FLAG_KEY;

    public HeatWorldGuide(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.FLAG_KEY = new NamespacedKey(plugin, "heat_guide_given");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** 监听玩家切换世界，进入炎热世界时发放引导书。 */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World targetWorld = player.getWorld();
        if (targetWorld != null && targetWorld.getName().equalsIgnoreCase("aeternum_heat")) {
            this.giveGuideIfNeeded(player);
        }

    }

    /** 监听玩家加入，若处于炎热世界则发放引导书。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (world != null && world.getName().equalsIgnoreCase("aeternum_heat")) {
            this.giveGuideIfNeeded(player);
        }

    }

    /** 监听玩家重生，稍后确认所处世界并在炎热世界发放引导书。 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        (new BukkitRunnable() {
            public void run() {
                World world = player.getWorld();
                if (world != null && world.getName().equalsIgnoreCase("aeternum_heat")) {
                    HeatWorldGuide.this.giveGuideIfNeeded(player);
                }

            }
        }).runTask(this.plugin);
    }

    /** 若玩家尚未领取过引导书，则发放并记录标记；背包满时掉落在脚下。 */
    private void giveGuideIfNeeded(Player player) {
        if (!this.hasGuideFlag(player)) {
            ItemStack book = this.createGuideBook(player);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{book});
            if (!leftover.isEmpty()) {
                for(ItemStack rest : leftover.values()) {
                    if (rest != null && rest.getAmount() != 0) {
                        player.getWorld().dropItemNaturally(player.getLocation().add((double)0.0F, (double)0.5F, (double)0.0F), rest);
                    }
                }
            }

            this.setGuideFlag(player, true);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
        }
    }

    /** 判断玩家是否已领取引导书。 */
    private boolean hasGuideFlag(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte b = (Byte)pdc.get(this.FLAG_KEY, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    /** 设置或清除玩家的引导书领取标记。 */
    private void setGuideFlag(Player player, boolean value) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (value) {
            pdc.set(this.FLAG_KEY, PersistentDataType.BYTE, (byte)1);
        } else {
            pdc.remove(this.FLAG_KEY);
        }

    }

    /** 根据语言文件分页构建炎热世界说明书。 */
    private ItemStack createGuideBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            return book;
        } else {
            meta.setTitle(this.plugin.lang.tr(player, "guide_heat.book_title"));
            meta.setAuthor(this.plugin.lang.tr(player, "guide_heat.book_author"));
            BookPaginator paginator = new BookPaginator();
            boolean first = true;

            for(int i = 1; i <= 50; ++i) {
                String key = "guide_heat.page" + i;
                String text = this.plugin.lang.tr(player, key);
                if (text.equals(key)) {
                    break;
                }

                if (!text.isEmpty()) {
                    if (!first) {
                        paginator.newPage();
                    }

                    first = false;
                    paginator.addText(text);
                }
            }

            List<String> pages = paginator.build();
            meta.setPages(pages);
            book.setItemMeta(meta);
            return book;
        }
    }
}
