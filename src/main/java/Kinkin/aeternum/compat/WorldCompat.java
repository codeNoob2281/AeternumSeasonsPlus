package kinkin.aeternum.compat;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * 世界/游戏规则兼容工具。
 *
 * <p>不同 Minecraft 版本的 GameRule 字段名存在差异（例如 ADVANCE_TIME 与 DO_DAYLIGHT_CYCLE），
 * 本工具通过反射在 {@code org.bukkit.GameRules} 与 {@code org.bukkit.GameRule} 中探测可用规则，
 * 并缓存探测结果，供需要手动控制时间/天气的场景使用。</p>
 *
 * <p>仅对常规维度（{@link Environment#NORMAL}）执行手动时间控制；其他维度视为不支持。</p>
 */
public final class WorldCompat {
    /**
     * 是否已提示过缺少 daylight cycle 规则（避免重复刷日志）。
     */
    private static boolean warnedDaylightMissing = false;
    /**
     * 是否已提示过缺少 weather cycle 规则（避免重复刷日志）。
     */
    private static boolean warnedWeatherMissing = false;
    /**
     * daylight 规则是否已探测完成。
     */
    private static volatile boolean daylightRuleResolved = false;
    /**
     * weather 规则是否已探测完成。
     */
    private static volatile boolean weatherRuleResolved = false;
    /**
     * 缓存的 daylight 规则。
     */
    private static GameRule<Boolean> cachedDaylightRule;
    /**
     * 缓存的 weather 规则。
     */
    private static GameRule<Boolean> cachedWeatherRule;

    private WorldCompat() {
        super();
    }

    /**
     * 依次在若干候选类、候选字段名中查找第一个可用的布尔 GameRule，找不到返回 null。
     */
    private static GameRule<Boolean> findBooleanGameRule(String... fieldNames) {
        String[] ownerNames = new String[]{"org.bukkit.GameRules", "org.bukkit.GameRule"};

        for (String ownerName : ownerNames) {
            try {
                Class<?> owner = Class.forName(ownerName);

                for (String name : fieldNames) {
                    try {
                        Field f = owner.getField(name);
                        Object value = f.get((Object) null);
                        if (value instanceof GameRule rule) {
                            return (GameRule<Boolean>) rule;
                        }
                    } catch (NoSuchFieldException ignoredField) {
                    }
                }
            } catch (ClassNotFoundException ignoredClass) {
            } catch (Throwable ignoredError) {
            }
        }

        return null;
    }

    /**
     * 获取 daylight cycle 对应的 GameRule（双重检查加锁懒加载并缓存，可能为 null）。
     */
    public static GameRule<Boolean> daylightCycleRule() {
        if (!daylightRuleResolved) {
            synchronized (WorldCompat.class) {
                if (!daylightRuleResolved) {
                    cachedDaylightRule = findBooleanGameRule("ADVANCE_TIME", "DO_DAYLIGHT_CYCLE");
                    daylightRuleResolved = true;
                }
            }
        }

        return cachedDaylightRule;
    }

    /**
     * 获取 weather cycle 对应的 GameRule（双重检查加锁懒加载并缓存，可能为 null）。
     */
    public static GameRule<Boolean> weatherCycleRule() {
        if (!weatherRuleResolved) {
            synchronized (WorldCompat.class) {
                if (!weatherRuleResolved) {
                    cachedWeatherRule = findBooleanGameRule("ADVANCE_WEATHER", "DO_WEATHER_CYCLE");
                    weatherRuleResolved = true;
                }
            }
        }

        return cachedWeatherRule;
    }

    /**
     * 判断世界是否允许时间自然推进（无法获取规则时默认按允许处理）。
     */
    public static boolean isTimeAdvancementEnabled(World world) {
        if (!supportsManualTime(world)) {
            return true;
        } else {
            GameRule<Boolean> rule = daylightCycleRule();
            if (rule == null) {
                return true;
            } else {
                try {
                    Boolean value = (Boolean) world.getGameRuleValue(rule);
                    return value == null || value;
                } catch (Throwable failure) {
                    return true;
                }
            }
        }
    }

    /**
     * 关闭世界的 daylight cycle；规则缺失或设置失败时返回 false。
     */
    public static boolean disableDaylightCycle(World world, JavaPlugin plugin) {
        if (world == null) {
            return false;
        } else {
            GameRule<Boolean> rule = daylightCycleRule();
            if (rule == null) {
                if (!warnedDaylightMissing) {
                    warnedDaylightMissing = true;
                    plugin.getLogger().warning("[Compat] No se encontró GameRule de daylight cycle (ni vieja ni nueva API).");
                }

                return false;
            } else {
                try {
                    return world.setGameRule(rule, false);
                } catch (Throwable failure) {
                    Logger logger = plugin.getLogger();
                    String worldName = world.getName();
                    logger.warning("[Compat] No se pudo desactivar daylight cycle en " + worldName + ": " + failure.getMessage());
                    return false;
                }
            }
        }
    }

    /**
     * 关闭世界的 weather cycle；规则缺失或设置失败时返回 false。
     */
    public static boolean disableWeatherCycle(World world, JavaPlugin plugin) {
        if (world == null) {
            return false;
        } else {
            GameRule<Boolean> rule = weatherCycleRule();
            if (rule == null) {
                if (!warnedWeatherMissing) {
                    warnedWeatherMissing = true;
                    plugin.getLogger().warning("[Compat] No se encontró GameRule de weather cycle (ni vieja ni nueva API).");
                }

                return false;
            } else {
                try {
                    return world.setGameRule(rule, false);
                } catch (Throwable failure) {
                    Logger logger = plugin.getLogger();
                    String worldName = world.getName();
                    logger.warning("[Compat] No se pudo desactivar weather cycle en " + worldName + ": " + failure.getMessage());
                    return false;
                }
            }
        }
    }

    /**
     * 判断世界是否支持手动设置时间（仅常规维度支持）。
     */
    public static boolean supportsManualTime(World world) {
        if (world == null) {
            return false;
        } else {
            return world.getEnvironment() == Environment.NORMAL;
        }
    }

    /**
     * 尝试设置世界时间（day time）；失败返回 false 并记录日志。
     */
    public static boolean trySetTime(World world, long time, JavaPlugin plugin) {
        if (!supportsManualTime(world)) {
            return false;
        } else {
            try {
                world.setTime(time);
                return true;
            } catch (IllegalArgumentException ex) {
                Logger logger = plugin.getLogger();
                String worldName = world.getName();
                logger.warning("[Compat] No se pudo aplicar setTime en " + worldName + ": " + ex.getMessage());
                return false;
            } catch (Throwable failure) {
                Logger logger = plugin.getLogger();
                String worldName = world.getName();
                logger.warning("[Compat] Error aplicando setTime en " + worldName + ": " + failure.getMessage());
                return false;
            }
        }
    }

    /**
     * 尝试设置世界完整时间（full time）；失败返回 false 并记录日志。
     */
    public static boolean trySetFullTime(World world, long fullTime, JavaPlugin plugin) {
        if (!supportsManualTime(world)) {
            return false;
        } else {
            try {
                world.setFullTime(fullTime);
                return true;
            } catch (IllegalArgumentException ex) {
                Logger logger = plugin.getLogger();
                String worldName = world.getName();
                logger.warning("[Compat] No se pudo aplicar setFullTime en " + worldName + ": " + ex.getMessage());
                return false;
            } catch (Throwable failure) {
                Logger logger = plugin.getLogger();
                String worldName = world.getName();
                logger.warning("[Compat] Error aplicando setFullTime en " + worldName + ": " + failure.getMessage());
                return false;
            }
        }
    }
}
