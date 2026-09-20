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

import net.raphimc.viabedrock.api.chunk.datapalette.BedrockBiomeArray;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guard biome array reads against out-of-bounds section coordinates.
 */
@Mixin(value = BedrockBiomeArray.class, remap = false)
public abstract class MixinBedrockBiomeArray {

    @Shadow
    @Final
    private byte[] biomes;

    @Inject(method = "idAt", at = @At("HEAD"), cancellable = true)
    private void viaFabricPlus$safeIdAt(final int sectionCoordinate, final CallbackInfoReturnable<Integer> cir) {
        if (this.biomes == null || sectionCoordinate < 0 || sectionCoordinate >= this.biomes.length) {
            cir.setReturnValue(0);
        }
    }
}
