/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.viaversion.viafabricplus.injection.mixin.features.bedrock.chunk;

import com.viaversion.viaversion.libs.fastutil.ints.IntList;
import net.raphimc.viabedrock.api.chunk.bitarray.BitArray;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents IndexOutOfBoundsException in Bedrock chunk/biome palette decoding
 * (common on some servers like Lifeboat) from disconnecting the client.
 * Out-of-range palette indices are treated as air (0).
 */
@Mixin(value = BedrockDataPalette.class, remap = false)
public abstract class MixinBedrockDataPalette {

    @Shadow
    @Final
    private IntList palette;

    @Shadow
    private BitArray bitArray;

    @Inject(method = "idAt", at = @At("HEAD"), cancellable = true)
    private void viaFabricPlus$safeIdAt(final int sectionCoordinate, final CallbackInfoReturnable<Integer> cir) {
        try {
            if (this.palette == null || this.bitArray == null || this.palette.isEmpty()) {
                cir.setReturnValue(0);
                return;
            }
            final int index = this.bitArray.get(sectionCoordinate);
            if (index < 0 || index >= this.palette.size()) {
                cir.setReturnValue(0);
            }
        } catch (Throwable t) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "idByIndex", at = @At("HEAD"), cancellable = true)
    private void viaFabricPlus$safeIdByIndex(final int index, final CallbackInfoReturnable<Integer> cir) {
        try {
            if (this.palette == null || this.palette.isEmpty() || index < 0 || index >= this.palette.size()) {
                cir.setReturnValue(0);
            }
        } catch (Throwable t) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "paletteIndexAt", at = @At("HEAD"), cancellable = true)
    private void viaFabricPlus$safePaletteIndexAt(final int packedCoordinate, final CallbackInfoReturnable<Integer> cir) {
        try {
            if (this.bitArray == null) {
                cir.setReturnValue(0);
            }
        } catch (Throwable t) {
            cir.setReturnValue(0);
        }
    }
}
