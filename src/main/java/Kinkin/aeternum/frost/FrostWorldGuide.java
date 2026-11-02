package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.util.BookPaginator;
import java.util.HashMap;
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

/**
 * 永霜世界引导书发放器。
 *
 * <p>当玩家进入、加入或重生到 {@code aeternum_frost} 世界时，若尚未领取过引导书，
 * 则生成一本由语言文件分页填充的成书交给玩家并播放提示音；已领取状态记录在玩家的
 * 持久化数据容器中（{@code frost_guide_given}），保证只发放一次。另提供立即发放与
 * 强制发放的公共入口。</p>
 */
public final class FrostWorldGuide implements Listener {
    private static final String FROST_WORLD_NAME = "aeternum_frost";
    private final AeternumSeasonsPlugin plugin;
    private final NamespacedKey FLAG_KEY;

    public FrostWorldGuide(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.FLAG_KEY = new NamespacedKey(plugin, "frost_guide_given");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * 玩家切换世界时，若目标世界为永霜世界则尝试发放引导书。
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World targetWorld = player.getWorld();
        if (targetWorld != null && targetWorld.getName().equalsIgnoreCase("aeternum_frost")) {
            this.giveGuideIfNeeded(player);
        }

    }

    /**
     * 玩家加入服务器时，若身处永霜世界则尝试发放引导书。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (world != null && world.getName().equalsIgnoreCase("aeternum_frost")) {
            this.giveGuideIfNeeded(player);
        }

    }

    /**
     * 玩家重生时，延迟一个调度周期后若位于永霜世界则尝试发放引导书。
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            World world = player.getWorld();
            if (world != null && world.getName().equalsIgnoreCase("aeternum_frost")) {
                this.giveGuideIfNeeded(player);
            }

        });
    }

    /**
     * 若玩家尚未领取引导书，则生成并放入背包、播放提示音并标记为已领取。
     */
    private void giveGuideIfNeeded(Player player) {
        if (!this.hasGuideFlag(player)) {
            ItemStack book = this.createGuideBook(player);
            player.getInventory().addItem(new ItemStack[]{book});
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
            this.setGuideFlag(player, true);
        }
    }

    /**
     * 查询玩家是否已领取过引导书。
     */
    private boolean hasGuideFlag(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte flag = (Byte)pdc.get(this.FLAG_KEY, PersistentDataType.BYTE);
        return flag != null && flag == 1;
    }

    /**
     * 设置或清除玩家的“已领取引导书”标记。
     */
    private void setGuideFlag(Player player, boolean value) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (value) {
            pdc.set(this.FLAG_KEY, PersistentDataType.BYTE, (byte)1);
        } else {
            pdc.remove(this.FLAG_KEY);
        }

    }

    /**
     * 依据语言文件逐页生成引导书；当某页键不存在时停止翻页。
     */
    private ItemStack createGuideBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            return book;
        } else {
            meta.setTitle(this.plugin.lang.tr(player, "frost_guide.book_title"));
            meta.setAuthor(this.plugin.lang.tr(player, "frost_guide.book_author"));
            BookPaginator paginator = new BookPaginator();
            boolean first = true;

            for(int i = 1; i <= 50; ++i) {
                String key = "frost_guide.page" + i;
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

    /**
     * 立即为位于永霜世界的玩家发放引导书：优先放入背包，放不下则掉落，返回是否成功。
     */
    public boolean giveGuideNow(Player player) {
        World world = player.getWorld();
        if (world != null && world.getName().equalsIgnoreCase("aeternum_frost")) {
            ItemStack book = this.createGuideBook(player);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{book});
            if (!leftover.isEmpty()) {
                world.dropItemNaturally(player.getLocation(), book);
            }

            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
            this.setGuideFlag(player, true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * 强制为位于永霜世界的玩家发放引导书（不播放提示音），放不下则掉落。
     */
    public void giveGuideForce(Player player) {
        if (player.getWorld() != null && player.getWorld().getName().equalsIgnoreCase("aeternum_frost")) {
            ItemStack book = this.createGuideBook(player);
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{book});
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), book);
            }

            this.setGuideFlag(player, true);
        }
    }
}
