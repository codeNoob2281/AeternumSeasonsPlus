package kinkin.aeternum.world;

import kinkin.aeternum.compat.BedrockPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.logging.Logger;

/**
 * 生物群系数据包发送器（BiomePacketSender）。
 *
 * <p>通过反射直接构造并发送 NMS 的 {@code ClientboundChunksBiomesPacket}，只对“当前视野内”
 * 的玩家刷新指定区块的生物群系数据，比逐块 {@code refreshChunk} 更高效。所有 NMS 方法/字段
 * 都做了缓存与可访问性处理，若服务端版本不兼容则自动降级（{@link #disable} 后返回失败）。</p>
 *
 * <p>依赖 Bukkit 的 {@link World}/{@link Player}/{@link Chunk}，以及反射访问的
 * {@code net.minecraft.world.level.chunk.LevelChunk} 与
 * {@code net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket}。
 * 对基岩版玩家不做此处理（其与 Java 版渲染管线不同）。</p>
 */
final class BiomePacketSender {
    private final JavaPlugin plugin;
    private final Map<Class<?>, Method> worldHandleMethods = new HashMap();
    private final Map<Class<?>, Method> levelChunkMethods = new HashMap();
    private final Map<Class<?>, Method> playerHandleMethods = new HashMap();
    private final Map<Class<?>, Field> connectionFields = new HashMap();
    private final Map<Class<?>, Method> sendMethods = new HashMap();
    private boolean resolved;
    private boolean supported;
    private boolean warned;
    private Class<?> levelChunkClass;
    private Class<?> packetClass;
    private Method packetFactory;

    BiomePacketSender(JavaPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    /**
     * 返回当前服务端是否支持该轻量数据包发送方式（首次调用时进行反射解析）。
     */
    boolean isSupported() {
        this.resolvePacketTypes();
        return this.supported;
    }

    /**
     * 把指定区块的生物群系数据发送给世界内视野覆盖到它们的玩家。
     *
     * <p>若存在基岩版玩家位于相关区块附近则放弃（返回 {@code false}），交由调用方降级处理；
     * 否则为每个玩家挑出其视野内的区块，构造一次数据包并发送。</p>
     *
     * @param world  目标世界
     * @param chunks 需要刷新的区块列表
     * @return 是否成功完成发送
     */
    boolean send(World world, List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return true;
        } else {
            int distance = Bukkit.getViewDistance() + 1;

            for (Player viewer : world.getPlayers()) {
                if (BedrockPlayers.isBedrock(viewer)) {
                    Location position = viewer.getLocation();

                    for (Chunk chunk : chunks) {
                        if (Math.abs((position.getBlockX() >> 4) - chunk.getX()) <= distance && Math.abs((position.getBlockZ() >> 4) - chunk.getZ()) <= distance) {
                            return false;
                        }
                    }
                }
            }

            if (!this.isSupported()) {
                return false;
            } else {
                try {
                    Object level = this.worldHandle(world).invoke(world);
                    Method getChunk = this.levelChunkMethod(level.getClass());
                    List<Object> handles = new ArrayList(chunks.size());

                    for (Chunk chunk : chunks) {
                        handles.add(getChunk.invoke(level, chunk.getX(), chunk.getZ()));
                    }

                    int viewDistance = Bukkit.getViewDistance() + 1;
                    Map<Player, List<Object>> chunksByViewer = new LinkedHashMap();

                    for (Player player : world.getPlayers()) {
                        Location location = player.getLocation();
                        int playerChunkX = location.getBlockX() >> 4;
                        int playerChunkZ = location.getBlockZ() >> 4;
                        List<Object> visible = null;

                        for (int i = 0; i < chunks.size(); ++i) {
                            Chunk chunk = (Chunk) chunks.get(i);
                            if (Math.abs(playerChunkX - chunk.getX()) <= viewDistance && Math.abs(playerChunkZ - chunk.getZ()) <= viewDistance) {
                                if (visible == null) {
                                    visible = new ArrayList();
                                }

                                visible.add(handles.get(i));
                            }
                        }

                        if (visible != null && !visible.isEmpty()) {
                            chunksByViewer.put(player, visible);
                        }
                    }

                    for (Map.Entry<Player, List<Object>> entry : chunksByViewer.entrySet()) {
                        Object packet = this.packetFactory.invoke((Object) null, entry.getValue());
                        this.sendPacket((Player) entry.getKey(), packet);
                    }

                    return true;
                } catch (Throwable error) {
                    this.disable(error);
                    return false;
                }
            }
        }
    }

