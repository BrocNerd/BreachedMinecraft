package nrd.breached.client.screen;

import net.minecraft.block.Blocks;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import nrd.breached.Breached;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BreachedArchiveScreen extends Screen {
    private static final int PANEL_BACKGROUND = 0xF0181818;
    private static final int CONTENT_BACKGROUND = 0xF023211D;
    private static final int BORDER_COLOR = 0xFF6D6256;
    private static final int TITLE_COLOR = 0xFFE2D2B0;
    private static final int SECTION_COLOR = 0xFFFFD26A;
    private static final int TEXT_COLOR = 0xFFE8E1D2;
    private static final int MUTED_TEXT_COLOR = 0xFFB8AEA0;
    private static final int RECIPE_BACKGROUND = 0xF02D2923;
    private static final int RECIPE_BUTTON = 0xFF3C342C;
    private static final int RECIPE_BUTTON_HOVER = 0xFF514638;
    private static final int NAV_WIDTH = 112;
    private static final int PADDING = 12;
    private static final int LINE_HEIGHT = 10;
    private static final int RECIPE_TOGGLE_HEIGHT = 17;
    private static final int RECIPE_CARD_HEIGHT = 84;
    private static final int SLOT_SIZE = 20;
    private static final int CORNER_ICON_SIZE = 32;
    private static final float CORNER_ICON_SCALE = 2.0F;
    private static final List<ArchivePage> PAGES = List.of(
            new ArchivePage(
                    "Basics",
                    "Breached Archive",
                    List.of(
                            new ArchiveSection(
                                    "Breached Basics",
                                    "Breached is survival Minecraft rebuilt around one core loop: loot, fight, breach, and bring the rewards home. Raid other players, upgrade your gear, strengthen your base, and keep climbing until you're on the top."
                            ),
                            new ArchiveSection(
                                    "Getting Started",
                                    "Find a strong location for your base and place a Landlock Block to claim it. Players can’t break or place blocks inside your claim, but they can still get in through any holes you leave behind and steal your stuff!\n" +
                                            "\n" +
                                            "To upgrade your base you’ll also want a Probe and a Reinforcer alongside your Landlock Block. These tools help you configure and strengthen your base protection. You can read more about them here in the Base tab."
                            )
                    )
            ),
            new ArchivePage(
                    "Base",
                    "Building a Base",
                    List.of(
                            new ArchiveSection(
                                    "Landlock Blocks",
                                    "Drop a Landlock Block near the heart of your base to claim a 17x17x17 cube. You are authorized automatically when you place it. Other players can right-click the Landlock Block to authorize themselves, or sneak-right-click it to deauthorize themselves. All authorized players can build, break, and manage the claimed area. Only Landlock owners can break a landlock block without a breacher.\n" +
                                            "\n" +
                                            "Unauthorized players cannot break or place blocks inside the claim without a breacher, but they can still open any chests or furnaces. If they can get inside, they will take your stuff!",
                                    List.of(recipe(
                                            "landlock_block",
                                            "Landlock Block",
                                            stack(Breached.LANDLOCK_BLOCK),
                                            stack(Blocks.IRON_BLOCK), stack(Blocks.COPPER_BLOCK), stack(Blocks.IRON_BLOCK),
                                            stack(Blocks.COPPER_BLOCK), stack(Blocks.OAK_PLANKS), stack(Blocks.COPPER_BLOCK),
                                            stack(Blocks.IRON_BLOCK), stack(Blocks.COPPER_BLOCK), stack(Blocks.IRON_BLOCK)
                                    ))
                            ),
                            new ArchiveSection(
                                    "Claim Limits",
                                    "You can only be authorized on 3 Landlocks, so pick your bases carefully. Landlocks must be 32 blocks apart, above Y 60, and 12 blocks away from protected major structures."
                            ),
                            new ArchiveSection(
                                    "Claim Control",
                                    "Use the Probe before building to check claims. IMPORTANT: Probe's allow you to move your claim center. When you sneak-right-click your Landlock with the Probe, then right-click a block inside that claim, you will move the claim center to that block. The new center must be somewhere inside the current 17x17x17 cube.",
                                    List.of(recipe(
                                            "probe",
                                            "Probe",
                                            stack(Breached.PROBE),
                                            empty(), stack(Items.IRON_INGOT), empty(),
                                            empty(), stack(Items.IRON_INGOT), empty(),
                                            empty(), empty(), empty()
                                    ))
                            ),
                            new ArchiveSection(
                                    "Reinforcing Land",
                                    "Claims stop casual griefing, but you'll need reinforcements to defend your base against a breacher! Hold a Reinforcer in your main hand and material in your offhand, then right-click authorized blocks. Wood reinforcement costs 16 logs, iron costs 4 iron blocks, and diamond costs 2 diamond blocks.",
                                    List.of(recipe(
                                            "reinforcer",
                                            "Reinforcer",
                                            stack(Breached.REINFORCER),
                                            empty(), stack(Blocks.OAK_LOG), stack(Blocks.OAK_LOG),
                                            empty(), stack(Items.STICK), stack(Blocks.OAK_LOG),
                                            stack(Items.STICK), empty(), empty()
                                    ))
                            ),
                            new ArchiveSection(
                                    "Removing Your Landlock",
                                    "Want your Landlock back? The owner must break it with a Reinforcer. Punching it will not do the job because Landlocks are always wood reinforced."
                            )
                    )
            ),
            new ArchivePage(
                    "Breach",
                    "Breaching",
                    List.of(
                            new ArchiveSection(
                                    "Breachers",
                                    "Normal tools cannot break into enemy claims. Bring a Breacher! Breachers mine normal blocks like rough all-purpose tools, but they are slow against reinforcements.",
                                    List.of(
                                            recipe(
                                                    "iron_breacher",
                                                    "Iron Breacher",
                                                    stack(Breached.IRON_BREACHER),
                                                    stack(Blocks.IRON_BLOCK), stack(Blocks.IRON_BLOCK), stack(Blocks.IRON_BLOCK),
                                                    empty(), stack(Blocks.DIAMOND_BLOCK), empty(),
                                                    empty(), stack(Blocks.DIAMOND_BLOCK), empty()
                                            ),
                                            recipe(
                                                    "diamond_breacher",
                                                    "Diamond Breacher",
                                                    stack(Breached.DIAMOND_BREACHER),
                                                    stack(Blocks.DIAMOND_BLOCK), stack(Blocks.DIAMOND_BLOCK), stack(Blocks.DIAMOND_BLOCK),
                                                    empty(), stack(Blocks.GOLD_BLOCK), empty(),
                                                    empty(), stack(Blocks.GOLD_BLOCK), empty()
                                            ),
                                            recipe(
                                                    "netherite_breacher",
                                                    "Netherite Breacher",
                                                    stack(Breached.NETHERITE_BREACHER),
                                                    stack(Blocks.DIAMOND_BLOCK), stack(Blocks.NETHERITE_BLOCK), stack(Blocks.DIAMOND_BLOCK),
                                                    empty(), stack(Blocks.DIAMOND_BLOCK), empty(),
                                                    empty(), stack(Blocks.DIAMOND_BLOCK), empty()
                                            )
                                    )
                            ),
                            new ArchiveSection(
                                    "Durability",
                                    "Treat Breacher durability is scarce. Iron is for early raids, Diamond lasts much longer, and Netherite is the serious raid tool. Normal block mining costs 4 durability."
                            ),
                            new ArchiveSection(
                                    "Reinforced Blocks",
                                    "Reinforcement is what makes walls expensive to break. Wood costs 16 Breacher durability, iron costs 64, and diamond costs 256."
                            ),
                            new ArchiveSection(
                                    "Failed Breaches",
                                    "Check durability before you swing. If the Breacher cannot pay the full cost, it breaks and the defender keeps the block."
                            ),
                            new ArchiveSection(
                                    "Raid Planning",
                                    "Scout first, then pick the cheapest path. Expect reinforced walls near storage and Landlocks, and bring extra Breachers if the raid matters."
                            )
                    )
            ),
            new ArchivePage(
                    "Craft",
                    "Crafting Progression",
                    List.of(
                            new ArchiveSection(
                                    "Crafting Lock",
                                    "The normal crafting table can't craft key progression items. If a recipe feels locked, move up to the right Breached crafting table."
                            ),
                            new ArchiveSection(
                                    "Tier 1",
                                    "The Iron Crafting Table is your first real upgrade. It opens early combat and raiding gear: iron equipment, bows, crossbows, and the Iron Breacher.",
                                    List.of(recipe(
                                            "tier_1_crafting_bench",
                                            "Iron Crafting Table",
                                            stack(Breached.TIER_1_CRAFTING_BENCH),
                                            stack(Blocks.IRON_BLOCK), stack(Items.GUNPOWDER), stack(Blocks.IRON_BLOCK),
                                            stack(Blocks.IRON_BLOCK), stack(Blocks.CRAFTING_TABLE), stack(Blocks.IRON_BLOCK),
                                            stack(Blocks.IRON_BLOCK), stack(Blocks.REDSTONE_BLOCK), stack(Blocks.IRON_BLOCK)
                                    ))
                            ),
                            new ArchiveSection(
                                    "Tier 2",
                                    "The Diamond Crafting Table is the midgame push. Use it for diamond gear, enchanting tables, and the Diamond Breacher.",
                                    List.of(recipe(
                                            "tier_2_crafting_bench",
                                            "Diamond Crafting Table",
                                            stack(Breached.TIER_2_CRAFTING_BENCH),
                                            stack(Blocks.DIAMOND_BLOCK), stack(Items.GHAST_TEAR), stack(Blocks.DIAMOND_BLOCK),
                                            stack(Blocks.DIAMOND_BLOCK), stack(Breached.TIER_1_CRAFTING_BENCH), stack(Blocks.DIAMOND_BLOCK),
                                            stack(Blocks.DIAMOND_BLOCK), stack(Items.HEAVY_CORE), stack(Blocks.DIAMOND_BLOCK)
                                    ))
                            ),
                            new ArchiveSection(
                                    "Tier 3",
                                    "The Netherite Crafting Table is late-game power. It unlocks the strongest progression recipes, including the Netherite Breacher, bookshelves, anvils, and the mace.",
                                    List.of(recipe(
                                            "tier_3_crafting_bench",
                                            "Netherite Crafting Table",
                                            stack(Breached.TIER_3_CRAFTING_BENCH),
                                            stack(Blocks.NETHERITE_BLOCK), stack(Items.NETHER_STAR), stack(Blocks.NETHERITE_BLOCK),
                                            stack(Blocks.NETHERITE_BLOCK), stack(Breached.TIER_2_CRAFTING_BENCH), stack(Blocks.NETHERITE_BLOCK),
                                            stack(Blocks.NETHERITE_BLOCK), stack(Items.DRAGON_BREATH), stack(Blocks.NETHERITE_BLOCK)
                                    ))
                            )
                    )
            ),
            new ArchivePage(
                    "Loot",
                    "General Looting",
                    List.of(
                            new ArchiveSection(
                                    "Structures",
                                    "When you aren't building raiding, or doing normal Minecraft things, look for structures. Barrels and chests have decent loot, colorful shulker boxes are even better, and black and white shulker boxes are the best."
                            ),
                            new ArchiveSection(
                                    "Restocks",
                                    "Major structures restock over time, so protected locations are always valuable. Minor structures will despawn and respawn so there will always be something new to loot."
                            ),
                            new ArchiveSection(
                                    "PvP Hotspots",
                                    "Good loot draws attention. Bring supplies, watch exits, and do not carry more than you can afford to lose."
                            )
                    )
            ),
            new ArchivePage(
                    "Other",
                    "Misc / Other",
                    List.of(
                            new ArchiveSection(
                                    "Beds",
                                    "Beds are for recovery, not skipping danger, so they don't skip night. You can have three beds at a time, one as a primary and two fallbacks."
                            ),
                            new ArchiveSection(
                                    "Dimensions",
                                    "Nether and End access is limited. You will have to use breached portal structures as normal portal access has been disabled. Portals will open when their containers restock, but keep your eyes open when looking up at the end portal."
                            ),
                            new ArchiveSection(
                                    "Villagers",
                                    "Villager trading is disabled so players cannot skip loot and crafting progression."
                            ),
                            new ArchiveSection(
                                    "Teams",
                                    "Teams help friends coordinate and identify each other. Join a team and you'll be able to see your teammates on the locator bar."
                            ),
                            new ArchiveSection(
                                    "Future Pages",
                                    "More Archive pages will be added as Breached grows."
                            )
                    )
            )
    );

    private final List<ButtonWidget> tabButtons = new ArrayList<>();
    private final List<RecipeToggleBounds> visibleRecipeToggles = new ArrayList<>();
    private final Set<String> openRecipes = new HashSet<>();
    private int activePage;
    private int scrollOffset;
    private int maxScroll;

    public BreachedArchiveScreen() {
        super(Text.literal("Breached Archive"));
    }

    @Override
    protected void init() {
        tabButtons.clear();
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        int tabX = panelLeft + PADDING;
        int tabY = panelTop + 34;
        int tabWidth = NAV_WIDTH - PADDING;

        for (int index = 0; index < PAGES.size(); index++) {
            final int pageIndex = index;
            ButtonWidget button = ButtonWidget.builder(
                            Text.literal(PAGES.get(index).tabName()),
                            pressed -> setActivePage(pageIndex)
                    )
                    .dimensions(tabX, tabY + index * 23, tabWidth, 20)
                    .build();
            tabButtons.add(button);
            addDrawableChild(button);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(panelLeft + panelWidth() - 68, panelTop + panelHeight() - 28, 56, 20)
                .build());
        refreshTabButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int contentLeft = panelLeft + NAV_WIDTH + PADDING;
        int contentTop = panelTop + 36;
        int contentWidth = panelWidth - NAV_WIDTH - PADDING * 2;
        int contentHeight = panelHeight - 76;

        context.fill(0, 0, width, height, 0x99000000);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BACKGROUND);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 28, 0xFF2B2118);
        context.drawStrokedRectangle(panelLeft, panelTop, panelWidth, panelHeight, BORDER_COLOR);
        context.drawTextWithShadow(textRenderer, title, panelLeft + PADDING, panelTop + 10, TITLE_COLOR);

        context.fill(contentLeft - 6, contentTop - 6, contentLeft + contentWidth + 6, contentTop + contentHeight + 6, CONTENT_BACKGROUND);
        context.drawStrokedRectangle(contentLeft - 6, contentTop - 6, contentWidth + 12, contentHeight + 12, 0xFF4A4238);
        renderPageContent(context, activeArchivePage(), contentLeft, contentTop, contentWidth, contentHeight, mouseX, mouseY);
        renderCornerIcon(context, panelLeft, panelTop, panelHeight);

        super.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        scrollOffset = clamp(scrollOffset - (int) Math.round(verticalAmount * 18.0D), 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && tryToggleRecipe(click.x(), click.y())) {
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void renderPageContent(
            DrawContext context,
            ArchivePage page,
            int left,
            int top,
            int width,
            int height,
            int mouseX,
            int mouseY
    ) {
        visibleRecipeToggles.clear();
        maxScroll = Math.max(0, getPageContentHeight(page, width) - height);
        scrollOffset = clamp(scrollOffset, 0, maxScroll);

        int y = top - scrollOffset;
        context.enableScissor(left, top, left + width, top + height);
        context.drawTextWithShadow(textRenderer, Text.literal(page.title()), left, y, TITLE_COLOR);
        y += 17;

        for (ArchiveSection section : page.sections()) {
            context.drawTextWithShadow(textRenderer, Text.literal(section.title()), left, y, SECTION_COLOR);
            y += LINE_HEIGHT + 2;
            for (OrderedText line : textRenderer.wrapLines(Text.literal(section.body()), width)) {
                context.drawTextWithShadow(textRenderer, line, left, y, TEXT_COLOR);
                y += LINE_HEIGHT;
            }
            y += 4;

            for (ArchiveRecipe recipe : section.recipes()) {
                renderRecipeToggle(context, recipe, left, y, width, top, height, mouseX, mouseY);
                y += RECIPE_TOGGLE_HEIGHT;
                if (openRecipes.contains(recipe.id())) {
                    renderRecipeCard(context, recipe, left, y, width);
                    y += RECIPE_CARD_HEIGHT;
                }
            }
            y += 8;
        }
        context.disableScissor();

        if (maxScroll > 0) {
            int scrollbarLeft = left + width + 2;
            int scrollbarHeight = Math.max(18, height * height / (height + maxScroll));
            int scrollbarTop = top + (height - scrollbarHeight) * scrollOffset / maxScroll;
            context.fill(scrollbarLeft, top, scrollbarLeft + 3, top + height, 0xFF322E28);
            context.fill(scrollbarLeft, scrollbarTop, scrollbarLeft + 3, scrollbarTop + scrollbarHeight, 0xFFB49B72);
        }
    }

    private void renderRecipeToggle(
            DrawContext context,
            ArchiveRecipe recipe,
            int left,
            int y,
            int width,
            int viewportTop,
            int viewportHeight,
            int mouseX,
            int mouseY
    ) {
        String label = (openRecipes.contains(recipe.id()) ? "v " : "> ") + recipe.title() + " Recipe";
        int buttonWidth = Math.min(width, Math.max(160, textRenderer.getWidth(label) + 12));
        boolean visible = y + RECIPE_TOGGLE_HEIGHT >= viewportTop && y <= viewportTop + viewportHeight;
        boolean hovered = mouseX >= left
                && mouseX < left + buttonWidth
                && mouseY >= y
                && mouseY < y + RECIPE_TOGGLE_HEIGHT;
        context.fill(left, y, left + buttonWidth, y + RECIPE_TOGGLE_HEIGHT - 2, hovered ? RECIPE_BUTTON_HOVER : RECIPE_BUTTON);
        context.drawStrokedRectangle(left, y, buttonWidth, RECIPE_TOGGLE_HEIGHT - 2, 0xFF625648);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal(label),
                left + 5,
                y + 4,
                TEXT_COLOR
        );

        if (visible) {
            visibleRecipeToggles.add(new RecipeToggleBounds(recipe.id(), left, y, buttonWidth, RECIPE_TOGGLE_HEIGHT - 2));
        }
    }

    private void renderRecipeCard(DrawContext context, ArchiveRecipe recipe, int left, int y, int width) {
        int cardWidth = Math.min(190, width);
        context.fill(left, y, left + cardWidth, y + RECIPE_CARD_HEIGHT - 6, RECIPE_BACKGROUND);
        context.drawStrokedRectangle(left, y, cardWidth, RECIPE_CARD_HEIGHT - 6, 0xFF5E5348);
        context.drawTextWithShadow(textRenderer, Text.literal("Crafting Table"), left + 6, y + 5, MUTED_TEXT_COLOR);

        int gridLeft = left + 8;
        int gridTop = y + 18;
        for (int slot = 0; slot < 9; slot++) {
            int slotX = gridLeft + (slot % 3) * SLOT_SIZE;
            int slotY = gridTop + (slot / 3) * SLOT_SIZE;
            renderSlot(context, slotX, slotY, recipe.inputs().get(slot));
        }

        context.drawTextWithShadow(textRenderer, Text.literal("->"), gridLeft + 66, gridTop + 23, MUTED_TEXT_COLOR);
        renderSlot(context, gridLeft + 88, gridTop + 20, recipe.output());
    }

    private void renderSlot(DrawContext context, int x, int y, ItemStack stack) {
        context.fill(x, y, x + 18, y + 18, 0xFF1B1915);
        context.drawStrokedRectangle(x, y, 18, 18, 0xFF6F6251);
        if (!stack.isEmpty()) {
            context.drawItem(stack, x + 1, y + 1);
            context.drawStackOverlay(textRenderer, stack, x + 1, y + 1);
        }
    }

    private void renderCornerIcon(DrawContext context, int panelLeft, int panelTop, int panelHeight) {
        int iconX = panelLeft + PADDING;
        int iconY = panelTop + panelHeight - PADDING - CORNER_ICON_SIZE;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(iconX, iconY);
        context.getMatrices().scale(CORNER_ICON_SCALE, CORNER_ICON_SCALE);
        context.drawItem(new ItemStack(Breached.NETHERITE_BREACHER), 0, 0);
        context.getMatrices().popMatrix();
    }

    private boolean tryToggleRecipe(double mouseX, double mouseY) {
        for (RecipeToggleBounds bounds : visibleRecipeToggles) {
            if (mouseX >= bounds.x()
                    && mouseX < bounds.x() + bounds.width()
                    && mouseY >= bounds.y()
                    && mouseY < bounds.y() + bounds.height()) {
                if (!openRecipes.add(bounds.recipeId())) {
                    openRecipes.remove(bounds.recipeId());
                }
                return true;
            }
        }

        return false;
    }

    private int getPageContentHeight(ArchivePage page, int width) {
        int height = 17;
        for (ArchiveSection section : page.sections()) {
            height += LINE_HEIGHT + 2;
            height += textRenderer.wrapLines(Text.literal(section.body()), width).size() * LINE_HEIGHT;
            height += 4;
            for (ArchiveRecipe recipe : section.recipes()) {
                height += RECIPE_TOGGLE_HEIGHT;
                if (openRecipes.contains(recipe.id())) {
                    height += RECIPE_CARD_HEIGHT;
                }
            }
            height += 8;
        }

        return height;
    }

    private ArchivePage activeArchivePage() {
        return PAGES.get(activePage);
    }

    private void setActivePage(int pageIndex) {
        if (activePage == pageIndex) {
            return;
        }

        activePage = pageIndex;
        scrollOffset = 0;
        refreshTabButtons();
    }

    private void refreshTabButtons() {
        for (int index = 0; index < tabButtons.size(); index++) {
            tabButtons.get(index).active = index != activePage;
        }
    }

    private int panelWidth() {
        return Math.min(486, Math.max(300, width - 24));
    }

    private int panelHeight() {
        return Math.min(292, Math.max(210, height - 24));
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private static ArchiveRecipe recipe(String id, String title, ItemStack output, ItemStack... inputs) {
        return new ArchiveRecipe(id, title, List.of(inputs), output);
    }

    private static ItemStack stack(ItemConvertible item) {
        return new ItemStack(item);
    }

    private static ItemStack empty() {
        return ItemStack.EMPTY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private record ArchivePage(String tabName, String title, List<ArchiveSection> sections) {
    }

    private record ArchiveSection(String title, String body, List<ArchiveRecipe> recipes) {
        private ArchiveSection(String title, String body) {
            this(title, body, List.of());
        }
    }

    private record ArchiveRecipe(String id, String title, List<ItemStack> inputs, ItemStack output) {
        private ArchiveRecipe {
            if (inputs.size() != 9) {
                throw new IllegalArgumentException("Archive recipes must have exactly 9 input slots.");
            }
            inputs = List.copyOf(inputs);
            output = output.copy();
        }
    }

    private record RecipeToggleBounds(String recipeId, int x, int y, int width, int height) {
    }
}
