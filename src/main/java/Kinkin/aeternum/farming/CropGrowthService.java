package kinkin.aeternum.farming;

import kinkin.aeternum.calendar.CalendarState;
import kinkin.aeternum.calendar.Season;
import kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 作物生长计算服务。
 *
 * <p>根据季节、光照、温室状态与天气等因素，决定一次作物生长（{@link org.bukkit.event.block.BlockGrowEvent}）
 * 是否应被取消，或额外推进多少生长阶段。</p>
 * <p>依赖 {@link SeasonalCropConfig} 提供季节配置，{@link GreenhouseService} 判定温室，
 * {@link SeasonService} 获取世界季节。</p>
 */
public final class CropGrowthService {
    private final SeasonalCropConfig config;
    private final GreenhouseService greenhouse;
    private final SeasonService seasons;

    public CropGrowthService(SeasonalCropConfig config, GreenhouseService greenhouse, SeasonService seasons) {
        super();
        this.config = config;
        this.greenhouse = greenhouse;
        this.seasons = seasons;
    }

    /**
     * 评估作物生长，不指定季节覆盖。
     *
     * @param block 待评估的作物方块
     * @return 生长决策
     */
    public GrowthDecision evaluate(Block block) {
        return this.evaluate(block, (EnumSet) null);
    }

    /**
     * 评估作物生长。
     *
     * <p>计算流程：首先确认作物是否为受管作物（或提供了季节覆盖）；然后结合当前季节、温室与天空可见性
     * 判断是否允许生长；再根据光照、地下、降雨与冬季温室等因素调整生长速度；
     * 最后据此决定取消生长或额外推进若干生长阶段。</p>
     *
     * @param block                  待评估的作物方块
     * @param allowedSeasonsOverride 可选的季节覆盖（用于人工生长等场景），为 null 时使用配置
     * @return 生长决策
     */
    public GrowthDecision evaluate(Block block, EnumSet<Season> allowedSeasonsOverride) {
        Material material = block.getType();
        if (allowedSeasonsOverride == null && !this.config.isManagedCrop(material)) {
            return new GrowthDecision(false, 0);
        } else {
            EnumSet<Season> allowed = allowedSeasonsOverride != null ? allowedSeasonsOverride : this.config.getAllowedSeasons(material);
            if (allowed != null && !allowed.isEmpty()) {
                CalendarState state = this.seasons.getStateCopy(block.getWorld());
                Season season = state.season;
                boolean inGreenhouse = this.greenhouse.isInGreenhouse(block);
                boolean seesSky = this.greenhouse.canSeeSky(block);
                if (!allowed.contains(season) && !inGreenhouse && seesSky) {
                    return new GrowthDecision(true, 0);
                } else {
                    double speed = (double) 1.0F;
                    int light = block.getLightLevel();
                    if (light < this.config.getRequiredLight()) {
                        speed *= this.config.getLowLightMultiplier();
                    }

                    if (!seesSky && !inGreenhouse) {
                        speed *= this.config.getUndergroundMultiplier();
                    }

                    if (this.greenhouse.isDirectlyUnderRain(block) && !inGreenhouse) {
                        speed += this.config.getRainBonus();
                    }

                    if (inGreenhouse && season == Season.WINTER) {
                        speed += this.config.getWinterGreenhouseBonus();
                    }

                    if (speed <= (double) 0.0F) {
                        return new GrowthDecision(true, 0);
                    } else {
                        double roll = ThreadLocalRandom.current().nextDouble();
                        if (speed < (double) 1.0F) {
                            return roll > speed ? new GrowthDecision(true, 0) : new GrowthDecision(false, 0);
                        } else {
                            double over = speed - (double) 1.0F;
                            int extra = (int) Math.floor(over);
                            double frac = over - (double) extra;
                            if (roll < frac) {
                                ++extra;
                            }

                            return new GrowthDecision(false, extra);
                        }
                    }
                }
            } else {
                return new GrowthDecision(false, 0);
            }
        }
    }

    /**
     * 判断指定作物在当前季节是否允许生长。
     *
     * @param block          待判定的作物方块
     * @param allowedSeasons 允许的季节集合
     * @return 若当前季节被允许，或作物在温室内/看不到天空，则返回 true
     */
    public boolean isSeasonAllowed(Block block, EnumSet<Season> allowedSeasons) {
        if (block != null && allowedSeasons != null && !allowedSeasons.isEmpty()) {
            Season season = this.seasons.getStateCopy(block.getWorld()).season;
            return allowedSeasons.contains(season) || this.greenhouse.isInGreenhouse(block) || !this.greenhouse.canSeeSky(block);
        } else {
            return true;
        }
    }

    /**
     * 一次生长评估的结果。
     *
     * <p>{@code cancel} 为 true 表示应取消本次生长；否则 {@code extraAges} 表示在正常生长之外
     * 额外推进的生长阶段数。</p>
     */
    public static final class GrowthDecision {
        private final boolean cancel;
        private final int extraAges;

        public GrowthDecision(boolean cancel, int extraAges) {
            super();
            this.cancel = cancel;
            this.extraAges = extraAges;
        }

        /**
         * 是否取消本次生长。
         */
        public boolean cancel() {
            return this.cancel;
        }

        /**
         * 额外推进的生长阶段数。
         */
        public int extraAges() {
            return this.extraAges;
        }
    }
}
