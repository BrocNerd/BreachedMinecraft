package nrd.breached.worldgen;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.TypedEntityData;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.HorseColor;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.HorseMarking;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.random.Random;
import nrd.breached.mixin.HorseEntityAccessor;

public final class TownhallHorseTrade {
    public static final int PRICE_DIAMONDS = 16;

    private static final String TOWNHALL_HORSE_MARKER = "BreachedTownHallHorse";
    private static final double MIN_MAX_HEALTH = 24.0D;
    private static final double MAX_MAX_HEALTH = 28.0D;
    private static final double MIN_MOVEMENT_SPEED = 0.25D;
    private static final double MAX_MOVEMENT_SPEED = 0.30D;
    private static final double MIN_JUMP_STRENGTH = 0.70D;
    private static final double MAX_JUMP_STRENGTH = 0.90D;

    private TownhallHorseTrade() {
    }

    public static ItemStack createHorseEgg() {
        ItemStack egg = new ItemStack(Items.HORSE_SPAWN_EGG);
        egg.set(DataComponentTypes.ITEM_NAME, Text.literal("Town Hall Horse Spawn Egg"));

        NbtCompound horseData = new NbtCompound();
        horseData.putBoolean(TOWNHALL_HORSE_MARKER, true);
        egg.set(DataComponentTypes.ENTITY_DATA, TypedEntityData.create(EntityType.HORSE, horseData));
        return egg;
    }

    public static void randomizeIfTownhallHorseEgg(Entity entity, ItemStack stack, Random random) {
        if (!(entity instanceof HorseEntity horse) || !isTownhallHorseEgg(stack)) {
            return;
        }

        double maxHealth = randomBetween(random, MIN_MAX_HEALTH, MAX_MAX_HEALTH);
        setBaseValue(horse, EntityAttributes.MAX_HEALTH, maxHealth);
        setBaseValue(horse, EntityAttributes.MOVEMENT_SPEED, randomBetween(random, MIN_MOVEMENT_SPEED, MAX_MOVEMENT_SPEED));
        setBaseValue(horse, EntityAttributes.JUMP_STRENGTH, randomBetween(random, MIN_JUMP_STRENGTH, MAX_JUMP_STRENGTH));
        horse.setHealth((float) maxHealth);

        HorseColor[] colors = HorseColor.values();
        HorseMarking[] markings = HorseMarking.values();
        ((HorseEntityAccessor) horse).breached$setHorseVariant(
                colors[random.nextInt(colors.length)],
                markings[random.nextInt(markings.length)]
        );
    }

    private static boolean isTownhallHorseEgg(ItemStack stack) {
        TypedEntityData<EntityType<?>> entityData = stack.get(DataComponentTypes.ENTITY_DATA);
        return entityData != null
                && entityData.getType() == EntityType.HORSE
                && entityData.contains(TOWNHALL_HORSE_MARKER);
    }

    private static void setBaseValue(
            HorseEntity horse,
            RegistryEntry<EntityAttribute> attribute,
            double value
    ) {
        EntityAttributeInstance instance = horse.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static double randomBetween(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }
}
