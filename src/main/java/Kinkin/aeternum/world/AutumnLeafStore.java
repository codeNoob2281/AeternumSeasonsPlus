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

final class AutumnLeafStore {
    private static final int MAGIC = 1095517745;

    AutumnLeafStore() {
        super();
    }

    static Map<String, Material> load(JavaPlugin plugin) {
        Map<String, Material> leaves = new HashMap();
        Path file = plugin.getDataFolder().toPath().resolve("autumn-leaves.dat");
        if (!Files.exists(file, new LinkOption[0])) {
            return leaves;
        } else {
            try {
                DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));

                try {
                    label55: {
                        if (input.readInt() != 1095517745) {
                            throw new IOException("Invalid leaf store header");
                        }

                        int count = input.readInt();
                        if (count >= 0 && count <= 10000000) {
                            int i = 0;

                            while(true) {
                                if (i >= count) {
                                    break label55;
                                }

                                String location = input.readUTF();
                                Material original = Material.matchMaterial(input.readUTF());
                                if (original != null) {
                                    leaves.put(location, original);
                                }

                                ++i;
                            }
                        }

                        throw new IOException("Invalid leaf count");
                    }
                } catch (Throwable var9) {
                    try {
                        input.close();
                    } catch (Throwable var8) {
                        var9.addSuppressed(var8);
                    }

                    throw var9;
                }

                input.close();
            } catch (IOException error) {
                plugin.getLogger().warning("[AutumnSoil] Could not fully read original leaves: " + error.getMessage());
            }

            return leaves;
        }
    }

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
            } catch (Throwable var9) {
                try {
                    output.close();
                } catch (Throwable var7) {
                    var9.addSuppressed(var7);
                }

                throw var9;
            }

            output.close();

            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var8) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            plugin.getLogger().severe("[AutumnSoil] Could not save original leaves: " + error.getMessage());
        }

    }
}
