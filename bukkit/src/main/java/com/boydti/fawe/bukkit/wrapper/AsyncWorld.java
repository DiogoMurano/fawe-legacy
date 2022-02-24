package com.boydti.fawe.bukkit.wrapper;

import com.boydti.fawe.FaweAPI;
import com.boydti.fawe.bukkit.v0.BukkitQueue_0;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.HasFaweQueue;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.object.queue.DelegateFaweQueue;
import com.boydti.fawe.util.SetQueue;
import com.boydti.fawe.util.StringMan;
import com.boydti.fawe.util.TaskManager;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.world.biome.BaseBiome;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Difficulty;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Consumer;
import org.bukkit.util.Vector;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Modify the world from an async thread<br>
 * - Use world.commit() to execute all the changes<br>
 * - Any Chunk/Block/BlockState objects returned should also be safe to use from the same async thread<br>
 * - Only block read,write and biome write are fast, other methods will perform slower async<br>
 * -
 *
 * @see #wrap(org.bukkit.World)
 * @see #create(org.bukkit.WorldCreator)
 */
public class AsyncWorld extends DelegateFaweQueue implements World, HasFaweQueue {

    private World parent;
    private FaweQueue queue;
    private BukkitImplAdapter adapter;

    @Override
    public <T> void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i, final double v3, final double v4, final double v5, final double v6, final T t) {
        parent.spawnParticle(particle, v, v1, v2, i, v3, v4, v5, v6, t);
    }

    @Override
    public <T> void spawnParticle(Particle particle, List<Player> list, Player player, double v, double v1, double v2, int i, double v3, double v4, double v5, double v6, T t, boolean b) {
        parent.spawnParticle(particle, list, player, v, v1, v2, i, v3, v4, v5, v6, t, b);
    }

    /**
     * @param parent    Parent world
     * @param autoQueue
     * @deprecated use {@link #wrap(org.bukkit.World)} instead
     */
    @Deprecated
    public AsyncWorld(final World parent, final boolean autoQueue) {
        this(parent, FaweAPI.createQueue(parent.getName(), autoQueue));
    }

    public AsyncWorld(final String world, final boolean autoQueue) {
        this(Bukkit.getWorld(world), autoQueue);
    }

    /**
     * @param parent
     * @param queue
     * @deprecated use {@link #wrap(org.bukkit.World)} instead
     */
    @Deprecated
    public AsyncWorld(final World parent, final FaweQueue queue) {
        super(queue);
        this.parent = parent;
        this.queue = queue;
        if (queue instanceof BukkitQueue_0) {
            this.adapter = BukkitQueue_0.getAdapter();
        } else {
            try {
                WorldEditPlugin instance = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
                Field fieldAdapter = WorldEditPlugin.class.getDeclaredField("bukkitAdapter");
                fieldAdapter.setAccessible(true);
                this.adapter = (BukkitImplAdapter) fieldAdapter.get(instance);
            } catch (final Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Wrap a world for async usage
     *
     * @param world
     * @return
     */
    public static AsyncWorld wrap(final World world) {
        if (world instanceof AsyncWorld) {
            return (AsyncWorld) world;
        }
        return new AsyncWorld(world, false);
    }

    public void changeWorld(final World world, final FaweQueue queue) {
        this.parent = world;
        if (queue != this.queue) {
            if (this.queue != null) {
                final FaweQueue oldQueue = this.queue;
                TaskManager.IMP.async(new Runnable() {
                    @Override
                    public void run() {
                        oldQueue.flush();
                    }
                });
            }
            this.queue = queue;
        }
        setParent(queue);
    }

    @Override
    public String toString() {
        return super.toString() + ":" + queue.toString();
    }

    public World getBukkitWorld() {
        return parent;
    }

    public FaweQueue getQueue() {
        return queue;
    }

    /**
     * Create a world async (untested)
     *  - Only optimized for 1.10
     * @param creator
     * @return
     */
    public synchronized static AsyncWorld create(final WorldCreator creator) {
        BukkitQueue_0 queue = (BukkitQueue_0) SetQueue.IMP.getNewQueue(creator.name(), true, false);
        World world = queue.createWorld(creator);
        return wrap(world);
    }

    public Operation commit() {
        flush();
        return null;
    }

    public void flush() {
        if (queue != null) {
            queue.flush();
        }
    }

    @Override
    public WorldBorder getWorldBorder() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final WorldBorder value) {
                this.value = parent.getWorldBorder();
            }
        });
    }

    @Override
    public void spawnParticle(final Particle particle, final Location location, final int i) {
        parent.spawnParticle(particle, location, i);
    }

    @Override
    public void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i) {
        parent.spawnParticle(particle, v, v1, v2, i);
    }

    @Override
    public <T> void spawnParticle(final Particle particle, final Location location, final int i, final T t) {
        parent.spawnParticle(particle, location, i, t);
    }

    @Override
    public <T> void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i, final T t) {
        parent.spawnParticle(particle, v, v1, v2, i, t);
    }

    @Override
    public void spawnParticle(final Particle particle, final Location location, final int i, final double v, final double v1, final double v2) {
        parent.spawnParticle(particle, location, i, v, v1, v2);
    }

    @Override
    public void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i, final double v3, final double v4, final double v5) {
        parent.spawnParticle(particle, v, v1, v2, i, v3, v4, v5);
    }

    @Override
    public <T> void spawnParticle(final Particle particle, final Location location, final int i, final double v, final double v1, final double v2, final T t) {
        parent.spawnParticle(particle, location, i, v, v1, v2, t);
    }

    @Override
    public <T> void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i, final double v3, final double v4, final double v5, final T t) {
        parent.spawnParticle(particle, v, v1, v2, i, v3, v4, v5, t);
    }

    @Override
    public void spawnParticle(final Particle particle, final Location location, final int i, final double v, final double v1, final double v2, final double v3) {
        parent.spawnParticle(particle, location, i, v, v1, v2, v3);
    }

    @Override
    public void spawnParticle(final Particle particle, final double v, final double v1, final double v2, final int i, final double v3, final double v4, final double v5, final double v6) {
        parent.spawnParticle(particle, v, v1, v2, i, v3, v4, v5, v6);
    }

    @Override
    public <T> void spawnParticle(final Particle particle, final Location location, final int i, final double v, final double v1, final double v2, final double v3, final T t) {
        parent.spawnParticle(particle, location, i, v, v1, v2, v3, t);
    }

    @Override
    public int getEntityCount() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Integer value) {
                this.value = parent.getEntityCount();
            }
        });
    }

    @Override
    public int getTileEntityCount() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Integer value) {
                this.value = parent.getTileEntityCount();
            }
        });
    }

    @Override
    public int getTickableTileEntityCount() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Integer value) {
                this.value = parent.getTickableTileEntityCount();
            }
        });
    }

    @Override
    public int getChunkCount() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Integer value) {
                this.value = parent.getChunkCount();
            }
        });
    }

    @Override
    public int getPlayerCount() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Integer value) {
                this.value = parent.getPlayerCount();
            }
        });
    }

    @Override
    public Block getBlockAt(final int x, final int y, final int z) {
        return new AsyncBlock(this, queue, x, y, z);
    }

    @Override
    public Block getBlockAt(final Location loc) {
        return getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @Override
    @Deprecated
    public int getBlockTypeIdAt(final int x, final int y, final int z) {
        return queue.getCachedCombinedId4Data(x, y & 0xFF, z, 0) >> 4;
    }

    @Override
    @Deprecated
    public int getBlockTypeIdAt(final Location loc) {
        return getBlockTypeIdAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @Override
    public int getHighestBlockYAt(final int x, final int z) {
        for (int y = getMaxHeight() - 1; y >= 0; y--) {
            if (queue.getCachedCombinedId4Data(x, y, z, 0) != 0) {
                return y;
            }
        }
        return 0;
    }

    @Override
    public int getHighestBlockYAt(final Location loc) {
        return getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
    }

    @Override
    public Block getHighestBlockAt(final int x, final int z) {
        int y = getHighestBlockYAt(x, z);
        return getBlockAt(x, y, z);
    }

    @Override
    public Block getHighestBlockAt(final Location loc) {
        return getHighestBlockAt(loc.getBlockX(), loc.getBlockZ());
    }

    @Override
    public Chunk getChunkAt(final int x, final int z) {
        return new AsyncChunk(this, queue, x, z);
    }

    @Override
    public Chunk getChunkAt(final Location location) {
        return getChunkAt(location.getBlockX(), location.getBlockZ());
    }

    @Override
    public Chunk getChunkAt(final Block block) {
        return getChunkAt(block.getX(), block.getZ());
    }

    @Override
    public boolean isChunkGenerated(final int i, final int i1) {
        return parent.isChunkGenerated(i, i1);
    }

    @Override
    public void getChunkAtAsync(final int x, final int z, final ChunkLoadCallback cb) {
        parent.getChunkAtAsync(x, z, cb);
    }

    @Override
    public void getChunkAtAsync(final Location location, final ChunkLoadCallback cb) {
        parent.getChunkAtAsync(location, cb);
    }

    @Override
    public void getChunkAtAsync(final Block block, final ChunkLoadCallback cb) {
        parent.getChunkAtAsync(block, cb);
    }


    @Override
    public boolean isChunkLoaded(final Chunk chunk) {
        return chunk.isLoaded();
    }

    @Override
    public Chunk[] getLoadedChunks() {
        return parent.getLoadedChunks();
    }

    @Override
    public void loadChunk(final Chunk chunk) {
        if (!chunk.isLoaded()) {
            TaskManager.IMP.sync(new RunnableVal<>() {
                @Override
                public void run(final Object value) {
                    parent.loadChunk(chunk);
                }
            });
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == null || !(obj instanceof World)) {
            return false;
        }
        World other = (World) obj;
        return StringMan.isEqual(other.getName(), getName());
    }

    @Override
    public int hashCode() {
        return this.getUID().hashCode();
    }

    @Override
    public boolean isChunkLoaded(final int x, final int z) {
        return parent.isChunkLoaded(x, z);
    }

    @Override
    public boolean isChunkInUse(final int x, final int z) {
        return parent.isChunkInUse(x, z);
    }

    @Override
    public void loadChunk(final int x, final int z) {
        if (!isChunkLoaded(x, z)) {
            TaskManager.IMP.sync(new RunnableVal<>() {
                @Override
                public void run(final Object value) {
                    parent.loadChunk(x, z);
                }
            });
        }
    }

    @Override
    public boolean loadChunk(final int x, final int z, final boolean generate) {
        if (!isChunkLoaded(x, z)) {
            return TaskManager.IMP.sync(new RunnableVal<>() {
                @Override
                public void run(final Boolean value) {
                    this.value = parent.loadChunk(x, z, generate);
                }
            });
        }
        return true;
    }

    @Override
    public boolean unloadChunk(final Chunk chunk) {
        if (chunk.isLoaded()) {
            return TaskManager.IMP.sync(new RunnableVal<>() {
                @Override
                public void run(final Boolean value) {
                    this.value = parent.unloadChunk(chunk);
                }
            });
        }
        return true;
    }

    @Override
    public boolean unloadChunk(final int x, final int z) {
        return unloadChunk(x, z, true);
    }

    @Override
    public boolean unloadChunk(final int x, final int z, final boolean save) {
        return unloadChunk(x, z, save, false);
    }

    @Deprecated
    @Override
    public boolean unloadChunk(final int x, final int z, final boolean save, final boolean safe) {
        if (isChunkLoaded(x, z)) {
            return TaskManager.IMP.sync(new RunnableVal<>() {
                @Override
                public void run(final Boolean value) {
                    this.value = parent.unloadChunk(x, z, save, safe);
                }
            });
        }
        return true;
    }

    @Override
    public boolean unloadChunkRequest(final int x, final int z) {
        return unloadChunk(x, z);
    }

    @Override
    public boolean unloadChunkRequest(final int x, final int z, final boolean safe) {
        return unloadChunk(x, z, safe);
    }

    @Override
    public boolean regenerateChunk(final int x, final int z) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.regenerateChunk(x, z);
            }
        });
    }

    @Override
    @Deprecated
    public boolean refreshChunk(final int x, final int z) {
        queue.sendChunk(queue.getFaweChunk(x, z));
        return true;
    }

    @Override
    public Item dropItem(final Location location, final ItemStack item) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Item value) {
                this.value = parent.dropItem(location, item);
            }
        });
    }

    @Override
    public Item dropItemNaturally(final Location location, final ItemStack item) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Item value) {
                this.value = parent.dropItemNaturally(location, item);
            }
        });
    }

    @Override
    public Arrow spawnArrow(final Location location, final Vector direction, final float speed, final float spread) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Arrow value) {
                this.value = parent.spawnArrow(location, direction, speed, spread);
            }
        });
    }

    @Override
    public <T extends Arrow> T spawnArrow(final Location location, final Vector vector, final float v, final float v1, final Class<T> aClass) {
        return parent.spawnArrow(location, vector, v, v1, aClass);
    }

    @Override
    public boolean generateTree(final Location location, final TreeType type) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.generateTree(location, type);
            }
        });
    }

    @Override
    public boolean generateTree(final Location loc, final TreeType type, final BlockChangeDelegate delegate) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.generateTree(loc, type, delegate);
            }
        });
    }

    @Override
    public Entity spawnEntity(final Location loc, final EntityType type) {
        return spawn(loc, type.getEntityClass());
    }

    @Override
    public LightningStrike strikeLightning(final Location loc) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final LightningStrike value) {
                this.value = parent.strikeLightning(loc);
            }
        });
    }

    @Override
    public LightningStrike strikeLightningEffect(final Location loc) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final LightningStrike value) {
                this.value = parent.strikeLightningEffect(loc);
            }
        });
    }

    @Override
    public List getEntities() {
        return TaskManager.IMP.sync(new RunnableVal<List<Entity>>() {
            @Override
            public void run(final List<Entity> value) {
                this.value = parent.getEntities();
            }
        });
    }

    @Override
    public List<LivingEntity> getLivingEntities() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final List<LivingEntity> value) {
                this.value = parent.getLivingEntities();
            }
        });
    }

    @Override
    @Deprecated
    public <T extends Entity> Collection<T> getEntitiesByClass(final Class<T>... classes) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Collection<T> value) {
                this.value = parent.getEntitiesByClass(classes);
            }
        });
    }

    @Override
    public <T extends Entity> Collection<T> getEntitiesByClass(final Class<T> cls) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Collection<T> value) {
                this.value = parent.getEntitiesByClass(cls);
            }
        });
    }

    @Override
    public Collection<Entity> getEntitiesByClasses(final Class<?>... classes) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Collection<Entity> value) {
                this.value = parent.getEntitiesByClasses(classes);
            }
        });
    }

    @Override
    public List<Player> getPlayers() {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final List<Player> value) {
                this.value = parent.getPlayers();
            }
        });
    }

    @Override
    public Collection<Entity> getNearbyEntities(final Location location, final double x, final double y, final double z) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Collection<Entity> value) {
                this.value = parent.getNearbyEntities(location, x, y, z);
            }
        });
    }

    @Override
    public Entity getEntity(final UUID uuid) {
        return TaskManager.IMP.sync(() -> parent.getEntity(uuid));
    }

    @Override
    public String getName() {
        return parent.getName();
    }

    @Override
    public UUID getUID() {
        return parent.getUID();
    }

    @Override
    public Location getSpawnLocation() {
        return parent.getSpawnLocation();
    }

    @Override
    public boolean setSpawnLocation(Location location) {
        return parent.setSpawnLocation(location);
    }

    @Override
    public boolean setSpawnLocation(final int x, final int y, final int z) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.setSpawnLocation(x, y, z);
            }
        });
    }

    @Override
    public long getTime() {
        return parent.getTime();
    }

    @Override
    public void setTime(final long time) {
        parent.setTime(time);
    }

    @Override
    public long getFullTime() {
        return parent.getFullTime();
    }

    @Override
    public void setFullTime(final long time) {
        parent.setFullTime(time);
    }

    @Override
    public boolean hasStorm() {
        return parent.hasStorm();
    }

    @Override
    public void setStorm(final boolean hasStorm) {
        parent.setStorm(hasStorm);
    }

    @Override
    public int getWeatherDuration() {
        return parent.getWeatherDuration();
    }

    @Override
    public void setWeatherDuration(final int duration) {
        parent.setWeatherDuration(duration);
    }

    @Override
    public boolean isThundering() {
        return parent.isThundering();
    }

    @Override
    public void setThundering(final boolean thundering) {
        parent.setThundering(thundering);
    }

    @Override
    public int getThunderDuration() {
        return parent.getThunderDuration();
    }

    @Override
    public void setThunderDuration(final int duration) {
        parent.setThunderDuration(duration);
    }

    public boolean createExplosion(final double x, final double y, final double z, final float power) {
        return this.createExplosion(x, y, z, power, false, true);
    }

    public boolean createExplosion(final double x, final double y, final double z, final float power, final boolean setFire) {
        return this.createExplosion(x, y, z, power, setFire, true);
    }

    public boolean createExplosion(final double x, final double y, final double z, final float power, final boolean setFire, final boolean breakBlocks) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.createExplosion(x, y, z, power, setFire, breakBlocks);
            }
        });
    }

    public boolean createExplosion(final Location loc, final float power) {
        return this.createExplosion(loc, power, false);
    }

    public boolean createExplosion(final Location loc, final float power, final boolean setFire) {
        return this.createExplosion(loc.getX(), loc.getY(), loc.getZ(), power, setFire);
    }

    @Override
    public boolean createExplosion(Entity entity, Location location, float v, boolean b, boolean b1) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Boolean value) {
                this.value = parent.createExplosion(entity, location, v, b, b1);
            }
        });
    }

    @Override
    public Environment getEnvironment() {
        return parent.getEnvironment();
    }

    @Override
    public long getSeed() {
        return parent.getSeed();
    }

    @Override
    public boolean getPVP() {
        return parent.getPVP();
    }

    @Override
    public void setPVP(final boolean pvp) {
        parent.setPVP(pvp);
    }

    @Override
    public ChunkGenerator getGenerator() {
        return parent.getGenerator();
    }

    @Override
    public void save() {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.save();
            }
        });
    }

    @Override
    public List<BlockPopulator> getPopulators() {
        return parent.getPopulators();
    }

    @Override
    public <T extends Entity> T spawn(final Location location, final Class<T> clazz) throws IllegalArgumentException {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final T value) {
                this.value = parent.spawn(location, clazz);
            }
        });
    }

    @Override
    public <T extends Entity> T spawn(final Location location, final Class<T> clazz, final Consumer<T> function) throws IllegalArgumentException {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final T value) {
                this.value = parent.spawn(location, clazz, function);
            }
        });
    }

    @Override
    public FallingBlock spawnFallingBlock(final Location location, final MaterialData data) throws IllegalArgumentException {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final FallingBlock value) {
                this.value = parent.spawnFallingBlock(location, data);
            }
        });
    }

    @Override
    @Deprecated
    public FallingBlock spawnFallingBlock(final Location location, final Material material, final byte data) throws IllegalArgumentException {
        return this.spawnFallingBlock(location, material.getId(), data);
    }

    @Override
    @Deprecated
    public FallingBlock spawnFallingBlock(final Location location, final int blockId, final byte blockData) throws IllegalArgumentException {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final FallingBlock value) {
                this.value = parent.spawnFallingBlock(location, blockId, blockData);
            }
        });
    }

    @Override
    public void playEffect(final Location location, final Effect effect, final int data) {
        this.playEffect(location, effect, data, 64);
    }

    @Override
    public void playEffect(final Location location, final Effect effect, final int data, final int radius) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playEffect(location, effect, data, radius);
            }
        });
    }

    @Override
    public <T> void playEffect(final Location loc, final Effect effect, final T data) {
        this.playEffect(loc, effect, data, 64);
    }

    @Override
    public <T> void playEffect(final Location location, final Effect effect, final T data, final int radius) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playEffect(location, effect, data, radius);
            }
        });
    }

    @Override
    public ChunkSnapshot getEmptyChunkSnapshot(final int x, final int z, final boolean includeBiome, final boolean includeBiomeTempRain) {
        return TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final ChunkSnapshot value) {
                this.value = parent.getEmptyChunkSnapshot(x, z, includeBiome, includeBiomeTempRain);
            }
        });
    }

    @Override
    public void setSpawnFlags(final boolean allowMonsters, final boolean allowAnimals) {
        parent.setSpawnFlags(allowMonsters, allowAnimals);
    }

    @Override
    public boolean getAllowAnimals() {
        return parent.getAllowAnimals();
    }

    @Override
    public boolean getAllowMonsters() {
        return parent.getAllowMonsters();
    }

    @Override
    public Biome getBiome(final int x, final int z) {
        return adapter.getBiome(queue.getBiomeId(x, z));
    }

    @Override
    public void setBiome(final int x, final int z, final Biome bio) {
        int id = adapter.getBiomeId(bio);
        queue.setBiome(x, z, new BaseBiome(id));
    }

    @Override
    public double getTemperature(final int x, final int z) {
        return parent.getTemperature(x, z);
    }

    @Override
    public double getHumidity(final int x, final int z) {
        return parent.getHumidity(x, z);
    }

    @Override
    public int getMaxHeight() {
        return parent.getMaxHeight();
    }

    @Override
    public int getSeaLevel() {
        return parent.getSeaLevel();
    }

    @Override
    public boolean getKeepSpawnInMemory() {
        return parent.getKeepSpawnInMemory();
    }

    @Override
    public void setKeepSpawnInMemory(final boolean keepLoaded) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.setKeepSpawnInMemory(keepLoaded);
            }
        });
    }

    @Override
    public boolean isAutoSave() {
        return parent.isAutoSave();
    }

    @Override
    public void setAutoSave(final boolean value) {
        parent.setAutoSave(value);
    }

    @Override
    public void setDifficulty(final Difficulty difficulty) {
        parent.setDifficulty(difficulty);
    }

    @Override
    public Difficulty getDifficulty() {
        return parent.getDifficulty();
    }

    @Override
    public File getWorldFolder() {
        return parent.getWorldFolder();
    }

    @Override
    public WorldType getWorldType() {
        return parent.getWorldType();
    }

    @Override
    public boolean canGenerateStructures() {
        return parent.canGenerateStructures();
    }

    @Override
    public long getTicksPerAnimalSpawns() {
        return parent.getTicksPerAnimalSpawns();
    }

    @Override
    public void setTicksPerAnimalSpawns(final int ticksPerAnimalSpawns) {
        parent.setTicksPerAnimalSpawns(ticksPerAnimalSpawns);
    }

    @Override
    public long getTicksPerMonsterSpawns() {
        return parent.getTicksPerMonsterSpawns();
    }

    @Override
    public void setTicksPerMonsterSpawns(final int ticksPerMonsterSpawns) {
        parent.setTicksPerMonsterSpawns(ticksPerMonsterSpawns);
    }

    @Override
    public int getMonsterSpawnLimit() {
        return parent.getMonsterSpawnLimit();
    }

    @Override
    public void setMonsterSpawnLimit(final int limit) {
        parent.setMonsterSpawnLimit(limit);
    }

    @Override
    public int getAnimalSpawnLimit() {
        return parent.getAnimalSpawnLimit();
    }

    @Override
    public void setAnimalSpawnLimit(final int limit) {
        parent.setAnimalSpawnLimit(limit);
    }

    @Override
    public int getWaterAnimalSpawnLimit() {
        return parent.getWaterAnimalSpawnLimit();
    }

    @Override
    public void setWaterAnimalSpawnLimit(final int limit) {
        parent.setWaterAnimalSpawnLimit(limit);
    }

    @Override
    public int getAmbientSpawnLimit() {
        return parent.getAmbientSpawnLimit();
    }

    @Override
    public void setAmbientSpawnLimit(final int limit) {
        parent.setAmbientSpawnLimit(limit);
    }

    @Override
    public void playSound(final Location location, final Sound sound, final float volume, final float pitch) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playSound(location, sound, volume, pitch);
            }
        });
    }

    @Override
    public void playSound(final Location location, final String sound, final float volume, final float pitch) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playSound(location, sound, volume, pitch);
            }
        });
    }

    @Override
    public void playSound(final Location location, final Sound sound, final SoundCategory category, final float volume, final float pitch) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playSound(location, sound, category, volume, pitch);
            }
        });
    }

    @Override
    public void playSound(final Location location, final String sound, final SoundCategory category, final float volume, final float pitch) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.playSound(location, sound, category, volume, pitch);
            }
        });
    }

    @Override
    public String[] getGameRules() {
        return parent.getGameRules();
    }

    @Override
    public String getGameRuleValue(final String rule) {
        return parent.getGameRuleValue(rule);
    }

    @Override
    public boolean setGameRuleValue(final String rule, final String value) {
        return parent.setGameRuleValue(rule, value);
    }

    @Override
    public boolean isGameRule(final String rule) {
        return parent.isGameRule(rule);
    }

    @Override
    public Spigot spigot() {
        return parent.spigot();
    }

    @Override
    public void setMetadata(final String key, final MetadataValue meta) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.setMetadata(key, meta);
            }
        });
    }

    @Override
    public List<MetadataValue> getMetadata(final String key) {
        return parent.getMetadata(key);
    }

    @Override
    public boolean hasMetadata(final String key) {
        return parent.hasMetadata(key);
    }

    @Override
    public void removeMetadata(final String key, final Plugin plugin) {
        TaskManager.IMP.sync(new RunnableVal<>() {
            @Override
            public void run(final Object value) {
                parent.removeMetadata(key, plugin);
            }
        });
    }

    @Override
    public void sendPluginMessage(final Plugin source, final String channel, final byte[] message) {
        parent.sendPluginMessage(source, channel, message);
    }

    @Override
    public Set<String> getListeningPluginChannels() {
        return parent.getListeningPluginChannels();
    }

    public BukkitImplAdapter getAdapter() {
        return adapter;
    }
}
