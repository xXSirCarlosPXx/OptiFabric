package me.modmuss50.optifabric.compat.origins;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.tinyremapper.IMappingProvider.Member;

import me.modmuss50.optifabric.compat.InterceptingMixinPlugin;
import me.modmuss50.optifabric.util.RemappingUtils;

public class OriginsMixinPlugin extends InterceptingMixinPlugin {
	protected void addFocusedEntity(InsnList extra, Member getFocusedEntity) {
		extra.add(new InsnNode(Opcodes.ACONST_NULL));
		extra.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, getFocusedEntity.owner, getFocusedEntity.name, getFocusedEntity.desc, false));
	}

	protected String fogStart() {
		return "fogStart";
	}

	protected String fogEnd() {
		return "fogEnd";
	}

	protected Member getElytraMixinTarget() {//ItemStack, getItem, ()Item
		return RemappingUtils.mapMethod("class_1799", "method_7909", "()Lnet/minecraft/class_1792;");
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		switch (mixinInfo.getName()) {
			case "BackgroundRendererMixin": {
				String renderDesc = "(Lnet/minecraft/class_4184;FLnet/minecraft/class_638;IF)V"; //(Camera, ClientWorld)
				String render = RemappingUtils.getMethodName("class_758", "method_3210", renderDesc); //BackgroundRenderer, render
				renderDesc = RemappingUtils.mapMethodDescriptor(renderDesc);
				//BackgroundRenderer, applyFog, (Camera, BackgroundRenderer$FogType)
				String applyFog = RemappingUtils.getMethodName("class_758", "method_3211", "(Lnet/minecraft/class_4184;Lnet/minecraft/class_758$class_4596;FZ)V");

				for (MethodNode method : targetClass.methods) {
					if (render.equals(method.name) && renderDesc.equals(method.desc)) {//Camera, getFocusedEntity, ()Entity
						Member getFocusedEntity = RemappingUtils.mapMethod("class_4184", "method_19331", "()Lnet/minecraft/class_1297;");
						LabelNode fakeStart = new LabelNode();
						LabelNode skip = new LabelNode();

						InsnList extra = new InsnList();
						extra.add(new JumpInsnNode(Opcodes.GOTO, skip));
						extra.add(fakeStart);
						extra.add(new VarInsnNode(Opcodes.DLOAD, 5));
						addFocusedEntity(extra, getFocusedEntity);
						extra.add(new InsnNode(Opcodes.POP2));
						extra.add(skip);

						method.instructions.insertBefore(method.instructions.getFirst(), extra);
						method.localVariables.add(new LocalVariableNode("fakeD", "D", null, fakeStart, skip, 5));
					} else if (applyFog.equals(method.name)) {
						LabelNode skip = new LabelNode();

						InsnList extra = new InsnList();
						extra.add(new JumpInsnNode(Opcodes.GOTO, skip));
						extra.add(new InsnNode(Opcodes.FCONST_0));
						extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/systems/RenderSystem", fogStart(), "(F)V", false));
						extra.add(new InsnNode(Opcodes.FCONST_1));
						extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/systems/RenderSystem", fogEnd(), "(F)V", false));
						extra.add(new InsnNode(Opcodes.FCONST_1));
						extra.add(new InsnNode(Opcodes.POP));
						extra.add(new LdcInsnNode(0.25F));
						extra.add(new LdcInsnNode(3F));
						extra.add(new InsnNode(Opcodes.POP2));
						extra.add(skip);

						method.instructions.insertBefore(method.instructions.getLast(), extra);
					}
				}
				break;
			}

			case "ElytraFeatureRendererMixin": {
				//ElytraFeatureRenderer, render, (MatrixStack, VertexConsumerProvider, LivingEntity)
				String render = RemappingUtils.getMethodName("class_979", "method_17161",
						"(Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;ILnet/minecraft/class_1309;FFFFFF)V");

				for (MethodNode method : targetClass.methods) {
					if (render.equals(method.name)) {//Origins does this to all methods called render
						Member target = getElytraMixinTarget();
						LabelNode skip = new LabelNode();

						InsnList extra = new InsnList();
						extra.add(new JumpInsnNode(Opcodes.GOTO, skip));
						extra.add(new InsnNode(Opcodes.ACONST_NULL));
						extra.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, target.owner, target.name, target.desc, false));
						extra.add(new InsnNode(Opcodes.POP));
						extra.add(skip);

						method.instructions.insertBefore(method.instructions.getLast(), extra);
					}
				}
				break;
			}
		}

		super.preApply(targetClassName, targetClass, mixinClassName, mixinInfo);
	}
}