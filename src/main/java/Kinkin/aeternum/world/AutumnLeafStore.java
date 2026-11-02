package Kinkin.aeternum.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 秋季树叶存储（AutumnLeafStore）。
 *
 * <p>负责把“方块位置的原始树叶材质”记录持久化到插件数据目录下的
 * {@code autumn-leaves.dat} 二进制文件，供秋季染色后还原原始树叶类型使用。
 * 文件以魔数 {@link #MAGIC} 开头，随后是条目数，再逐条写入“位置字符串 + 材质名”。
 * 读写均做了容错：读取失败仅记录警告并返回已读到的部分，保存失败记录严重日志。</p>
 *
 * <p>依赖 Bukkit 的 {@link JavaPlugin} 以定位数据目录与日志器。</p>
 */
final class AutumnLeafStore {
    private static final int MAGIC = 1095517745;

    AutumnLeafStore() {
        super();
    }

    /**
     * 从数据目录读取秋季树叶映射。
     *
     * <p>文件不存在时返回空映射；解析过程中出现异常只会记录警告，并返回已成功读取的条目。</p>
     *
     * @param plugin 用于定位数据目录与日志的插件实例
     * @return 位置字符串到原始树叶材质的映射
     */
    static Map<String, Material> load(JavaPlugin plugin) {
        Map<String, Material> leaves = new HashMap();
        Path file = plugin.getDataFolder().toPath().resolve("autumn-leaves.dat");
        if (!Files.exists(file, new LinkOption[0])) {
            return leaves;
        } else {
            try {
                DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));

                try {
                    readEntriesBlock: {
                        if (input.readInt() != 1095517745) {
                            throw new IOException("Invalid leaf store header");
                        }

                        int count = input.readInt();
                        if (count >= 0 && count <= 10000000) {
                            int index = 0;

                            while(true) {
                                if (index >= count) {
                                    break readEntriesBlock;
                                }

                                String location = input.readUTF();
                                Material original = Material.matchMaterial(input.readUTF());
                                if (original != null) {
                                    leaves.put(location, original);
                                }

                                ++index;
                            }
                        }

                        throw new IOException("Invalid leaf count");
                    }
                } catch (Throwable readFailure) {
                    try {
                        input.close();
                    } catch (Throwable closeFailure) {
                        readFailure.addSuppressed(closeFailure);
                    }

                    throw readFailure;
                }

                input.close();
            } catch (IOException error) {
                plugin.getLogger().warning("[AutumnSoil] Could not fully read original leaves: " + error.getMessage());
            }

            return leaves;
        }
    }

    /**
     * 把秋季树叶映射写入数据目录。
     *
     * <p>先写入同目录的临时文件，再原子移动到目标文件（不支持原子移动时回退为普通替换），
     * 以避免写入中断破坏已有文件。写入失败记录严重日志。</p>
     *
     * @param plugin 用于定位数据目录与日志的插件实例
     * @param leaves 待保存的位置字符串到原始树叶材质的映射
     */
    static void save(JavaPlugin plugin, Map<String, Material> leaves) {
        Path file = plugin.getDataFolder().toPath().resolve("autumn-leaves.dat");
        Path temp = file.resolveSibling("autumn-leaves.dat.tmp");

        try {
            Files.createDirectories(file.getParent());
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)));

            try {
                output.writeInt(1095517745);
                output.writeInt(leaves.size());

                for(Map.Entry<String, Material> entry : leaves.entrySet()) {
                    output.writeUTF((String)entry.getKey());
                    output.writeUTF(((Material)entry.getValue()).name());
                }
            } catch (Throwable writeFailure) {
                try {
                    output.close();
                } catch (Throwable closeFailure) {
                    writeFailure.addSuppressed(closeFailure);
                }

                throw writeFailure;
            }

            output.close();

            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicMoveUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            plugin.getLogger().severe("[AutumnSoil] Could not save original leaves: " + error.getMessage());
        }

    }
}
