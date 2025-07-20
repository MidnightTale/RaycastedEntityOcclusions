package games.cubi.raycastedEntityOcclusion.Raycast;


import games.cubi.raycastedEntityOcclusion.Snapshot.ChunkSnapshotManager;
import games.cubi.raycastedEntityOcclusion.ConfigManager;
import games.cubi.raycastedEntityOcclusion.RaycastedEntityOcclusion;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Engine {

    public static ConcurrentHashMap<Location, Set<Player>> canSeeTileEntity = new ConcurrentHashMap<>();

    private static class RayJob {
        final Player player;
        final Entity target;
        final Location start, predictedStart, end;

        RayJob(Player p, Entity e, Location s, Location pred, Location t) {
            player = p;
            target = e;
            start = s;
            predictedStart = pred;
            end = t;
        }
    }

    private static class RayResult {
        final Player player;
        final Entity target;
        final boolean visible;

        RayResult(Player p, Entity e, boolean v) {
            player = p;
            target = e;
            visible = v;
        }
    }

    public static void runEngine(ConfigManager cfg, ChunkSnapshotManager snapMgr, MovementTracker tracker, RaycastedEntityOcclusion plugin) {
        // ----- PHASE 1: SYNC GATHER -----
        List<RayJob> jobs = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("raycastedentityocclusions.bypass")) continue;
            RaycastedEntityOcclusion.instance.foliaLib.getScheduler().runAtEntity(p, (runEngine) -> {
            Location eye = p.getEyeLocation().clone();
            Location predEye = null;
            if (cfg.engineMode == 2) {
                // getPredictedLocation returns null if insufficient data or too slow
                predEye = tracker.getPredictedLocation(p);
            }

            for (Entity e : p.getNearbyEntities(cfg.searchRadius, cfg.searchRadius, cfg.searchRadius)) {
                if (e == p) continue;
                // Cull-players logic
                if (e instanceof Player pl && (!cfg.cullPlayers || (cfg.onlyCullSneakingPlayers && !pl.isSneaking()))) {
                    p.showEntity(plugin, e);
                    continue;
                }

                Location target = e.getLocation().add(0, e.getHeight() / 2, 0).clone();
                double dist = eye.distance(target);
                if (dist <= cfg.alwaysShowRadius) {
                    p.showEntity(plugin, e);
                } else if (dist > cfg.raycastRadius) {
                    p.hideEntity(plugin, e);
                } else if (p.canSee(e) && plugin.tick % cfg.recheckInterval != 0) {
                    // player can see entity, no need to raycast
                } else {
                    // schedule for async raycast (with or without predEye)
                    jobs.add(new RayJob(p, e, eye, predEye, target));
                }
            }});
        }

        // ----- PHASE 2: ASYNC RAYCASTS -----
        RaycastedEntityOcclusion.instance.foliaLib.getScheduler().runAsync((asyncRaycast) -> {
            List<RayResult> results = new ArrayList<>(jobs.size());
            for (RayJob job : jobs) {
                // if the player is not in the same world as the target, skip
                if (!job.player.getWorld().equals(job.target.getWorld())) {
                    continue;
                }
                // first cast from real eye
                boolean vis = RaycastUtil.raycast(job.start, job.end, cfg.maxOccludingCount, cfg.debugMode, snapMgr);

                // if that fails and we have a predEye, cast again from predicted
                if (!vis && job.predictedStart != null) {
                    if (cfg.debugMode) {
                        job.predictedStart.getWorld().spawnParticle(Particle.DUST, job.predictedStart, 1, new Particle.DustOptions(Color.BLUE, 1f));
                    }
                    vis = RaycastUtil.raycast(job.predictedStart, job.end, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                }

                results.add(new RayResult(job.player, job.target, vis));
            }

            // ----- PHASE 3: SYNC APPLY -----
                for (RayResult r : results) {
                    Player p = r.player;
                    Entity ent = r.target;
                    if (p != null && ent != null) {
                        RaycastedEntityOcclusion.instance.foliaLib.getScheduler().runAtEntity(p, (runEngine) -> {
                        boolean currentState = p.canSee(ent);
                        if (currentState != r.visible) {
                            if (r.visible) p.showEntity(plugin, ent);
                            else {
                                p.hideEntity(plugin, ent);
                            }
                        }});
                    }
                }
        });

    }

    public static void runTileEngine(ConfigManager cfg, ChunkSnapshotManager snapMgr, MovementTracker tracker, RaycastedEntityOcclusion plugin) {
        if (cfg.checkTileEntities) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("raycastedentityocclusions.bypass")) continue;
                String world = p.getWorld().getName();
                //get player's chunk location
                int chunkX = p.getLocation().getBlockX() >> 4;
                int chunkZ = p.getLocation().getBlockZ() >> 4;
                //async run with vars passed in
                RaycastedEntityOcclusion.instance.foliaLib.getScheduler().runAsync((runTileEngine) -> {
                    int chunksRadius = (cfg.searchRadius + 15) / 16;
                    HashSet<Location> tileEntities = new HashSet<>();
                    for (int x = (-chunksRadius/2)+chunkX; x <= chunksRadius+chunkX; x++) {
                        for (int z = (-chunksRadius/2)+chunkZ; z <= chunksRadius+chunkZ; z++) {
                            tileEntities.addAll(snapMgr.getTileEntitiesInChunk(world, x, z));
                        }
                    }
                    for (Location loc : tileEntities) {
                        Set<Player> seen = canSeeTileEntity.get(loc);
                        if (seen != null && seen.contains(p)) {
                            if (cfg.tileEntityRecheckInterval == 0) continue;
                            if (plugin.tick % (cfg.tileEntityRecheckInterval*20) != 0) continue;
                        }

                        if (snapMgr.getMaterialAt(loc).equals(Material.BEACON)) continue;

                        double distSquared = loc.distanceSquared(p.getLocation());
                        if (distSquared > cfg.searchRadius * cfg.searchRadius) {
                            hideTileEntity(p, loc);
                            continue;
                        }
                        if (distSquared < cfg.alwaysShowRadius * cfg.alwaysShowRadius) {
                            showTileEntity(p, loc);
                            continue;
                        }

                        boolean result = RaycastUtil.raycast(p.getEyeLocation(), loc, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                        if (cfg.engineMode == 2) {
                            Location predEye = tracker.getPredictedLocation(p);
                            if (predEye != null) {
                                boolean result2 = RaycastUtil.raycast(predEye, loc, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                                if (result2) {
                                    result = true;
                                }
                            }
                        }
                        syncToggleTileEntity(p, loc, result, plugin);
                        if (result) {
                            canSeeTileEntity.computeIfAbsent(loc, k -> ConcurrentHashMap.newKeySet()).add(p);
                        } else {
                            Set<Player> seenPlayers = canSeeTileEntity.get(loc);
                            if (seenPlayers != null) {
                                seenPlayers.remove(p);
                                if (seenPlayers.isEmpty()) {
                                    canSeeTileEntity.remove(loc);
                                }
                            }
                        }
                    }
                });
            }
        }
    }

    public static void hideTileEntity(Player p, Location location) {
        if (p.hasPermission("raycastedentityocclusions.bypass")) return;
        BlockData fake;
        if (location.getBlockY() < 0) {
            fake = Material.DEEPSLATE.createBlockData();
        }
        else {
            fake = Material.STONE.createBlockData();
        }
        p.sendBlockChange(location, fake);
    }
    public static void showTileEntity(Player p, Location location) {
        Block block = location.getBlock();
        BlockData data = block.getBlockData();
        p.sendBlockChange(location, data);

    }
    public static void syncToggleTileEntity(Player p, Location loc, boolean bool, RaycastedEntityOcclusion plugin) {
        RaycastedEntityOcclusion.instance.foliaLib.getScheduler().runAtEntity(p, (syncToggleTileEntity) -> {
            if (bool) {
                showTileEntity(p, loc);
            } else {
                hideTileEntity(p, loc);
            }
        });
    }
}

/*
 */