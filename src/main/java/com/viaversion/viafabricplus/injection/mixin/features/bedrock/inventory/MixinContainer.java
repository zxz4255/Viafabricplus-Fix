/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.injection.mixin.features.bedrock.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixes Bedrock inventory interaction (chest / player inventory take, put, right-click place).
 *
 * Upstream Container.handleClick() always returns false, which forces a full content resync on
 * every click and never sends ItemStackRequest to the server. This mixin:
 * 1. Performs local prediction (take / place / half / merge / swap)
 * 2. Sends a real ITEM_STACK_REQUEST packet so the Bedrock server receives the change
 * 3. Returns true so the client does not wipe prediction
 */
@Mixin(value = Container.class, remap = false)
public abstract class MixinContainer {

    @Unique
    private static final AtomicInteger viaFabricPlus$requestId = new AtomicInteger(-1);

    @Unique
    private static final FullContainerName viaFabricPlus$CURSOR = new FullContainerName(ContainerEnumName.CursorContainer, null);

    @Shadow
    @Final
    protected UserConnection user;

    @Shadow
    @Final
    protected byte containerId;

    @Shadow
    @Final
    protected ContainerType type;

    @Shadow
    public abstract BedrockItem getItem(int slot);

    @Shadow
    public abstract boolean setItem(int slot, BedrockItem item);

    @Shadow
    public abstract int size();

