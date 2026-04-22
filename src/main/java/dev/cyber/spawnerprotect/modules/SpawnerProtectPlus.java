package dev.cyber.spawnerprotect.modules;

import dev.cyber.spawnerprotect.SpawnerProtectAddon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class SpawnerProtectPlus extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("Show chat feedback.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range")
            .description("Range to check for spawners to collect.")
            .defaultValue(16)
            .min(1)
            .max(50)
            .sliderMax(50)
            .build());

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-timeout-ms")
            .description("Time in milliseconds before skipping a spawner that cannot be mined.")
            .defaultValue(4000)
            .min(1000)
            .max(30000)
            .sliderMax(30000)
            .build());

    private final Setting<Integer> rollbackVerifyTicks = sgGeneral.add(new IntSetting.Builder()
            .name("rollback-verify-ticks")
            .description("Ticks to wait after a spawner disappears before confirming it was collected (detects server rollbacks).")
            .defaultValue(8)
            .min(1)
            .max(40)
            .sliderMax(40)
            .build());

    private final Setting<Boolean> depositToEChest = sgGeneral.add(new BoolSetting.Builder()
            .name("deposit-to-echest")
            .description("Deposit items into ender chest after collecting spawners.")
            .defaultValue(true)
            .build());

    private final Setting<List<Item>> depositBlacklist = sgGeneral.add(new ItemListSetting.Builder()
            .name("deposit-blacklist")
            .description("Items that will never be deposited into the ender chest.")
            .defaultValue(Arrays.asList(
                    Items.ENDER_PEARL,
                    Items.END_CRYSTAL,
                    Items.OBSIDIAN,
                    Items.RESPAWN_ANCHOR,
                    Items.GLOWSTONE,
                    Items.TOTEM_OF_UNDYING))
            .visible(depositToEChest::get)
            .build());

    private final Setting<Boolean> ignoreAlphabetPrefix = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-alphabet-prefix")
            .description("Skip protection if the detected player's nametag prefix contains letters (staff ranks like SRMOD, DEV, OWNER, etc.).")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist")
            .description("Whitelisted players will not trigger protection.")
            .defaultValue(false)
            .build());

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players")
            .description("List of player names to ignore.")
            .defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get)
            .build());

    private enum State {
        IDLE,
        COLLECTING_SPAWNERS,
        OPENING_CHEST,
        DEPOSITING_ITEMS,
        DISCONNECTING
    }

    private State currentState = State.IDLE;
    private String detectedPlayer = "";

    private BlockPos currentTarget = null;
    private long currentTargetStartTime = -1;
    private final Set<BlockPos> invalidSpawners = new HashSet<>();

    // Rollback verification: after a spawner disappears, wait before confirming it's gone
    private BlockPos pendingVerifyPos = null;
    private int pendingVerifyTicks = 0;

    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private int lastProcessedSlot = -1;
    private int transferDelayCounter = 0;
    private int tickCounter = 0;

    private float targetYaw, targetPitch;
    private boolean rotating = false;
    private static final float ROTATION_SPEED = 8.0f;

    public SpawnerProtectPlus() {
        super(SpawnerProtectAddon.CATEGORY, "spawner-protect+",
                "Instantly collects spawners and disconnects when a player is detected.");
    }

    @Override
    public void onActivate() {
        resetState();
        // Check for players already in the world when the module is turned on
        if (mc.world != null && mc.player != null) {
            for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                if (!(p instanceof AbstractClientPlayerEntity acp)) continue;
                String name = p.getGameProfile().name();
                if (isWhitelisted(name)) continue;
                if (isStaffByPrefix(acp)) continue;
                if (findNearestSpawner() == null) continue;
                detectedPlayer = name;
                disableAutoReconnect();
                currentState = State.COLLECTING_SPAWNERS;
                if (notifications.get()) info("Player already present: " + name + " - starting protection!");
                return;
            }
        }
        if (notifications.get()) info("SpawnerProtect+ active - monitoring for players...");
    }

    @Override
    public void onDeactivate() {
        stopBreaking();
        resetState();
    }

    private void resetState() {
        currentState = State.IDLE;
        detectedPlayer = "";
        currentTarget = null;
        currentTargetStartTime = -1;
        invalidSpawners.clear();
        pendingVerifyPos = null;
        pendingVerifyTicks = 0;
        targetChest = null;
        chestOpenAttempts = 0;
        lastProcessedSlot = -1;
        transferDelayCounter = 0;
        tickCounter = 0;
        rotating = false;
    }

    // Triggered the instant a player entity appears in the world
    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (currentState != State.IDLE) return;
        if (mc.player == null || mc.world == null) return;

        Entity entity = event.entity;
        if (!(entity instanceof AbstractClientPlayerEntity player)) return;
        if (entity == mc.player) return;

        String name = player.getGameProfile().name();
        if (isWhitelisted(name)) return;
        if (isStaffByPrefix(player)) {
            if (notifications.get()) info("Ignored (staff prefix): " + name);
            return;
        }

        // Only trigger if there are spawners nearby worth protecting.
        if (findNearestSpawner() == null) return;

        detectedPlayer = name;
        if (notifications.get()) info("Player detected: " + name + " - starting protection!");

        disableAutoReconnect();
        currentState = State.COLLECTING_SPAWNERS;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (rotating) {
            smoothRotate();
        }

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        tickCounter++;

        switch (currentState) {
            case IDLE -> { if (tickCounter % 20 == 0) scanForPlayers(); }
            case COLLECTING_SPAWNERS -> handleCollectingSpawners();
            case OPENING_CHEST -> handleOpeningChest();
            case DEPOSITING_ITEMS -> handleDepositingItems();
            case DISCONNECTING -> handleDisconnecting();
        }
    }

    private void handleCollectingSpawners() {
        mc.options.sneakKey.setPressed(true);

        // Rollback verification: spawner just disappeared, wait to confirm it's really gone
        if (pendingVerifyPos != null) {
            if (mc.world.getBlockState(pendingVerifyPos).getBlock() == Blocks.SPAWNER) {
                // Server rolled it back - resume mining without resetting the total timer
                if (notifications.get()) info("Rollback detected at " + pendingVerifyPos + ", re-mining...");
                currentTarget = pendingVerifyPos;
                // Keep currentTargetStartTime as-is to accumulate total time spent on this spawner
                pendingVerifyPos = null;
                pendingVerifyTicks = 0;
            } else {
                // Check total timeout even during verification wait
                if (currentTargetStartTime != -1 && System.currentTimeMillis() - currentTargetStartTime > spawnerTimeout.get()) {
                    if (notifications.get()) info("Timeout on spawner at " + pendingVerifyPos + " (with rollbacks), skipping...");
                    invalidSpawners.add(pendingVerifyPos);
                    pendingVerifyPos = null;
                    pendingVerifyTicks = 0;
                    currentTargetStartTime = -1;
                    return;
                }
                pendingVerifyTicks--;
                if (pendingVerifyTicks > 0) return; // still waiting
                // Confirmed gone
                pendingVerifyPos = null;
                pendingVerifyTicks = 0;
                currentTargetStartTime = -1;
            }
        }

        // Spawner disappeared - start rollback verification instead of immediately moving on
        if (currentTarget != null && mc.world.getBlockState(currentTarget).getBlock() != Blocks.SPAWNER) {
            pendingVerifyPos = currentTarget;
            pendingVerifyTicks = rollbackVerifyTicks.get();
            currentTarget = null;
            // Keep currentTargetStartTime so total elapsed time accumulates across rollbacks
            stopBreaking();
            return;
        }

        if (currentTarget == null) {
            currentTarget = findNearestSpawner();

            if (currentTarget == null) {
                // No spawners left - move to chest or disconnect
                mc.options.sneakKey.setPressed(false);
                invalidSpawners.clear();
                stopBreaking();
                if (depositToEChest.get()) {
                    targetChest = findNearestEnderChest();
                    if (targetChest != null) {
                        currentState = State.OPENING_CHEST;
                        chestOpenAttempts = 0;
                        if (notifications.get()) info("All spawners collected, opening ender chest...");
                    } else {
                        if (notifications.get()) info("No ender chest found nearby, disconnecting...");
                        currentState = State.DISCONNECTING;
                    }
                } else {
                    currentState = State.DISCONNECTING;
                }
                return;
            }

            currentTargetStartTime = System.currentTimeMillis();
            if (notifications.get()) info("Collecting spawner at " + currentTarget);
        }

        // Timeout check
        if (System.currentTimeMillis() - currentTargetStartTime > spawnerTimeout.get()) {
            if (notifications.get()) info("Timeout on spawner at " + currentTarget + ", skipping...");
            invalidSpawners.add(currentTarget);
            currentTarget = null;
            currentTargetStartTime = -1;
            stopBreaking();
            return;
        }

        // Look at spawner and mine (shift + left click = collect all stacked spawners)
        Direction side = getExposedSide(currentTarget);
        lookAtBlock(currentTarget, side);

        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getBlockPos().equals(currentTarget)) {
            FindItemResult pickaxe = findSilkTouchPickaxe();
            if (!pickaxe.found()) {
                if (notifications.get()) info("No silk touch pickaxe found, disconnecting...");
                stopBreaking();
                currentState = State.DISCONNECTING;
                return;
            }
            InvUtils.swap(pickaxe.slot(), true);
            mc.options.attackKey.setPressed(true);
            mc.interactionManager.updateBlockBreakingProgress(currentTarget, hit.getSide());
        }
    }

    private void handleOpeningChest() {
        if (targetChest == null) {
            currentState = State.DISCONNECTING;
            return;
        }

        mc.options.sneakKey.setPressed(false);
        mc.options.attackKey.setPressed(false);

        // Look at the ender chest and right-click - no jumping needed
        lookAtBlock(targetChest, Direction.UP);

        if (chestOpenAttempts % 5 == 0) {
            mc.interactionManager.interactBlock(
                    mc.player,
                    Hand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3d.ofCenter(targetChest),
                            Direction.UP,
                            targetChest,
                            false));
            if (notifications.get()) info("Opening ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
        }

        chestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            currentState = State.DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            tickCounter = 0;
            if (notifications.get()) info("Ender chest opened! Depositing items...");
        }

        if (chestOpenAttempts > 100) {
            if (notifications.get()) ChatUtils.error("Failed to open ender chest!");
            currentState = State.DISCONNECTING;
        }
    }

    private void handleDepositingItems() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
            return;
        }

        if (!hasItemsToDeposit()) {
            if (notifications.get()) info("All items deposited!");
            mc.player.closeHandledScreen();
            transferDelayCounter = 10;
            currentState = State.DISCONNECTING;
            return;
        }

        if (isChestFull(handler)) {
            if (notifications.get()) ChatUtils.error("Ender chest is full! Disconnecting.");
            currentState = State.DISCONNECTING;
            return;
        }

        transferNextItem(handler);

        if (tickCounter > 600) {
            if (notifications.get()) ChatUtils.error("Deposit timed out!");
            currentState = State.DISCONNECTING;
        }
    }

    private void handleDisconnecting() {
        stopBreaking();
        mc.options.sneakKey.setPressed(false);

        if (notifications.get()) info("Disconnecting due to player: " + detectedPlayer);

        if (mc.world != null) {
            mc.world.disconnect(Text.literal("SpawnerProtect+: Player detected - " + detectedPlayer));
        }

        toggle();
    }

    // --- Helpers ---

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        double maxDistSq = (double) spawnerRange.get() * spawnerRange.get();

        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()),
                playerPos.add(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {

            if (mc.world.getBlockState(pos).getBlock() != Blocks.SPAWNER) continue;
            if (invalidSpawners.contains(pos)) continue;

            double distSq = pos.getSquaredDistance(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            if (distSq > maxDistSq) continue;

            if (distSq < nearestDist) {
                nearestDist = distSq;
                nearest = pos.toImmutable();
            }
        }

        return nearest;
    }

    private BlockPos findNearestEnderChest() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-16, -8, -16),
                playerPos.add(16, 8, 16))) {

            if (mc.world.getBlockState(pos).getBlock() != Blocks.ENDER_CHEST) continue;

            double dist = pos.getSquaredDistance(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos.toImmutable();
            }
        }

        return nearest;
    }

    private FindItemResult findSilkTouchPickaxe() {
        return InvUtils.find(stack -> {
            if (!stack.isIn(ItemTags.PICKAXES)) return false;
            for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
                if (entry.getKey().matchesKey(Enchantments.SILK_TOUCH)) return true;
            }
            return false;
        });
    }

    private Direction getExposedSide(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            if (mc.world.getBlockState(neighbor).isAir()
                    || !mc.world.getBlockState(neighbor).isFullCube(mc.world, neighbor)) {
                return side;
            }
        }
        return Direction.UP;
    }

    private void lookAtBlock(BlockPos pos, Direction side) {
        Vec3d target = Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5));
        Vec3d eye = mc.player.getEyePos();
        Vec3d dir = target.subtract(eye).normalize();

        targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));
        rotating = true;
    }

    private void smoothRotate() {
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();

        float yawDiff = wrapDegrees(targetYaw - yaw);
        float pitchDiff = targetPitch - pitch;

        mc.player.setYaw(yaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED));
        mc.player.setPitch(pitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED));

        if (Math.abs(yawDiff) < 1f && Math.abs(pitchDiff) < 1f) rotating = false;
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }

    private void stopBreaking() {
        mc.options.attackKey.setPressed(false);
    }

    private void scanForPlayers() {
        if (mc.world == null || mc.player == null) return;
        if (findNearestSpawner() == null) return;
        for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (!(p instanceof AbstractClientPlayerEntity acp)) continue;
            String name = p.getGameProfile().name();
            if (isWhitelisted(name)) continue;
            if (isStaffByPrefix(acp)) {
                if (notifications.get()) info("Ignored (staff prefix): " + name);
                continue;
            }
            detectedPlayer = name;
            disableAutoReconnect();
            currentState = State.COLLECTING_SPAWNERS;
            if (notifications.get()) info("Player detected: " + name + " - starting protection!");
            return;
        }
    }

    private boolean isStaffByPrefix(AbstractClientPlayerEntity player) {
        if (!ignoreAlphabetPrefix.get()) return false;
        String tag    = player.getDisplayName().getString();
        String name   = player.getGameProfile().name();
        int idx = tag.indexOf(name);
        if (idx <= 0) return false;
        String prefix = tag.substring(0, idx);
        return prefix.chars().anyMatch(Character::isLetter);
    }

    private boolean isWhitelisted(String name) {
        if (!enableWhitelist.get() || whitelistPlayers.get().isEmpty()) return false;
        return whitelistPlayers.get().stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    private void disableAutoReconnect() {
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            if (notifications.get()) info("AutoReconnect disabled.");
        }
    }

    private boolean isVitalItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR) return true;
        if (depositBlacklist.get().contains(stack.getItem())) return true;
        if (stack.getItem() == Items.ENDER_CHEST) return true;
        return false;
    }

    private boolean hasItemsToDeposit() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isVitalItem(stack)) return true;
        }
        return false;
    }

    private boolean isChestFull(GenericContainerScreenHandler handler) {
        int chestSlots = handler.slots.size() - 36;
        for (int i = 0; i < chestSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    private void transferNextItem(GenericContainerScreenHandler handler) {
        int chestSlots = handler.slots.size() - 36;
        int playerStart = chestSlots;

        // Spawners first
        for (int i = 0; i < 36; i++) {
            int slotId = playerStart + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                clickSlot(handler, slotId, stack);
                return;
            }
        }

        // Then other non-vital items
        for (int i = 0; i < 36; i++) {
            int slotId = playerStart + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && !isVitalItem(stack)) {
                clickSlot(handler, slotId, stack);
                return;
            }
        }
    }

    private void clickSlot(GenericContainerScreenHandler handler, int slotId, ItemStack stack) {
        if (notifications.get()) info("Depositing: " + stack.getItem());
        mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
        lastProcessedSlot = slotId;
        transferDelayCounter = 2;
    }
}
