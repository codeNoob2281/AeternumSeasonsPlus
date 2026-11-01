package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import Kinkin.aeternum.lang.LanguageManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.entity.Villager.Type;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public final class VillagerTypeOverrides implements Listener {
    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager langManager;
    private boolean enabled;
    private boolean appearanceEnabled;
    private boolean professionRotationEnabled;
    private double overrideChance;
    private boolean showLazyTag = true;
    private final Set<String> worlds = new HashSet();
    private final List<Villager.Type> allowedTypes = new ArrayList();
    private Set<String> allLazyTags = Collections.emptySet();
    private int lastUpdateDay = -1;
    private static final int VILLAGE_RANGE_CHUNKS = 10;
    private int updatePeriodDays = 5;
    private int maxLazyPerArea = 2;
    private double lazyRatio = (double)0.25F;
    private int currentCalendarDay = -1;

    public VillagerTypeOverrides(AeternumSeasonsPlugin plugin, LanguageManager langManager) {
        super();
        this.plugin = plugin;
        this.langManager = langManager;
        this.loadState();
        this.reloadFromConfig();
    }

    private void loadState() {
        File f = new File(this.plugin.getDataFolder(), "data/villager_override_state.yml");
        if (!f.exists()) {
            this.lastUpdateDay = -1;
        } else {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            this.lastUpdateDay = y.getInt("last_update_day", -1);
        }
    }

    private void saveState() {
        try {
            File f = new File(this.plugin.getDataFolder(), "data/villager_override_state.yml");
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }

            YamlConfiguration y = new YamlConfiguration();
            y.set("last_update_day", this.lastUpdateDay);
            y.save(f);
        } catch (IOException e) {
            this.plugin.getLogger().warning("No se pudo guardar villager_override_state.yml: " + e.getMessage());
        }

    }

    public void reloadFromConfig() {
        ConfigurationSection sec = this.plugin.cfg.climate.getConfigurationSection("villager_type_overrides");
        if (sec == null) {
            this.enabled = false;
            this.appearanceEnabled = false;
            this.professionRotationEnabled = false;
            this.worlds.clear();
            this.allowedTypes.clear();
        } else {
            this.enabled = sec.getBoolean("enabled", false);
            this.appearanceEnabled = this.enabled && sec.getBoolean("appearance.enabled", true);
            this.professionRotationEnabled = this.enabled && sec.getBoolean("profession_rotation.enabled", false);
            this.overrideChance = Math.max((double)0.0F, Math.min((double)1.0F, sec.getDouble("appearance.override_chance", sec.getDouble("override_chance", (double)1.0F))));
            this.showLazyTag = sec.getBoolean("profession_rotation.show_lazy_tag", sec.getBoolean("show_lazy_tag", true));
            this.updatePeriodDays = Math.max(1, sec.getInt("profession_rotation.update_period_days", 5));
            this.maxLazyPerArea = Math.max(0, sec.getInt("profession_rotation.max_lazy_per_area", 2));
            this.lazyRatio = Math.max((double)0.0F, Math.min(0.9, sec.getDouble("profession_rotation.lazy_ratio", (double)0.25F)));
            this.worlds.clear();

            for(String w : sec.getStringList("worlds")) {
                if (w != null && !w.isEmpty()) {
                    this.worlds.add(w);
                }
            }

            this.allowedTypes.clear();

            for(String s : sec.getStringList("allowed_types")) {
                try {
                    Villager.Type t = Type.valueOf(s.toUpperCase(Locale.ROOT));
                    this.allowedTypes.add(t);
                } catch (IllegalArgumentException var5) {
                    this.plugin.getLogger().warning("[VillagerTypes] Invalid villager type '" + s + "' in allowed_types");
                }
            }

            if (this.allowedTypes.isEmpty()) {
                this.allowedTypes.addAll(Arrays.asList(Type.PLAINS, Type.DESERT, Type.SAVANNA, Type.TAIGA, Type.SNOW, Type.SWAMP, Type.JUNGLE));
            }

            this.allLazyTags = this.langManager.getAllTranslations("villager.lazy_tag");
            Logger var10000 = this.plugin.getLogger();
            boolean var10001 = this.enabled;
            var10000.info("[VillagerTypes] Enabled=" + var10001 + ", appearanceEnabled=" + this.appearanceEnabled + ", professionRotationEnabled=" + this.professionRotationEnabled + ", showLazyTag=" + this.showLazyTag + ", worlds=" + String.valueOf(this.worlds) + ", allowedTypes=" + String.valueOf(this.allowedTypes));
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (this.enabled) {
            if (e.getEntityType() == EntityType.VILLAGER) {
                CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
                switch (reason) {
                    case BREEDING:
                    case CURED:
                    case NATURAL:
                    case JOCKEY:
                    case COMMAND:
                    case SPAWNER_EGG:
                        World w = e.getLocation().getWorld();
                        if (w != null && (this.worlds.isEmpty() || this.worlds.contains(w.getName()))) {
                            Villager villager = (Villager)e.getEntity();
                            if (this.appearanceEnabled && villager.getVillagerLevel() <= 1 && ThreadLocalRandom.current().nextDouble() <= this.overrideChance) {
                                Villager.Type newType = (Villager.Type)this.allowedTypes.get(ThreadLocalRandom.current().nextInt(this.allowedTypes.size()));
                                villager.setVillagerType(newType);
                            }

                            if (this.professionRotationEnabled) {
                                this.updateVillagerDisplay(villager, (Player)null);
                            }

                            return;
                        } else {
                            return;
                        }
                    default:
                }
            }
        }
    }

    @EventHandler
    public void onSeasonUpdate(SeasonUpdateEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            CalendarState state = e.getState();
            this.currentCalendarDay = state.day;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            if (e.getTo() != null) {
                Chunk from = e.getFrom().getChunk();
                Chunk to = e.getTo().getChunk();
                if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                    this.maybeUpdateVillagersIfPeriodPassed();
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
                if (e.getPlayer().isOnline()) {
                    this.maybeUpdateVillagersIfPeriodPassed();
                }

            }, 40L);
        }
    }

    private void maybeUpdateVillagersIfPeriodPassed() {
        if (this.enabled && this.professionRotationEnabled) {
            if (this.currentCalendarDay > 0) {
                boolean anyPlayerInConfiguredWorld = false;

                for(Player p : this.plugin.getServer().getOnlinePlayers()) {
                    if (this.worlds.contains(p.getWorld().getName())) {
                        anyPlayerInConfiguredWorld = true;
                        break;
                    }
                }

                if (anyPlayerInConfiguredWorld) {
                    boolean shouldUpdate;
                    if (this.lastUpdateDay == -1) {
                        shouldUpdate = true;
                    } else if (this.currentCalendarDay >= this.lastUpdateDay) {
                        shouldUpdate = this.currentCalendarDay - this.lastUpdateDay >= this.updatePeriodDays;
                    } else {
                        shouldUpdate = true;
                    }

                    if (shouldUpdate) {
                        int previousDay = this.lastUpdateDay;
                        this.plugin.getLogger().info("[VillagerTypes] Have approved at least " + this.updatePeriodDays + " days since the last rotation (" + previousDay + " -> " + this.currentCalendarDay + "). Updating villagers close to players.");
                        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                            this.performVillageUpdate();
                            this.lastUpdateDay = this.currentCalendarDay;
                            this.saveState();
                        });
                    }
                }
            }
        }
    }

    private void performVillageUpdate() {
        if (!this.allowedTypes.isEmpty()) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            for(Player p : this.plugin.getServer().getOnlinePlayers()) {
                World w = p.getWorld();
                if (this.worlds.contains(w.getName())) {
                    Chunk pChunk = p.getLocation().getChunk();
                    int r = 10;
                    List<Villager> candidates = new ArrayList();

                    for(int x = pChunk.getX() - r; x <= pChunk.getX() + r; ++x) {
                        for(int z = pChunk.getZ() - r; z <= pChunk.getZ() + r; ++z) {
                            if (w.isChunkLoaded(x, z)) {
                                for(Entity entity : w.getChunkAt(x, z).getEntities()) {
                                    if (entity.getType() == EntityType.VILLAGER) {
                                        Villager villager = (Villager)entity;
                                        boolean hasTraded = villager.getVillagerLevel() > 1 || villager.getVillagerExperience() > 0;
                                        if (hasTraded) {
                                            this.updateVillagerDisplay(villager, p);
                                        } else {
                                            candidates.add(villager);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!candidates.isEmpty()) {
                        Collections.shuffle(candidates, rnd);
                        int total = candidates.size();
                        int lazyToAssign = 0;
                        if (total >= 3) {
                            int lazyByRatio = (int)Math.floor((double)total * this.lazyRatio);
                            lazyToAssign = Math.max(1, lazyByRatio);
                            lazyToAssign = Math.min(this.maxLazyPerArea, lazyToAssign);
                            if (lazyToAssign >= total) {
                                lazyToAssign = total - 1;
                            }
                        }

                        for(int i = 0; i < total; ++i) {
                            Villager v = (Villager)candidates.get(i);
                            if (i < lazyToAssign) {
                                v.setProfession(Profession.NITWIT);
                            } else {
                                v.setProfession(Profession.NONE);
                            }

                            if (this.appearanceEnabled && rnd.nextDouble() <= this.overrideChance) {
                                Villager.Type newType = (Villager.Type)this.allowedTypes.get(rnd.nextInt(this.allowedTypes.size()));
                                v.setVillagerType(newType);
                            }

                            this.updateVillagerDisplay(v, p);
                        }
                    }
                }
            }

        }
    }

    private void updateVillagerDisplay(Villager villager, Player p) {
        String currentName = villager.getCustomName();
        if (villager.getProfession() == Profession.NITWIT) {
            String safeCurrentName = currentName != null ? currentName : "";
            String nameWithoutTag = this.removeAllLazyTags(safeCurrentName).trim();
            if (!this.showLazyTag) {
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName((String)null);
                    villager.setCustomNameVisible(false);
                } else if (!safeCurrentName.equals(nameWithoutTag)) {
                    villager.setCustomName(nameWithoutTag);
                    villager.setCustomNameVisible(true);
                }

            } else {
                String lazyTag = this.langManager.tr(p, "villager.lazy_tag");
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName(lazyTag);
                } else {
                    villager.setCustomName(nameWithoutTag + " " + lazyTag);
                }

                villager.setCustomNameVisible(true);
            }
        } else {
            if (currentName != null) {
                String nameWithoutTag = this.removeAllLazyTags(currentName).trim();
                if (nameWithoutTag.isEmpty()) {
                    villager.setCustomName((String)null);
                    villager.setCustomNameVisible(false);
                } else if (!currentName.equals(nameWithoutTag)) {
                    villager.setCustomName(nameWithoutTag);
                    villager.setCustomNameVisible(true);
                }
            }

        }
    }

    private String removeAllLazyTags(String name) {
        if (name == null) {
            return "";
        } else {
            String cleanName = name;

            for(String tag : this.allLazyTags) {
                cleanName = cleanName.replace(tag, "").trim();
            }

            String currentLazyTag = this.langManager.trServer("villager.lazy_tag");
            cleanName = cleanName.replace(currentLazyTag, "").trim();
            return cleanName;
        }
    }

    @EventHandler
    public void onPlayerLocaleChange(PlayerLocaleChangeEvent e) {
        if (this.enabled && this.professionRotationEnabled) {
            this.checkNearbyVillagers(e.getPlayer());
        }
    }

    private void checkNearbyVillagers(Player p) {
        World w = p.getWorld();
        if (this.worlds.contains(w.getName())) {
            Chunk pChunk = p.getLocation().getChunk();
            int r = 10;

            for(int x = pChunk.getX() - r; x <= pChunk.getX() + r; ++x) {
                for(int z = pChunk.getZ() - r; z <= pChunk.getZ() + r; ++z) {
                    if (w.isChunkLoaded(x, z)) {
                        for(Entity entity : w.getChunkAt(x, z).getEntities()) {
                            if (entity.getType() == EntityType.VILLAGER) {
                                this.updateVillagerDisplay((Villager)entity, p);
                            }
                        }
                    }
                }
            }

        }
    }
}