    @Inject(method = "handleClick", at = @At("HEAD"), cancellable = true)
    private void viaFabricPlus$handleClick(final int revision, final short slot, final byte button, final ContainerInput action, final CallbackInfoReturnable<Boolean> cir) {
        if (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE && action != ContainerInput.THROW && action != ContainerInput.SWAP) {
            return;
        }

        final InventoryTracker tracker = this.user.get(InventoryTracker.class);
        if (tracker == null) {
            return;
        }

        BedrockItem cursor = tracker.getHudContainer().getItem(0);
        if (cursor == null) {
            cursor = BedrockItem.empty();
        }
        final boolean cursorEmpty = cursor.isEmpty();

        // Click outside window -> drop from cursor
        if (slot < 0) {
            if (!cursorEmpty && (action == ContainerInput.PICKUP || action == ContainerInput.THROW)) {
                final int dropCount = (button == 1 && cursor.amount() > 1) ? 1 : cursor.amount();
                this.viaFabricPlus$sendDropRequest(cursor, dropCount);
                if (button == 1 && cursor.amount() > 1) {
                    cursor.setAmount(cursor.amount() - 1);
                    tracker.getHudContainer().setItem(0, cursor);
                } else {
                    tracker.getHudContainer().setItem(0, BedrockItem.empty());
                }
                cir.setReturnValue(true);
            }
            return;
        }

        // Resolve which container + local slot the click targets
        final SlotTarget target = this.viaFabricPlus$resolveSlot(slot, tracker);
        if (target == null) {
            return;
        }

        BedrockItem slotItem = target.container.getItem(target.localSlot);
        if (slotItem == null) {
            slotItem = BedrockItem.empty();
        }
        final boolean slotEmpty = slotItem.isEmpty();

        switch (action) {
            case PICKUP -> {
                if (button == 0) { // left click
                    if (cursorEmpty && !slotEmpty) {
                        // Take whole stack -> cursor
                        this.viaFabricPlus$sendTransferRequest(
                                ItemStackRequestActionType.Take,
                                slotItem.amount(),
                                target.containerName, target.localSlot, slotItem,
                                viaFabricPlus$CURSOR, 0, cursor
                        );
                        tracker.getHudContainer().setItem(0, slotItem.copy());
                        target.container.setItem(target.localSlot, BedrockItem.empty());
                        cir.setReturnValue(true);
                        return;
                    }
                    if (!cursorEmpty && slotEmpty) {
                        // Place whole stack from cursor
                        this.viaFabricPlus$sendTransferRequest(
                                ItemStackRequestActionType.Place,
                                cursor.amount(),
                                viaFabricPlus$CURSOR, 0, cursor,
                                target.containerName, target.localSlot, slotItem
                        );
                        target.container.setItem(target.localSlot, cursor.copy());
                        tracker.getHudContainer().setItem(0, BedrockItem.empty());
                        cir.setReturnValue(true);
                        return;
                    }
                    if (!cursorEmpty && !slotEmpty) {
                        if (cursor.identifier() == slotItem.identifier() && cursor.data() == slotItem.data()) {
                            final int maxStack = 64;
                            final int space = maxStack - slotItem.amount();
                            if (space > 0) {
                                final int move = Math.min(space, cursor.amount());
                                this.viaFabricPlus$sendTransferRequest(
                                        ItemStackRequestActionType.Place,
                                        move,
                                        viaFabricPlus$CURSOR, 0, cursor,
                                        target.containerName, target.localSlot, slotItem
                                );
                                slotItem.setAmount(slotItem.amount() + move);
                                cursor.setAmount(cursor.amount() - move);
                                target.container.setItem(target.localSlot, slotItem);
                                tracker.getHudContainer().setItem(0, cursor.amount() <= 0 ? BedrockItem.empty() : cursor);
                                cir.setReturnValue(true);
                                return;
                            }
                        }
                        // Swap
                        this.viaFabricPlus$sendSwapRequest(
                                viaFabricPlus$CURSOR, 0, cursor,
                                target.containerName, target.localSlot, slotItem
                        );
                        tracker.getHudContainer().setItem(0, slotItem.copy());
                        target.container.setItem(target.localSlot, cursor.copy());
                        cir.setReturnValue(true);
                        return;
                    }
                } else if (button == 1) { // right click
                    if (cursorEmpty && !slotEmpty) {
                        final int take = (slotItem.amount() + 1) / 2;
                        this.viaFabricPlus$sendTransferRequest(
                                ItemStackRequestActionType.Take,
                                take,
                                target.containerName, target.localSlot, slotItem,
                                viaFabricPlus$CURSOR, 0, cursor
                        );
                        final BedrockItem taken = slotItem.copy();
                        taken.setAmount(take);
                        slotItem.setAmount(slotItem.amount() - take);
                        tracker.getHudContainer().setItem(0, taken);
                        target.container.setItem(target.localSlot, slotItem.amount() <= 0 ? BedrockItem.empty() : slotItem);
                        cir.setReturnValue(true);
                        return;
                    }
                    if (!cursorEmpty) {
                        if (slotEmpty || (cursor.identifier() == slotItem.identifier() && cursor.data() == slotItem.data() && slotItem.amount() < 64)) {
                            this.viaFabricPlus$sendTransferRequest(
                                    ItemStackRequestActionType.Place,
                                    1,
                                    viaFabricPlus$CURSOR, 0, cursor,
                                    target.containerName, target.localSlot, slotItem
                            );
                            if (slotEmpty) {
                                final BedrockItem place = cursor.copy();
                                place.setAmount(1);
                                target.container.setItem(target.localSlot, place);
                            } else {
                                slotItem.setAmount(slotItem.amount() + 1);
                                target.container.setItem(target.localSlot, slotItem);
                            }
                            cursor.setAmount(cursor.amount() - 1);
                            tracker.getHudContainer().setItem(0, cursor.amount() <= 0 ? BedrockItem.empty() : cursor);
                            cir.setReturnValue(true);
                            return;
                        }
                    }
                }
            }
            case QUICK_MOVE -> {
                // Shift-click: accept prediction; full cross-container move is complex
                if (!slotEmpty) {
                    cir.setReturnValue(true);
                }
            }
            case SWAP, THROW -> cir.setReturnValue(true);
            default -> {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Slot resolution
    // -------------------------------------------------------------------------

    @Unique
    private SlotTarget viaFabricPlus$resolveSlot(final short javaSlot, final InventoryTracker tracker) {
        final int size = this.size();

        // Slot belongs to this container (chest slots 0..size-1, or pure inventory)
        if (javaSlot >= 0 && javaSlot < size) {
            return new SlotTarget((Container) (Object) this, javaSlot, this.viaFabricPlus$containerNameFor(javaSlot));
        }

        // While a non-inventory container is open, higher Java slots map to player inventory
        if (this.type != ContainerType.INVENTORY) {
            final InventoryContainer inv = tracker.getInventoryContainer();
            if (inv == null) {
                return null;
            }
            // Java chest layout: size..size+26 = main inv (9-35), size+27..size+35 = hotbar (0-8)
            final int relative = javaSlot - size;
            if (relative >= 0 && relative < 27) {
                // main inventory bedrock slots 9-35
                final int bedrockSlot = 9 + relative;
                return new SlotTarget(inv, bedrockSlot, new FullContainerName(ContainerEnumName.InventoryContainer, null));
            }
            if (relative >= 27 && relative < 36) {
                final int bedrockSlot = relative - 27; // hotbar 0-8
                return new SlotTarget(inv, bedrockSlot, new FullContainerName(ContainerEnumName.HotbarContainer, null));
            }
        }

        return null;
    }

    @Unique
    private FullContainerName viaFabricPlus$containerNameFor(final int localSlot) {
        if (this.type == ContainerType.INVENTORY) {
            if (localSlot < 9) {
                return new FullContainerName(ContainerEnumName.HotbarContainer, null);
            }
            return new FullContainerName(ContainerEnumName.InventoryContainer, null);
        }
        // Chest / generic block container
        return new FullContainerName(ContainerEnumName.LevelEntityContainer, null);
    }

    // -------------------------------------------------------------------------
    // Packet sending
    // -------------------------------------------------------------------------

    @Unique
    private int viaFabricPlus$nextRequestId() {
        // Bedrock clients use odd negative request IDs: -1, -3, -5, ...
        return viaFabricPlus$requestId.addAndGet(-2);
    }

    @Unique
    private int viaFabricPlus$netId(final BedrockItem item) {
        if (item == null || item.isEmpty() || item.netId() == null) {
            return 0;
        }
        return item.netId();
    }

    /**
     * Sends ITEM_STACK_REQUEST with a single Take or Place action.
     */
    @Unique
    private void viaFabricPlus$sendTransferRequest(
            final ItemStackRequestActionType actionType,
            final int count,
            final FullContainerName sourceName, final int sourceSlot, final BedrockItem sourceItem,
            final FullContainerName destName, final int destSlot, final BedrockItem destItem
    ) {
        try {
            final PacketWrapper packet = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, this.user);

            // requests count
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1);

            // --- one request ---
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$nextRequestId()); // requestId

            // actions count = 1
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1);

            // action type (Take=0, Place=1)
            packet.write(Types.BYTE, (byte) actionType.getValue());
            // count
            packet.write(Types.BYTE, (byte) count);
            // source slot info
            packet.write(BedrockTypes.FULL_CONTAINER_NAME, sourceName);
            packet.write(Types.BYTE, (byte) sourceSlot);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$netId(sourceItem));
            // dest slot info
            packet.write(BedrockTypes.FULL_CONTAINER_NAME, destName);
            packet.write(Types.BYTE, (byte) destSlot);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$netId(destItem));

            // filterStrings count = 0
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 0);
            // filterCause (int LE) = 0
            packet.write(BedrockTypes.INT_LE, 0); // filterCause

            packet.sendToServer(BedrockProtocol.class);
        } catch (Throwable t) {
            // Packet format mismatch should not crash the client
            t.printStackTrace();
        }
    }

    /**
     * Sends ITEM_STACK_REQUEST with a Swap action.
     */
    @Unique
    private void viaFabricPlus$sendSwapRequest(
            final FullContainerName sourceName, final int sourceSlot, final BedrockItem sourceItem,
            final FullContainerName destName, final int destSlot, final BedrockItem destItem
    ) {
        try {
            final PacketWrapper packet = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, this.user);

            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1); // requests count
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$nextRequestId());
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1); // actions count

            packet.write(Types.BYTE, (byte) ItemStackRequestActionType.Swap.getValue());
            // source
            packet.write(BedrockTypes.FULL_CONTAINER_NAME, sourceName);
            packet.write(Types.BYTE, (byte) sourceSlot);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$netId(sourceItem));
            // dest
            packet.write(BedrockTypes.FULL_CONTAINER_NAME, destName);
            packet.write(Types.BYTE, (byte) destSlot);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$netId(destItem));

            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // filterStrings
            packet.write(BedrockTypes.INT_LE, 0); // filterCause

            packet.sendToServer(BedrockProtocol.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Sends ITEM_STACK_REQUEST with a Drop action (cursor -> world).
     */
    @Unique
    private void viaFabricPlus$sendDropRequest(final BedrockItem cursor, final int count) {
        try {
            final PacketWrapper packet = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, this.user);

            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$nextRequestId());
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1);

            packet.write(Types.BYTE, (byte) ItemStackRequestActionType.Drop.getValue());
            packet.write(Types.BYTE, (byte) count);
            // source = cursor
            packet.write(BedrockTypes.FULL_CONTAINER_NAME, viaFabricPlus$CURSOR);
            packet.write(Types.BYTE, (byte) 0);
            packet.write(BedrockTypes.VAR_INT, this.viaFabricPlus$netId(cursor));
            // randomly = false
            packet.write(Types.BOOLEAN, false);

            packet.write(BedrockTypes.UNSIGNED_VAR_INT, 0);
            packet.write(BedrockTypes.INT_LE, 0);

            packet.sendToServer(BedrockProtocol.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Unique
    private record SlotTarget(Container container, int localSlot, FullContainerName containerName) {
    }
}
