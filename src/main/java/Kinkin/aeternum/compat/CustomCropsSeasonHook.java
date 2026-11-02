package Kinkin.aeternum.compat;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.core.world.Season;
import net.momirealms.customcrops.api.integration.SeasonProvider;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * CustomCrops 季节适配钩子。
 *
 * <p>向 CustomCrops 注册一个 {@link SeasonProvider}，把本插件（AeternumSeasons）的日历季节
 * 翻译为其期望的季节枚举，使作物生长逻辑跟随本插件的季节变化。</p>
 *
 * <p>依赖：CustomCrops 插件 API。若未安装 CustomCrops，则不应调用 {@link #register}。</p>
 */
public final class CustomCropsSeasonHook {
    private CustomCropsSeasonHook() {
        super();
    }

    /** 注册 CustomCrops 的季节提供者，并输出一条钩子就绪日志。 */
    public static void register(AeternumSeasonsPlugin plugin) {
        BukkitCustomCropsPlugin.getInstance().getIntegrationManager().registerSeasonProvider(new AeternumSeasonProvider(plugin));
        plugin.getLogger().info("[AeternumSeasons] Hooked CustomCrops SeasonProvider (AeternumSeasons).");
    }

    /** 将 AeternumSeasons 的日历季节桥接为 CustomCrops 的季节枚举。 */
    private static final class AeternumSeasonProvider implements SeasonProvider {
        private final AeternumSeasonsPlugin plugin;

        private AeternumSeasonProvider(AeternumSeasonsPlugin plugin) {
            super();
            this.plugin = plugin;
        }

        /**
         * 返回给定世界当前的季节。
         *
         * <p>先按名称精确匹配；若季节命名不同（AUTUMN/FALL 差异），则互做一次兼容转换；
         * 仍无法匹配时回退到 {@link Season#SPRING}。</p>
         */
        public @NotNull Season getSeason(@NotNull World world) {
            if (this.plugin.getSeasons() == null) {
                return Season.SPRING;
            } else {
                CalendarState st = this.plugin.getSeasons().getStateCopy(world);
                Kinkin.aeternum.calendar.Season s = st.season;
                String name = s.name();

                try {
                    return Season.valueOf(name);
                } catch (Throwable unknownSeason) {
                    if ("AUTUMN".equalsIgnoreCase(name)) {
                        try {
                            return Season.valueOf("FALL");
                        } catch (Throwable fallLookupFailure) {
                        }
                    }

                    if ("FALL".equalsIgnoreCase(name)) {
                        try {
                            return Season.valueOf("AUTUMN");
                        } catch (Throwable autumnLookupFailure) {
                        }
                    }

                    return Season.SPRING;
                }
            }
        }

        /** 提供者标识名，供 CustomCrops 区分来源。 */
        public String identifier() {
            return "AeternumSeasons";
        }
    }
}
