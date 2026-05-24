package nrd.breached.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import nrd.breached.Breached;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CentralSpawnPoiManager {
    private static final Identifier STRUCTURE_ID = Identifier.of(Breached.MOD_ID, "central_spawn");
    // Distance between the two structure reference corners.
    private static final int REFERENCE_CORNER_SPACING = 55;
    private static final int FIRST_STRUCTURE_X_OFFSET = -REFERENCE_CORNER_SPACING / 2;
    private static final int FIRST_STRUCTURE_Z_OFFSET = 0;
    private static final int SECOND_STRUCTURE_X_OFFSET = FIRST_STRUCTURE_X_OFFSET + REFERENCE_CORNER_SPACING;
    private static final int SECOND_STRUCTURE_Z_OFFSET = 0;
    private static final int SITE_SEARCH_RADIUS = 500;
    private static final int SITE_SEARCH_STEP = 32;
    private static final int SITE_SAMPLE_STEP = 16;
    private static final int SITE_HALF_X = 72;
    private static final int SITE_HALF_Z = 40;
    private static final int SITE_CANDIDATES_PER_TICK = 1;
    private static final int ACCEPTABLE_HEIGHT_RANGE = 4;
    // Structure origin Y relative to the chosen surface. 1 means the structure sits on top of the ground.
    private static final int STRUCTURE_Y_OFFSET_FROM_SURFACE = 1;
    private static final int PROTECTION_RADIUS = 72;
    private static final int PROTECTION_RADIUS_SQUARED = PROTECTION_RADIUS * PROTECTION_RADIUS;
    private static final int BLOCK_UPDATE_FLAGS = 2;
    private static SearchState searchState;

    private CentralSpawnPoiManager() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(CentralSpawnPoiManager::placeCentralSpawnPoiOnce);
        registerProtectionEvents();
    }

    private static void placeCentralSpawnPoiOnce(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        CentralSpawnPoiState state = CentralSpawnPoiState.get(world.getServer());
        if (state.isPlaced()) {
            return;
        }

        if (world.getServer().getPlayerManager().getPlayerList().isEmpty()) {
            return;
        }

        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(STRUCTURE_ID);
        if (template.isEmpty()) {
            System.out.println("[Breached] Central spawn POI structure " + STRUCTURE_ID + " was not found. Placement will retry.");
            return;
        }

        SiteCandidate site = findPlacementSiteIncrementally(world);
        if (site == null) {
            return;
        }

        placeCentralSpawnPoi(world, state, template.get(), site);
        searchState = null;
    }

    private static void placeCentralSpawnPoi(ServerWorld world, CentralSpawnPoiState state, StructureTemplate template, SiteCandidate site) {
        int structureY = site.surfaceY() + STRUCTURE_Y_OFFSET_FROM_SURFACE;
        int firstStructureX = site.centerX() + FIRST_STRUCTURE_X_OFFSET;
        int firstStructureZ = site.centerZ() + FIRST_STRUCTURE_Z_OFFSET;
        int secondStructureX = site.centerX() + SECOND_STRUCTURE_X_OFFSET;
        int secondStructureZ = site.centerZ() + SECOND_STRUCTURE_Z_OFFSET;

        placeStructure(world, template, firstStructureX, structureY, firstStructureZ, BlockMirror.NONE, BlockRotation.NONE);
        placeStructure(world, template, secondStructureX, structureY, secondStructureZ, BlockMirror.LEFT_RIGHT, BlockRotation.CLOCKWISE_180);
        state.markPlaced(site.centerX(), site.centerZ());

        System.out.println("[Breached] Placed central spawn POI using " + STRUCTURE_ID + " at reference corners x "
                + firstStructureX + ", z " + firstStructureZ + " and x "
                + secondStructureX + ", z " + secondStructureZ + " near center x "
                + site.centerX() + ", z " + site.centerZ() + " on surface y " + site.surfaceY()
                + " after finding terrain height range " + site.heightRange() + ".");
    }

    private static SiteCandidate findPlacementSiteIncrementally(ServerWorld world) {
        BlockPos spawnPos = world.getSpawnPoint().getPos();
        if (searchState == null || searchState.worldSeed() != world.getSeed() || !searchState.spawnPos().equals(spawnPos)) {
            searchState = new SearchState(world.getSeed(), spawnPos);
            System.out.println("[Breached] Searching for central spawn POI surface site within " + SITE_SEARCH_RADIUS + " blocks of world spawn.");
        }

        SiteCandidate acceptableSite = searchState.evaluateNextSites(world);
        if (acceptableSite != null) {
            return acceptableSite;
        }

        if (!searchState.isComplete()) {
            return null;
        }

        SiteCandidate best = searchState.best();
        if (best != null) {
            return best;
        }

        int surfaceY = getSurfaceY(world, spawnPos.getX(), spawnPos.getZ());
        System.out.println("[Breached] No dry central spawn POI site found within " + SITE_SEARCH_RADIUS + " blocks. Falling back to world spawn.");
        return new SiteCandidate(spawnPos.getX(), spawnPos.getZ(), surfaceY, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static SiteCandidate evaluateSite(ServerWorld world, int centerX, int centerZ, int xOffset, int zOffset) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int liquidSamples = 0;
        int samples = 0;

        for (int x = centerX - SITE_HALF_X; x <= centerX + SITE_HALF_X; x += SITE_SAMPLE_STEP) {
            for (int z = centerZ - SITE_HALF_Z; z <= centerZ + SITE_HALF_Z; z += SITE_SAMPLE_STEP) {
                int surfaceY = getSurfaceY(world, x, z);
                BlockState surfaceState = world.getBlockState(new BlockPos(x, surfaceY - 1, z));
                if (surfaceState.isOf(Blocks.WATER) || surfaceState.isOf(Blocks.LAVA)) {
                    liquidSamples++;
                }

                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                samples++;
            }
        }

        if (liquidSamples > 0) {
            return null;
        }

        int heightRange = maxY - minY;
        int distanceScore = (xOffset * xOffset + zOffset * zOffset) / SITE_SEARCH_STEP;
        int score = heightRange * 100_000 + distanceScore;
        return new SiteCandidate(centerX, centerZ, maxY, heightRange, score);
    }

    private static int getSurfaceY(ServerWorld world, int x, int z) {
        world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static void placeStructure(
            ServerWorld world,
            StructureTemplate template,
            int x,
            int y,
            int z,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        BlockPos pos = new BlockPos(x, y, z);
        ChunkPos chunkPos = new ChunkPos(pos);
        world.getChunk(chunkPos.x, chunkPos.z);

        StructurePlacementData placementData = new StructurePlacementData()
                .setMirror(mirror)
                .setRotation(rotation)
                .setIgnoreEntities(false)
                .setUpdateNeighbors(true);

        template.place(world, pos, pos, placementData, Random.create(world.getSeed()), BLOCK_UPDATE_FLAGS);
    }

    private static void registerProtectionEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!shouldProtect(world, player, pos)) {
                return true;
            }

            player.sendMessage(Text.literal("The central spawn area cannot be modified."), false);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || canBypassProtection(player) || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace() || !isInsideProtectedArea(world, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("The central spawn area cannot be modified."), false);
            return ActionResult.FAIL;
        });
    }

    public static boolean isInsideProtectedArea(World world, BlockPos pos) {
        if (world.isClient() || !world.getRegistryKey().equals(World.OVERWORLD)) {
            return false;
        }

        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        CentralSpawnPoiState state = CentralSpawnPoiState.get(serverWorld.getServer());
        if (!state.isPlaced()) {
            return false;
        }

        long xDistance = pos.getX() - state.getCenterX();
        long zDistance = pos.getZ() - state.getCenterZ();
        return xDistance * xDistance + zDistance * zDistance <= PROTECTION_RADIUS_SQUARED;
    }

    private static boolean shouldProtect(World world, PlayerEntity player, BlockPos pos) {
        return !world.isClient()
                && !canBypassProtection(player)
                && isInsideProtectedArea(world, pos);
    }

    private static boolean canBypassProtection(PlayerEntity player) {
        return player.isCreative() || player.isCreativeLevelTwoOp();
    }

    private static final class SearchState {
        private final long worldSeed;
        private final BlockPos spawnPos;
        private final List<CandidateOffset> offsets;
        private int nextIndex;
        private SiteCandidate best;

        private SearchState(long worldSeed, BlockPos spawnPos) {
            this.worldSeed = worldSeed;
            this.spawnPos = spawnPos;
            this.offsets = createCandidateOffsets();
        }

        private SiteCandidate evaluateNextSites(ServerWorld world) {
            int evaluated = 0;
            SiteCandidate acceptableSite = null;

            while (evaluated < SITE_CANDIDATES_PER_TICK && nextIndex < offsets.size()) {
                CandidateOffset offset = offsets.get(nextIndex);
                nextIndex++;
                evaluated++;

                SiteCandidate candidate = evaluateSite(
                        world,
                        spawnPos.getX() + offset.xOffset(),
                        spawnPos.getZ() + offset.zOffset(),
                        offset.xOffset(),
                        offset.zOffset()
                );
                if (candidate == null) {
                    continue;
                }

                if (best == null || candidate.score() < best.score()) {
                    best = candidate;
                }

                if (candidate.heightRange() <= ACCEPTABLE_HEIGHT_RANGE) {
                    acceptableSite = candidate;
                    break;
                }
            }

            return acceptableSite;
        }

        private boolean isComplete() {
            return nextIndex >= offsets.size();
        }

        private long worldSeed() {
            return worldSeed;
        }

        private BlockPos spawnPos() {
            return spawnPos;
        }

        private SiteCandidate best() {
            return best;
        }
    }

    private static List<CandidateOffset> createCandidateOffsets() {
        int searchSteps = SITE_SEARCH_RADIUS / SITE_SEARCH_STEP;
        List<CandidateOffset> offsets = new ArrayList<>();

        for (int xStep = -searchSteps; xStep <= searchSteps; xStep++) {
            for (int zStep = -searchSteps; zStep <= searchSteps; zStep++) {
                int xOffset = xStep * SITE_SEARCH_STEP;
                int zOffset = zStep * SITE_SEARCH_STEP;
                if (xOffset * xOffset + zOffset * zOffset <= SITE_SEARCH_RADIUS * SITE_SEARCH_RADIUS) {
                    offsets.add(new CandidateOffset(xOffset, zOffset));
                }
            }
        }

        offsets.sort(Comparator.comparingInt(CandidateOffset::distanceSquared));
        return offsets;
    }

    private record CandidateOffset(int xOffset, int zOffset) {
        private int distanceSquared() {
            return xOffset * xOffset + zOffset * zOffset;
        }
    }

    private record SiteCandidate(int centerX, int centerZ, int surfaceY, int heightRange, int score) {
    }
}
