package com.boydti.fawe.jnbt.anvil;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweCache;
import com.boydti.fawe.example.SimpleCharFaweChunk;
import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FaweInputStream;
import com.boydti.fawe.object.FaweLocation;
import com.boydti.fawe.object.FaweOutputStream;
import com.boydti.fawe.object.FawePlayer;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.Metadatable;
import com.boydti.fawe.object.PseudoRandom;
import com.boydti.fawe.object.RunnableVal2;
import com.boydti.fawe.object.brush.visualization.VirtualWorld;
import com.boydti.fawe.object.change.StreamChange;
import com.boydti.fawe.object.changeset.CFIChangeSet;
import com.boydti.fawe.object.collection.DifferentialArray;
import com.boydti.fawe.object.collection.DifferentialBlockBuffer;
import com.boydti.fawe.object.collection.IterableThreadLocal;
import com.boydti.fawe.object.collection.LocalBlockVector2DSet;
import com.boydti.fawe.object.collection.SummedAreaTable;
import com.boydti.fawe.object.exception.FaweException;
import com.boydti.fawe.object.queue.LazyFaweChunk;
import com.boydti.fawe.object.schematic.Schematic;
import com.boydti.fawe.util.CachedTextureUtil;
import com.boydti.fawe.util.RandomTextureUtil;
import com.boydti.fawe.util.ReflectionUtils;
import com.boydti.fawe.util.SetQueue;
import com.boydti.fawe.util.TaskManager;
import com.boydti.fawe.util.TextureUtil;
import com.boydti.fawe.util.image.Drawable;
import com.boydti.fawe.util.image.ImageViewer;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.MutableBlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.TreeGenerator;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BaseBiome;
import com.sk89q.worldedit.world.registry.WorldData;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class HeightMapMCAGenerator extends MCAWriter implements StreamChange, Drawable, VirtualWorld {
    private final MutableBlockVector mutable = new MutableBlockVector();

    private final ThreadLocal<int[]> indexStore = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[256];
        }
    };

    private final DifferentialBlockBuffer blocks;
    protected final DifferentialArray<byte[]> heights;
    protected final DifferentialArray<byte[]> biomes;
    protected final DifferentialArray<char[]> floor;
    protected final DifferentialArray<char[]> main;
    protected DifferentialArray<char[]> overlay;

    protected final CFIPrimtives primtives = new CFIPrimtives();
    private CFIPrimtives oldPrimitives = new CFIPrimtives();

    public final class CFIPrimtives implements Cloneable {
        int waterHeight = 0;
        private int floorThickness = 0;
        private int worldThickness = 0;
        private boolean randomVariation = true;
        private int biomePriority = 0;
        byte waterId = BlockID.STATIONARY_WATER;
        private byte bedrockId = 7;
        private boolean modifiedMain = false;

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof CFIPrimtives)) {
                return false;
            }
            try {
                for (Field field : CFIPrimtives.class.getDeclaredFields()) {
                    if (field.get(this) != field.get(obj)) return false;
                }
            } catch (final IllegalAccessException e) {
                e.printStackTrace();
            }
            return true;
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }


    protected Metadatable metaData = new Metadatable();
    protected TextureUtil textureUtil;

    @Override
    public void flushChanges(final FaweOutputStream out) throws IOException {
        heights.flushChanges(out);
        biomes.flushChanges(out);
        floor.flushChanges(out);
        main.flushChanges(out);
        out.writeBoolean(overlay != null);
        if (overlay != null) overlay.flushChanges(out);
        try {
            for (Field field : ReflectionUtils.sortFields(CFIPrimtives.class.getDeclaredFields())) {
                Object now = field.get(primtives);
                Object old = field.get(oldPrimitives);
                boolean diff = old != now;
                out.writeBoolean(diff);
                if (diff) {
                    out.writePrimitive(old);
                    out.writePrimitive(now);
                }
            }
            resetPrimtives();
        } catch (final Throwable neverHappens) {
            neverHappens.printStackTrace();
        }

        blocks.flushChanges(out);
    }

    public boolean isModified() {
        return blocks.isModified() ||
                heights.isModified() ||
                biomes.isModified() ||
                (overlay != null && overlay.isModified()) ||
                !primtives.equals(oldPrimitives);
    }

    private void resetPrimtives() throws CloneNotSupportedException {
        oldPrimitives = (CFIPrimtives) primtives.clone();
    }

    @Override
    public void undoChanges(final FaweInputStream in) throws IOException {
        heights.undoChanges(in);
        biomes.undoChanges(in);
        floor.undoChanges(in);
        main.undoChanges(in);
        if (in.readBoolean()) overlay.undoChanges(in);
        try {
            for (Field field : ReflectionUtils.sortFields(CFIPrimtives.class.getDeclaredFields())) {
                if (in.readBoolean()) {
                    field.set(primtives, in.readPrimitive(field.getType())); // old
                    in.readPrimitive(field.getType()); // new
                }
            }
            resetPrimtives();
        } catch (final Throwable neverHappens) {
            neverHappens.printStackTrace();
        }
        blocks.undoChanges(in);
    }

    @Override
    public void redoChanges(final FaweInputStream in) throws IOException {
        heights.redoChanges(in);
        biomes.redoChanges(in);
        floor.redoChanges(in);
        main.redoChanges(in);
        if (in.readBoolean()) overlay.redoChanges(in);

        try {
            for (Field field : ReflectionUtils.sortFields(CFIPrimtives.class.getDeclaredFields())) {
                if (in.readBoolean()) {
                    in.readPrimitive(field.getType()); // old
                    field.set(primtives, in.readPrimitive(field.getType())); // new
                }
            }
            resetPrimtives();
        } catch (final Throwable neverHappens) {
            neverHappens.printStackTrace();
        }

        blocks.clearChanges(); // blocks.redoChanges(in); Unsupported
    }

    @Override
    public void addEditSession(final EditSession session) {
        session.setFastMode(true);
        this.editSession = session;
    }

    @Override
    public boolean supports(final Capability capability) {
        return false;
    }

    // Used for visualizing the world on a map
    private ImageViewer viewer;
    // Used for visualizing the world by sending chunk packets
    // These three variables should be set together
