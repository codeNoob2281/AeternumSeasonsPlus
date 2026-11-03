package kinkin.aeternum.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 跨平台调度器工具。
 *
 * <p>兼容 Folia 与普通 Paper/Spigot：若服务端提供区域化调度 API（{@code getGlobalRegionScheduler} /
 * {@code getRegionScheduler}），通过反射优先使用；否则回退到传统的 {@link Bukkit#getScheduler()}。</p>
 *
 * <p>工具类：私有构造，全部为静态方法。返回的 {@link TaskHandle} 统一封装取消操作，
 * 屏蔽底层调度器差异。</p>
 */
public final class PlatformScheduler {
    private PlatformScheduler() {
        super();
    }

    /**
     * 判断类上是否存在指定签名的方法。
     */
    private static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        try {
            c.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    /**
     * 服务端是否支持全局区域调度器（Folia 新增 API）。
     */
    public static boolean hasGlobalRegionScheduler() {
        return hasMethod(Bukkit.class, "getGlobalRegionScheduler");
    }

    /**
     * 反射获取全局区域调度器，失败返回 null。
     */
    private static Object getGlobalScheduler() {
        try {
            Method m = Bukkit.class.getMethod("getGlobalRegionScheduler");
            return m.invoke((Object) null);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 反射获取区域调度器，失败返回 null。
     */
    private static Object getRegionScheduler() {
        try {
            Method m = Bukkit.class.getMethod("getRegionScheduler");
            return m.invoke((Object) null);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 在全局线程执行一次任务：优先区域调度器，否则回退主线程调度。
     */
    public static void executeGlobal(Plugin plugin, Runnable run) {
        Object gs = getGlobalScheduler();
        if (gs != null) {
            try {
                Method exec = gs.getClass().getMethod("execute", Plugin.class, Runnable.class);
                exec.invoke(gs, plugin, run);
                return;
            } catch (Throwable failure) {
            }
        }

        Bukkit.getScheduler().runTask(plugin, run);
    }

    /**
     * 在全局线程以固定周期运行任务，返回可取消的句柄。
     */
    public static TaskHandle runGlobalTimer(Plugin plugin, Runnable run, long delayTicks, long periodTicks) {
        Object gs = getGlobalScheduler();
        if (gs != null) {
            try {
                Method m = gs.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, Long.TYPE, Long.TYPE);
                Consumer<Object> c = (ignored) -> run.run();
                Object task = m.invoke(gs, plugin, c, delayTicks, periodTicks);
                return new ReflectTaskHandle(task);
            } catch (Throwable failure) {
            }
        }

        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, run, delayTicks, periodTicks));
    }

    /**
     * 在指定位置所属区域执行一次任务（位置为 null 时不做任何事）。
     */
    public static void executeAtLocation(Plugin plugin, Location loc, Runnable run) {
        if (loc != null) {
            Object rs = getRegionScheduler();
            if (rs != null) {
                try {
                    Method exec = rs.getClass().getMethod("execute", Plugin.class, Location.class, Runnable.class);
                    exec.invoke(rs, plugin, loc, run);
                    return;
                } catch (Throwable failure) {
                }
            }

            Bukkit.getScheduler().runTask(plugin, run);
        }
    }

    /**
     * 在全局线程延迟执行一次任务，返回可取消的句柄。
     */
    public static TaskHandle runGlobalLater(Plugin plugin, Runnable run, long delayTicks) {
        Object gs = getGlobalScheduler();
        if (gs != null) {
            try {
                Method m = gs.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Long.TYPE);
                Consumer<Object> c = (ignored) -> run.run();
                Object task = m.invoke(gs, plugin, c, delayTicks);
                return new ReflectTaskHandle(task);
            } catch (Throwable failure) {
            }
        }

        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, run, delayTicks));
    }

    /**
     * 基于 {@link BukkitTask} 的任务句柄。
     */
    private static final class BukkitTaskHandle implements TaskHandle {
        private final BukkitTask task;

        private BukkitTaskHandle(BukkitTask task) {
            super();
            this.task = task;
        }

        /**
         * 取消底层任务。
         */
        @Override
        public void cancel() {
            if (this.task != null) {
                this.task.cancel();
            }

        }
    }

    /**
     * 基于反射对象（Folia 任务）的任务句柄。
     */
    private static final class ReflectTaskHandle implements TaskHandle {
        private final Object task;

        private ReflectTaskHandle(Object task) {
            super();
            this.task = task;
        }

        /**
         * 通过反射调用底层任务的 cancel 方法。
         */
        @Override
        public void cancel() {
            if (this.task != null) {
                try {
                    Method m = this.task.getClass().getMethod("cancel");
                    m.invoke(this.task);
                } catch (Throwable failure) {
                }

            }
        }
    }

    /**
     * 可取消的任务句柄抽象。
     */
    public interface TaskHandle {
        /**
         * 取消关联的任务。
         */
        void cancel();
    }
}
