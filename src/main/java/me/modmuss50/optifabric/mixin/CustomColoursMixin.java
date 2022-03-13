package me.modmuss50.optifabric.mixin;

import org.objectweb.asm.Opcodes;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.BlockState;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.BlockRenderView;

@Pseudo
@Mixin(targets = "net/optifine/CustomColors", remap = false)
abstract class CustomColoursMixin {
	@Inject(method = "getColorMultiplier(ZLnet/minecraft/block/BlockState;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/util/math/BlockPos;Lnet/optifine/render/RenderEnv;)I", cancellable = true, remap = true,
			at = @At(value = "FIELD", target = "Lnet/minecraft/block/Blocks;LILY_PAD:Lnet/minecraft/block/Block;", opcode = Opcodes.GETSTATIC, remap = true))
	private static void skip(boolean quadHasTintIndex, BlockState blockState, BlockRenderView blockAccess, BlockPos blockPos, @Coerce Object renderEnv, CallbackInfoReturnable<Integer> call) {
		if (!"minecraft".equals(Registry.BLOCK.getId(blockState.getBlock()).getNamespace())) {
			call.setReturnValue(-1); //Avoid tinting a mod block which wouldn't otherwise be tinted
		}
	}

	@Shadow
	public static BlockColors getBlockColors() {
		throw new AssertionError("Unexpectedly reached code path");
	}
}
