package me.modmuss50.optifabric.patcher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.tuple.Pair;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.launch.common.FabricLauncherBase;

public class StaticFuzzer extends LambdaRebuilder {
	public StaticFuzzer() throws IOException {
		super(FabricLauncherBase.minecraftJar.toFile());
	}

	public byte[] apply(byte[] in) throws IOException {
		ClassReader reader = new ClassReader(in);

		ClassNode optifine = new ClassNode();
		reader.accept(optifine, 0);
		findLambdas(optifine);

		Map<String, String> remapped = new HashMap<>();
		for (Entry<Member, Pair<String, String>> entry : fuzzes.entrySet()) {
			Member lambda = entry.getKey();
			Pair<String, String> remap = entry.getValue();
			assert lambda.owner.equals(reader.getClassName());
			remapped.put(lambda.name.concat(lambda.desc), remap.getLeft());
		}

		if (remapped.isEmpty()) {
			assert fuzzes.isEmpty();
			return in;
		} else {
			fuzzes.clear();

			for (MethodNode method : optifine.methods) {
				String remap = remapped.get(method.name.concat(method.desc));
				if (remap != null) method.name = remap;

				for (AbstractInsnNode insn : method.instructions) {
					switch (insn.getType()) {
					case AbstractInsnNode.METHOD_INSN: {
						MethodInsnNode minsn = (MethodInsnNode) insn;

						if (optifine.name.equals(minsn.owner)) {
							remap = remapped.get(minsn.name.concat(minsn.desc));
							if (remap != null) minsn.name = remap;
						}
						break;
					}

					case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
						InvokeDynamicInsnNode dinsn = (InvokeDynamicInsnNode) insn;

						if (MethodComparison.isJavaLambdaMetafactory(dinsn.bsm)) {
							Handle lambda = (Handle) dinsn.bsmArgs[1];
							if (optifine.name.equals(lambda.getOwner())) {
								remap = remapped.get(lambda.getName().concat(lambda.getDesc()));
								if (remap != null) dinsn.bsmArgs[1] = new Handle(lambda.getTag(), lambda.getOwner(), remap, lambda.getDesc(), lambda.isInterface());
							}
						}
						break;
					}
					}
				}
			}

			ClassWriter writer = new ClassWriter(reader, 0);
			optifine.accept(writer);
			return writer.toByteArray();
		}
	}
}