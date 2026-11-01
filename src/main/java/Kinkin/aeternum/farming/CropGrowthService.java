package Kinkin.aeternum.farming;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.block.Block;

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

    public GrowthDecision evaluate(Block b) {
        return this.evaluate(b, (EnumSet)null);
    }

    public GrowthDecision evaluate(Block b, EnumSet<Season> allowedSeasonsOverride) {
        Material m = b.getType();
        if (allowedSeasonsOverride == null && !this.config.isManagedCrop(m)) {
            return new GrowthDecision(false, 0);
        } else {
            EnumSet<Season> allowed = allowedSeasonsOverride != null ? allowedSeasonsOverride : this.config.getAllowedSeasons(m);
            if (allowed != null && !allowed.isEmpty()) {
                CalendarState st = this.seasons.getStateCopy(b.getWorld());
                Season season = st.season;
                boolean inGreenhouse = this.greenhouse.isInGreenhouse(b);
                boolean seesSky = this.greenhouse.canSeeSky(b);
                if (!allowed.contains(season) && !inGreenhouse && seesSky) {
                    return new GrowthDecision(true, 0);
                } else {
                    double speed = (double)1.0F;
                    int light = b.getLightLevel();
                    if (light < this.config.getRequiredLight()) {
                        speed *= this.config.getLowLightMultiplier();
                    }

                    if (!seesSky && !inGreenhouse) {
                        speed *= this.config.getUndergroundMultiplier();
                    }

                    if (this.greenhouse.isDirectlyUnderRain(b) && !inGreenhouse) {
                        speed += this.config.getRainBonus();
                    }

                    if (inGreenhouse && season == Season.WINTER) {
                        speed += this.config.getWinterGreenhouseBonus();
                    }

                    if (speed <= (double)0.0F) {
                        return new GrowthDecision(true, 0);
                    } else {
                        double r = ThreadLocalRandom.current().nextDouble();
                        if (speed < (double)1.0F) {
                            return r > speed ? new GrowthDecision(true, 0) : new GrowthDecision(false, 0);
                        } else {
                            double over = speed - (double)1.0F;
                            int extra = (int)Math.floor(over);
                            double frac = over - (double)extra;
                            if (r < frac) {
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

    public boolean isSeasonAllowed(Block block, EnumSet<Season> allowedSeasons) {
        if (block != null && allowedSeasons != null && !allowedSeasons.isEmpty()) {
            Season season = this.seasons.getStateCopy(block.getWorld()).season;
            return allowedSeasons.contains(season) || this.greenhouse.isInGreenhouse(block) || !this.greenhouse.canSeeSky(block);
        } else {
            return true;
        }
    }

    public static final class GrowthDecision {
        private final boolean cancel;
        private final int extraAges;

        public GrowthDecision(boolean cancel, int extraAges) {
            super();
            this.cancel = cancel;
            this.extraAges = extraAges;
        }

        public boolean cancel() {
            return this.cancel;
        }

        public int extraAges() {
            return this.extraAges;
        }
    }
}
