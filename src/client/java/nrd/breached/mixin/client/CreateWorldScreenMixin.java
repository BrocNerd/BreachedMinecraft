package nrd.breached.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldCallback;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import nrd.breached.Breached;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    private static final RegistryKey<WorldPreset> BREACHED_ISLAND_PRESET = RegistryKey.of(
            RegistryKeys.WORLD_PRESET,
            Identifier.of(Breached.MOD_ID, "breached_island")
    );

    @Shadow
    @Final
    private WorldCreator worldCreator;

    @Inject(
            method = "<init>(Lnet/minecraft/client/MinecraftClient;Ljava/lang/Runnable;Lnet/minecraft/client/world/GeneratorOptionsHolder;Ljava/util/Optional;Ljava/util/OptionalLong;Lnet/minecraft/client/gui/screen/world/CreateWorldCallback;)V",
            at = @At("TAIL")
    )
    private void breached$defaultToBreachedIslandPreset(
            MinecraftClient client,
            Runnable onClosed,
            GeneratorOptionsHolder generatorOptionsHolder,
            Optional<RegistryKey<WorldPreset>> defaultWorldType,
            OptionalLong seed,
            CreateWorldCallback callback,
            CallbackInfo ci
    ) {
        if (defaultWorldType.isPresent() && !defaultWorldType.get().equals(WorldPresets.DEFAULT)) {
            return;
        }

        List<WorldCreator.WorldType> normalWorldTypes = this.worldCreator.getNormalWorldTypes();
        WorldCreator.WorldType currentWorldType = this.worldCreator.getWorldType();
        if (currentWorldType != null && normalWorldTypes.stream().anyMatch(type -> type.preset().equals(currentWorldType.preset()))) {
            return;
        }

        normalWorldTypes.stream()
                .filter(CreateWorldScreenMixin::isBreachedIslandPreset)
                .findFirst()
                .ifPresent(this.worldCreator::setWorldType);
    }

    private static boolean isBreachedIslandPreset(WorldCreator.WorldType worldType) {
        return worldType.preset().getKey()
                .map(BREACHED_ISLAND_PRESET::equals)
                .orElse(false);
    }
}
