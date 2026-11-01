package Kinkin.aeternum.compat;

import java.lang.reflect.Field;
import java.util.logging.Logger;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldCompat {
    private static boolean warnedDaylightMissing = false;
    private static boolean warnedWeatherMissing = false;
    private static volatile boolean daylightRuleResolved = false;
    private static volatile boolean weatherRuleResolved = false;
    private static GameRule<Boolean> cachedDaylightRule;
    private static GameRule<Boolean> cachedWeatherRule;

    private WorldCompat() {
        super();
    }

    private static GameRule<Boolean> findBooleanGameRule(String... fieldNames) {
        String[] ownerNames = new String[]{"org.bukkit.GameRules", "org.bukkit.GameRule"};

        for(String ownerName : ownerNames) {
            try {
                Class<?> owner = Class.forName(ownerName);

                for(String name : fieldNames) {
                    try {
                        Field f = owner.getField(name);
                        Object value = f.get((Object)null);
                        if (value instanceof GameRule rule) {
                            return (GameRule<Boolean>)rule;
                        }
                    } catch (NoSuchFieldException var14) {
                    }
                }
            } catch (ClassNotFoundException var15) {
            } catch (Throwable var16) {
            }
        }

        return null;
    }

    public static GameRule<Boolean> daylightCycleRule() {
        if (!daylightRuleResolved) {
            synchronized(WorldCompat.class) {
                if (!daylightRuleResolved) {
                    cachedDaylightRule = findBooleanGameRule("ADVANCE_TIME", "DO_DAYLIGHT_CYCLE");
                    daylightRuleResolved = true;
                }
            }
        }

        return cachedDaylightRule;
    }

    public static GameRule<Boolean> weatherCycleRule() {
        if (!weatherRuleResolved) {
            synchronized(WorldCompat.class) {
                if (!weatherRuleResolved) {
                    cachedWeatherRule = findBooleanGameRule("ADVANCE_WEATHER", "DO_WEATHER_CYCLE");
                    weatherRuleResolved = true;
                }
            }
        }

        return cachedWeatherRule;
    }

    public static boolean isTimeAdvancementEnabled(World world) {
        if (!supportsManualTime(world)) {
            return true;
        } else {
            GameRule<Boolean> rule = daylightCycleRule();
            if (rule == null) {
                return true;
            } else {
                try {
                    Boolean value = (Boolean)world.getGameRuleValue(rule);
                    return value == null || value;
                } catch (Throwable var3) {
                    return true;
                }
            }
        }
    }

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
                } catch (Throwable t) {
                    Logger var10000 = plugin.getLogger();
                    String var10001 = world.getName();
                    var10000.warning("[Compat] No se pudo desactivar daylight cycle en " + var10001 + ": " + t.getMessage());
                    return false;
                }
            }
        }
    }

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
                } catch (Throwable t) {
                    Logger var10000 = plugin.getLogger();
                    String var10001 = world.getName();
                    var10000.warning("[Compat] No se pudo desactivar weather cycle en " + var10001 + ": " + t.getMessage());
                    return false;
                }
            }
        }
    }

    public static boolean supportsManualTime(World world) {
        if (world == null) {
            return false;
        } else {
            return world.getEnvironment() == Environment.NORMAL;
        }
    }

    public static boolean trySetTime(World world, long time, JavaPlugin plugin) {
        if (!supportsManualTime(world)) {
            return false;
        } else {
            try {
                world.setTime(time);
                return true;
            } catch (IllegalArgumentException ex) {
                Logger var7 = plugin.getLogger();
                String var8 = world.getName();
                var7.warning("[Compat] No se pudo aplicar setTime en " + var8 + ": " + ex.getMessage());
                return false;
            } catch (Throwable t) {
                Logger var10000 = plugin.getLogger();
                String var10001 = world.getName();
                var10000.warning("[Compat] Error aplicando setTime en " + var10001 + ": " + t.getMessage());
                return false;
            }
        }
    }

    public static boolean trySetFullTime(World world, long fullTime, JavaPlugin plugin) {
        if (!supportsManualTime(world)) {
            return false;
        } else {
            try {
                world.setFullTime(fullTime);
                return true;
            } catch (IllegalArgumentException ex) {
                Logger var7 = plugin.getLogger();
                String var8 = world.getName();
                var7.warning("[Compat] No se pudo aplicar setFullTime en " + var8 + ": " + ex.getMessage());
                return false;
            } catch (Throwable t) {
                Logger var10000 = plugin.getLogger();
                String var10001 = world.getName();
                var10000.warning("[Compat] Error aplicando setFullTime en " + var10001 + ": " + t.getMessage());
                return false;
            }
        }
    }
}
