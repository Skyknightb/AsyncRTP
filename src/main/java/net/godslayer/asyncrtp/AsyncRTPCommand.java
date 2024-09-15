package net.godslayer.asyncrtp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AsyncRTPCommand {
    private static final int MAX_RADIUS = 5000;
    private static final Map<ServerPlayer, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_DURATION_MS = TimeUnit.MINUTES.toMillis(3); // 3 minutes in milliseconds
    private static final int MAX_ATTEMPTS = 10; // Maximum attempts to find a safe location
    private static final int WATER_CHECK_RADIUS = 3; // Radius around the player to check for water
    private static final ExecutorService executor = Executors.newCachedThreadPool(); // Thread pool for async tasks

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("rtp")
                .requires(source -> source.hasPermission(2))  // Permission level 2 required
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    if (source.getEntity() instanceof ServerPlayer player) {
                        long currentTime = System.currentTimeMillis();

                        // Check cooldown
                        if (cooldowns.containsKey(player)) {
                            long lastUseTime = cooldowns.get(player);
                            if (currentTime - lastUseTime < COOLDOWN_DURATION_MS) {
                                long timeLeft = (COOLDOWN_DURATION_MS - (currentTime - lastUseTime)) / 1000;
                                player.sendSystemMessage(Component.literal("You must wait " + timeLeft + " seconds before using this command again."));
                                return Command.SINGLE_SUCCESS;
                            }
                        }

                        // Send the "Calculating..." message
                        player.sendSystemMessage(Component.literal("Calculating..."));

                        CompletableFuture.runAsync(() -> {
                            long startTime = System.currentTimeMillis(); // Start timing the teleport

                            Level level = player.getCommandSenderWorld();
                            if (!(level instanceof ServerLevel world)) {
                                source.sendFailure(Component.literal("This command can only be executed in a server world."));
                                return;
                            }

                            Random random = new Random();
                            double randomX = player.getX() + (random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS);
                            double randomZ = player.getZ() + (random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS);

                            BlockPos targetPos = new BlockPos((int) randomX, 0, (int) randomZ);

                            // Ensure the chunk is loaded
                            world.getChunkSource().getChunk((int) randomX >> 4, (int) randomZ >> 4, true);

                            // Calculate the height based on the heightmap
                            int highestY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetPos.getX(), targetPos.getZ());

                            BlockPos safePos = new BlockPos((int) randomX, highestY, (int) randomZ);

                            // Check if the position or nearby area is in water
                            if (isNearWater(world, safePos, WATER_CHECK_RADIUS)) {
                                // If near water, search for a new position within the area
                                safePos = findNearbySafeLocation(world, safePos, WATER_CHECK_RADIUS);
                            }

                            // Ensure the position is safe (not obstructed by walls or water)
                            int attempts = 0;
                            while (!isSafeLocation(world, safePos) && attempts < MAX_ATTEMPTS) {
                                safePos = safePos.above(); // Move up if the position is obstructed or unsafe
                                attempts++;
                            }

                            // Log debug info before teleporting
                            player.sendSystemMessage(Component.literal("Teleporting to: X: " + (int) randomX + ", Y: " + safePos.getY() + ", Z: " + (int) randomZ));

                            // Attempt teleportation
                            try {
                                player.teleportTo(world, safePos.getX(), safePos.getY(), safePos.getZ(), player.getYRot(), player.getXRot());
                                player.sendSystemMessage(Component.literal("Teleportation successful."));
                            } catch (Exception e) {
                                player.sendSystemMessage(Component.literal("Teleportation failed: " + e.getMessage()));
                            }

                            // Calculate and display the time taken
                            long endTime = System.currentTimeMillis();
                            long timeTaken = endTime - startTime;
                            player.sendSystemMessage(Component.literal("Command executed in " + timeTaken + " ms."));

                            // Update cooldown
                            cooldowns.put(player, currentTime);
                        }, executor);

                    } else {
                        source.sendFailure(Component.literal("This command can only be executed by a player."));
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }

    // Check if the position or its surrounding area is near water
    private static boolean isNearWater(ServerLevel world, BlockPos pos, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos checkPos = pos.offset(dx, 0, dz);
                if (world.getBlockState(checkPos.below()).getFluidState().is(Fluids.WATER)) {
                    return true; // Water found nearby
                }
            }
        }
        return false; // No water in the radius
    }

    // Find a safe location near the given position that's not near water
    private static BlockPos findNearbySafeLocation(ServerLevel world, BlockPos pos, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos checkPos = pos.offset(dx, 0, dz);
                int highestY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, checkPos.getX(), checkPos.getZ());
                BlockPos potentialPos = new BlockPos(checkPos.getX(), highestY, checkPos.getZ());

                if (!isNearWater(world, potentialPos, radius) && isSafeLocation(world, potentialPos)) {
                    return potentialPos; // Return the first safe location found
                }
            }
        }
        return pos; // Default to the original position if no better location is found
    }

    private static boolean isSafeLocation(ServerLevel world, BlockPos pos) {
        // Check if the position is safe by ensuring it's not obstructed and not underwater
        BlockState state = world.getBlockState(pos);
        BlockState stateBelow = world.getBlockState(pos.below());
        BlockState stateAbove = world.getBlockState(pos.above());

        boolean isSolidGround = stateBelow.getBlock() != Blocks.AIR && stateBelow.getFluidState().isEmpty(); // Ensure solid ground below
        boolean isNotWater = !state.getFluidState().is(Fluids.WATER); // Ensure current position isn't in water
        boolean isAirAbove = stateAbove.getBlock() == Blocks.AIR; // Ensure there's air above the player's head

        return isSolidGround && isNotWater && state.getBlock() == Blocks.AIR && isAirAbove;
    }
}