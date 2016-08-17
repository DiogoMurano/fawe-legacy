package com.boydti.fawe.object.extent;

import com.boydti.fawe.FaweCache;
import com.boydti.fawe.config.BBC;
import com.boydti.fawe.object.FaweLimit;
import com.boydti.fawe.object.RegionWrapper;
import com.boydti.fawe.util.WEManager;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.util.List;

public class ProcessedWEExtent extends FaweRegionExtent {
    private final FaweLimit limit;
    private final RegionWrapper[] mask;

    public ProcessedWEExtent(final Extent parent, final RegionWrapper[] mask, FaweLimit limit) {
        super(parent);
        this.mask = mask;
        this.limit = limit;
    }

    @Override
    public Entity createEntity(final Location location, final BaseEntity entity) {
        if (entity == null) {
            return null;
        }
        if (WEManager.IMP.maskContains(this.mask, location.getBlockX(), location.getBlockZ())) {
            if (!limit.MAX_ENTITIES()) {
                WEManager.IMP.cancelEditSafe(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_CHANGES);
                return null;
            }
            return super.createEntity(location, entity);
        } else if (!limit.MAX_FAILS()) {
            WEManager.IMP.cancelEditSafe(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_FAILS);
        }
        return null;
    }

    @Override
    public BaseBiome getBiome(final Vector2D position) {
        return super.getBiome(position);
    }

    private BaseBlock lastBlock;
    private BlockVector lastVector;

    @Override
    public BaseBlock getLazyBlock(final Vector position) {
        return super.getLazyBlock(position);
    }

    @Override
    public List<? extends Entity> getEntities() {
        return super.getEntities();
    }

    @Override
    public List<? extends Entity> getEntities(final Region region) {
        return super.getEntities(region);
    }

    @Override
    public BaseBlock getBlock(final Vector position) {
        return this.getLazyBlock(position);
    }

    @Override
    public boolean setBlock(final Vector location, final BaseBlock block) throws WorldEditException {
        if (block.hasNbtData() && FaweCache.hasNBT(block.getType())) {
            if (!limit.MAX_BLOCKSTATES()) {
                WEManager.IMP.cancelEdit(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_TILES);
                return false;
            }
        }
        if (WEManager.IMP.maskContains(this.mask, (int) location.x, (int) location.z)) {
            if (!limit.MAX_CHANGES()) {
                WEManager.IMP.cancelEdit(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_CHANGES);
                return false;
            }
            return super.setBlock(location, block);
        } else if (!limit.MAX_FAILS()) {
            WEManager.IMP.cancelEdit(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_FAILS);
        }
        return false;
    }

    @Override
    public boolean setBiome(final Vector2D position, final BaseBiome biome) {
        if (WEManager.IMP.maskContains(this.mask, (int) position.getX(), (int) position.getZ())) {
            if (!limit.MAX_CHANGES()) {
                WEManager.IMP.cancelEditSafe(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_CHANGES);
                return false;
            }
            return super.setBiome(position, biome);
        } else if (!limit.MAX_FAILS()) {
            WEManager.IMP.cancelEditSafe(this, BBC.WORLDEDIT_CANCEL_REASON_MAX_FAILS);
        }
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return WEManager.IMP.maskContains(this.mask, x, z);
    }
}
