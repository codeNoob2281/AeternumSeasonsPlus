package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

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
        } catch (IOException e) {
            plugin.getLogger().warning("[BiomeBackup] No se pudo crear la carpeta: " + e.getMessage());
        }

        if (this.migrateLegacyFiles) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, this::migrateLegacyBackups), 1L);
        }

    }

    public void saveFirstTouch(Chunk chunk, Biome[] originalGrid, int stepXZ, int stepY) {
        if (!this.restoreInProgress.get()) {
            World world = chunk.getWorld();
            UUID worldId = world.getUID();
            int cx = chunk.getX();
            int cz = chunk.getZ();
            long coordinate = coordinateKey(cx, cz);
            WorldContainer state = this.container(worldId);
            if (!state.known.contains(coordinate) && state.pending.add(coordinate)) {
                Biome[] copy = (Biome[])Arrays.copyOf(originalGrid, originalGrid.length);
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();
                Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                    try {
                        if (this.packedStorage) {
                            this.savePacked(state, cx, cz, copy, stepXZ, stepY, minY, maxY);
                        } else {
                            this.saveLegacy(state, cx, cz, copy, stepXZ, stepY, minY, maxY);
                        }
                    } catch (Throwable t) {
                        Logger var10000 = this.plugin.getLogger();
                        String var10001 = String.valueOf(worldId);
                        var10000.warning("[BiomeBackup] Error guardando " + var10001 + " " + cx + "," + cz + ": " + t.getMessage());
                    } finally {
                        state.pending.remove(coordinate);
                    }

                });
            }
        }
    }

    public void loadOriginalGridAsync(World world, int cx, int cz, int expectedStepXZ, int expectedStepY, Consumer<Biome[]> callback) {
        UUID worldId = world.getUID();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Biome[] result = null;

            try {
                result = this.loadOriginalGrid(worldId, cx, cz, expectedStepXZ, expectedStepY, minY, maxY);
            } catch (Throwable t) {
                Logger var10000 = this.plugin.getLogger();
                String var10001 = String.valueOf(worldId);
                var10000.warning("[BiomeBackup] Could not read " + var10001 + " " + cx + "," + cz + ": " + t.getMessage());
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
        synchronized(state.lock) {
            this.ensureContainerLoaded(state);
            if (state.records.containsKey(coordinateKey(cx, cz))) {
                payload = this.readPackedPayload(state, cx, cz);
            }
        }

        if (payload == null) {
            synchronized(this.legacyOperationLock) {
                synchronized(state.lock) {
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

                for(int i = 0; i < data.indices.length; ++i) {
                    int paletteIndex = data.indices[i];
                    if (paletteIndex < 0 || paletteIndex >= data.palette.length) {
                        throw new IOException("Invalid palette index in biome backup");
                    }

                    grid[i] = this.safeBiome(data.palette[paletteIndex]);
                }

                return grid;
            } else {
                this.plugin.getLogger().warning("[BiomeBackup] Incompatible backup for " + String.valueOf(worldId) + " " + cx + "," + cz + "; ignoring it to avoid applying a bad grid.");
                return null;
            }
        }
    }

    private void savePacked(WorldContainer state, int cx, int cz, Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        long coordinate = coordinateKey(cx, cz);
        synchronized(state.lock) {
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
        synchronized(state.lock) {
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

    private byte[] encodeBackup(Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        Map<String, Integer> paletteMap = new LinkedHashMap();
        List<String> palette = new ArrayList();
        int[] indices = new int[grid.length];

        for(int i = 0; i < grid.length; ++i) {
            String name = grid[i].name();
            Integer index = (Integer)paletteMap.get(name);
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

            for(String biome : palette) {
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
                for(int value : indices) {
                    out.writeByte(value);
                }
            } else {
                for(int value : indices) {
                    out.writeShort(value);
                }
            }
        } catch (Throwable var17) {
            try {
                out.close();
            } catch (Throwable var16) {
                var17.addSuppressed(var16);
            }

            throw var17;
        }

        out.close();
        return bytes.toByteArray();
    }

    private WorldContainer container(UUID worldId) {
        return (WorldContainer)this.containers.computeIfAbsent(worldId, (id) -> new WorldContainer(id, this.root.resolve(String.valueOf(id) + ".asb")));
    }

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
                        throw new IOException("Contenedor incompleto: " + String.valueOf(state.path));
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
                    for(long position = 21L; position < size; position = next) {
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
                        next = payloadOffset + (long)length;
                        if (next > size) {
                            this.truncateIncompleteTail(channel, state.path, position);
                            break;
                        }

                        byte[] payload = readBytes(channel, payloadOffset, length);
                        if (checksum(payload) != checksum) {
                            Logger var10000 = this.plugin.getLogger();
                            String var10001 = String.valueOf(state.path.getFileName());
                            var10000.warning("[BiomeBackup] Registro corrupto ignorado en " + var10001 + " offset=" + position);
                        } else {
                            long key = coordinateKey(cx, cz);
                            state.records.putIfAbsent(key, new RecordMeta(cx, cz, position, payloadOffset, length, checksum));
                            state.known.add(key);
                        }
                    }
                } catch (Throwable var27) {
                    if (channel != null) {
                        try {
                            channel.close();
                        } catch (Throwable var26) {
                            var27.addSuppressed(var26);
                        }
                    }

                    throw var27;
                }

                if (channel != null) {
                    channel.close();
                }

                state.loaded = true;
            }
        }
    }

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
                    } catch (Throwable var17) {
                        if (channel != null) {
                            try {
                                channel.close();
                            } catch (Throwable var16) {
                                var17.addSuppressed(var16);
                            }
                        }

                        throw var17;
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

    private void writeContainerHeader(FileChannel channel, UUID worldId) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(21);
        header.putInt(-1364128752);
        header.put((byte)1);
        header.putLong(worldId.getMostSignificantBits());
        header.putLong(worldId.getLeastSignificantBits());
        header.flip();
        writeFully(channel, header, 0L);
    }

    private byte[] readPackedPayload(WorldContainer state, int cx, int cz) throws IOException {
        synchronized(state.lock) {
            this.ensureContainerLoaded(state);
            RecordMeta meta = (RecordMeta)state.records.get(coordinateKey(cx, cz));
            if (meta == null) {
                throw new IOException("Registro ya no existe");
            } else {
                FileChannel channel = FileChannel.open(state.path, StandardOpenOption.READ);

                byte[] var8;
                try {
                    byte[] payload = readBytes(channel, meta.payloadOffset, meta.length);
                    if (checksum(payload) != meta.checksum) {
                        throw new IOException("Checksum inválido");
                    }

                    var8 = payload;
                } catch (Throwable var11) {
                    if (channel != null) {
                        try {
                            channel.close();
                        } catch (Throwable var10) {
                            var11.addSuppressed(var10);
                        }
                    }

                    throw var11;
                }

                if (channel != null) {
                    channel.close();
                }

                return var8;
            }
        }
    }

    private void truncateIncompleteTail(FileChannel channel, Path path, long position) throws IOException {
        channel.truncate(position);
        this.plugin.getLogger().warning("[BiomeBackup] Se descartó una escritura incompleta al final de " + String.valueOf(path.getFileName()));
    }

    private void migrateLegacyBackups() {
        synchronized(this.legacyOperationLock) {
            if (!this.restoreInProgress.get()) {
                List<Path> legacyFiles = this.findLegacyFiles();
                if (!legacyFiles.isEmpty()) {
                    int migrated = 0;
                    int alreadyPacked = 0;
                    int failed = 0;

                    for(Path file : legacyFiles) {
                        LegacyRef ref = this.parseLegacyFile(file);
                        if (ref == null) {
                            ++failed;
                        } else {
                            WorldContainer state = this.container(ref.worldId);

                            try {
                                byte[] payload = Files.readAllBytes(file);
                                this.decodeBackup(payload);
                                synchronized(state.lock) {
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
                            } catch (Throwable t) {
                                ++failed;
                                Logger var10000 = this.plugin.getLogger();
                                String var10001 = String.valueOf(file);
                                var10000.warning("[BiomeBackup] No se pudo migrar " + var10001 + ": " + t.getMessage());
                            }
                        }
                    }

                    this.plugin.getLogger().info("[BiomeBackup] Migración a contenedores terminada. migrados=" + migrated + ", duplicados=" + alreadyPacked + ", fallidos=" + failed);
                }
            }
        }
    }

    public void startRestoreAll(CommandSender sender, int budgetChunksPerTick) {
        if (!this.restoreInProgress.compareAndSet(false, true)) {
            sender.sendMessage("§e[BiomeBackup] Ya hay una restauración en curso.");
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                try {
                    List<RestoreEntry> entries;
                    synchronized(this.legacyOperationLock) {
                        entries = this.collectRestoreEntries();
                    }

                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (entries.isEmpty()) {
                            this.restoreInProgress.set(false);
                            sender.sendMessage("§e[BiomeBackup] No hay backups para restaurar.");
                        } else {
                            int var10001 = entries.size();
                            sender.sendMessage("§a[BiomeBackup] Restaurando " + var10001 + " chunks... budget=" + Math.max(1, budgetChunksPerTick) + "/tick");
                            (new RestoreTask(entries.iterator(), sender, Math.max(1, budgetChunksPerTick))).runTaskTimer(this.plugin, 1L, 1L);
                        }
                    });
                } catch (Throwable t) {
                    this.restoreInProgress.set(false);
                    this.plugin.getLogger().warning("[BiomeBackup] Error preparando restore: " + t.getMessage());
                    Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage("§c[BiomeBackup] No se pudo preparar la restauración."));
                }

            });
        }
    }

    private List<RestoreEntry> collectRestoreEntries() {
        LinkedHashMap<ChunkRef, RestoreEntry> unique = new LinkedHashMap();

        for(UUID worldId : this.findPackedWorlds()) {
            WorldContainer state = this.container(worldId);

            try {
                synchronized(state.lock) {
                    this.ensureContainerLoaded(state);
                    List<RecordMeta> records = new ArrayList(state.records.values());
                    records.sort(Comparator.comparingLong((metax) -> metax.recordStart));

                    for(RecordMeta meta : records) {
                        ChunkRef ref = new ChunkRef(worldId, meta.cx, meta.cz);
                        unique.putIfAbsent(ref, BiomeBackupStore.RestoreEntry.packed(ref));
                    }
                }
            } catch (Throwable t) {
                Logger var10000 = this.plugin.getLogger();
                String var10001 = String.valueOf(state.path);
                var10000.warning("[BiomeBackup] No se pudo leer " + var10001 + ": " + t.getMessage());
            }
        }

        for(Path file : this.findLegacyFiles()) {
            LegacyRef legacy = this.parseLegacyFile(file);
            if (legacy != null) {
                ChunkRef ref = new ChunkRef(legacy.worldId, legacy.cx, legacy.cz);
                RestoreEntry existing = (RestoreEntry)unique.get(ref);
                if (existing == null) {
                    unique.put(ref, BiomeBackupStore.RestoreEntry.legacy(ref, file));
                } else {
                    existing.legacyAliases.add(file);
                }
            }
        }

        return new ArrayList(unique.values());
    }

    private void finishRestoreAsync(CommandSender sender, int restored, int failed, Map<UUID, Set<Long>> restoredPacked, List<Path> restoredLegacy) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            int cleanupFailures = 0;

            for(Path file : restoredLegacy) {
                try {
                    Files.deleteIfExists(file);
                    this.deleteDirectoryIfEmpty(file.getParent());
                } catch (IOException e) {
                    ++cleanupFailures;
                    Logger var10000 = this.plugin.getLogger();
                    String var10001 = String.valueOf(file);
                    var10000.warning("[BiomeBackup] No se pudo borrar " + var10001 + ": " + e.getMessage());
                }
            }

            for(Map.Entry<UUID, Set<Long>> entry : restoredPacked.entrySet()) {
                try {
                    this.compactContainer(this.container((UUID)entry.getKey()), (Set)entry.getValue());
                } catch (Throwable t) {
                    ++cleanupFailures;
                    Logger var14 = this.plugin.getLogger();
                    String var15 = String.valueOf(entry.getKey());
                    var14.warning("[BiomeBackup] No se pudo compactar " + var15 + ": " + t.getMessage());
                }
            }

            final int cleanupFailuresFinal = cleanupFailures;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.restoreInProgress.set(false);
                sender.sendMessage("§a[BiomeBackup] Restore terminado. OK=" + restored + ", FAIL=" + failed + (cleanupFailuresFinal == 0 ? "" : ", CLEANUP_FAIL=" + cleanupFailuresFinal));
            });
        });
    }

    private void compactContainer(WorldContainer state, Set<Long> removed) throws IOException {
        synchronized(state.lock) {
            this.ensureContainerLoaded(state);
            List<RecordMeta> kept = new ArrayList();

            for(Map.Entry<Long, RecordMeta> entry : state.records.entrySet()) {
                if (!removed.contains(entry.getKey())) {
                    kept.add((RecordMeta)entry.getValue());
                }
            }

            kept.sort(Comparator.comparingLong((metax) -> metax.recordStart));
            if (kept.size() != state.records.size()) {
                if (kept.isEmpty()) {
                    Files.deleteIfExists(state.path);
                    state.records.clear();
                    state.known.removeAll(removed);
                    state.loaded = true;
                } else {
                    Path temp = state.path.resolveSibling(String.valueOf(state.path.getFileName()) + ".tmp");
                    FileChannel source = FileChannel.open(state.path, StandardOpenOption.READ);

                    try {
                        FileChannel target = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

                        try {
                            this.writeContainerHeader(target, state.worldId);

                            for(RecordMeta meta : kept) {
                                byte[] payload = readBytes(source, meta.payloadOffset, meta.length);
                                this.writeRecordAtEnd(target, meta.cx, meta.cz, payload);
                            }

                            target.force(true);
                        } catch (Throwable var15) {
                            if (target != null) {
                                try {
                                    target.close();
                                } catch (Throwable var13) {
                                    var15.addSuppressed(var13);
                                }
                            }

                            throw var15;
                        }

                        if (target != null) {
                            target.close();
                        }
                    } catch (Throwable var16) {
                        if (source != null) {
                            try {
                                source.close();
                            } catch (Throwable var12) {
                                var16.addSuppressed(var12);
                            }
                        }

                        throw var16;
                    }

                    if (source != null) {
                        source.close();
                    }

                    try {
                        Files.move(temp, state.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException var14) {
                        Files.move(temp, state.path, StandardCopyOption.REPLACE_EXISTING);
                    }

                    state.loaded = false;
                    this.ensureContainerLoaded(state);
                }
            }
        }
    }

    private void writeRecordAtEnd(FileChannel channel, int cx, int cz, byte[] payload) throws IOException {
        long start = channel.size();
        int crc = checksum(payload);
        ByteBuffer header = ByteBuffer.allocate(20);
        header.putInt(-1364082687).putInt(cx).putInt(cz).putInt(payload.length).putInt(crc).flip();
        writeFully(channel, header, start);
        writeFully(channel, ByteBuffer.wrap(payload), start + 20L);
    }

    private BackupData decodeBackup(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(payload)));

        BackupData var21;
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

            for(int i = 0; i < paletteSize; ++i) {
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
                for(int i = 0; i < count; ++i) {
                    indices[i] = in.readUnsignedByte();
                }
            } else {
                for(int i = 0; i < count; ++i) {
                    indices[i] = in.readUnsignedShort();
                }
            }

            var21 = new BackupData(stepXZ, stepY, minY, maxY, palette, indices);
        } catch (Throwable var16) {
            try {
                in.close();
            } catch (Throwable var15) {
                var16.addSuppressed(var15);
            }

            throw var16;
        }

        in.close();
        return var21;
    }

    private void applyBackup(World world, int cx, int cz, BackupData data) {
        world.getChunkAt(cx, cz);
        int baseX = cx << 4;
        int baseZ = cz << 4;
        int currentMin = world.getMinHeight();
        int currentMax = world.getMaxHeight();
        int index = 0;

        for(int x = 0; x < 16; x += data.stepXZ) {
            for(int z = 0; z < 16; z += data.stepXZ) {
                for(int y = data.minY; y < data.maxY && index < data.indices.length; y += data.stepY) {
                    int paletteIndex = data.indices[index++];
                    if (paletteIndex >= 0 && paletteIndex < data.palette.length && y >= currentMin && y < currentMax) {
                        world.setBiome(baseX + x, y, baseZ + z, this.safeBiome(data.palette[paletteIndex]));
                    }
                }
            }
        }

        world.refreshChunk(cx, cz);
    }

    private Biome safeBiome(String name) {
        try {
            return Biome.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException var3) {
            this.plugin.getLogger().warning("[BiomeBackup] Bioma desconocido '" + name + "', usando PLAINS");
            return Biome.PLAINS;
        }
    }

    private Set<UUID> findPackedWorlds() {
        Set<UUID> worlds = new LinkedHashSet(this.containers.keySet());
        if (!Files.isDirectory(this.root, new LinkOption[0])) {
            return worlds;
        } else {
            try {
                Stream<Path> stream = Files.list(this.root);

                try {
                    stream.filter((x$0) -> Files.isRegularFile(x$0, new LinkOption[0])).map((path) -> path.getFileName().toString()).filter((name) -> name.endsWith(".asb")).forEach((name) -> {
                        String raw = name.substring(0, name.length() - ".asb".length());

                        try {
                            worlds.add(UUID.fromString(raw));
                        } catch (IllegalArgumentException var5) {
                            this.plugin.getLogger().warning("[BiomeBackup] Contenedor con nombre inválido: " + name);
                        }

                    });
                } catch (Throwable var6) {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (Throwable var5) {
                            var6.addSuppressed(var5);
                        }
                    }

                    throw var6;
                }

                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
                this.plugin.getLogger().warning("[BiomeBackup] Error buscando contenedores: " + e.getMessage());
            }

            return worlds;
        }
    }

    private List<Path> findLegacyFiles() {
        List<Path> files = new ArrayList();
        if (!Files.isDirectory(this.root, new LinkOption[0])) {
            return files;
        } else {
            try {
                Stream<Path> stream = Files.walk(this.root);

                try {
                    stream.filter((x) -> Files.isRegularFile(x, new LinkOption[0])).filter((path) -> path.getFileName().toString().endsWith(".bin")).forEach(files::add);
                } catch (Throwable var6) {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (Throwable var5) {
                            var6.addSuppressed(var5);
                        }
                    }

                    throw var6;
                }

                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
                this.plugin.getLogger().warning("[BiomeBackup] Error leyendo backups legacy: " + e.getMessage());
            }

            return files;
        }
    }

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
        } catch (IllegalArgumentException var9) {
            this.plugin.getLogger().warning("[BiomeBackup] Nombre legacy inválido: " + String.valueOf(file));
            return null;
        }
    }

    private Path legacyChunkFile(UUID worldId, int cx, int cz) {
        return this.root.resolve(worldId.toString()).resolve(cx + "_" + cz + ".bin");
    }

    private void deleteDirectoryIfEmpty(Path directory) {
        if (directory != null && !directory.equals(this.root)) {
            try {
                Files.delete(directory);
            } catch (DirectoryNotEmptyException var3) {
            } catch (IOException e) {
                this.plugin.getLogger().fine("[BiomeBackup] No se pudo limpiar carpeta vacía: " + e.getMessage());
            }

        }
    }

    private static long coordinateKey(int cx, int cz) {
        return ((long)cx & 4294967295L) << 32 | (long)cz & 4294967295L;
    }

    private static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int)crc.getValue();
    }

    private static byte[] readBytes(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        if (!readFully(channel, buffer, position)) {
            throw new EOFException("Payload incompleto");
        } else {
            return buffer.array();
        }
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;

        while(buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                return false;
            }

            if (read != 0) {
                offset += (long)read;
            }
        }

        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int written;
        for(long offset = position; buffer.hasRemaining(); offset += (long)written) {
            written = channel.write(buffer, offset);
            if (written <= 0) {
                throw new EOFException("No se pudo completar la escritura");
            }
        }

    }

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

        public void run() {
            for(int processed = 0; processed < this.budget && this.iterator.hasNext(); ++processed) {
                RestoreEntry entry = (RestoreEntry)this.iterator.next();
                if (this.restoreOne(entry)) {
                    ++this.restored;
                    if (entry.packed) {
                        ((Set)this.restoredPacked.computeIfAbsent(entry.ref.worldId, (ignored) -> new HashSet())).add(BiomeBackupStore.coordinateKey(entry.ref.cx, entry.ref.cz));
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
                    BiomeBackupStore.this.plugin.getLogger().warning("[BiomeBackup] Mundo no cargado: " + String.valueOf(entry.ref.worldId));
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
            } catch (Throwable t) {
                Logger var10000 = BiomeBackupStore.this.plugin.getLogger();
                String var10001 = String.valueOf(entry.ref.worldId);
                var10000.warning("[BiomeBackup] Restore error " + var10001 + " " + entry.ref.cx + "," + entry.ref.cz + ": " + t.getMessage());
                return false;
            }
        }
    }

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

    private static record RecordMeta(int cx, int cz, long recordStart, long payloadOffset, int length, int checksum) {
        private RecordMeta {
        }
    }

    private static record ChunkRef(UUID worldId, int cx, int cz) {
        private ChunkRef {
        }
    }

    private static record LegacyRef(UUID worldId, int cx, int cz) {
        private LegacyRef {
        }
    }

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
            return new RestoreEntry(ref, true, (Path)null);
        }

        static RestoreEntry legacy(ChunkRef ref, Path file) {
            return new RestoreEntry(ref, false, file);
        }
    }

    private static record BackupData(int stepXZ, int stepY, int minY, int maxY, String[] palette, int[] indices) {
        private BackupData {
        }
    }
}
