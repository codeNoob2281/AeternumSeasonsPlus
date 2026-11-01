package Kinkin.aeternum.frost;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class FrostEnvironmentListener implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;
    private BukkitTask task;
    private static final String FROST_WORLD_NAME = "aeternum_frost";

    public FrostEnvironmentListener(AeternumSeasonsPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.task = (new BukkitRunnable() {
            public void run() {
                FrostEnvironmentListener.this.run();
            }
        }).runTaskTimer(this.plugin, 40L, 40L);
    }

    public void unregister() {
        if (this.task != null) {
            this.task.cancel();
        }

        HandlerList.unregisterAll(this);
    }

    public void run() {
        World w = Bukkit.getWorld("aeternum_frost");
        if (w != null) {
            boolean storm = w.hasStorm();

            for(Player p : w.getPlayers()) {
                this.spawnColdBreath(p);
                this.applyCold(p, storm);
            }

        }
    }

    private void applyCold(Player p, boolean storm) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w != null) {
            boolean outside = this.isOutside(loc);
            if (!outside) {
                if (p.getFreezeTicks() > 0) {
                    p.setFreezeTicks(Math.max(0, p.getFreezeTicks() - 20));
                }

            } else {
                int armorPieces = this.countArmorPieces(p);
                boolean hasHotItem = this.isHotItem(p.getInventory().getItemInMainHand().getType()) || this.isHotItem(p.getInventory().getItemInOffHand().getType());
                boolean nearHeat = this.isNearHeatSource(loc, 3);
                long time = w.getTime();
                boolean isNight = time >= 13000L && time < 23000L;
                int requiredArmorPieces = isNight ? 4 : 2;
                boolean coldProtected = armorPieces >= requiredArmorPieces || hasHotItem || nearHeat;
                if (!coldProtected) {
                    int freezeStep = storm ? 80 : 40;
                    p.setFreezeTicks(Math.min(p.getFreezeTicks() + freezeStep, 200));
                    int slowAmp = storm ? 1 : 0;
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, slowAmp, true, true, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, slowAmp, true, true, true));
                    if (storm && !hasHotItem) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, true, true));
                    }

                    if (storm && p.getHealth() > (double)1.0F) {
                        p.damage((double)1.0F);
                    }
                } else {
                    if (p.getFreezeTicks() > 0) {
                        p.setFreezeTicks(Math.max(0, p.getFreezeTicks() - 20));
                    }

                    if (storm && !hasHotItem) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, true, true));
                    }
                }

            }
        }
    }

    private boolean isOutside(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            int surfaceY = w.getHighestBlockYAt(x, z);
            return loc.getBlockY() >= surfaceY - 1;
        }
    }

    private int countArmorPieces(Player p) {
        int count = 0;
        if (p.getInventory().getHelmet() != null) {
            ++count;
        }

        if (p.getInventory().getChestplate() != null) {
            ++count;
        }

        if (p.getInventory().getLeggings() != null) {
            ++count;
        }

        if (p.getInventory().getBoots() != null) {
            ++count;
        }

        return count;
    }

    private boolean isHotItem(Material m) {
        boolean var10000;
        switch (m) {
            case TORCH:
            case SOUL_TORCH:
            case REDSTONE_TORCH:
            case LANTERN:
            case SOUL_LANTERN:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case MAGMA_BLOCK:
            case LAVA_BUCKET:
            case FIRE_CHARGE:
            case BLAZE_ROD:
                var10000 = true;
                break;
            default:
                var10000 = false;
        }

        return var10000;
    }

    private boolean isNearHeatSource(Location loc, int radius) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        } else {
            int baseX = loc.getBlockX();
            int baseY = loc.getBlockY();
            int baseZ = loc.getBlockZ();

            for(int x = baseX - radius; x <= baseX + radius; ++x) {
                for(int y = baseY - radius; y <= baseY + radius; ++y) {
                    for(int z = baseZ - radius; z <= baseZ + radius; ++z) {
                        Block b = w.getBlockAt(x, y, z);
                        if (this.isHotBlock(b.getType())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    private boolean isHotBlock(Material m) {
        boolean var10000;
        switch (m) {
            case TORCH:
            case SOUL_TORCH:
            case REDSTONE_TORCH:
            case LANTERN:
            case SOUL_LANTERN:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case MAGMA_BLOCK:
            case FIRE:
            case SOUL_FIRE:
            case LAVA:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
                var10000 = true;
                break;
            case LAVA_BUCKET:
            case FIRE_CHARGE:
            case BLAZE_ROD:
            default:
                var10000 = false;
        }

        return var10000;
    }

    private void spawnColdBreath(Player p) {
        Location eye = p.getEyeLocation().clone();
        World w = eye.getWorld();
        if (w != null) {
            Vector dir = eye.getDirection().normalize().multiply((double)0.25F);
            Location pos = eye.clone();

            for(int i = 0; i < 6; ++i) {
                pos.add(dir);
                w.spawnParticle(Particle.SNOWFLAKE, pos.getX(), pos.getY(), pos.getZ(), 1, 0.03, 0.03, 0.03, (double)0.0F);
            }

        }
    }
}
