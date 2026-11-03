package kinkin.aeternum.farming;

import kinkin.aeternum.AeternumSeasonsPlugin;
import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import kinkin.aeternum.food.SeasonFoods;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.EnumSet;

/**
 * 季节作物生长监听器。
 *
 * <p>监听 {@link BlockGrowEvent}，结合 {@link CropGrowthService} 判断作物是否在当前季节允许生长，
 * 必要时取消生长或额外推进生长阶段；同时处理西瓜/南瓜藤蔓的季节判定，
 * 并提供调试木棍查看作物温室保护状态。</p>
 */
public final class SeasonalCropGrowthListener implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final SeasonalCropConfig config;
    private final GreenhouseService greenhouse;
    private final CropGrowthService cropGrowth;
    private final SeasonalCropLoreListener loreListener;

    public SeasonalCropGrowthListener(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        super();
        this.plugin = plugin;
        this.seasons = seasons;
        this.config = new SeasonalCropConfig(plugin);
        this.greenhouse = new GreenhouseService(this.config);
        this.cropGrowth = new CropGrowthService(this.config, this.greenhouse, seasons);
        this.loreListener = new SeasonalCropLoreListener(plugin, this.config);
    }

    public void register() {
        if (this.config.isEnabled()) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            this.loreListener.register();
        }
    }

    public void unregister() {
        BlockGrowEvent.getHandlerList().unregister(this);
        PlayerInteractEvent.getHandlerList().unregister(this);
        this.loreListener.unregister();
    }

    /**
     * 从磁盘重新加载作物配置。
     */
    public void reloadFromConfig() {
        this.config.reload();
    }

    /**
     * 预留的方块蔓延处理，目前为空实现。
     */
    @EventHandler
    public void onBlockSpread(BlockGrowEvent e) {
        Material newType = e.getNewState().getType();
        if (newType == Material.PUMPKIN) {
        }

    }

    /**
     * 监听作物生长，按季节规则取消或加速生长。
     */
    @EventHandler
    public void onGrow(BlockGrowEvent e) {
        if (this.config.isEnabled()) {
            Block block = e.getBlock();
            Material newType = e.getNewState().getType();
            if (newType != Material.MELON && newType != Material.PUMPKIN) {
                BlockData blockData = e.getNewState().getBlockData();
                if (blockData instanceof Ageable) {
                    Ageable age = (Ageable) blockData;
                    EnumSet allowedSeasons = null;
                    SeasonFoods seasonFoods = this.plugin.getSeasonFoods();
                    if (seasonFoods != null) {
                        allowedSeasons = seasonFoods.getArtificialGrowthSeasons(block);
                    }

                    CropGrowthService.GrowthDecision decision = this.cropGrowth.evaluate(block, allowedSeasons);
                    if (decision.cancel() && Math.random() > this.config.getOffSeasonMultiplier()) {
                        e.setCancelled(true);
                    } else {
                        int extra = decision.extraAges();
                        if (extra > 0) {
                            int newAge = Math.min(age.getMaximumAge(), age.getAge() + extra);
                            age.setAge(newAge);
                            e.getNewState().setBlockData(age);
                        }

                    }
                }
            } else if (!this.isStemInSeason(block) && Math.random() > this.config.getOffSeasonMultiplier()) {
                e.setCancelled(true);
            }
        }
    }

    /**
     * 判断在给定季节集合下是否允许尝试人工生长（不满足季节时按概率放行）。
     */
    public boolean allowsArtificialGrowthAttempt(Block block, EnumSet<Season> allowedSeasons) {
        if (this.config.isEnabled() && block != null && allowedSeasons != null && !allowedSeasons.isEmpty()) {
            return this.cropGrowth.isSeasonAllowed(block, allowedSeasons) || Math.random() <= this.config.getOffSeasonMultiplier();
        } else {
            return true;
        }
    }

    /**
     * 判断结果方块（西瓜/南瓜）附近的藤蔓是否处于当前季节或温室内。
     *
     * @param fruitBlock 结果方块
     * @return 若藤蔓处于温室内或当前季节允许，返回 true；未找到藤蔓时也返回 true
     */
    private boolean isStemInSeason(Block fruitBlock) {
        Material[] stems = new Material[]{Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM};

        for (BlockFace face : BlockFace.values()) {
            if (face.isCartesian()) {
                Block adjacent = fruitBlock.getRelative(face);

                for (Material stemType : stems) {
                    if (adjacent.getType() == stemType) {
                        if (this.greenhouse.isInGreenhouse(adjacent)) {
                            return true;
                        }

                        EnumSet<Season> allowed = this.config.getAllowedSeasons(adjacent.getType());
                        return allowed != null && allowed.contains(this.seasons.getStateCopy(adjacent.getWorld()).season);
                    }
                }
            }
        }

        return true;
    }

    /**
     * 监听调试木棍右键，向玩家显示目标作物是否受温室保护。
     */
    @EventHandler(
            ignoreCancelled = true
    )
    public void onDebugStick(PlayerInteractEvent e) {
        if (this.config.isEnabled() && this.config.isDebugEnabled()) {
            if (e.getHand() == EquipmentSlot.HAND) {
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    if (e.getClickedBlock() != null) {
                        if (e.getItem() != null) {
                            if (e.getItem().getType() == this.config.getDebugStickMaterial()) {
                                Block block = e.getClickedBlock();
                                if (this.config.isManagedCrop(block.getType())) {
                                    boolean inGreenhouse = this.greenhouse.isInGreenhouse(block);
                                    Player player = e.getPlayer();
                                    CalendarState state = this.seasons.getStateCopy(block.getWorld());
                                    if (inGreenhouse) {
                                        String greenColor = String.valueOf(ChatColor.GREEN);
                                        player.sendMessage(greenColor + "This crop is protected by the greenhouse.(" + state.season.name() + ").");
                                    } else {
                                        String redColor = String.valueOf(ChatColor.RED);
                                        player.sendMessage(redColor + "This crop is NOT protected by the greenhouse (" + state.season.name() + ").");
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
