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

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        World to = p.getWorld();
        if (to != null && to.getName().equalsIgnoreCase("aeternum_heat")) {
            this.giveGuideIfNeeded(p);
        }

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        World w = p.getWorld();
        if (w != null && w.getName().equalsIgnoreCase("aeternum_heat")) {
            this.giveGuideIfNeeded(p);
        }

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        (new BukkitRunnable() {
            public void run() {
                World w = p.getWorld();
                if (w != null && w.getName().equalsIgnoreCase("aeternum_heat")) {
                    HeatWorldGuide.this.giveGuideIfNeeded(p);
                }

            }
        }).runTask(this.plugin);
    }

    private void giveGuideIfNeeded(Player p) {
        if (!this.hasGuideFlag(p)) {
            ItemStack book = this.createGuideBook(p);
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack[]{book});
            if (!leftover.isEmpty()) {
                for(ItemStack rest : leftover.values()) {
                    if (rest != null && rest.getAmount() != 0) {
                        p.getWorld().dropItemNaturally(p.getLocation().add((double)0.0F, (double)0.5F, (double)0.0F), rest);
                    }
                }
            }

            this.setGuideFlag(p, true);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
        }
    }

    private boolean hasGuideFlag(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Byte b = (Byte)pdc.get(this.FLAG_KEY, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    private void setGuideFlag(Player p, boolean value) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (value) {
            pdc.set(this.FLAG_KEY, PersistentDataType.BYTE, (byte)1);
        } else {
            pdc.remove(this.FLAG_KEY);
        }

    }

    private ItemStack createGuideBook(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta)book.getItemMeta();
        if (meta == null) {
            return book;
        } else {
            meta.setTitle(this.plugin.lang.tr(p, "guide_heat.book_title"));
            meta.setAuthor(this.plugin.lang.tr(p, "guide_heat.book_author"));
            BookPaginator paginator = new BookPaginator();
            boolean first = true;

            for(int i = 1; i <= 50; ++i) {
                String key = "guide_heat.page" + i;
                String text = this.plugin.lang.tr(p, key);
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
