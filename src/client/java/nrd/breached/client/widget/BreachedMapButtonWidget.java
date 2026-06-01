package nrd.breached.client.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import nrd.breached.client.BreachedClient;

public class BreachedMapButtonWidget extends PressableWidget {
    private static final ItemStack ICON = new ItemStack(Items.FILLED_MAP);

    public BreachedMapButtonWidget(int x, int y) {
        super(x, y, 20, 18, Text.literal("Open Breached Map"));
        setTooltip(Tooltip.of(Text.literal("Open Breached Map")));
    }

    @Override
    public void onPress(AbstractInput input) {
        BreachedClient.requestBreachedMap();
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