    /**
     * 反射解析 NMS 类名与数据包工厂方法（只解析一次，线程安全）。
     */
    private synchronized void resolvePacketTypes() {
        if (!this.resolved) {
            this.resolved = true;

            try {
                this.levelChunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
                this.packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket");

                for (Method method : this.packetClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1 && List.class.isAssignableFrom(method.getParameterTypes()[0]) && this.packetClass.isAssignableFrom(method.getReturnType())) {
                        method.trySetAccessible();
                        this.packetFactory = method;
                        break;
                    }
                }

                if (this.packetFactory == null) {
                    throw new NoSuchMethodException("ClientboundChunksBiomesPacket.forChunks(List)");
                }

                this.supported = true;
            } catch (Throwable error) {
                this.disable(error);
            }

        }
    }

    /**
     * 取（并缓存）世界对象的 {@code getHandle} 方法，用于访问对应的 NMS 服务端世界。
     */
    private Method worldHandle(World world) throws NoSuchMethodException {
        Method cached = (Method) this.worldHandleMethods.get(world.getClass());
        if (cached != null) {
            return cached;
        } else {
            Method method = world.getClass().getMethod("getHandle");
            method.trySetAccessible();
            this.worldHandleMethods.put(world.getClass(), method);
            return method;
        }
    }

    /**
     * 在 NMS 世界对象上查找（并缓存）{@code getChunk(int,int)} 方法。
     */
    private Method levelChunkMethod(Class<?> levelClass) throws NoSuchMethodException {
        Method cached = (Method) this.levelChunkMethods.get(levelClass);
        if (cached != null) {
            return cached;
        } else {
            for (Method method : levelClass.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == Integer.TYPE && parameters[1] == Integer.TYPE && this.levelChunkClass.isAssignableFrom(method.getReturnType())) {
                    method.trySetAccessible();
                    this.levelChunkMethods.put(levelClass, method);
                    return method;
                }
            }

            throw new NoSuchMethodException(levelClass.getName() + ".getChunk(int,int)");
        }
    }

    /**
     * 通过玩家连接把数据包发送出去（反射链路：Player → handle → connection → send）。
     */
    private void sendPacket(Player player, Object packet) throws ReflectiveOperationException {
        Method getHandle = (Method) this.playerHandleMethods.get(player.getClass());
        if (getHandle == null) {
            getHandle = player.getClass().getMethod("getHandle");
            getHandle.trySetAccessible();
            this.playerHandleMethods.put(player.getClass(), getHandle);
        }

        Object handle = getHandle.invoke(player);
        Field connectionField = (Field) this.connectionFields.get(handle.getClass());
        if (connectionField == null) {
            connectionField = this.findConnectionField(handle.getClass());
            this.connectionFields.put(handle.getClass(), connectionField);
        }

        Object connection = connectionField.get(handle);
        Method send = (Method) this.sendMethods.get(connection.getClass());
        if (send == null) {
            send = this.findSendMethod(connection.getClass());
            this.sendMethods.put(connection.getClass(), send);
        }

        send.invoke(connection, packet);
    }

    /**
     * 在指定类的继承链上查找类型名以 {@code ServerGamePacketListenerImpl} 结尾的连接字段。
     */
    private Field findConnectionField(Class<?> owner) throws NoSuchFieldException {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getName().endsWith("ServerGamePacketListenerImpl")) {
                    field.trySetAccessible();
                    return field;
                }
            }
        }

        throw new NoSuchFieldException(owner.getName() + ".connection");
    }

    /**
     * 在连接类上查找可发送该数据包的 {@code send} 方法（优先名为 send 的方法，否则回退到首个匹配项）。
     */
    private Method findSendMethod(Class<?> connectionClass) throws NoSuchMethodException {
        Method fallback = null;

        for (Method method : connectionClass.getMethods()) {
            if (method.getParameterCount() == 1 && method.getReturnType() == Void.TYPE && method.getParameterTypes()[0].isAssignableFrom(this.packetClass)) {
                if ("send".equals(method.getName())) {
                    method.trySetAccessible();
                    return method;
                }

                fallback = method;
            }
        }

        if (fallback != null) {
            fallback.trySetAccessible();
            return fallback;
        } else {
            throw new NoSuchMethodException(connectionClass.getName() + ".send(Packet)");
        }
    }

    /**
     * 标记该发送器不可用，并（仅首次）输出一条降级警告日志。
     */
    private synchronized void disable(Throwable error) {
        this.supported = false;
        this.resolved = true;
        if (!this.warned) {
            this.warned = true;
            Logger logger = this.plugin.getLogger();
            String causeName = error.getClass().getSimpleName();
            logger.warning("[BiomeSpoof] No se pudo usar el paquete ligero de biomas; se usará refreshChunk limitado. Causa: " + causeName + ": " + error.getMessage());
        }
    }
}
