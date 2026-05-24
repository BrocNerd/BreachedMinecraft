package nrd.breached.worldgen;

public record BreachedStructureSite(
        int originX,
        int originZ,
        int surfaceY,
        int minSurfaceY,
        int maxSurfaceY,
        int heightRange,
        int score,
        String rejectionReason
) {
    public boolean accepted() {
        return rejectionReason == null;
    }
}
