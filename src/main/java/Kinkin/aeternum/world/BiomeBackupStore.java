package kinkin.aeternum.world;

import kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * 生物群系备份存储（BiomeBackupStore）。
 *
 * <p>在生物群系伪装首次影响某个区块时，把该区块原始的“缩放生物群系网格”持久化下来，以便日后
 * 把地形还原为真实群系。支持两种存储形态：
 * <ul>
 *     <li><b>PACKED</b>：每个世界一个 {@code .asb} 容器文件，内部按“记录头 + 载荷 + CRC 校验”追加写入；</li>
 *     <li><b>LEGACY_FILES</b>：每个区块一个 {@code 世界ID/x_z.bin} 文件。</li>
 * </ul>
 * 首次读/写时会把旧文件迁移进容器。还原（restore）以异步分片任务进行，先收集条目，再按每 tick 预算
 * 逐块应用，最后压缩容器并清理旧文件。</p>
 *
 * <p>依赖 Bukkit 的 {@link Chunk}/{@link World}、调度器与 {@link BukkitRunnable}；持久化基于
 * {@link FileChannel} 与 CRC32 校验。</p>
 */
public final class BiomeBackupStore {
    private static final int BACKUP_MAGIC = -1364129008;
    private static final byte BACKUP_VERSION = 1;
    private static final int CONTAINER_MAGIC = -1364128752;
    private static final byte CONTAINER_VERSION = 1;
    private static final int RECORD_MAGIC = -1364082687;
    private static final int CONTAINER_HEADER_BYTES = 21;
    private static final int RECORD_HEADER_BYTES = 20;
    private static final int MAX_PAYLOAD_BYTES = 16777216;
    private static final String CONTAINER_EXTENSION = ".asb";
    private final AeternumSeasonsPlugin plugin;
    private final Path root;
    private final boolean packedStorage;
    private final boolean migrateLegacyFiles;
    private final Map<UUID, WorldContainer> containers = new ConcurrentHashMap();
    private final AtomicBoolean restoreInProgress = new AtomicBoolean(false);
    private final Object legacyOperationLock = new Object();

