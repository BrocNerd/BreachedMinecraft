package nrd.breached.message;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class BreachedMessages {
    public static final Formatting PREFIX_COLOR = Formatting.GOLD;
    public static final Formatting INFO_COLOR = Formatting.GRAY;
    public static final Formatting SUCCESS_COLOR = Formatting.GREEN;
    public static final Formatting WARNING_COLOR = Formatting.YELLOW;
    public static final Formatting ERROR_COLOR = Formatting.RED;
    public static final Formatting PROTECTION_COLOR = Formatting.GOLD;

    private BreachedMessages() {
    }

    public static void info(PlayerEntity player, String message) {
        send(player, message, INFO_COLOR);
    }

    public static void success(PlayerEntity player, String message) {
        send(player, message, SUCCESS_COLOR);
    }

    public static void warning(PlayerEntity player, String message) {
        send(player, message, WARNING_COLOR);
    }

    public static void error(PlayerEntity player, String message) {
        send(player, message, ERROR_COLOR);
    }

    public static void protection(PlayerEntity player, String message) {
        send(player, message, PROTECTION_COLOR);
    }

    public static void send(PlayerEntity player, Text message, Formatting color) {
        player.sendMessage(prefixed(message.copy().formatted(color)), false);
    }

    public static void send(PlayerEntity player, String message, Formatting color) {
        player.sendMessage(prefixed(Text.literal(message).formatted(color)), false);
    }

    public static MutableText prefixed(Text message) {
        return Text.literal("[BREACHED] ")
                .formatted(PREFIX_COLOR)
                .append(message);
    }
}
