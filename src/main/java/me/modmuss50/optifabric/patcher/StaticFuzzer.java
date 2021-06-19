package me.modmuss50.optifabric.patcher;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Map.Entry;

import org.apache.commons.lang3.tuple.Pair;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.launch.common.FabricLauncherBase;

import net.fabricmc.tinyremapper.IMappingProvider.Member;

import me.modmuss50.optifabric.util.ASMUtils;
import me.modmuss50.optifabric.util.ThrowingFunction;

public class StaticFuzzer extends LambdaRebuilder implements ThrowingFunction<InputStream, byte[], IOException>, Closeable {
	private final Map<String, Map<String, String>> fuzzes;
	private final JarFile vanilla;

	public StaticFuzzer() throws IOException {
		this(null);
	}

	public StaticFuzzer(Map<String, Map<String, String>> fuzzes) throws IOException {
		this.fuzzes = fuzzes;
		vanilla = new JarFile(FabricLauncherBase.minecraftJar.toFile());
	}

	private ClassNode getVanilla(String name) {
		try {
			JarEntry entry = vanilla.getJarEntry(name.concat(".class"));
			return entry != null ? ASMUtils.readClass(vanilla, entry) : null;
		} catch (IOException e) {
			throw new RuntimeException("Error reading vanilla class " + name + " from " + vanilla.getName(), e);
		}
	}

