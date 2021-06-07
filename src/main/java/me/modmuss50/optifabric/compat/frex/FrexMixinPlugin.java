package me.modmuss50.optifabric.compat.frex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Iterator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;
import org.spongepowered.asm.util.Annotations;

import net.fabricmc.tinyremapper.IMappingProvider.Member;

import me.modmuss50.optifabric.compat.InterceptingMixinPlugin;
import me.modmuss50.optifabric.util.RemappingUtils;
import me.modmuss50.optifabric.util.MixinFinder.Mixin;

public class FrexMixinPlugin extends InterceptingMixinPlugin {
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.CLASS)
	public @interface CoercedInPlace {
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		switch (mixinInfo.getName()) {
		case "RebuildTaskMixin": {
			Mixin thisMixin = Mixin.create(mixinInfo);

			on: for (MethodNode method : thisMixin.getClassNode().methods) {
				if (Annotations.getVisible(method, Surrogate.class) != null) {
					String coercedDesc = coerceDesc(method);
					if (coercedDesc == null) continue; //Perfectly fine

					for (Method realMethod : thisMixin.getMethods()) {
						if (realMethod.getOriginalName().equals(method.name) && !method.desc.equals(realMethod.getOriginalDesc())) {
							Annotations.setInvisible(method, CoercedInPlace.class);
							method.name = realMethod.getName(); //Mangle name to whatever Mixin is using for the real injection
							method.desc = coercedDesc;

							targetClass.methods.add(method);
							continue on;
						}
					}

					throw new IllegalStateException("Cannot find original Mixin method for surrogate " + method.name + method.desc + " in " + thisMixin);	
				}
			}
		}
		case "old.RebuildTaskMixin": {//BlockPos, iterate, (BlockPos, BlockPos)
			Member iterate = RemappingUtils.mapMethod("class_2338", "method_10097", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;)Ljava/lang/Iterable;");
			String renderHandDesc = "(FFFLnet/minecraft/class_846$class_849;Lnet/minecraft/class_750;)Ljava/util/Set;"; //(ChunkBuilder$ChunkData, BlockBufferBuilderStorage)
			String renderHand = RemappingUtils.getMethodName("class_846$class_851$class_4578", "method_22785", renderHandDesc);
			renderHandDesc = RemappingUtils.mapMethodDescriptor(renderHandDesc); //^ ChunkBuilder$BuiltChunk$RebuildTask, render

			for (MethodNode method : targetClass.methods) {
				if (renderHand.equals(method.name) && renderHandDesc.equals(method.desc)) {
					LabelNode skip = new LabelNode();

					InsnList extra = new InsnList();
					extra.add(new JumpInsnNode(Opcodes.GOTO, skip));
					extra.add(new InsnNode(Opcodes.ACONST_NULL));
					extra.add(new InsnNode(Opcodes.ACONST_NULL));
					extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, iterate.owner, iterate.name, iterate.desc, false));
					extra.add(new InsnNode(Opcodes.POP));
					extra.add(skip);

					method.instructions.insertBefore(method.instructions.getLast(), extra);
					break;
				}
			}
			break;
		}
		}

		super.preApply(targetClassName, targetClass, mixinClassName, mixinInfo);
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if ("RebuildTaskMixin".equals(mixinInfo.getName())) {
			for (Iterator<MethodNode> it = targetClass.methods.iterator(); it.hasNext();) {
				MethodNode method = it.next();

				if (Annotations.getInvisible(method, CoercedInPlace.class) != null) {
					it.remove();
				} else if (Annotations.getVisible(method, Surrogate.class) != null) {
					String coercedDesc = coerceDesc(method);
					if (coercedDesc != null) method.desc = coercedDesc;
				}
			}
		}

		super.postApply(targetClassName, targetClass, mixinClassName, mixinInfo);
	}
}