    public BiomeBackupStore(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("biome_backups");
        String storage = plugin.cfg.climate.getString("biome_spoof.disk_backup.storage", "PACKED");
        this.packedStorage = !"LEGACY_FILES".equalsIgnoreCase(storage) && !"UNPACKED".equalsIgnoreCase(storage);
        this.migrateLegacyFiles = this.packedStorage && plugin.cfg.climate.getBoolean("biome_spoof.disk_backup.migrate_legacy_files", true);

        try {
            Files.createDirectories(this.root);
        } catch (IOException error) {
            plugin.getLogger().warning("[BiomeBackup] No se pudo crear la carpeta: " + error.getMessage());
        }

        if (this.migrateLegacyFiles) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, this::migrateLegacyBackups), 1L);
        }

    }

    /**
     * 首次触及某区块时，把其原始生物群系网格异步入库（每个区块只会保存一次）。
     */
    public void saveFirstTouch(Chunk chunk, Biome[] originalGrid, int stepXZ, int stepY) {
        if (!this.restoreInProgress.get()) {
            World world = chunk.getWorld();
            UUID worldId = world.getUID();
            int cx = chunk.getX();
            int cz = chunk.getZ();
            long coordinate = coordinateKey(cx, cz);
            WorldContainer state = this.container(worldId);
            if (!state.known.contains(coordinate) && state.pending.add(coordinate)) {
                Biome[] copy = (Biome[]) Arrays.copyOf(originalGrid, originalGrid.length);
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();
                Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                    try {
                        if (this.packedStorage) {
                            this.savePacked(state, cx, cz, copy, stepXZ, stepY, minY, maxY);
                        } else {
                            this.saveLegacy(state, cx, cz, copy, stepXZ, stepY, minY, maxY);
                        }
                    } catch (Throwable failure) {
                        Logger logger = this.plugin.getLogger();
                        String worldIdText = String.valueOf(worldId);
                        logger.warning("[BiomeBackup] Error guardando " + worldIdText + " " + cx + "," + cz + ": " + failure.getMessage());
                    } finally {
                        state.pending.remove(coordinate);
                    }

                });
            }
        }
    }

    /**
     * 异步读取指定区块的原始群系网格，读取完成后回到主线程通过 {@code callback} 回调。
     */
    public void loadOriginalGridAsync(World world, int cx, int cz, int expectedStepXZ, int expectedStepY, Consumer<Biome[]> callback) {
        UUID worldId = world.getUID();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Biome[] result = null;

            try {
                result = this.loadOriginalGrid(worldId, cx, cz, expectedStepXZ, expectedStepY, minY, maxY);
            } catch (Throwable failure) {
                Logger logger = this.plugin.getLogger();
                String worldIdText = String.valueOf(worldId);
                logger.warning("[BiomeBackup] Could not read " + worldIdText + " " + cx + "," + cz + ": " + failure.getMessage());
            }

            if (this.plugin.isEnabled()) {
                final Biome[] finalResult = result;
                Bukkit.getScheduler().runTask(this.plugin, () -> callback.accept(finalResult));
            }

        });
    }

    private Biome[] loadOriginalGrid(UUID worldId, int cx, int cz, int expectedStepXZ, int expectedStepY, int expectedMinY, int expectedMaxY) throws IOException {
        byte[] payload = null;
        WorldContainer state = this.container(worldId);
        synchronized (state.lock) {
            this.ensureContainerLoaded(state);
            if (state.records.containsKey(coordinateKey(cx, cz))) {
                payload = this.readPackedPayload(state, cx, cz);
            }
        }

        if (payload == null) {
            synchronized (this.legacyOperationLock) {
                synchronized (state.lock) {
                    this.ensureContainerLoaded(state);
                    if (state.records.containsKey(coordinateKey(cx, cz))) {
                        payload = this.readPackedPayload(state, cx, cz);
                    }
                }

                if (payload == null) {
                    Path legacy = this.legacyChunkFile(worldId, cx, cz);
                    if (Files.isRegularFile(legacy, new LinkOption[0])) {
                        payload = Files.readAllBytes(legacy);
                    }
                }
            }
        }

        if (payload == null) {
            return null;
        } else {
            BackupData data = this.decodeBackup(payload);
            if (data.stepXZ == expectedStepXZ && data.stepY == expectedStepY && data.minY == expectedMinY && data.maxY == expectedMaxY) {
                Biome[] grid = new Biome[data.indices.length];

                for (int i = 0; i < data.indices.length; ++i) {
                    int paletteIndex = data.indices[i];
                    if (paletteIndex < 0 || paletteIndex >= data.palette.length) {
                        throw new IOException("Invalid palette index in biome backup");
                    }

                    grid[i] = this.safeBiome(data.palette[paletteIndex]);
                }

                return grid;
            } else {
                this.plugin.getLogger().warning("[BiomeBackup] Incompatible backup for " + worldId + " " + cx + "," + cz + "; ignoring it to avoid applying a bad grid.");
                return null;
            }
        }
    }

    private void savePacked(WorldContainer state, int cx, int cz, Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        long coordinate = coordinateKey(cx, cz);
        synchronized (state.lock) {
            this.ensureContainerLoaded(state);
            if (state.records.containsKey(coordinate)) {
                state.known.add(coordinate);
            } else {
                Path legacy = this.legacyChunkFile(state.worldId, cx, cz);
                boolean cameFromLegacy = Files.isRegularFile(legacy, new LinkOption[0]);
                byte[] payload;
                if (cameFromLegacy) {
                    payload = Files.readAllBytes(legacy);
                    this.decodeBackup(payload);
                } else {
                    payload = this.encodeBackup(grid, stepXZ, stepY, minY, maxY);
                }

                this.appendRecord(state, cx, cz, payload);
                if (cameFromLegacy) {
                    Files.deleteIfExists(legacy);
                    this.deleteDirectoryIfEmpty(legacy.getParent());
                }

            }
        }
    }

    private void saveLegacy(WorldContainer state, int cx, int cz, Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        long coordinate = coordinateKey(cx, cz);
        Path file = this.legacyChunkFile(state.worldId, cx, cz);
        synchronized (state.lock) {
            this.ensureContainerLoaded(state);
            if (!state.records.containsKey(coordinate) && !Files.isRegularFile(file, new LinkOption[0])) {
                Files.createDirectories(file.getParent());
                byte[] payload = this.encodeBackup(grid, stepXZ, stepY, minY, maxY);
                Files.write(file, payload, new OpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE});
                state.known.add(coordinate);
            } else {
                state.known.add(coordinate);
            }
        }
    }

    /**
     * 把群系网格编码为带回调色板的二进制载荷（含魔数、步长、范围、调色板与索引）。
     */
    private byte[] encodeBackup(Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        Map<String, Integer> paletteMap = new LinkedHashMap();
        List<String> palette = new ArrayList();
        int[] indices = new int[grid.length];

        for (int i = 0; i < grid.length; ++i) {
            String name = grid[i].name();
            Integer index = (Integer) paletteMap.get(name);
            if (index == null) {
                index = palette.size();
                palette.add(name);
                paletteMap.put(name, index);
            }

            indices[i] = index;
        }

        boolean useByte = palette.size() <= 255;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(256, grid.length + 128));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(bytes));

        try {
            out.writeInt(-1364129008);
            out.writeByte(1);
            out.writeByte(stepXZ);
            out.writeByte(stepY);
            out.writeInt(minY);
            out.writeInt(maxY);
            out.writeInt(palette.size());

            for (String biome : palette) {
                byte[] name = biome.getBytes(StandardCharsets.UTF_8);
                if (name.length > 65535) {
                    throw new IOException("Nombre de bioma demasiado largo");
                }

                out.writeShort(name.length);
                out.write(name);
            }

            out.writeInt(indices.length);
            out.writeBoolean(useByte);
            if (useByte) {
                for (int value : indices) {
                    out.writeByte(value);
                }
            } else {
                for (int value : indices) {
                    out.writeShort(value);
                }
            }
        } catch (Throwable writeFailure) {
            try {
                out.close();
            } catch (Throwable closeFailure) {
                writeFailure.addSuppressed(closeFailure);
            }

            throw writeFailure;
        }

        out.close();
        return bytes.toByteArray();
    }

    /**
     * 获取（或惰性创建）某世界对应的容器状态。
     */
    private WorldContainer container(UUID worldId) {
        return (WorldContainer) this.containers.computeIfAbsent(worldId, (id) -> new WorldContainer(id, this.root.resolve(id + ".asb")));
    }

    /**
     * 惰性加载容器文件：校验头部与各记录，跳过损坏记录，并在末尾截断不完整的写入。
     */
    private void ensureContainerLoaded(WorldContainer state) throws IOException {
        if (!state.loaded) {
            state.records.clear();
            state.known.clear();
            if (!Files.exists(state.path, new LinkOption[0])) {
                state.loaded = true;
            } else {
                FileChannel channel = FileChannel.open(state.path, StandardOpenOption.READ, StandardOpenOption.WRITE);

                try {
                    long size = channel.size();
                    if (size < 21L) {
                        throw new IOException("Contenedor incompleto: " + state.path);
                    }

                    ByteBuffer header = ByteBuffer.allocate(21);
                    if (!readFully(channel, header, 0L)) {
                        throw new EOFException("Header incompleto");
                    }

                    header.flip();
                    int magic = header.getInt();
                    byte version = header.get();
                    UUID storedWorld = new UUID(header.getLong(), header.getLong());
                    if (magic != -1364128752) {
                        throw new IOException("Bad container magic");
                    }

                    if (version != 1) {
                        throw new IOException("Bad container version " + version);
                    }

                    if (!state.worldId.equals(storedWorld)) {
                        throw new IOException("World UUID no coincide");
                    }

                    long next;
                    for (long position = 21L; position < size; position = next) {
                        if (size - position < 20L) {
                            this.truncateIncompleteTail(channel, state.path, position);
                            break;
                        }

                        ByteBuffer recordHeader = ByteBuffer.allocate(20);
                        if (!readFully(channel, recordHeader, position)) {
                            this.truncateIncompleteTail(channel, state.path, position);
                            break;
                        }

                        recordHeader.flip();
                        int recordMagic = recordHeader.getInt();
                        int cx = recordHeader.getInt();
                        int cz = recordHeader.getInt();
                        int length = recordHeader.getInt();
                        int checksum = recordHeader.getInt();
                        if (recordMagic != -1364082687 || length <= 0 || length > 16777216) {
                            throw new IOException("Registro inválido en offset " + position);
                        }

                        long payloadOffset = position + 20L;
                        next = payloadOffset + (long) length;
                        if (next > size) {
                            this.truncateIncompleteTail(channel, state.path, position);
                            break;
                        }

                        byte[] payload = readBytes(channel, payloadOffset, length);
                        if (checksum(payload) != checksum) {
                            Logger logger = this.plugin.getLogger();
                            String fileNameText = String.valueOf(state.path.getFileName());
                            logger.warning("[BiomeBackup] Registro corrupto ignorado en " + fileNameText + " offset=" + position);
                        } else {
                            long key = coordinateKey(cx, cz);
                            state.records.putIfAbsent(key, new RecordMeta(cx, cz, position, payloadOffset, length, checksum));
                            state.known.add(key);
                        }
                    }
                } catch (Throwable readFailure) {
                    if (channel != null) {
                        try {
                            channel.close();
                        } catch (Throwable closeFailure) {
                            readFailure.addSuppressed(closeFailure);
                        }
                    }

                    throw readFailure;
                }

                if (channel != null) {
                    channel.close();
                }

                state.loaded = true;
            }
        }
    }

    /**
     * 向容器文件追加一条区块备份记录（若已存在则跳过），并更新内存索引。
     */
    private void appendRecord(WorldContainer state, int cx, int cz, byte[] payload) throws IOException {
        if (payload.length > 0 && payload.length <= 16777216) {
            this.ensureContainerLoaded(state);
            long key = coordinateKey(cx, cz);
            if (state.records.containsKey(key)) {
                state.known.add(key);
            } else {
                Files.createDirectories(this.root);
                boolean newFile = !Files.exists(state.path, new LinkOption[0]);

                try {
                    FileChannel channel = FileChannel.open(state.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

                    try {
                        if (newFile || channel.size() == 0L) {
                            channel.truncate(0L);
                            this.writeContainerHeader(channel, state.worldId);
                        }

                        long recordStart = channel.size();
                        long payloadOffset = recordStart + 20L;
                        int crc = checksum(payload);
                        ByteBuffer header = ByteBuffer.allocate(20);
                        header.putInt(-1364082687);
                        header.putInt(cx);
                        header.putInt(cz);
                        header.putInt(payload.length);
                        header.putInt(crc);
                        header.flip();
                        writeFully(channel, header, recordStart);
                        writeFully(channel, ByteBuffer.wrap(payload), payloadOffset);
                        RecordMeta meta = new RecordMeta(cx, cz, recordStart, payloadOffset, payload.length, crc);
                        state.records.put(key, meta);
                        state.known.add(key);
                    } catch (Throwable appendFailure) {
                        if (channel != null) {
                            try {
                                channel.close();
                            } catch (Throwable closeFailure) {
                                appendFailure.addSuppressed(closeFailure);
                            }
                        }

                        throw appendFailure;
                    }

                    if (channel != null) {
                        channel.close();
                    }

                } catch (RuntimeException | IOException failure) {
                    state.loaded = false;
                    state.records.clear();
                    state.known.clear();
                    throw failure;
                }
            }
        } else {
            throw new IOException("Payload fuera de rango: " + payload.length);
        }
    }

    /**
     * 写入容器头部（魔数、版本、世界 UUID）。
     */
    private void writeContainerHeader(FileChannel channel, UUID worldId) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(21);
        header.putInt(-1364128752);
        header.put((byte) 1);
        header.putLong(worldId.getMostSignificantBits());
        header.putLong(worldId.getLeastSignificantBits());
        header.flip();
        writeFully(channel, header, 0L);
    }

    /**
     * 从容器文件读取指定区块的载荷并校验 CRC。
     */
    private byte[] readPackedPayload(WorldContainer state, int cx, int cz) throws IOException {
        synchronized (state.lock) {
            this.ensureContainerLoaded(state);
            RecordMeta meta = (RecordMeta) state.records.get(coordinateKey(cx, cz));
            if (meta == null) {
                throw new IOException("Registro ya no existe");
            } else {
                FileChannel channel = FileChannel.open(state.path, StandardOpenOption.READ);

                byte[] payloadResult;
                try {
                    byte[] payload = readBytes(channel, meta.payloadOffset, meta.length);
                    if (checksum(payload) != meta.checksum) {
                        throw new IOException("Checksum inválido");
                    }

                    payloadResult = payload;
                } catch (Throwable readFailure) {
                    if (channel != null) {
                        try {
                            channel.close();
                        } catch (Throwable closeFailure) {
                            readFailure.addSuppressed(closeFailure);
                        }
                    }

                    throw readFailure;
                }

                if (channel != null) {
                    channel.close();
                }

                return payloadResult;
            }
        }
    }

    /**
     * 丢弃容器末尾不完整的写入（截断到指定位置）并告警。
     */
    private void truncateIncompleteTail(FileChannel channel, Path path, long position) throws IOException {
        channel.truncate(position);
        this.plugin.getLogger().warning("[BiomeBackup] Se descartó una escritura incompleta al final de " + path.getFileName());
    }

    /**
     * 把旧的按区块 {@code .bin} 文件迁移进打包容器，成功后删除旧文件与空目录。
     */
    private void migrateLegacyBackups() {
        synchronized (this.legacyOperationLock) {
            if (!this.restoreInProgress.get()) {
                List<Path> legacyFiles = this.findLegacyFiles();
                if (!legacyFiles.isEmpty()) {
                    int migrated = 0;
                    int alreadyPacked = 0;
                    int failed = 0;

                    for (Path file : legacyFiles) {
                        LegacyRef ref = this.parseLegacyFile(file);
                        if (ref == null) {
                            ++failed;
                        } else {
                            WorldContainer state = this.container(ref.worldId);

                            try {
                                byte[] payload = Files.readAllBytes(file);
                                this.decodeBackup(payload);
                                synchronized (state.lock) {
                                    this.ensureContainerLoaded(state);
                                    long key = coordinateKey(ref.cx, ref.cz);
                                    if (!state.records.containsKey(key)) {
                                        this.appendRecord(state, ref.cx, ref.cz, payload);
                                        ++migrated;
                                    } else {
                                        ++alreadyPacked;
                                    }

                                    Files.deleteIfExists(file);
                                    this.deleteDirectoryIfEmpty(file.getParent());
                                }
                            } catch (Throwable failure) {
                                ++failed;
                                Logger logger = this.plugin.getLogger();
                                String fileText = String.valueOf(file);
                                logger.warning("[BiomeBackup] No se pudo migrar " + fileText + ": " + failure.getMessage());
                            }
                        }
                    }

                    this.plugin.getLogger().info("[BiomeBackup] Migración a contenedores terminada. migrados=" + migrated + ", duplicados=" + alreadyPacked + ", fallidos=" + failed);
                }
            }
        }
    }

    /**
     * 启动一次“还原全部备份”的异步流程：先收集条目，再用带预算的 {@link RestoreTask} 分片应用。
     */
    public void startRestoreAll(CommandSender sender, int budgetChunksPerTick) {
        if (!this.restoreInProgress.compareAndSet(false, true)) {
            sender.sendMessage("§e[BiomeBackup] Ya hay una restauración en curso.");
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                try {
                    List<RestoreEntry> entries;
                    synchronized (this.legacyOperationLock) {
                        entries = this.collectRestoreEntries();
                    }

                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (entries.isEmpty()) {
                            this.restoreInProgress.set(false);
                            sender.sendMessage("§e[BiomeBackup] No hay backups para restaurar.");
                        } else {
                            int entryCount = entries.size();
                            sender.sendMessage("§a[BiomeBackup] Restaurando " + entryCount + " chunks... budget=" + Math.max(1, budgetChunksPerTick) + "/tick");
                            (new RestoreTask(entries.iterator(), sender, Math.max(1, budgetChunksPerTick))).runTaskTimer(this.plugin, 1L, 1L);
                        }
                    });
                } catch (Throwable failure) {
                    this.restoreInProgress.set(false);
                    this.plugin.getLogger().warning("[BiomeBackup] Error preparando restore: " + failure.getMessage());
                    Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage("§c[BiomeBackup] No se pudo preparar la restauración."));
                }

            });
        }
    }

    /**
     * 汇总所有容器与旧文件中的待还原条目，按区块去重（旧文件作为别名挂到已有条目上）。
     */
    private List<RestoreEntry> collectRestoreEntries() {
        LinkedHashMap<ChunkRef, RestoreEntry> unique = new LinkedHashMap();

        for (UUID worldId : this.findPackedWorlds()) {
            WorldContainer state = this.container(worldId);

            try {
                synchronized (state.lock) {
                    this.ensureContainerLoaded(state);
                    List<RecordMeta> records = new ArrayList(state.records.values());
                    records.sort(Comparator.comparingLong((recordMeta) -> recordMeta.recordStart));

                    for (RecordMeta meta : records) {
                        ChunkRef ref = new ChunkRef(worldId, meta.cx, meta.cz);
                        unique.putIfAbsent(ref, BiomeBackupStore.RestoreEntry.packed(ref));
                    }
                }
            } catch (Throwable failure) {
                Logger logger = this.plugin.getLogger();
                String pathText = String.valueOf(state.path);
                logger.warning("[BiomeBackup] No se pudo leer " + pathText + ": " + failure.getMessage());
            }
        }

        for (Path file : this.findLegacyFiles()) {
            LegacyRef legacy = this.parseLegacyFile(file);
            if (legacy != null) {
                ChunkRef ref = new ChunkRef(legacy.worldId, legacy.cx, legacy.cz);
                RestoreEntry existing = (RestoreEntry) unique.get(ref);
                if (existing == null) {
                    unique.put(ref, BiomeBackupStore.RestoreEntry.legacy(ref, file));
                } else {
                    existing.legacyAliases.add(file);
                }
            }
        }

        return new ArrayList(unique.values());
    }

    /**
     * 还原完成后的异步收尾：删除已还原的旧文件、压缩容器，然后回主线程报告结果。
     */
    private void finishRestoreAsync(CommandSender sender, int restored, int failed, Map<UUID, Set<Long>> restoredPacked, List<Path> restoredLegacy) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            int cleanupFailures = 0;

            for (Path file : restoredLegacy) {
                try {
                    Files.deleteIfExists(file);
                    this.deleteDirectoryIfEmpty(file.getParent());
                } catch (IOException error) {
                    ++cleanupFailures;
                    Logger logger = this.plugin.getLogger();
                    String fileText = String.valueOf(file);
                    logger.warning("[BiomeBackup] No se pudo borrar " + fileText + ": " + error.getMessage());
                }
            }

            for (Map.Entry<UUID, Set<Long>> entry : restoredPacked.entrySet()) {
                try {
                    this.compactContainer(this.container((UUID) entry.getKey()), (Set) entry.getValue());
                } catch (Throwable failure) {
                    ++cleanupFailures;
                    Logger compactLogger = this.plugin.getLogger();
                    String worldIdText = String.valueOf(entry.getKey());
                    compactLogger.warning("[BiomeBackup] No se pudo compactar " + worldIdText + ": " + failure.getMessage());
                }
            }

            final int cleanupFailuresFinal = cleanupFailures;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.restoreInProgress.set(false);
                sender.sendMessage("§a[BiomeBackup] Restore terminado. OK=" + restored + ", FAIL=" + failed + (cleanupFailuresFinal == 0 ? "" : ", CLEANUP_FAIL=" + cleanupFailuresFinal));
            });
        });
    }

    /**
     * 压缩容器：删除已还原记录，把剩余记录重写到临时文件后原子替换。
     */
    private void compactContainer(WorldContainer state, Set<Long> removed) throws IOException {
        synchronized (state.lock) {
            this.ensureContainerLoaded(state);
            List<RecordMeta> kept = new ArrayList();

            for (Map.Entry<Long, RecordMeta> entry : state.records.entrySet()) {
                if (!removed.contains(entry.getKey())) {
                    kept.add((RecordMeta) entry.getValue());
                }
            }

            kept.sort(Comparator.comparingLong((recordMeta) -> recordMeta.recordStart));
            if (kept.size() != state.records.size()) {
                if (kept.isEmpty()) {
                    Files.deleteIfExists(state.path);
                    state.records.clear();
                    state.known.removeAll(removed);
                    state.loaded = true;
                } else {
                    Path temp = state.path.resolveSibling(state.path.getFileName() + ".tmp");
                    FileChannel source = FileChannel.open(state.path, StandardOpenOption.READ);

                    try {
                        FileChannel target = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

                        try {
                            this.writeContainerHeader(target, state.worldId);

                            for (RecordMeta meta : kept) {
                                byte[] payload = readBytes(source, meta.payloadOffset, meta.length);
                                this.writeRecordAtEnd(target, meta.cx, meta.cz, payload);
                            }

                            target.force(true);
                        } catch (Throwable targetFailure) {
                            if (target != null) {
                                try {
                                    target.close();
                                } catch (Throwable targetCloseFailure) {
                                    targetFailure.addSuppressed(targetCloseFailure);
                                }
                            }

                            throw targetFailure;
                        }

                        if (target != null) {
                            target.close();
                        }
                    } catch (Throwable sourceFailure) {
                        if (source != null) {
                            try {
                                source.close();
                            } catch (Throwable sourceCloseFailure) {
                                sourceFailure.addSuppressed(sourceCloseFailure);
                            }
                        }

                        throw sourceFailure;
                    }

                    if (source != null) {
                        source.close();
                    }

                    try {
                        Files.move(temp, state.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException atomicMoveUnsupported) {
                        Files.move(temp, state.path, StandardCopyOption.REPLACE_EXISTING);
                    }

                    state.loaded = false;
                    this.ensureContainerLoaded(state);
                }
            }
        }
    }

    /**
     * 在文件末尾追加一条记录（头部 + 载荷）。
     */
    private void writeRecordAtEnd(FileChannel channel, int cx, int cz, byte[] payload) throws IOException {
        long start = channel.size();
        int crc = checksum(payload);
        ByteBuffer header = ByteBuffer.allocate(20);
        header.putInt(-1364082687).putInt(cx).putInt(cz).putInt(payload.length).putInt(crc).flip();
        writeFully(channel, header, start);
        writeFully(channel, ByteBuffer.wrap(payload), start + 20L);
    }

    /**
     * 把二进制载荷解析回 {@link BackupData}（含魔数/版本/范围/调色板/索引校验）。
     */
    private BackupData decodeBackup(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(payload)));

        BackupData backupData;
        try {
            int magic = in.readInt();
            if (magic != -1364129008) {
                throw new IOException("Bad backup magic");
            }

            byte version = in.readByte();
            if (version != 1) {
                throw new IOException("Bad backup version " + version);
            }

            int stepXZ = in.readUnsignedByte();
            int stepY = in.readUnsignedByte();
            int minY = in.readInt();
            int maxY = in.readInt();
            if (stepXZ <= 0 || stepY <= 0 || maxY <= minY) {
                throw new IOException("Rangos inválidos");
            }

            int paletteSize = in.readInt();
            if (paletteSize <= 0 || paletteSize > 65535) {
                throw new IOException("Paleta inválida");
            }

            String[] palette = new String[paletteSize];

            for (int i = 0; i < paletteSize; ++i) {
                int length = in.readUnsignedShort();
                byte[] name = new byte[length];
                in.readFully(name);
                palette[i] = new String(name, StandardCharsets.UTF_8);
            }

            int count = in.readInt();
            if (count < 0 || count > 1000000) {
                throw new IOException("Grid inválido: " + count);
            }

            boolean useByte = in.readBoolean();
            int[] indices = new int[count];
            if (useByte) {
                for (int i = 0; i < count; ++i) {
                    indices[i] = in.readUnsignedByte();
                }
            } else {
                for (int i = 0; i < count; ++i) {
                    indices[i] = in.readUnsignedShort();
                }
            }

            backupData = new BackupData(stepXZ, stepY, minY, maxY, palette, indices);
        } catch (Throwable readFailure) {
            try {
                in.close();
            } catch (Throwable closeFailure) {
                readFailure.addSuppressed(closeFailure);
            }

            throw readFailure;
        }

        in.close();
        return backupData;
    }

    /**
     * 把备份网格按步长应用到世界的实际区块，并刷新该区块。
     */
    private void applyBackup(World world, int cx, int cz, BackupData data) {
        world.getChunkAt(cx, cz);
        int baseX = cx << 4;
        int baseZ = cz << 4;
        int currentMin = world.getMinHeight();
        int currentMax = world.getMaxHeight();
        int index = 0;

        for (int x = 0; x < 16; x += data.stepXZ) {
            for (int z = 0; z < 16; z += data.stepXZ) {
                for (int y = data.minY; y < data.maxY && index < data.indices.length; y += data.stepY) {
                    int paletteIndex = data.indices[index++];
                    if (paletteIndex >= 0 && paletteIndex < data.palette.length && y >= currentMin && y < currentMax) {
                        world.setBiome(baseX + x, y, baseZ + z, this.safeBiome(data.palette[paletteIndex]));
                    }
                }
            }
        }

        world.refreshChunk(cx, cz);
    }

    /**
     * 把群系名安全转换为 {@link Biome}，无法识别时告警并回退为平原。
     */
    private Biome safeBiome(String name) {
        try {
            return Biome.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownBiome) {
            this.plugin.getLogger().warning("[BiomeBackup] Bioma desconocido '" + name + "', usando PLAINS");
            return Biome.PLAINS;
        }
    }

    /**
     * 枚举磁盘上所有 {@code .asb} 容器对应的世界 UUID（含内存中已创建的容器）。
     */
    private Set<UUID> findPackedWorlds() {
        Set<UUID> worlds = new LinkedHashSet(this.containers.keySet());
        if (!Files.isDirectory(this.root, new LinkOption[0])) {
            return worlds;
        } else {
            try {
                Stream<Path> stream = Files.list(this.root);

                try {
                    stream.filter((path) -> Files.isRegularFile(path, new LinkOption[0])).map((path) -> path.getFileName().toString()).filter((name) -> name.endsWith(".asb")).forEach((name) -> {
                        String raw = name.substring(0, name.length() - ".asb".length());

                        try {
                            worlds.add(UUID.fromString(raw));
                        } catch (IllegalArgumentException invalidUuid) {
                            this.plugin.getLogger().warning("[BiomeBackup] Contenedor con nombre inválido: " + name);
                        }

                    });
                } catch (Throwable streamFailure) {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (Throwable streamCloseFailure) {
                            streamFailure.addSuppressed(streamCloseFailure);
                        }
                    }

                    throw streamFailure;
                }

                if (stream != null) {
                    stream.close();
                }
            } catch (IOException error) {
                this.plugin.getLogger().warning("[BiomeBackup] Error buscando contenedores: " + error.getMessage());
            }

            return worlds;
        }
    }

    /**
     * 递归枚举磁盘上所有旧式 {@code .bin} 备份文件。
     */
    private List<Path> findLegacyFiles() {
        List<Path> files = new ArrayList();
        if (!Files.isDirectory(this.root, new LinkOption[0])) {
            return files;
        } else {
            try {
                Stream<Path> stream = Files.walk(this.root);

                try {
                    stream.filter((path) -> Files.isRegularFile(path, new LinkOption[0])).filter((path) -> path.getFileName().toString().endsWith(".bin")).forEach(files::add);
                } catch (Throwable streamFailure) {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (Throwable streamCloseFailure) {
                            streamFailure.addSuppressed(streamCloseFailure);
                        }
                    }

                    throw streamFailure;
                }

                if (stream != null) {
                    stream.close();
                }
            } catch (IOException error) {
                this.plugin.getLogger().warning("[BiomeBackup] Error leyendo backups legacy: " + error.getMessage());
            }

            return files;
        }
    }

    /**
     * 解析旧式备份文件路径，得到（世界 UUID, 区块 X, 区块 Z）；格式不符返回 null。
     */
    private LegacyRef parseLegacyFile(Path file) {
        try {
            Path parent = file.getParent();
            if (parent == null) {
                return null;
            } else {
                UUID worldId = UUID.fromString(parent.getFileName().toString());
                String name = file.getFileName().toString();
                int underscore = name.indexOf(95);
                int extension = name.lastIndexOf(".bin");
                if (underscore > 0 && extension > underscore) {
                    int cx = Integer.parseInt(name.substring(0, underscore));
                    int cz = Integer.parseInt(name.substring(underscore + 1, extension));
                    return new LegacyRef(worldId, cx, cz);
                } else {
                    return null;
                }
            }
        } catch (IllegalArgumentException invalidName) {
            this.plugin.getLogger().warning("[BiomeBackup] Nombre legacy inválido: " + file);
            return null;
        }
    }

    private Path legacyChunkFile(UUID worldId, int cx, int cz) {
        return this.root.resolve(worldId.toString()).resolve(cx + "_" + cz + ".bin");
    }

    /**
     * 若目录为空则删除之（根目录不删；非空则忽略）。
     */
    private void deleteDirectoryIfEmpty(Path directory) {
        if (directory != null && !directory.equals(this.root)) {
            try {
                Files.delete(directory);
            } catch (DirectoryNotEmptyException directoryNotEmpty) {
            } catch (IOException error) {
                this.plugin.getLogger().fine("[BiomeBackup] No se pudo limpiar carpeta vacía: " + error.getMessage());
            }

        }
    }

    /**
     * 把区块坐标 (cx,cz) 打包成一个 64 位键。
     */
    private static long coordinateKey(int cx, int cz) {
        return ((long) cx & 4294967295L) << 32 | (long) cz & 4294967295L;
    }

    /**
     * 计算载荷的 CRC32 校验值。
     */
    private static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }

    /**
     * 从通道指定位置读取 {@code length} 字节，读不满则抛异常。
     */
    private static byte[] readBytes(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        if (!readFully(channel, buffer, position)) {
            throw new EOFException("Payload incompleto");
        } else {
            return buffer.array();
        }
    }

    /**
     * 从通道指定位置把缓冲填满；遇到流末尾返回 false。
     */
    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;

        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                return false;
            }

            if (read != 0) {
                offset += (long) read;
            }
        }

        return true;
    }

    /**
     * 把缓冲全部写入通道；写入不足则抛异常。
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int written;
        for (long offset = position; buffer.hasRemaining(); offset += (long) written) {
            written = channel.write(buffer, offset);
            if (written <= 0) {
                throw new EOFException("No se pudo completar la escritura");
            }
        }

    }

    /**
     * 分片还原任务：每 tick 处理有限数量的条目，全部完成后转入收尾流程。
     */
    private final class RestoreTask extends BukkitRunnable {
        private final Iterator<RestoreEntry> iterator;
        private final CommandSender sender;
        private final int budget;
        private final Map<UUID, Set<Long>> restoredPacked = new HashMap();
        private final List<Path> restoredLegacy = new ArrayList();
        private int restored;
        private int failed;

        private RestoreTask(Iterator<RestoreEntry> iterator, CommandSender sender, int budget) {
            super();
            this.iterator = iterator;
            this.sender = sender;
            this.budget = budget;
        }

        @Override
        public void run() {
            for (int processed = 0; processed < this.budget && this.iterator.hasNext(); ++processed) {
                RestoreEntry entry = (RestoreEntry) this.iterator.next();
                if (this.restoreOne(entry)) {
                    ++this.restored;
                    if (entry.packed) {
                        ((Set) this.restoredPacked.computeIfAbsent(entry.ref.worldId, (ignoredKey) -> new HashSet())).add(BiomeBackupStore.coordinateKey(entry.ref.cx, entry.ref.cz));
                    } else if (entry.legacyFile != null) {
                        this.restoredLegacy.add(entry.legacyFile);
                    }

                    this.restoredLegacy.addAll(entry.legacyAliases);
                } else {
                    ++this.failed;
                }
            }

            if (!this.iterator.hasNext()) {
                this.cancel();
                this.sender.sendMessage("§e[BiomeBackup] Biomas aplicados; compactando respaldos...");
                BiomeBackupStore.this.finishRestoreAsync(this.sender, this.restored, this.failed, this.restoredPacked, this.restoredLegacy);
            }

        }

        private boolean restoreOne(RestoreEntry entry) {
            try {
                World world = Bukkit.getWorld(entry.ref.worldId);
                if (world == null) {
                    BiomeBackupStore.this.plugin.getLogger().warning("[BiomeBackup] Mundo no cargado: " + entry.ref.worldId);
                    return false;
                } else {
                    byte[] payload;
                    if (entry.packed) {
                        payload = BiomeBackupStore.this.readPackedPayload(BiomeBackupStore.this.container(entry.ref.worldId), entry.ref.cx, entry.ref.cz);
                    } else {
                        payload = Files.readAllBytes(entry.legacyFile);
                    }

                    BackupData data = BiomeBackupStore.this.decodeBackup(payload);
                    BiomeBackupStore.this.applyBackup(world, entry.ref.cx, entry.ref.cz, data);
                    return true;
                }
            } catch (Throwable failure) {
                Logger logger = BiomeBackupStore.this.plugin.getLogger();
                String worldIdText = String.valueOf(entry.ref.worldId);
                logger.warning("[BiomeBackup] Restore error " + worldIdText + " " + entry.ref.cx + "," + entry.ref.cz + ": " + failure.getMessage());
                return false;
            }
        }
    }

    /**
     * 单个世界容器在内存中的状态：文件路径、记录索引、已/待处理坐标与加载标记。
     */
    private static final class WorldContainer {
        final UUID worldId;
        final Path path;
        final Object lock = new Object();
        final Map<Long, RecordMeta> records = new HashMap();
        final Set<Long> known = ConcurrentHashMap.newKeySet();
        final Set<Long> pending = ConcurrentHashMap.newKeySet();
        boolean loaded;

        WorldContainer(UUID worldId, Path path) {
            super();
            this.worldId = worldId;
            this.path = path;
        }
    }

    /**
     * 容器内单条记录的元数据：区块坐标、记录起点、载荷偏移、长度与 CRC。
     */
    private record RecordMeta(int cx, int cz, long recordStart, long payloadOffset, int length, int checksum) {
        private RecordMeta {
        }
    }

    /**
     * 区块引用：世界 UUID 与区块坐标。
     */
    private record ChunkRef(UUID worldId, int cx, int cz) {
        private ChunkRef {
        }
    }

    /**
     * 旧式备份文件解析结果：世界 UUID 与区块坐标。
     */
    private record LegacyRef(UUID worldId, int cx, int cz) {
        private LegacyRef {
        }
    }

    /**
     * 待还原条目：区块引用、是否来自打包容器、旧文件及其别名列表。
     */
    private static final class RestoreEntry {
        final ChunkRef ref;
        final boolean packed;
        final Path legacyFile;
        final List<Path> legacyAliases = new ArrayList();

        private RestoreEntry(ChunkRef ref, boolean packed, Path legacyFile) {
            super();
            this.ref = ref;
            this.packed = packed;
            this.legacyFile = legacyFile;
        }

        static RestoreEntry packed(ChunkRef ref) {
            return new RestoreEntry(ref, true, (Path) null);
        }

        static RestoreEntry legacy(ChunkRef ref, Path file) {
            return new RestoreEntry(ref, false, file);
        }
    }

    /**
     * 解码后的备份数据：步长、纵坐标范围、调色板与索引数组。
     */
    private record BackupData(int stepXZ, int stepY, int minY, int maxY, String[] palette, int[] indices) {
        private BackupData {
        }
    }
}