	public byte[] apply(byte[] in) {
		assert fuzzes == null;
		ClassReader reader = new ClassReader(in);

		ClassNode vanilla = getVanilla(reader.getClassName()); 
		if (vanilla == null) {
			System.err.println("Failed to find " + reader.getClassName());
			return in;
		}
		ClassNode optifine = new ClassNode();
		reader.accept(optifine, 0);
		findLambdas(vanilla, optifine);

		Map<String, String> remapped = new HashMap<>();
		Map<String, String> toCheck = new HashMap<>();
		for (Entry<Member, Pair<String, String>> entry : fixes.entrySet()) {
			Member lambda = entry.getKey();
			Pair<String, String> remap = entry.getValue();
			assert lambda.owner.equals(reader.getClassName());
			remapped.put(lambda.name.concat(lambda.desc), remap.getLeft());
			toCheck.put(remap.getLeft().concat(lambda.desc), remap.getLeft().concat(remap.getRight()));
		}

		assert remapped.size() == toCheck.size();
		if (toCheck.isEmpty()) {
			assert fixes.isEmpty();
			return in;
		} else {
			fixes.clear();
			for (MethodNode method : optifine.methods) {
				for (AbstractInsnNode insn : method.instructions) {
					switch (insn.getType()) {
					case AbstractInsnNode.METHOD_INSN: {
						MethodInsnNode minsn = (MethodInsnNode) insn;

						if (optifine.name.equals(minsn.owner)) {
							String remap = remapped.get(minsn.name.concat(minsn.desc));
							if (remap != null) minsn.name = remap;
						}
						break;
					}

					case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
						InvokeDynamicInsnNode dinsn = (InvokeDynamicInsnNode) insn;

						if (MethodComparison.isJavaLambdaMetafactory(dinsn.bsm)) {
							Handle lambda = (Handle) dinsn.bsmArgs[1];
							if (optifine.name.equals(lambda.getOwner())) {
								String remap = remapped.get(lambda.getName().concat(lambda.getDesc()));
								if (remap != null) dinsn.bsmArgs[1] = new Handle(lambda.getTag(), lambda.getOwner(), remap, lambda.getDesc(), lambda.isInterface());
							}
						}
						break;
					}
					}
				}
			}
			return apply(reader, vanilla, optifine, toCheck);			
		}
	}

	@Override
	@SuppressWarnings("deprecation") //Reduce, reuse, recycle
	public byte[] apply(InputStream in) throws IOException {
		assert fuzzes != null;
		ClassReader reader = new ClassReader(in);

		Map<String, String> toCheck = fuzzes.get(reader.getClassName());
		if (toCheck == null) return reader.b;

		ClassNode optifine = new ClassNode();
		reader.accept(optifine, 0);
		ClassNode vanilla = getVanilla(reader.getClassName());
		return vanilla != null ? apply(reader, vanilla, optifine, toCheck) : reader.b;
	}

	private byte[] apply(ClassReader reader, ClassNode vanilla, ClassNode optifine, Map<String, String> toCheck) {
		fix(toCheck, vanilla, optifine);

		ClassWriter writer = new ClassWriter(reader, 0);
		optifine.accept(writer);
		return writer.toByteArray();		
	}

	private void fix(Map<String, String> toCheck, ClassNode minecraft, ClassNode optifine) {
		Object2IntMap<String> memberToAccess = new Object2IntOpenHashMap<>(minecraft.methods.size());
		memberToAccess.defaultReturnValue(-1);
		for (MethodNode method : minecraft.methods) {
			String key = method.name.concat(method.desc);
			memberToAccess.put(key, method.access);
		}

		Set<String> staticFlip = new HashSet<>();
		for (MethodNode method : optifine.methods) {
			String key = method.name.concat(method.desc);

			String remap = toCheck.get(key);
			if (remap == null) continue;
			int access = memberToAccess.getInt(remap);
			if (access == -1) throw new IllegalStateException("Unable to find vanilla method " + minecraft.name + '#' + remap);

			if (Modifier.isStatic(method.access) != Modifier.isStatic(access)) {
				if (Modifier.isPrivate(method.access)) {//We'll just locally fix the difference
					staticFlip.add(key);
				} else {
					if (Modifier.isStatic(method.access)) {//Become static, previously wasn't
						Type[] args = Type.getArgumentTypes(method.desc);

						if (args.length >= 1 && optifine.name.equals(args[0].getInternalName())) {//Could we fix it quickly?
							staticFlip.add(method.name.concat(method.desc));									
						} else {//Not really
							System.err.println("Method has become static: " + optifine.name + '#' + key);
						}
					} else {//No longer static, previously was
						System.err.println("Method is no longer static: " + optifine.name + '#' + key);
					}
				}
			}
		}

		if (!staticFlip.isEmpty()) {
			for (MethodNode method : optifine.methods) {
				if (staticFlip.contains(method.name.concat(method.desc))) {
					if (Modifier.isStatic(method.access ^= Modifier.STATIC)) {//Method made static, need to add this as a parameter
						method.desc = "(L" + optifine.name + ';' + method.desc.substring(1);
					} else {//Method no longer static, need to add a this somehow
						Type[] args = Type.getArgumentTypes(method.desc);

						if (args.length >= 1 && optifine.name.equals(args[0].getInternalName())) {//Just remove the leading type argument
							method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), Arrays.copyOfRange(args, 1, args.length));									
						} else {//More of a nuisance, all the LVT indices will need bumping up by one
							throw new UnsupportedOperationException("Removing static from " + optifine.name + '#' + method.name + method.desc + " is a bit more effort");
						}
					}
				}

				for (AbstractInsnNode insn : method.instructions) {
					switch (insn.getType()) {
					case AbstractInsnNode.METHOD_INSN: {
						MethodInsnNode minsn = (MethodInsnNode) insn;

						if (optifine.name.equals(minsn.owner) && staticFlip.contains(minsn.name.concat(minsn.desc))) {
							minsn.setOpcode(minsn.getOpcode() == Opcodes.INVOKESTATIC ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC);
						}
						break;
					}

					case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
						InvokeDynamicInsnNode dinsn = (InvokeDynamicInsnNode) insn;

						if (MethodComparison.isJavaLambdaMetafactory(dinsn.bsm)) {
							Handle lambda = (Handle) dinsn.bsmArgs[1];
							if (optifine.name.equals(lambda.getOwner()) && staticFlip.contains(lambda.getName().concat(lambda.getDesc()))) {
								dinsn.bsmArgs[1] = new Handle(lambda.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.H_INVOKEVIRTUAL : Opcodes.H_INVOKESTATIC,
																lambda.getOwner(), lambda.getName(), lambda.getDesc(), lambda.isInterface());
							}
						}
						break;
					}
					}
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		vanilla.close();
	}
}