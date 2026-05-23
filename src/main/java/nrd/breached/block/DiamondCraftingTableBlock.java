package nrd.breached.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.screen.DiamondCraftingScreenHandler;

public class DiamondCraftingTableBlock extends CraftingTableBlock {
    private static final Text TITLE = Text.translatable("block.breached.tier_2_crafting_bench");

    public DiamondCraftingTableBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected NamedScreenHandlerFactory createScreenHandlerFactory(BlockState state, World world, BlockPos pos) {
        return new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, player) -> new DiamondCraftingScreenHandler(
                        syncId,
                        playerInventory,
                        ScreenHandlerContext.create(world, pos)
                ),
                TITLE
        );
    }
}