//    private FaweQueue packetQueue;
    private FawePlayer player;
    private Vector2D chunkOffset = Vector2D.ZERO;
    private EditSession editSession;
    // end

    public HeightMapMCAGenerator(final BufferedImage img, final File regionFolder) {
        this(img.getWidth(), img.getHeight(), regionFolder);
        setHeight(img);
    }

    public HeightMapMCAGenerator(final int width, final int length, final File regionFolder) {
        super(width, length, regionFolder);
        int area = getArea();

        blocks = new DifferentialBlockBuffer(width, length);
        heights = new DifferentialArray(new byte[getArea()]);
        biomes = new DifferentialArray(new byte[getArea()]);
        floor = new DifferentialArray(new char[getArea()]);
        main = new DifferentialArray(new char[getArea()]);

        char stone = (char) FaweCache.getCombined(1, 0);
        char grass = (char) FaweCache.getCombined(2, 0);
        Arrays.fill(main.getCharArray(), stone);
        Arrays.fill(floor.getCharArray(), grass);
    }

    public Metadatable getMetaData() {
        return metaData;
    }

    @Override
    public FaweQueue getQueue() {
        throw new UnsupportedOperationException("Not supported: Queue is not backed by a real world");
    }

    @Override
    public Vector getOrigin() {
        return new BlockVector(chunkOffset.getBlockX() << 4, 0, chunkOffset.getBlockZ() << 4);
    }

    public boolean hasPacketViewer() {
        return player != null;
    }

    public void setPacketViewer(final FawePlayer player) {
        this.player = player;
        if (player != null) {
            FaweLocation pos = player.getLocation();
            this.chunkOffset = new Vector2D(1 + (pos.x >> 4), 1 + (pos.z >> 4));
        }
    }

    public FawePlayer getOwner() {
        return player;
    }

    private char[][][] getChunkArray(final int x, final int z) {
        char[][][][][] blocksData = blocks.get();
        if (blocksData == null) return null;
        char[][][][] arr = blocksData[z];
        return arr != null ? arr[x] : null;
    }

    public void setImageViewer(final ImageViewer viewer) {
        this.viewer = viewer;
    }

    public ImageViewer getImageViewer() {
        return viewer;
    }

    @Override
    public void update() {
        if (viewer != null) {
            viewer.view(this);
        }
        if (chunkOffset != null && player != null) {
            FaweQueue packetQueue = SetQueue.IMP.getNewQueue(player.getWorld(), true, false);

            if (!packetQueue.supports(Capability.CHUNK_PACKETS)) {
                return;
            }

            int lenCX = (getWidth() + 15) >> 4;
            int lenCZ = (getLength() + 15) >> 4;

            int OX = chunkOffset.getBlockX();
            int OZ = chunkOffset.getBlockZ();

            FaweLocation position = player.getLocation();
            int pcx = (position.x >> 4) - OX;
            int pcz = (position.z >> 4) - OZ;

            int scx = Math.max(0, pcx - 15);
            int scz = Math.max(0, pcz - 15);
            int ecx = Math.min(lenCX - 1, pcx + 15);
            int ecz = Math.min(lenCZ - 1, pcz + 15);

            MCAChunk chunk = new MCAChunk(this, 0, 0);
            for (int cz = scz; cz <= ecz; cz++) {
                for (int cx = scx; cx <= ecx; cx++) {
                    final int finalCX = cx;
                    final int finalCZ = cz;
                    TaskManager.IMP.getPublicForkJoinPool().submit(() -> {
                        try {
                            FaweChunk toSend = getSnapshot(finalCX, finalCZ);
                            toSend.setLoc(HeightMapMCAGenerator.this, finalCX + OX, finalCZ + OZ);
                            packetQueue.sendChunkUpdate(toSend, player);
                        } catch (final Throwable e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        }
    }

    public TextureUtil getRawTextureUtil() {
        if (textureUtil == null) {
            textureUtil = Fawe.get().getTextureUtil();
        }
        return this.textureUtil;
    }

    public TextureUtil getTextureUtil() {
        if (textureUtil == null) {
            textureUtil = Fawe.get().getTextureUtil();
        }
        try {
            if (primtives.randomVariation) {
                return new RandomTextureUtil(textureUtil);
            } else if (textureUtil instanceof CachedTextureUtil) {
                return textureUtil;
            } else {
                return new CachedTextureUtil(textureUtil);
            }
        } catch (final FileNotFoundException neverHappens) {
            neverHappens.printStackTrace();
            return null;
        }
    }

    public void setBedrockId(final int bedrockId) {
        this.primtives.bedrockId = (byte) bedrockId;
    }

    public void setFloorThickness(final int floorThickness) {
        this.primtives.floorThickness = floorThickness;
    }

    public void setWorldThickness(final int height) {
        this.primtives.worldThickness = height;
    }

    public void setWaterHeight(final int waterHeight) {
        this.primtives.waterHeight = waterHeight;
    }

    public void setWaterId(final int waterId) {
        this.primtives.waterId = (byte) waterId;
    }

    public void setTextureRandomVariation(final boolean randomVariation) {
        this.primtives.randomVariation = randomVariation;
    }

    public boolean getTextureRandomVariation() {
        return this.primtives.randomVariation;
    }

    public void setTextureUtil(final TextureUtil textureUtil) {
        this.textureUtil = textureUtil;
    }

    public void smooth(final BufferedImage img, final boolean white, final int radius, final int iterations) {
        smooth(img, null, white, radius, iterations);
    }

    public void smooth(final Mask mask, final int radius, final int iterations) {
        smooth(null, mask, false, radius, iterations);
    }

    public void smooth(final Vector2D min, final Vector2D max, final int radius, final int iterations) {
        char snowLayer = 78 << 4;
        char snowBlock = 80;

        char[] floor = this.floor.get();
        byte[] heights = this.heights.get();

        int width = getWidth();
        int length = getLength();

        int minX = min.getBlockX();
        int minZ = min.getBlockZ();

        int maxX = max.getBlockX();
        int maxZ = max.getBlockZ();

        int tableWidth = (maxX - minX + 1);
        int tableLength = (maxZ - minZ + 1);
        int smoothArea = tableWidth * tableLength;

        long[] copy = new long[smoothArea];
        char[] layers = new char[smoothArea];

        SummedAreaTable table = new SummedAreaTable(copy, layers, tableWidth, radius);
        for (int j = 0; j < iterations; j++) {

            { // Copy to table
                int localIndex = 0;
                int zIndex = (minZ * getWidth());
                for (int z = minZ; z <= maxZ; z++, zIndex += getWidth()) {
                    int index = zIndex + minX;
                    for (int x = minX; x <= maxX; x++, index++, localIndex++) {
                        char combined = floor[index];
                        int id = combined >> 4;
                        if (id == 78) {
                            layers[localIndex] = (char) (((heights[index] & 0xFF) << 3) + (floor[index] & 0x7) - 7);
                        } else {
                            layers[localIndex] = (char) (((heights[index] & 0xFF) << 3));
                        }
                    }
                }
            }
            // Process table
            table.processSummedAreaTable();
            { // Copy from table
                int localIndex = 0;
                int zIndex = (minZ * getWidth());
                for (int z = minZ, localZ = 0; z <= maxZ; z++, localZ++, zIndex += getWidth()) {
                    int index = zIndex + minX;
                    for (int x = minX, localX = 0; x <= maxX; x++, localX++, index++, localIndex++) {
                        int y = heights[index] & 0xFF;
                        int newHeight = table.average(localX, localZ, localIndex);
                        setLayerHeight(index, newHeight);
                    }
                }
            }
        }
    }

    private final void setLayerHeight(final int index, final int height) {
        int blockHeight = (height) >> 3;
        int layerHeight = (height) & 0x7;
        setLayerHeight(index, blockHeight, layerHeight);
    }

    private final void setLayerHeight(final int index, final int blockHeight, final int layerHeight) {
        int floorId = floor.get()[index] >> 4;
        if (floorId == 78 || floorId == 80) {
            if (layerHeight != 0) {
                this.heights.setByte(index, (byte) (blockHeight + 1));
                this.floor.setChar(index, (char) (1248 + layerHeight));
            } else {
                this.heights.setByte(index, (byte) (blockHeight));
                this.floor.setChar(index, (char) (1280));
            }
        } else {
            this.heights.setByte(index, (byte) (blockHeight));
        }
    }

    private final void setLayerHeightRaw(final int index, final int height) {
        int blockHeight = (height) >> 3;
        int layerHeight = (height) & 0x7;
        setLayerHeightRaw(index, blockHeight, layerHeight);
    }

    private final void setLayerHeightRaw(final int index, final int blockHeight, final int layerHeight) {
        int floorId = floor.get()[index] >> 4;
        if (floorId == 78 || floorId == 80) {
            if (layerHeight != 0) {
                this.heights.getByteArray()[index] = (byte) (blockHeight + 1);
                this.floor.getCharArray()[index] = (char) (1248 + layerHeight);
            } else {
                this.heights.getByteArray()[index] = (byte) (blockHeight);
                this.floor.getCharArray()[index] = (char) (1280);
            }
        } else {
            this.heights.getByteArray()[index] = (byte) (blockHeight);
        }
    }

    private void smooth(final BufferedImage img, final Mask mask, final boolean white, final int radius, final int iterations) {
        char snowLayer = 78 << 4;
        char snowBlock = 80;

        char[] floor = this.floor.get();
        byte[] heights = this.heights.get();

        long[] copy = new long[heights.length];
        char[] layers = new char[heights.length];

        this.floor.record(() -> HeightMapMCAGenerator.this.heights.record(() -> {
            int width = getWidth();
            int length = getLength();
            SummedAreaTable table = new SummedAreaTable(copy, layers, width, radius);
            for (int j = 0; j < iterations; j++) {
                for (int i = 0; i < heights.length; i++) {
                    char combined = floor[i];
                    int id = combined >> 4;
                    if (id == 78) {
                        layers[i] = (char) (((heights[i] & 0xFF) << 3) + (floor[i] & 0x7) - 7);
                    } else {
                        layers[i] = (char) (((heights[i] & 0xFF) << 3));
                    }
                }
                int index = 0;
                table.processSummedAreaTable();
                if (img != null) {
                    for (int z = 0; z < getLength(); z++) {
                        for (int x = 0; x < getWidth(); x++, index++) {
                            int height = img.getRGB(x, z) & 0xFF;
                            if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                                int newHeight = table.average(x, z, index);
                                setLayerHeightRaw(index, newHeight);
                            }
                        }
                    }
                } else if (mask != null) {
                    for (int z = 0; z < getLength(); z++) {
                        mutable.mutZ(z);
                        for (int x = 0; x < getWidth(); x++, index++) {
                            int y = heights[index] & 0xFF;
                            mutable.mutX(x);
                            mutable.mutY(y);
                            if (mask.test(mutable)) {
                                int newHeight = table.average(x, z, index);
                                setLayerHeightRaw(index, newHeight);
                            }
                        }
                    }
                } else {
                    for (int z = 0; z < getLength(); z++) {
                        for (int x = 0; x < getWidth(); x++, index++) {
                            int newHeight = table.average(x, z, index);
                            setLayerHeightRaw(index, newHeight);
                        }
                    }
                }
            }
        }));
    }

    public void setHeight(final BufferedImage img) {
        int index = 0;
        for (int z = 0; z < getLength(); z++) {
            for (int x = 0; x < getWidth(); x++, index++) {
                heights.setByte(index, (byte) (img.getRGB(x, z) >> 8));
            }
        }
    }

    public void addCaves() throws WorldEditException {
        CuboidRegion region = new CuboidRegion(new Vector(0, 0, 0), new Vector(getWidth() -1, 255, getLength() -1));
        addCaves(region);
    }

    @Deprecated
    public void addSchems(final Mask mask, final WorldData worldData, final List<ClipboardHolder> clipboards, final int rarity, final boolean rotate) throws WorldEditException {
        CuboidRegion region = new CuboidRegion(new Vector(0, 0, 0), new Vector(getWidth() - 1, 255, getLength() - 1));
        addSchems(region, mask, worldData, clipboards, rarity, rotate);
    }

    public void addSchems(final BufferedImage img, final Mask mask, final WorldData worldData, final List<ClipboardHolder> clipboards, final int rarity, final int distance, final boolean randomRotate) throws WorldEditException {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        double doubleRarity = rarity / 100d;
        int index = 0;
        AffineTransform identity = new AffineTransform();
        LocalBlockVector2DSet placed = new LocalBlockVector2DSet();
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                int height = img.getRGB(x, z) & 0xFF;
                if (height == 0 || PseudoRandom.random.nextInt(256) > height * doubleRarity) {
                    continue;
                }
                mutable.mutX(x);
                mutable.mutY(y);
                if (!mask.test(mutable)) {
                    continue;
                }
                if (placed.containsRadius(x, z, distance)) {
                    continue;
                }
                placed.add(x, z);
                ClipboardHolder holder = clipboards.get(PseudoRandom.random.random(clipboards.size()));
                if (randomRotate) {
                    int rotate = PseudoRandom.random.random(4) * 90;
                    if (rotate != 0) {
                        holder.setTransform(new AffineTransform().rotateY(PseudoRandom.random.random(4) * 90));
                    } else {
                        holder.setTransform(identity);
                    }
                }
                Clipboard clipboard = holder.getClipboard();
                Schematic schematic = new Schematic(clipboard);
                Transform transform = holder.getTransform();
                if (transform.isIdentity()) {
                    schematic.paste(this, mutable, false);
                } else {
                    schematic.paste(this, worldData, mutable, false, transform);
                }
                if (x + distance < getWidth()) {
                    x += distance;
                    index += distance;
                } else {
                    break;
                }
            }
        }
    }

    public void addSchems(final Mask mask, final WorldData worldData, final List<ClipboardHolder> clipboards, final int rarity, final int distance, final boolean randomRotate) throws WorldEditException {
        int scaledRarity = (256 * rarity) / 100;
        int index = 0;
        AffineTransform identity = new AffineTransform();
        LocalBlockVector2DSet placed = new LocalBlockVector2DSet();
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                if (PseudoRandom.random.nextInt(256) > scaledRarity) {
                    continue;
                }
                mutable.mutX(x);
                mutable.mutY(y);
                if (!mask.test(mutable)) {
                    continue;
                }
                if (placed.containsRadius(x, z, distance)) {
                    continue;
                }
                mutable.mutY(y + 1);
                placed.add(x, z);
                ClipboardHolder holder = clipboards.get(PseudoRandom.random.random(clipboards.size()));
                if (randomRotate) {
                    int rotate = PseudoRandom.random.random(4) * 90;
                    if (rotate != 0) {
                        holder.setTransform(new AffineTransform().rotateY(PseudoRandom.random.random(4) * 90));
                    } else {
                        holder.setTransform(identity);
                    }
                }
                Clipboard clipboard = holder.getClipboard();
                Schematic schematic = new Schematic(clipboard);
                Transform transform = holder.getTransform();
                if (transform.isIdentity()) {
                    schematic.paste(this, mutable, false);
                } else {
                    schematic.paste(this, worldData, mutable, false, transform);
                }
                if (x + distance < getWidth()) {
                    x += distance;
                    index += distance;
                } else {
                    break;
                }
            }
        }
    }

    public void addOre(final Mask mask, final Pattern material, final int size, final int frequency, final int rarity, final int minY, final int maxY) throws WorldEditException {
        CuboidRegion region = new CuboidRegion(new Vector(0, 0, 0), new Vector(getWidth() - 1, 255, getLength() - 1));
        addOre(region, mask, material, size, frequency, rarity, minY, maxY);
    }

    public void addDefaultOres(final Mask mask) throws WorldEditException {
        addOres(new CuboidRegion(new Vector(0, 0, 0), new Vector(getWidth() - 1, 255, getLength() - 1)), mask);
    }

    @Override
    public Vector getMinimumPoint() {
        return new Vector(0, 0, 0);
    }

    @Override
    public FawePlayer getPlayer() {
        return player;
    }

    @Override
    public Vector getMaximumPoint() {
        return new Vector(getWidth() - 1, 255, getLength() - 1);
    }

    @Override
    public boolean setBlock(final Vector position, final BaseBlock block) throws WorldEditException {
        return setBlock(position.getBlockX(), position.getBlockY(), position.getBlockZ(), block);
    }

    @Override
    public boolean setBiome(final Vector2D position, final BaseBiome biome) {
        return this.setBiome(position.getBlockX(), position.getBlockZ(), biome);
    }

    private boolean setBlock(final int x, int y, final int z, char combined) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) return false;
        int height = heights.getByte(index) & 0xFF;
        switch (y - height) {
            case 0:
                floor.setChar(index, combined);
                return true;
            case 1:
                char mainId = main.getChar(index);
                char floorId = floor.getChar(index);
                floor.setChar(index, combined);

                byte currentHeight = heights.getByte(index);
                currentHeight++;
                heights.setByte(index, currentHeight);
                if (mainId == floorId) return true;
                y--;
                combined = floorId;
            default:
                try {
                    short chunkX = (short) (x >> 4);
                    short chunkZ = (short) (z >> 4);
                    blocks.set(x, y, z, combined);
                    return true;
                } catch (final IndexOutOfBoundsException ignore) {
                    return false;
                }
        }
    }

    @Override
    public boolean setBlock(final int x, final int y, final int z, final int id, final int data) {
        return this.setBlock(x, y, z, (char) FaweCache.getCombined(id, data));
    }

    @Override
    public void setTile(final int x, final int y, final int z, final CompoundTag tag) {
        // Not implemented
    }

    @Override
    public void setEntity(final int x, final int y, final int z, final CompoundTag tag) {
        // Not implemented
    }

    @Override
    public void removeEntity(final int x, final int y, final int z, final UUID uuid) {
        // Not implemented
    }

    @Override
    public boolean setBiome(final int x, final int z, final BaseBiome biome) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) return false;
        biomes.setByte(index, (byte) biome.getId());
        return true;
    }

    @Override
    public FaweChunk getFaweChunk(final int chunkX, final int chunkZ) {
        return new SimpleCharFaweChunk(this, chunkX, chunkZ);
    }

    @Override
    public FaweChunk getSnapshot(final int chunkX, final int chunkZ) {
        return getSnapshot(null, chunkX, chunkZ);
    }

    private FaweChunk getSnapshot(final MCAChunk chunk, final int chunkX, final int chunkZ) {
        return new LazyFaweChunk<MCAChunk>(this, chunkX, chunkZ) {
            @Override
            public MCAChunk getChunk() {
                MCAChunk tmp = chunk;
                if (tmp == null) {
                    tmp = new MCAChunk(HeightMapMCAGenerator.this, chunkX, chunkZ);
                } else {
                    tmp.setLoc(HeightMapMCAGenerator.this, chunkX, chunkZ);
                }
                int cbx = chunkX << 4;
                int cbz = chunkZ << 4;
                int csx = Math.max(0, cbx);
                int csz = Math.max(0, cbz);
                int cex = Math.min(getWidth(), cbx + 15);
                int cez = Math.min(getLength(), cbz + 15);
                write(tmp, csx, cex, csz, cez);
                tmp.setLoc(HeightMapMCAGenerator.this, getX(), getZ());
                return tmp;
            }

            @Override
            public void addToQueue() {
                MCAChunk cached = getCachedChunk();
                if (cached != null) setChunk(cached);
            }
        };
    }

    @Override
    public Collection<FaweChunk> getFaweChunks() {
        return Collections.emptyList();
    }

    @Override
    public void setChunk(final FaweChunk chunk) {
        char[][] src = chunk.getCombinedIdArrays();
        for (int i = 0; i < src.length; i++) {
            if (src[i] != null) {
                int bx = chunk.getX() << 4;
                int bz = chunk.getZ() << 4;
                int by = i << 4;
                for (int layer = i; layer < src.length; layer++) {
                    char[] srcLayer = src[layer];
                    if (srcLayer != null) {
                        int index = 0;
                        for (int y = 0; y < 16; y++) {
                            int yy = by + y;
                            for (int z = 0; z < 16; z++) {
                                int zz = bz + z;
                                for (int x = 0; x < 16; x++, index++) {
                                    char combined = srcLayer[index];
                                    if (combined != 0) {
                                        setBlock(bx + x, yy, zz, combined);
                                    }
                                }
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    @Override
    public File getSaveFolder() {
        return getFolder();
    }

    @Override
    public boolean regenerateChunk(final int x, final int z, @Nullable final BaseBiome biome, @Nullable final Long seed) {
        // Unsupported
        return false;
    }

    @Override
    public void sendBlockUpdate(final FaweChunk chunk, final FawePlayer... players) {

    }

    @Override
    public void flush(final int time) {
        next(0, time);
    }

    @Override
    public boolean next(final int amount, final long time) {
        EditSession curES = editSession;
        if (curES != null && isModified()) {
            try {
                update();
                FawePlayer esPlayer = curES.getPlayer();
                UUID uuid = esPlayer != null ? esPlayer.getUUID() : EditSession.CONSOLE;
                try {
                    curES.setRawChangeSet(new CFIChangeSet(this, uuid));
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            } catch (final Throwable e) {
                e.printStackTrace();
            }
        }
        clear();
        return false;
    }

    @Override
    public void sendChunk(final FaweChunk chunk) {
    }

    @Override
    public void sendChunk(final int x, final int z, final int bitMask) {
    }

    @Override
    public void clear() {
        this.editSession = null;
    }

    @Override
    public void close(final boolean update) {
        clear();
        if (chunkOffset != null && player != null && update) {
            FaweQueue packetQueue = SetQueue.IMP.getNewQueue(player.getWorld(), true, false);

            int lenCX = (getWidth() + 15) >> 4;
            int lenCZ = (getLength() + 15) >> 4;

            int OX = chunkOffset.getBlockX();
            int OZ = chunkOffset.getBlockZ();

            FaweLocation position = player.getLocation();
            int pcx = (position.x >> 4) - OX;
            int pcz = (position.z >> 4) - OZ;

            int scx = Math.max(0, pcx - 10);
            int scz = Math.max(0, pcz - 10);
            int ecx = Math.min(lenCX - 1, pcx + 10);
            int ecz = Math.min(lenCZ - 1, pcz + 10);

            for (int cz = scz; cz <= ecz; cz++) {
                for (int cx = scx; cx <= ecx; cx++) {
                    packetQueue.sendChunk(cx + OX, cz + OZ, 0);
                }
            }
        }
        if (player != null) {
            player.deleteMeta("CFISettings");
            LocalSession session = player.getSession();
            session.clearHistory();
        }
        player = null;
        chunkOffset = null;
    }

    @Override
    public void addNotifyTask(final int x, final int z, final Runnable runnable) {
        if (runnable != null) runnable.run();
    }

    @Override
    public int getBiomeId(final int x, final int z) throws FaweException.FaweChunkLoadException {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) index = Math.floorMod(index, getArea());
        return biomes.getByte(index) & 0xFF;
    }

    @Override
    public int getCombinedId4Data(final int x, final int y, final int z) throws FaweException.FaweChunkLoadException {
        int index = z * getWidth() + x;
        if (y < 0) return 0;
        if (index < 0 || index >= getArea() || x < 0 || x >= getWidth()) return 0;
        int height = heights.getByte(index) & 0xFF;
        if (y > height) {
            if (y == height + 1) {
                return overlay != null ? overlay.getChar(index) : 0;
            }
            if (blocks != null) {
                short chunkX = (short) (x >> 4);
                short chunkZ = (short) (z >> 4);
                char[][][] map = getChunkArray(chunkX, chunkZ);
                if (map != null) {
                    char combined = get(map, x, y, z);
                    if (combined != 0) {
                        return combined;
                    }
                }
            }
            if (y <= primtives.waterHeight) {
                return primtives.waterId << 4;
            }
            return 0;
        } else if (y == height) {
            return floor.getChar(index);
        } else {
            if (blocks != null) {
                short chunkX = (short) (x >> 4);
                short chunkZ = (short) (z >> 4);
                char[][][] map = getChunkArray(chunkX, chunkZ);
                if (map != null) {
                    char combined = get(map, x, y, z);
                    if (combined != 0) {
                        return combined;
                    }
                }
            }
            return main.getChar(index);
        }
    }

    @Override
    public int getCombinedId4Data(final int x, final int y, final int z, final int def) {
        return getCombinedId4Data(x, y, z);
    }

    @Override
    public int getCachedCombinedId4Data(final int x, final int y, final int z) throws FaweException.FaweChunkLoadException {
        return getCombinedId4Data(x, y, z);
    }

    @Override
    public boolean hasSky() {
        return true;
    }

    @Override
    public int getSkyLight(final int x, final int y, final int z) {
        return getNearestSurfaceTerrainBlock(x, z, y, 0, 255) < y ? 15 : 0;
    }

    @Override
    public int getEmmittedLight(final int x, final int y, final int z) {
        return 0;
    }

    @Override
    public CompoundTag getTileEntity(final int x, final int y, final int z) throws FaweException.FaweChunkLoadException {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean setBlock(final int x, final int y, final int z, final BaseBlock block) throws WorldEditException {
        return this.setBlock(x, y, z, (char) block.getCombined());
    }

    @Override
    public BaseBiome getBiome(final Vector2D position) {
        return FaweCache.CACHE_BIOME[getBiomeId(position.getBlockX(), position.getBlockZ())];
    }

    @Override
    public BaseBlock getBlock(final Vector position) {
        return getLazyBlock(position);
    }

    public BaseBlock getFloor(final int x, final int z) {
        int index = z * getWidth() + x;
        return FaweCache.CACHE_BLOCK[floor.getChar(index)];
    }

    public int getHeight(final int x, final int z) {
        int index = z * getWidth() + x;
        return heights.getByte(index) & 0xFF;
    }

    public int getHeight(final int index) {
        return heights.getByte(index) & 0xFF;
    }

    public void setFloor(final int x, final int z, final BaseBlock block) {
        int index = z * getWidth() + x;
        floor.setChar(index, (char) block.getCombined());
    }

    @Override
    public BaseBlock getLazyBlock(final Vector position) {
        return getLazyBlock(position.getBlockX(), position.getBlockY(), position.getBlockZ());
    }

    @Override
    public BaseBlock getLazyBlock(final int x, final int y, final int z) {
        return FaweCache.CACHE_BLOCK[getCombinedId4Data(x, y, z)];
    }

    @Override
    public int getNearestSurfaceLayer(final int x, final int z, final int y, final int minY, final int maxY) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) index = Math.floorMod(index, getArea());
        return ((heights.getByte(index) & 0xFF) << 3) + (floor.getChar(index) & 0xFF) + 1;
    }

    @Override
    public int getNearestSurfaceTerrainBlock(final int x, final int z, final int y, final int minY, final int maxY) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) index = Math.floorMod(index, getArea());
        return heights.getByte(index) & 0xFF;
    }

    @Override
    public int getNearestSurfaceTerrainBlock(final int x, final int z, final int y, final int minY, final int maxY, final int failedMin, final int failedMax) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) index = Math.floorMod(index, getArea());
        return heights.getByte(index) & 0xFF;
    }

    public void setBiome(final BufferedImage img, final byte biome, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        biomes.record(new Runnable() {
            @Override
            public void run() {
                byte[] biomeArr = biomes.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int height = img.getRGB(x, z) & 0xFF;
                        if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                            biomeArr[index] = biome;
                        }
                    }
                }
            }
        });
    }

    public BufferedImage draw() {
        return new HeightMapMCADrawer(this).draw();
    }

    public void setBiomePriority(final int value) {
        this.primtives.biomePriority = ((value * 65536) / 100) - 32768;
    }

    public int getBiomePriority() {
        return ((primtives.biomePriority + 32768) * 100) / 65536;
    }

    public void setBlockAndBiomeColor(final BufferedImage img, final Mask mask, final BufferedImage imgMask, final boolean whiteOnly) {
        if (mask == null && imgMask == null) {
            setBlockAndBiomeColor(img);
            return;
        }
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        TextureUtil textureUtil = getTextureUtil();

        int widthIndex = img.getWidth() - 1;
        int heightIndex = img.getHeight() - 1;
        int maxIndex = getArea() - 1;

        biomes.record(() -> floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();
            byte[] biomesArr = biomes.get();

            int index = 0;
            int[] buffer = new int[2];
            for (int z = 0; z < img.getHeight(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < img.getWidth(); x++, index++) {
                    if (mask != null) {
                        mutable.mutX(z);
                        mutable.mutY(heights.getByte(index) & 0xFF);
                        if (!mask.test(mutable)) continue;
                    }
                    if (imgMask != null) {
                        int height = imgMask.getRGB(x, z) & 0xFF;
                        if (height != 255 && (height <= 0 || !whiteOnly || PseudoRandom.random.nextInt(256) > height)) continue;
                    }
                    int color = img.getRGB(x, z);
                    if (textureUtil.getIsBlockCloserThanBiome(buffer, color, primtives.biomePriority)) {
                        char combined = (char) buffer[0];
                        mainArr[index] = combined;
                        floorArr[index] = combined;
                    }
                    biomesArr[index] = (byte) buffer[1];
                }
            }
        })));
    }

    public void setBlockAndBiomeColor(final BufferedImage img) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        TextureUtil textureUtil = getTextureUtil();
        int widthIndex = img.getWidth() - 1;
        int heightIndex = img.getHeight() - 1;
        int maxIndex = getArea() - 1;

        biomes.record(() -> floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();
            byte[] biomesArr = biomes.get();

            int[] buffer = new int[2];
            int index = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                boolean yBiome = y > 0 && y < heightIndex;
                for (int x = 0; x < img.getWidth(); x++, index++) {
                    int color = img.getRGB(x, y);
                    if (textureUtil.getIsBlockCloserThanBiome(buffer, color, primtives.biomePriority)) {
                        char combined = (char) buffer[0];
                        mainArr[index] = combined;
                        floorArr[index] = combined;
                    }
                    biomesArr[index] = (byte) buffer[1];
                }
            }
        })));
    }

    public void setBiomeColor(final BufferedImage img) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        TextureUtil textureUtil = getTextureUtil();

        biomes.record(new Runnable() {
            @Override
            public void run() {
                byte[] biomesArr = biomes.get();
                int index = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int color = img.getRGB(x, y);
                        TextureUtil.BiomeColor biome = textureUtil.getNearestBiome(color);
                        if (biome != null) {
                            biomesArr[index] = (byte) biome.id;
                        }
                        index++;
                    }
                }
            }
        });
    }

    public void setColor(final BufferedImage img, final BufferedImage mask, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        if (mask.getWidth() != getWidth() || mask.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        primtives.modifiedMain = true;
        TextureUtil textureUtil = getTextureUtil();

        floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();

            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++, index++) {
                    int height = mask.getRGB(x, z) & 0xFF;
                    if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                        int color = img.getRGB(x, z);
                        BaseBlock block = textureUtil.getNearestBlock(color);
                        if (block != null) {
                            char combined = (char) block.getCombined();
                            mainArr[index] = combined;
                            floorArr[index] = combined;
                        }
                    }
                }
            }
        }));
    }

    public void setColor(final BufferedImage img, final Mask mask) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        primtives.modifiedMain = true;
        TextureUtil textureUtil = getTextureUtil();


        floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();

            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < getWidth(); x++, index++) {
                    mutable.mutX(x);
                    mutable.mutY(heights.getByte(index) & 0xFF);
                    if (mask.test(mutable)) {
                        int color = img.getRGB(x, z);
                        BaseBlock block = textureUtil.getNearestBlock(color);
                        if (block != null) {
                            char combined = (char) block.getCombined();
                            mainArr[index] = combined;
                            floorArr[index] = combined;
                        }
                    }
                }
            }
        }));
    }

    public void setColor(final BufferedImage img) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        primtives.modifiedMain = true;
        TextureUtil textureUtil = getTextureUtil();

        floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();

            int index = 0;
            for (int z = 0; z < img.getHeight(); z++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int color = img.getRGB(x, z);
                    BaseBlock block = textureUtil.getNearestBlock(color);
                    if (block != null) {
                        char combined = (char) block.getCombined();
                        mainArr[index] = combined;
                        floorArr[index] = combined;
                    }
                    index++;
                }
            }
        }));
    }

    public void setColorWithGlass(final BufferedImage img) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        TextureUtil textureUtil = getTextureUtil();

        floor.record(() -> main.record(() -> {
            char[] mainArr = main.get();
            char[] floorArr = floor.get();

            int index = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int color = img.getRGB(x, y);
                    char[] layer = textureUtil.getNearestLayer(color);
                    if (layer != null) {
                        floorArr[index] = layer[0];
                        mainArr[index] = layer[1];
                    }
                    index++;
                }
            }
        }));
    }

    public void setBiome(final Mask mask, final byte biome) {
        int index = 0;
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                mutable.mutX(x);
                mutable.mutY(y);
                if (mask.test(mutable)) {
                    biomes.setByte(index, biome);
                }
            }
        }
    }

    public void setOverlay(final BufferedImage img, final Pattern pattern, final boolean white) {
        if (pattern instanceof BaseBlock) {
            setOverlay(img, (char) ((BaseBlock) pattern).getCombined(), white);
        } else {
            if (img.getWidth() != getWidth() || img.getHeight() != getLength())
                throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
            if (overlay == null) {
                overlay = new DifferentialArray<>(new char[getArea()]);
            }

            overlay.record(() -> {
                char[] overlayArr = overlay.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int height = img.getRGB(x, z) & 0xFF;
                        if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                            mutable.mutX(x);
                            mutable.mutY(height);
                            overlayArr[index] = (char) pattern.apply(mutable).getCombined();
                        }
                    }
                }
            });

        }
    }

    public void setMain(final BufferedImage img, final Pattern pattern, final boolean white) {
        if (pattern instanceof BaseBlock) {
            setMain(img, (char) ((BaseBlock) pattern).getCombined(), white);
        } else {
            if (img.getWidth() != getWidth() || img.getHeight() != getLength())
                throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
            primtives.modifiedMain = true;

            main.record(() -> {
                char[] mainArr = main.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int height = img.getRGB(x, z) & 0xFF;
                        if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                            mutable.mutX(x);
                            mutable.mutY(height);
                            mainArr[index] = (char) pattern.apply(mutable).getCombined();
                        }
                    }
                }
            });
        }
    }

    public void setFloor(final BufferedImage img, final Pattern pattern, final boolean white) {
        if (pattern instanceof BaseBlock) {
            setFloor(img, (char) ((BaseBlock) pattern).getCombined(), white);
        } else {
            if (img.getWidth() != getWidth() || img.getHeight() != getLength())
                throw new IllegalArgumentException("Input image dimensions do not match the current height map!");

            floor.record(() -> {
                char[] floorArr = floor.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int height = img.getRGB(x, z) & 0xFF;
                        if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                            mutable.mutX(x);
                            mutable.mutY(height);
                            floorArr[index] = (char) pattern.apply(mutable).getCombined();
                        }
                    }
                }
            });
        }
    }

    public void setColumn(final BufferedImage img, final Pattern pattern, final boolean white) {
        if (pattern instanceof BaseBlock) {
            setColumn(img, (char) ((BaseBlock) pattern).getCombined(), white);
        } else {
            if (img.getWidth() != getWidth() || img.getHeight() != getLength())
                throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
            primtives.modifiedMain = true;

            main.record(() -> floor.record(() -> {
                char[] floorArr = floor.get();
                char[] mainArr = main.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int height = img.getRGB(x, z) & 0xFF;
                        if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                            mutable.mutX(x);
                            mutable.mutY(height);
                            char combined = (char) pattern.apply(mutable).getCombined();
                            mainArr[index] = combined;
                            floorArr[index] = combined;
                        }
                    }
                }
            }));
        }
    }

    public void setOverlay(final Mask mask, final Pattern pattern) {
        if (pattern instanceof BaseBlock) {
            setOverlay(mask, (char) ((BaseBlock) pattern).getCombined());
        } else {
            int index = 0;
            if (overlay == null) overlay = new DifferentialArray<>(new char[getArea()]);
            for (int z = 0; z < getLength(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < getWidth(); x++, index++) {
                    int y = heights.getByte(index) & 0xFF;
                    mutable.mutX(x);
                    mutable.mutY(y);
                    if (mask.test(mutable)) {
                        overlay.setChar(index, (char) pattern.apply(mutable).getCombined());
                    }
                }
            }
        }
    }

    public void setFloor(final Mask mask, final Pattern pattern) {
        if (pattern instanceof BaseBlock) {
            setFloor(mask, (char) ((BaseBlock) pattern).getCombined());
        } else {
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < getWidth(); x++, index++) {
                    int y = heights.getByte(index) & 0xFF;
                    mutable.mutX(x);
                    mutable.mutY(y);
                    if (mask.test(mutable)) {
                        floor.setChar(index, (char) pattern.apply(mutable).getCombined());
                    }
                }
            }
        }
    }

    public void setMain(final Mask mask, final Pattern pattern) {
        if (pattern instanceof BaseBlock) {
            setMain(mask, (char) ((BaseBlock) pattern).getCombined());
        } else {
            primtives.modifiedMain = true;
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < getWidth(); x++, index++) {
                    int y = heights.getByte(index) & 0xFF;
                    mutable.mutX(x);
                    mutable.mutY(y);
                    if (mask.test(mutable)) {
                        main.setChar(index, (char) pattern.apply(mutable).getCombined());
                    }
                }
            }
        }
    }

    public void setColumn(final Mask mask, final Pattern pattern) {
        if (pattern instanceof BaseBlock) {
            setColumn(mask, (char) ((BaseBlock) pattern).getCombined());
        } else {
            primtives.modifiedMain = true;
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                mutable.mutZ(z);
                for (int x = 0; x < getWidth(); x++, index++) {
                    int y = heights.getByte(index) & 0xFF;
                    mutable.mutX(x);
                    mutable.mutY(y);
                    if (mask.test(mutable)) {
                        char combined = (char) pattern.apply(mutable).getCombined();
                        floor.setChar(index, combined);
                        main.setChar(index, combined);
                    }
                }
            }
        }
    }

    public void setBiome(final int biome) {
        biomes.record(() -> Arrays.fill(biomes.get(), (byte) biome));
    }

    public void setFloor(final Pattern value) {
        if (value instanceof BaseBlock) {
            setFloor(((BaseBlock) value).getCombined());
        } else {
            floor.record(() -> {
                char[] floorArr = floor.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int y = heights.getByte(index) & 0xFF;
                        mutable.mutX(x);
                        mutable.mutY(y);
                        floorArr[index] = (char) value.apply(mutable).getCombined();
                    }
                }
            });
        }
    }

    public void setColumn(final Pattern value) {
        if (value instanceof BaseBlock) {
            setColumn(((BaseBlock) value).getCombined());
        } else {
            main.record(() -> floor.record(() -> {
                char[] floorArr = floor.get();
                char[] mainArr = main.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int y = heights.getByte(index) & 0xFF;
                        mutable.mutX(x);
                        mutable.mutY(y);
                        char combined = (char) value.apply(mutable).getCombined();
                        mainArr[index] = combined;
                        floorArr[index] = combined;
                    }
                }
            }));
        }
    }

    public void setMain(final Pattern value) {
        if (value instanceof BaseBlock) {
            setMain(((BaseBlock) value).getCombined());
        } else {
            main.record(() -> {
                char[] mainArr = main.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int y = heights.getByte(index) & 0xFF;
                        mutable.mutX(x);
                        mutable.mutY(y);
                        mainArr[index] = (char) value.apply(mutable).getCombined();
                    }
                }
            });
        }
    }

    public void setOverlay(final Pattern value) {
        if (overlay == null) overlay = new DifferentialArray<>(new char[getArea()]);
        if (value instanceof BaseBlock) {
            setOverlay(((BaseBlock) value).getCombined());
        } else {
            overlay.record(() -> {
                char[] overlayArr = overlay.get();
                int index = 0;
                for (int z = 0; z < getLength(); z++) {
                    mutable.mutZ(z);
                    for (int x = 0; x < getWidth(); x++, index++) {
                        int y = heights.getByte(index) & 0xFF;
                        mutable.mutX(x);
                        mutable.mutY(y);
                        overlayArr[index] = (char) value.apply(mutable).getCombined();
                    }
                }
            });
        }
    }

    public void setHeight(final int x, final int z, final int height) {
        int index = z * getWidth() + x;
        if (index < 0 || index >= getArea()) return;
        heights.setByte(index, (byte) height);
    }

    public void setHeight(final int index, final int height) {
        heights.setByte(index, (byte) height);
    }

    public void setHeights(final int value) {
        heights.record(() -> {
            Arrays.fill(heights.get(), (byte) value);
        });
    }

    @Override
    public boolean shouldWrite(final int chunkX, final int chunkZ) {
        return true;
    }

    @Override
    public MCAChunk write(final MCAChunk chunk, final int csx, final int cex, final int csz, final int cez) {
        byte[] heights = this.heights.get();
        byte[] biomes = this.biomes.get();
        char[] main = this.main.get();
        char[] floor = this.floor.get();
        char[] overlay = this.overlay != null ? this.overlay.get() : null;
        try {
            int[] indexes = indexStore.get();
            for (int i = 0; i < chunk.ids.length; i++) {
                byte[] idsArray = chunk.ids[i];
                if (idsArray != null) {
                    Arrays.fill(idsArray, (byte) 0);
                    Arrays.fill(chunk.data[i], (byte) 0);
                }
            }
            int index;
            int maxY = 0;
            int minY = Integer.MAX_VALUE;
            int[] heightMap = chunk.getHeightMapArray();
            int globalIndex;
            for (int z = csz; z <= cez; z++) {
                globalIndex = z * getWidth() + csx;
                index = (z & 15) << 4;
                for (int x = csx; x <= cex; x++, index++, globalIndex++) {
                    indexes[index] = globalIndex;
                    int height = heights[globalIndex] & 0xFF;
                    heightMap[index] = height;
                    maxY = Math.max(maxY, height);
                    minY = Math.min(minY, height);
                }
            }
            boolean hasOverlay = this.overlay != null;
            if (hasOverlay) {
                maxY++;
            }
            int maxLayer = maxY >> 4;
            int fillLayers = Math.max(0, (minY - 1)) >> 4;
            for (int layer = 0; layer <= maxLayer; layer++) {
                if (chunk.ids[layer] == null) {
                    chunk.ids[layer] = new byte[4096];
                    chunk.data[layer] = new byte[2048];
                    chunk.skyLight[layer] = new byte[2048];
                    chunk.blockLight[layer] = new byte[2048];
                }
            }
            if (primtives.waterHeight != 0) {
                maxY = Math.max(maxY, primtives.waterHeight);
                int maxWaterLayer = ((primtives.waterHeight + 15) >> 4);
                for (int layer = 0; layer < maxWaterLayer; layer++) {
                    boolean fillAll = (layer << 4) + 15 <= primtives.waterHeight;
                    byte[] ids = chunk.ids[layer];
                    if (ids == null) {
                        chunk.ids[layer] = ids = new byte[4096];
                        chunk.data[layer] = new byte[2048];
                        chunk.skyLight[layer] = new byte[2048];
                        chunk.blockLight[layer] = new byte[2048];
                        Arrays.fill(chunk.skyLight[layer], (byte) 255);
                    }
                    if (fillAll) {
                        Arrays.fill(ids, primtives.waterId);
                    } else {
                        int maxIndex = (primtives.waterHeight & 15) << 8;
                        Arrays.fill(ids, 0, maxIndex, primtives.waterId);
                    }
                }
            }

            if (primtives.modifiedMain) { // If the main block is modified, we can't short circuit this
                for (int layer = 0; layer < fillLayers; layer++) {
                    byte[] layerIds = chunk.ids[layer];
                    byte[] layerDatas = chunk.data[layer];
                    for (int z = csz; z <= cez; z++) {
                        index = (z & 15) << 4;
                        for (int x = csx; x <= cex; x++, index++) {
                            globalIndex = indexes[index];
                            char mainCombined = main[globalIndex];
                            byte id = (byte) FaweCache.getId(mainCombined);
                            int data = FaweCache.getData(mainCombined);
                            if (data != 0) {
                                for (int y = 0; y < 16; y++) {
                                    int mainIndex = index + (y << 8);
                                    chunk.setNibble(mainIndex, layerDatas, data);
                                }
                            }
                            for (int y = 0; y < 16; y++) {
                                layerIds[index + (y << 8)] = id;
                            }
                        }
                    }
                }
            } else {
                for (int layer = 0; layer < fillLayers; layer++) {
                    Arrays.fill(chunk.ids[layer], (byte) 1);
                }
            }

            for (int layer = fillLayers; layer <= maxLayer; layer++) {
                Arrays.fill(chunk.skyLight[layer], (byte) 255);
                byte[] layerIds = chunk.ids[layer];
                byte[] layerDatas = chunk.data[layer];
                int startY = layer << 4;
                int endY = startY + 15;
                for (int z = csz; z <= cez; z++) {
                    index = (z & 15) << 4;
                    for (int x = csx; x <= cex; x++, index++) {
                        globalIndex = indexes[index];
                        int height = heightMap[index];
                        int diff;
                        if (height > endY) {
                            diff = 16;
                        } else if (height >= startY) {
                            diff = height - startY;
                            char floorCombined = floor[globalIndex];
                            int id = FaweCache.getId(floorCombined);
                            int floorIndex = index + ((height & 15) << 8);
                            layerIds[floorIndex] = (byte) id;
                            int data = FaweCache.getData(floorCombined);
                            if (data != 0) {
                                chunk.setNibble(floorIndex, layerDatas, data);
                            }
                            if (hasOverlay && height >= startY - 1 && height < endY) {
                                char overlayCombined = overlay[globalIndex];
                                id = FaweCache.getId(overlayCombined);
                                int overlayIndex = index + (((height + 1) & 15) << 8);
                                layerIds[overlayIndex] = (byte) id;
                                data = FaweCache.getData(overlayCombined);
                                if (data != 0) {
                                    chunk.setNibble(overlayIndex, layerDatas, data);
                                }
                            }
                        } else if (hasOverlay && height == startY - 1) {
                            char overlayCombined = overlay[globalIndex];
                            int id = FaweCache.getId(overlayCombined);
                            int overlayIndex = index + (((height + 1) & 15) << 8);
                            layerIds[overlayIndex] = (byte) id;
                            int data = FaweCache.getData(overlayCombined);
                            if (data != 0) {
                                chunk.setNibble(overlayIndex, layerDatas, data);
                            }
                            continue;
                        } else {
                            continue;
                        }
                        char mainCombined = main[globalIndex];
                        byte id = (byte) FaweCache.getId(mainCombined);
                        int data = FaweCache.getData(mainCombined);
                        if (data != 0) {
                            for (int y = 0; y < diff; y++) {
                                int mainIndex = index + (y << 8);
                                chunk.setNibble(mainIndex, layerDatas, data);
                            }
                        }
                        for (int y = 0; y < diff; y++) {
                            layerIds[index + (y << 8)] = id;
                        }
                    }
                }
            }

            int maxYMod = 15 + (maxLayer << 4);
            for (int layer = (maxY >> 4) + 1; layer < 16; layer++) {
                chunk.ids[layer] = null;
                chunk.data[layer] = null;
            }

            if (primtives.bedrockId != 0) { // Bedrock
                byte[] layerIds = chunk.ids[0];
                for (int z = csz; z <= cez; z++) {
                    index = (z & 15) << 4;
                    for (int x = csx; x <= cex; x++) {
                        layerIds[index++] = primtives.bedrockId;
                    }
                }
            }

            char[][][] localBlocks = getChunkArray(chunk.getX(), chunk.getZ());
            if (localBlocks != null) {
                for (int layer = 0; layer < 16; layer++) {
                    int by = layer << 4;
                    int ty = by + 15;
                    index = 0;
                    for (int y = by; y <= ty; y++, index += 256) {
                        char[][] yBlocks = localBlocks[y];
                        if (yBlocks != null) {
                            if (chunk.ids[layer] == null) {
                                chunk.ids[layer] = new byte[4096];
                                chunk.data[layer] = new byte[2048];
                                chunk.skyLight[layer] = new byte[2048];
                                chunk.blockLight[layer] = new byte[2048];
                                Arrays.fill(chunk.skyLight[layer], (byte) 255);
                            }
                            byte[] idsLayer = chunk.ids[layer];
                            byte[] dataLayer = chunk.data[layer];
                            for (int z = 0; z < yBlocks.length; z++) {
                                char[] zBlocks = yBlocks[z];
                                if (zBlocks != null) {
                                    int zIndex = index + (z << 4);
                                    for (int x = 0; x < zBlocks.length; x++, zIndex++) {
                                        char combined = zBlocks[x];
                                        if (combined == 0) continue;
                                        int id = FaweCache.getId(combined);
                                        int data = FaweCache.getData(combined);
                                        if (data == 0) {
                                            chunk.setIdUnsafe(idsLayer, zIndex, (byte) id);
                                        } else {
                                            chunk.setBlockUnsafe(idsLayer, dataLayer, zIndex, (byte) id, FaweCache.getData(combined));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (primtives.floorThickness != 0 || primtives.worldThickness != 0) {
                // Use biomes array as temporary buffer
                byte[] minArr = chunk.biomes;
                for (int z = csz; z <= cez; z++) {
                    index = (z & 15) << 4;
                    for (int x = csx; x <= cex; x++, index++) {
                        int gi = indexes[index];
                        int height = heightMap[index];
                        int min = height;
                        if (x > 0) min = Math.min(heights[gi - 1] & 0xFF, min);
                        if (x < getWidth() - 1) min = Math.min(heights[gi + 1] & 0xFF, min);
                        if (z > 0) min = Math.min(heights[gi - getWidth()] & 0xFF, min);
                        if (z < getLength() - 1) min = Math.min(heights[gi + getWidth()] & 0xFF, min);
                        minArr[index] = (byte) min;
                    }
                }

                int minLayer = Math.max(0, (minY - primtives.floorThickness) >> 4);

                if (primtives.floorThickness != 0) {
                    for (int layer = minLayer; layer <= maxLayer; layer++) {
                        byte[] layerIds = chunk.ids[layer];
                        byte[] layerDatas = chunk.data[layer];
                        int startY = layer << 4;
                        int endY = startY + 15;
                        for (int z = csz; z <= cez; z++) {
                            index = (z & 15) << 4;
                            for (int x = csx; x <= cex; x++, index++) {
                                globalIndex = indexes[index];
                                int height = heightMap[index];

                                int min = (minArr[index] & 0xFF) - primtives.floorThickness;
                                int localMin = min - startY;

                                int max = height + 1;
                                if (min < startY) min = startY;
                                if (max > endY) max = endY + 1;


                                if (min < max) {
                                    char floorCombined = floor[globalIndex];
                                    final byte id = (byte) FaweCache.getId(floorCombined);
                                    final int data = FaweCache.getData(floorCombined);
                                    for (int y = min; y < max; y++) {
                                        int floorIndex = index + ((y & 15) << 8);
                                        layerIds[floorIndex] = id;
                                        if (data != 0) {
                                            chunk.setNibble(floorIndex, layerDatas, data);
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
                if (primtives.worldThickness != 0) {
                    for (int layer = 0; layer < minLayer; layer++) {
                        chunk.ids[layer] = null;
                        chunk.data[layer] = null;
                    }
                    for (int layer = minLayer; layer <= maxLayer; layer++) {
                        byte[] layerIds = chunk.ids[layer];
                        byte[] layerDatas = chunk.data[layer];
                        int startY = layer << 4;
                        int endY = startY + 15;
                        for (int z = csz; z <= cez; z++) {
                            index = (z & 15) << 4;
                            for (int x = csx; x <= cex; x++, index++) {
                                globalIndex = indexes[index];
                                int height = heightMap[index];

                                int min = (minArr[index] & 0xFF) - primtives.worldThickness;
                                int localMin = min - startY;
                                if (localMin > 0) {
                                    char floorCombined = floor[globalIndex];
                                    final byte id = (byte) FaweCache.getId(floorCombined);
                                    final int data = FaweCache.getData(floorCombined);

                                    for (int y = 0; y < localMin; y++) {
                                        int floorIndex = index + ((y & 15) << 8);
                                        layerIds[floorIndex] = 0;
                                        if (data != 0) {
                                            chunk.setNibble(floorIndex, layerDatas, 0);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                for (int layer = fillLayers; layer <= maxLayer; layer++) {
                    Arrays.fill(chunk.skyLight[layer], (byte) 255);

                }
            }

            for (int i = 0; i < 256; i++) {
                chunk.biomes[i] = biomes[indexes[i]];
            }


        } catch (final Throwable e) {
            e.printStackTrace();
        }
        return chunk;
    }

    private void setUnsafe(final char[][][] map, final char combined, final int x, final int y, final int z) {
        char[][] yMap = map[y];
        if (yMap == null) {
            map[y] = yMap = new char[16][];
        }
        char[] zMap = yMap[z];
        if (zMap == null) {
            yMap[z] = zMap = new char[16];
        }
        zMap[x] = combined;
    }

    private char get(final char[][][] map, final int x, final int y, final int z) {
        char[][] yMap = map[y];
        if (yMap == null) {
            return 0;
        }
        char[] zMap = yMap[z & 15];
        if (zMap == null) {
            return 0;
        }
        return zMap[x & 15];
    }

    private void setOverlay(final Mask mask, final char combined) {
        int index = 0;
        if (overlay == null) overlay = new DifferentialArray<>(new char[getArea()]);
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                mutable.mutX(x);
                mutable.mutY(y);
                if (mask.test(mutable)) {
                    overlay.setChar(index, combined);
                }
            }
        }
    }

    private void setFloor(final Mask mask, final char combined) {
        int index = 0;
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                mutable.mutX(x);
                mutable.mutY(y);
                if (mask.test(mutable)) {
                    floor.setChar(index, combined);
                }
            }
        }
    }

    private void setMain(final Mask mask, final char combined) {
        primtives.modifiedMain = true;
        int index = 0;
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                mutable.mutX(x);
                mutable.mutY(y);
                if (mask.test(mutable)) {
                    main.setChar(index, combined);
                }
            }
        }
    }

    private void setColumn(final Mask mask, final char combined) {
        primtives.modifiedMain = true;
        int index = 0;
        for (int z = 0; z < getLength(); z++) {
            mutable.mutZ(z);
            for (int x = 0; x < getWidth(); x++, index++) {
                int y = heights.getByte(index) & 0xFF;
                mutable.mutX(x);
                mutable.mutY(y);
                if (mask.test(mutable)) {
                    floor.setChar(index, combined);
                    main.setChar(index, combined);
                }
            }
        }
    }

    private void setFloor(final int value) {
        floor.record(() -> Arrays.fill(floor.get(), (char) value));
    }

    private void setColumn(final int value) {
        setFloor(value);
        setMain(value);
    }

    private void setMain(final int value) {
        primtives.modifiedMain = true;
        main.record(() -> Arrays.fill(main.get(), (char) value));
    }

    private void setOverlay(final int value) {
        if (overlay == null) overlay = new DifferentialArray<>(new char[getArea()]);
        overlay.record(() -> Arrays.fill(overlay.get(), (char) value));
    }

    private void setOverlay(final BufferedImage img, final char combined, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        if (overlay == null) overlay = new DifferentialArray<>(new char[getArea()]);

        overlay.record(() -> {
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++, index++) {
                    int height = img.getRGB(x, z) & 0xFF;
                    if (height == 255 || height > 0 && white && PseudoRandom.random.nextInt(256) <= height) {
                        overlay.get()[index] = combined;
                    }
                }
            }
        });
    }

    private void setMain(final BufferedImage img, final char combined, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        primtives.modifiedMain = true;

        main.record(() -> {
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++, index++) {
                    int height = img.getRGB(x, z) & 0xFF;
                    if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                        main.get()[index] = combined;
                    }
                }
            }
        });
    }

    private void setFloor(final BufferedImage img, final char combined, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");

        floor.record(() -> {
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++, index++) {
                    int height = img.getRGB(x, z) & 0xFF;
                    if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                        floor.get()[index] = combined;
                    }
                }
            }
        });
    }

    private void setColumn(final BufferedImage img, final char combined, final boolean white) {
        if (img.getWidth() != getWidth() || img.getHeight() != getLength())
            throw new IllegalArgumentException("Input image dimensions do not match the current height map!");
        primtives.modifiedMain = true;

        main.record(() -> floor.record(() -> {
            int index = 0;
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++, index++) {
                    int height = img.getRGB(x, z) & 0xFF;
                    if (height == 255 || height > 0 && !white && PseudoRandom.random.nextInt(256) <= height) {
                        main.get()[index] = combined;
                        floor.get()[index] = combined;
                    }
                }
            }
        }));
    }

    @Override
    protected void finalize() throws Throwable {
        IterableThreadLocal.clean(indexStore);
        super.finalize();
    }

    @Override
    public int getMaxY() {
        return 255;
    }

    @Override
    public void setWorld(final String world) {

    }

    @Override
    public World getWEWorld() {
        return this;
    }

    @Override
    public String getWorldName() {
        return getName();
    }

    @Override
    public long getModified() {
        return 0;
    }

    @Override
    public void setModified(final long modified) {
        // Unsupported
    }

    @Override
    public RunnableVal2<ProgressType, Integer> getProgressTask() {
        return null;
    }

    @Override
    public void setProgressTask(final RunnableVal2<ProgressType, Integer> progressTask) {

    }

    @Override
    public void setChangeTask(final RunnableVal2<FaweChunk, FaweChunk> changeTask) {

    }

    @Override
    public RunnableVal2<FaweChunk, FaweChunk> getChangeTask() {
        return null;
    }

    @Override
    public SetQueue.QueueStage getStage() {
        return SetQueue.QueueStage.NONE;
    }

    @Override
    public void setStage(final SetQueue.QueueStage stage) {
        // Not supported
    }

    @Override
    public void addNotifyTask(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void runTasks() {

    }

    @Override
    public void addTask(final Runnable whenFree) {
        whenFree.run();
    }

    @Override
    public boolean isEmpty() {
        return !isModified();
    }

    @Nullable
    @Override
    public Operation commit() {
        return null;
    }

    @Override
    public String getName() {
        File folder = getFolder();
        if (folder != null) {
            String name = folder.getName();
            if (name.equalsIgnoreCase("region")) return folder.getParentFile().getName();
            return name;
        }
        return Integer.toString(hashCode());
    }

    @Override
    public boolean setBlock(final Vector position, final BaseBlock block, final boolean notifyAndLight) throws WorldEditException {
        return setBlock(position, block);
    }

    // These aren't implemented yet...
    @Override
    public int getBlockLightLevel(final Vector position) {
        return 0;
    }

    @Override
    public boolean clearContainerBlockContents(final Vector position) {
        return false;
    }

    @Override
    public void dropItem(final Vector position, final BaseItemStack item) {

    }

    @Override
    public boolean regenerate(final Region region, final EditSession editSession) {
        return false;
    }

    @Override
    public boolean generateTree(final TreeGenerator.TreeType type, final EditSession editSession, final Vector position) throws MaxChangedBlocksException {
        return false;
    }

    @Override
    public WorldData getWorldData() {
        if (player != null) return player.getWorld().getWorldData();
        return null;
    }
}
