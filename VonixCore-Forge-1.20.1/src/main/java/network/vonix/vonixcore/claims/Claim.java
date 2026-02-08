package network.vonix.vonixcore.claims;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a protected land claim.
 */
public class Claim {

    private final int id;
    private final UUID owner;
    private final String ownerName;
    private final String world;
    private final int x1, y1, z1; // Corner 1 (min)
    private final int x2, y2, z2; // Corner 2 (max)
    private final Set<UUID> trusted;
    private final long createdAt;

    public Claim(int id, UUID owner, String ownerName, String world,
            int x1, int y1, int z1, int x2, int y2, int z2,
            Set<UUID> trusted, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        // Normalize coordinates (min/max)
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
        this.z2 = Math.max(z1, z2);
        this.trusted = trusted != null ? new HashSet<>(trusted) : new HashSet<>();
        this.createdAt = createdAt;
    }

    /**
     * Check if a position is within this claim
     */
    public boolean contains(String world, BlockPos pos) {
        if (!this.world.equals(world))
            return false;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /**
     * Check if a position is within this claim (2D check, ignores Y)
     */
    public boolean contains2D(String world, BlockPos pos) {
        if (!this.world.equals(world))
            return false;
        int x = pos.getX();
        int z = pos.getZ();
        return x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    /**
     * Check if a player can interact in this claim
     */
    public boolean canInteract(UUID player) {
        return owner.equals(player) || trusted.contains(player);
    }

    /**
     * Add a trusted player
     */
    public void addTrusted(UUID player) {
        trusted.add(player);
    }

    /**
     * Remove a trusted player
     */
    public void removeTrusted(UUID player) {
        trusted.remove(player);
    }

    /**
     * Get claim size in blocks
     */
    public int getArea() {
        return (x2 - x1 + 1) * (z2 - z1 + 1);
    }

    /**
     * Get claim volume
     */
    public int getVolume() {
        return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    // Getters
    public int getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getWorld() {
        return world;
    }

    public int getX1() {
        return x1;
    }

    public int getY1() {
        return y1;
    }

    public int getZ1() {
        return z1;
    }

    public int getX2() {
        return x2;
    }

    public int getY2() {
        return y2;
    }

    public int getZ2() {
        return z2;
    }

    public Set<UUID> getTrusted() {
        return new HashSet<>(trusted);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Claim[id=%d, owner=%s, world=%s, (%d,%d,%d)->(%d,%d,%d)]",
                id, ownerName, world, x1, y1, z1, x2, y2, z2);
    }
}
