package me.modmuss50.optifabric.compat.fabricrendererapi;

import org.objectweb.asm.tree.ClassNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import me.modmuss50.optifabric.compat.EmptyMixinPlugin;
import me.modmuss50.optifabric.compat.LoudCoerce.CoercionApplicator;
import me.modmuss50.optifabric.util.MixinUtils;

public class RendererMixinPlugin extends EmptyMixinPlugin {
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		switch (mixinInfo.getName()) {
		case "BlockModelRendererMixin":
		case "BlockRenderManagerMixin": {
			ClassInfo info = ClassInfo.forName(targetClassName);
			MixinUtils.completeClassInfo(info, targetClass.methods);
			CoercionApplicator.preApply(mixinInfo, targetClass);
			break;
		}
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		switch (mixinInfo.getName()) {
		case "BlockModelRendererMixin":
		case "BlockRenderManagerMixin":
			CoercionApplicator.postApply(targetClass);
			break;
		}
	}
}