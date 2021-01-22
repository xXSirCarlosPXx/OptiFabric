package me.modmuss50.optifabric.compat.fabricrendererapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.reflect.FieldUtils;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;

import me.modmuss50.optifabric.compat.EmptyMixinPlugin;

public class RendererMixinPlugin extends EmptyMixinPlugin {
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		switch (mixinInfo.getName()) {
		case "BlockModelRendererMixin":
		case "BlockRenderManagerMixin": {
			ClassInfo info = ClassInfo.forName(targetClassName);
			Set<String> known = info.getMethods().stream().map(method -> method.getName().concat(method.getDesc())).collect(Collectors.toSet());
			List<Method> extra = new ArrayList<>();

			for (MethodNode method : targetClass.methods) {
				if (!known.contains(method.name.concat(method.desc)) && method.name.charAt(0) != '<') {
					extra.add(info.new Method(method));
				}
			}

			if (!extra.isEmpty()) {
				try {
					@SuppressWarnings("unchecked") //We need to add to this...
					Set<Method> methods = (Set<Method>) FieldUtils.readDeclaredField(info, "methods", true);
					methods.addAll(extra);
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException("Unable to add extra " + extra.size() + " methods to " + info + "'s class info", e);
				}
			}
			break;
		}
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}