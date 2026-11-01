package Kinkin.aeternum.compat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.core.world.Season;
import net.momirealms.customcrops.api.integration.SeasonProvider;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public final class CustomCropsSeasonHook {
    private CustomCropsSeasonHook() {
        super();
    }

    public static void register(AeternumSeasonsPlugin plugin) {
        BukkitCustomCropsPlugin.getInstance().getIntegrationManager().registerSeasonProvider(new AeternumSeasonProvider(plugin));
        plugin.getLogger().info("[AeternumSeasons] Hooked CustomCrops SeasonProvider (AeternumSeasons).");
    }

    private static final class AeternumSeasonProvider implements SeasonProvider {
        private final AeternumSeasonsPlugin plugin;

        private AeternumSeasonProvider(AeternumSeasonsPlugin plugin) {
            super();
            this.plugin = plugin;
        }

        public @NotNull Season getSeason(@NotNull World world) {
            if (this.plugin.getSeasons() == null) {
                return Season.SPRING;
            } else {
                CalendarState st = this.plugin.getSeasons().getStateCopy(world);
                Kinkin.aeternum.calendar.Season s = st.season;
                String name = s.name();

                try {
                    return Season.valueOf(name);
                } catch (Throwable var9) {
                    if ("AUTUMN".equalsIgnoreCase(name)) {
                        try {
                            return Season.valueOf("FALL");
                        } catch (Throwable var8) {
                        }
                    }

                    if ("FALL".equalsIgnoreCase(name)) {
                        try {
                            return Season.valueOf("AUTUMN");
                        } catch (Throwable var7) {
                        }
                    }

                    return Season.SPRING;
                }
            }
        }

        public String identifier() {
            return "AeternumSeasons";
        }
    }
}
