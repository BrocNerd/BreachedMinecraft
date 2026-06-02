package nrd.breached.client.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import nrd.breached.Breached;
import nrd.breached.client.BreachedClient;

public class BreachedArchiveButtonWidget extends PressableWidget {
    private static final ItemStack ICON = new ItemStack(Breached.BREACHED_ARCHIVE);

    public BreachedArchiveButtonWidget(int x, int y) {
        super(x, y, 20, 18, Text.literal("Open Breached Archive"));
        setTooltip(Tooltip.of(Text.literal("Open Breached Archive")));
    }

    @Override
    public void onPress(AbstractInput input) {
        BreachedClient.openBreachedArchive(MinecraftClient.getInstance().currentScreen);
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.drawItem(ICON, getX() + 2, getY() + 1);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
