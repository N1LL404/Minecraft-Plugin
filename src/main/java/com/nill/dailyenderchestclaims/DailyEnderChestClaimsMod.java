package com.nill.dailyenderchestclaims;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DailyEnderChestClaimsMod implements DedicatedServerModInitializer {
    private static final String MOD_ID = "daily_ender_chest_claims";
    private static final Identifier STORAGE_ID = Identifier.of(MOD_ID, "claims");
    private static final int MAX_CLAIMS_PER_DAY = 2;
    private static final int TPA_GUI_SIZE = 27;
    private static final int TPA_DENY_SLOT = 10;
    private static final int TPA_WORLD_SLOT = 12;
    private static final int TPA_PLAYER_SLOT = 13;
    private static final int TPA_FEATHER_SLOT = 14;
    private static final int TPA_ACCEPT_SLOT = 16;
    private static final Map<UUID, TeleportRequest> PENDING_TELEPORT_REQUESTS = new HashMap<>();

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register(DailyEnderChestClaimsMod::registerCommands);
        ServerTickEvents.END_WORLD_TICK.register(DailyEnderChestClaimsMod::skipNightWhenOnePlayerSleeps);
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("claim")
                .then(CommandManager.literal("ec")
                        .executes(context -> claimEnderChest(context.getSource()))));
        dispatcher.register(CommandManager.literal("tpa")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> requestTeleportToPlayer(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "player")
                        ))));
        dispatcher.register(CommandManager.literal("tphere")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> requestPlayerTeleportHere(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "player")
                        ))));
        dispatcher.register(CommandManager.literal("tpaccept")
                .executes(context -> openTeleportAcceptGui(context.getSource())));
    }

    private static int claimEnderChest(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        MinecraftServer server = source.getServer();
        long currentDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        String playerId = player.getUuidAsString();

        NbtCompound claims = server.getDataCommandStorage().get(STORAGE_ID).copy();
        NbtCompound playerClaims = claims.getCompoundOrEmpty(playerId);

        long savedDay = playerClaims.getLong("day", Long.MIN_VALUE);
        int usedToday = savedDay == currentDay ? playerClaims.getInt("used", 0) : 0;

        if (usedToday >= MAX_CLAIMS_PER_DAY) {
            source.sendError(Text.literal("You already claimed 2 ender chests today. Wait until tomorrow."));
            return 0;
        }

        player.giveOrDropStack(new ItemStack(Items.ENDER_CHEST));

        int newUsedToday = usedToday + 1;
        playerClaims.putLong("day", currentDay);
        playerClaims.putInt("used", newUsedToday);
        claims.put(playerId, playerClaims);
        server.getDataCommandStorage().set(STORAGE_ID, claims);

        int remaining = MAX_CLAIMS_PER_DAY - newUsedToday;
        source.sendFeedback(
                () -> Text.literal("Claimed 1 ender chest. Claims left today: " + remaining + "."),
                false
        );
        return 1;
    }

    private static int requestTeleportToPlayer(ServerCommandSource source, ServerPlayerEntity target) throws CommandSyntaxException {
        ServerPlayerEntity requester = source.getPlayerOrThrow();
        if (requester.getUuid().equals(target.getUuid())) {
            source.sendError(Text.literal("You cannot send a teleport request to yourself."));
            return 0;
        }

        TeleportRequest request = new TeleportRequest(
                requester.getUuid(),
                requester.getName().getString(),
                target.getUuid(),
                target.getName().getString(),
                TeleportRequestType.TPA
        );
        saveTeleportRequest(request, requester, target);
        requester.sendMessage(Text.literal("Sent a tpa request to " + target.getName().getString() + ".")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int requestPlayerTeleportHere(ServerCommandSource source, ServerPlayerEntity target) throws CommandSyntaxException {
        ServerPlayerEntity requester = source.getPlayerOrThrow();
        if (requester.getUuid().equals(target.getUuid())) {
            source.sendError(Text.literal("You cannot send a teleport request to yourself."));
            return 0;
        }

        TeleportRequest request = new TeleportRequest(
                requester.getUuid(),
                requester.getName().getString(),
                target.getUuid(),
                target.getName().getString(),
                TeleportRequestType.TPHERE
        );
        saveTeleportRequest(request, requester, target);
        requester.sendMessage(Text.literal("Sent a tphere request to " + target.getName().getString() + ".")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static void saveTeleportRequest(TeleportRequest request, ServerPlayerEntity requester, ServerPlayerEntity target) {
        PENDING_TELEPORT_REQUESTS.put(target.getUuid(), request);
        target.sendMessage(Text.literal(request.requesterName() + " sent you a " + request.commandName() + " request.")
                .formatted(Formatting.GRAY), false);
        target.sendMessage(clickableAcceptMessage(request), false);
    }

    private static MutableText clickableAcceptMessage(TeleportRequest request) {
        return Text.literal("[CLICK]")
                .formatted(Formatting.AQUA)
                .styled(style -> style.withClickEvent(new ClickEvent.RunCommand("/tpaccept")))
                .append(Text.literal(" or type ").formatted(Formatting.GRAY))
                .append(Text.literal("/tpaccept").formatted(Formatting.AQUA))
                .append(Text.literal(" to review " + request.requesterName() + "'s request.").formatted(Formatting.GRAY));
    }

    private static int openTeleportAcceptGui(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        TeleportRequest request = PENDING_TELEPORT_REQUESTS.get(player.getUuid());
        if (request == null) {
            source.sendError(Text.literal("You do not have any pending teleport requests."));
            return 0;
        }

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, playerEntity) -> new TeleportAcceptScreenHandler(syncId, playerInventory),
                Text.literal("ACCEPT REQUEST")
        ));
        return 1;
    }

    private static void acceptPendingTeleportRequest(ServerPlayerEntity acceptingPlayer) {
        TeleportRequest request = PENDING_TELEPORT_REQUESTS.remove(acceptingPlayer.getUuid());
        if (request == null) {
            acceptingPlayer.sendMessage(Text.literal("That teleport request is no longer available.")
                    .formatted(Formatting.RED), false);
            return;
        }

        MinecraftServer server = ((ServerWorld) acceptingPlayer.getEntityWorld()).getServer();
        ServerPlayerEntity requester = server.getPlayerManager().getPlayer(request.requesterUuid());
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(request.targetUuid());
        if (requester == null || target == null) {
            acceptingPlayer.sendMessage(Text.literal("That teleport request expired because a player left the server.")
                    .formatted(Formatting.RED), false);
            return;
        }

        ServerPlayerEntity teleportingPlayer = request.type() == TeleportRequestType.TPA ? requester : target;
        ServerPlayerEntity destinationPlayer = request.type() == TeleportRequestType.TPA ? target : requester;
        teleportPlayerToPlayer(teleportingPlayer, destinationPlayer);

        teleportingPlayer.sendMessage(Text.literal("Teleporting to " + destinationPlayer.getName().getString() + ".")
                .formatted(Formatting.GREEN), false);
        destinationPlayer.sendMessage(Text.literal("Accepted teleport request from " + request.requesterName() + ".")
                .formatted(Formatting.GREEN), false);
    }

    private static void denyPendingTeleportRequest(ServerPlayerEntity acceptingPlayer) {
        TeleportRequest request = PENDING_TELEPORT_REQUESTS.remove(acceptingPlayer.getUuid());
        if (request == null) {
            acceptingPlayer.sendMessage(Text.literal("That teleport request is no longer available.")
                    .formatted(Formatting.RED), false);
            return;
        }

        MinecraftServer server = ((ServerWorld) acceptingPlayer.getEntityWorld()).getServer();
        ServerPlayerEntity requester = server.getPlayerManager().getPlayer(request.requesterUuid());
        acceptingPlayer.sendMessage(Text.literal("Denied " + request.requesterName() + "'s teleport request.")
                .formatted(Formatting.RED), false);
        if (requester != null) {
            requester.sendMessage(Text.literal(acceptingPlayer.getName().getString() + " denied your teleport request.")
                    .formatted(Formatting.RED), false);
        }
    }

    private static void teleportPlayerToPlayer(ServerPlayerEntity player, ServerPlayerEntity destination) {
        ServerWorld destinationWorld = (ServerWorld) destination.getEntityWorld();
        player.teleport(
                destinationWorld,
                destination.getX(),
                destination.getY(),
                destination.getZ(),
                Set.<PositionFlag>of(),
                destination.getYaw(),
                destination.getPitch(),
                true
        );
    }

    private static SimpleInventory createTeleportAcceptInventory(ServerPlayerEntity viewer) {
        SimpleInventory inventory = new SimpleInventory(TPA_GUI_SIZE);
        TeleportRequest request = PENDING_TELEPORT_REQUESTS.get(viewer.getUuid());
        String requesterName = request == null ? "Unknown" : request.requesterName();
        String actionText = request == null ? "Teleport request" : request.actionText();

        inventory.setStack(TPA_DENY_SLOT, namedStack(Items.RED_STAINED_GLASS_PANE, "Deny request", Formatting.RED,
                "Cancel " + requesterName + "'s request."));
        inventory.setStack(TPA_WORLD_SLOT, namedStack(Items.GRASS_BLOCK, "World", Formatting.GREEN,
                "Teleport will happen in the target player's world."));
        inventory.setStack(TPA_PLAYER_SLOT, playerHeadStack(requesterName, actionText));
        inventory.setStack(TPA_FEATHER_SLOT, namedStack(Items.FEATHER, "Teleport", Formatting.WHITE, actionText));
        inventory.setStack(TPA_ACCEPT_SLOT, namedStack(Items.GREEN_STAINED_GLASS_PANE, "Accept request", Formatting.GREEN,
                "Click to accept " + requesterName + "'s request."));
        return inventory;
    }

    private static ItemStack playerHeadStack(String playerName, String lore) {
        ItemStack stack = namedStack(Items.PLAYER_HEAD, playerName, Formatting.YELLOW, lore);
        stack.set(DataComponentTypes.PROFILE, net.minecraft.component.type.ProfileComponent.ofDynamic(playerName));
        return stack;
    }

    private static ItemStack namedStack(net.minecraft.item.Item item, String name, Formatting color, String lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(color));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(lore).formatted(Formatting.GRAY))));
        return stack;
    }

    private static void skipNightWhenOnePlayerSleeps(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD || !world.isNight()) {
            return;
        }

        List<ServerPlayerEntity> sleepingPlayers = world.getPlayers(ServerPlayerEntity::isSleeping);
        if (sleepingPlayers.isEmpty()) {
            return;
        }

        long nextMorning = ((world.getTimeOfDay() / 24000L) + 1L) * 24000L;
        world.setTimeOfDay(nextMorning);
        world.setWeather(6000, 0, false, false);

        for (ServerPlayerEntity player : sleepingPlayers) {
            player.wakeUp(false, false);
        }
        world.updateSleepingPlayers();

        world.getServer().getPlayerManager().broadcast(
                Text.literal(sleepingPlayers.getFirst().getName().getString() + " slept, so night was skipped."),
                false
        );
    }

    private enum TeleportRequestType {
        TPA,
        TPHERE
    }

    private record TeleportRequest(
            UUID requesterUuid,
            String requesterName,
            UUID targetUuid,
            String targetName,
            TeleportRequestType type
    ) {
        private String commandName() {
            return type == TeleportRequestType.TPA ? "tpa" : "tphere";
        }

        private String actionText() {
            return type == TeleportRequestType.TPA
                    ? requesterName + " will teleport to you."
                    : "You will teleport to " + requesterName + ".";
        }
    }

    private static final class TeleportAcceptScreenHandler extends GenericContainerScreenHandler {
        private TeleportAcceptScreenHandler(int syncId, PlayerInventory playerInventory) {
            super(
                    ScreenHandlerType.GENERIC_9X3,
                    syncId,
                    playerInventory,
                    createTeleportAcceptInventory((ServerPlayerEntity) playerInventory.player),
                    3
            );
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (slotIndex >= 0 && slotIndex < TPA_GUI_SIZE) {
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    if (slotIndex == TPA_ACCEPT_SLOT) {
                        serverPlayer.closeHandledScreen();
                        acceptPendingTeleportRequest(serverPlayer);
                    } else if (slotIndex == TPA_DENY_SLOT) {
                        serverPlayer.closeHandledScreen();
                        denyPendingTeleportRequest(serverPlayer);
                    }
                }
                return;
            }
            super.onSlotClick(slotIndex, button, actionType, player);
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
