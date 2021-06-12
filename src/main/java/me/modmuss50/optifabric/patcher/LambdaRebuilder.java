/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.modmuss50.optifabric.patcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Runnables;

import org.apache.commons.lang3.tuple.Pair;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor;
import net.fabricmc.tinyremapper.IMappingProvider.Member;

import me.modmuss50.optifabric.util.ASMUtils;

public class LambdaRebuilder {
	private static final boolean ALLOW_VAGUE_EQUIVALENCE = !Boolean.getBoolean("optifabric.exactOnly");
	private final File optifineFile;
	private final File minecraftClientFile;
	protected final Map<Member, Pair<String, String>> fixes = new HashMap<>();

	public static void main(String... args) throws IOException {
		if (args == null || args.length != 2) {
			System.out.println("Usage: <vanilla_class> <optifine_class>");
			return;
		}

		File vanilla = new File(args[0]);
		if (!vanilla.exists() || !vanilla.isFile()) {
			System.err.println("Invalid vanilla class: " + args[0]);
			System.exit(1);
		}
		File optifine = new File(args[1]);
		if (!optifine.exists() || !optifine.isFile()) {
			System.err.println("Invalid OptiFine class: " + args[0]);
			System.exit(1);
		}

		ClassNode minecraft = ASMUtils.readClass(vanilla);
		ClassNode patched = ASMUtils.readClass(optifine);

		LambdaRebuilder rebuilder = new LambdaRebuilder();
		int unsolved = rebuilder.findLambdas(minecraft.name, minecraft.methods, patched.methods);

		System.out.printf(unsolved == 0 ? "Fully matched up %d lambdas:%n" : "Partially matched %d/%d lambdas%n", rebuilder.fixes.size(), rebuilder.fixes.size() + unsolved);
		for (Entry<Member, Pair<String, String>> entry : rebuilder.fixes.entrySet()) {
			Member lambda = entry.getKey();
			Pair<String, String> remap = entry.getValue();
			System.out.printf("\t%s#%s%s => %s%n", lambda.owner, lambda.name, lambda.desc, remap.getLeft(), remap.getRight());
		}
	}

	protected LambdaRebuilder() {
		minecraftClientFile = optifineFile = null;
	}

	public LambdaRebuilder(File optifineFile, File minecraftClientFile) throws IOException {
		this.optifineFile = optifineFile;
		this.minecraftClientFile = minecraftClientFile;
	}

	public void buildLambdaMap() throws IOException {
		try (JarFile optifineJar = new JarFile(optifineFile); JarFile clientJar = new JarFile(minecraftClientFile)) {
			Enumeration<JarEntry> entrys = optifineJar.entries();

			while (entrys.hasMoreElements()) {
				JarEntry entry = entrys.nextElement();
				String name = entry.getName();

				if (name.endsWith(".class") && !name.startsWith("net/") && !name.startsWith("optifine/") && !name.startsWith("javax/")) {
					ClassNode classNode = ASMUtils.readClass(optifineJar, entry);
					ClassNode minecraftClass = ASMUtils.readClass(clientJar, Objects.requireNonNull(clientJar.getJarEntry(name), name.concat(" not present in vanilla")));

					findLambdas(minecraftClass, classNode);
				}
			}	
		}
	}

	protected int findLambdas(ClassNode original, ClassNode patched) {
		if (!original.name.equals(patched.name)) {
			throw new IllegalArgumentException("Patched class (" + patched.name + ") is not the same as the original (" + original.name + ')');
		}

		return findLambdas(original.name, original.methods, patched.methods);
	}

	private int findLambdas(String className, List<MethodNode> original, List<MethodNode> patched) {
		final Collector<MethodNode, ?, Map<String, MethodNode>> methodMapper = Collectors.toMap(method -> method.name.concat(method.desc), Function.identity());

		List<MethodComparison> commonMethods = new ArrayList<>();
		List<MethodNode> lostMethods = new ArrayList<>();
		List<MethodNode> gainedMethods = new ArrayList<>(); {
			Map<String, MethodNode> originalMethods = original.stream().collect(methodMapper);
			Map<String, MethodNode> patchedMethods = patched.stream().collect(methodMapper);

			for (String methodName : Sets.union(originalMethods.keySet(), patchedMethods.keySet())) {
				MethodNode originalMethod = originalMethods.get(methodName);
				MethodNode patchedMethod = patchedMethods.get(methodName);

				if (originalMethod != null) {
					if (patchedMethod != null) {//Both have the method
						commonMethods.add(new MethodComparison(originalMethod, patchedMethod));
					} else {//Just the original has the method
						lostMethods.add(originalMethod);
					}
				} else if (patchedMethod != null) {//Just the modified has the method
					gainedMethods.add(patchedMethod);
				} else {//Neither have the method?!
					throw new IllegalStateException("Unable to find " + methodName + " in either " + className + " versions");
				}
			}

			commonMethods.sort(Comparator.comparingInt(method -> !"<clinit>".equals(method.node.name) ? patched.indexOf(method.node) : "com/mojang/blaze3d/platform/GLX".equals(className) ? patched.size() : -1));
			lostMethods.sort(Comparator.comparingInt(original::indexOf));
			gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
		}


		//Make sure at least one method contains lambdas, and there are both lost and gained methods which probably are a lambda 
		if (commonMethods.stream().noneMatch(method -> !method.equal && method.hasLambdas()) || lostMethods.isEmpty() || gainedMethods.isEmpty()) return 0;


		//The collection of lambdas we're looking to fix, any others are irrelevant from the point of view that they're probably fine
		Map<String, MethodNode> possibleLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).collect(methodMapper);
		if (possibleLambdas.isEmpty()) return 0; //Nothing looks like a lambda 
		Map<String, MethodNode> nameToLosses = lostMethods.stream().collect(methodMapper);

		for (int i = 0; i < commonMethods.size(); i++) {//Indexed for loop as each added fix will add to commonMethods
			MethodComparison method = commonMethods.get(i);

			if (method.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, method, nameToLosses, possibleLambdas);
		}


		for (int i = 0; i < commonMethods.size(); i++) {
			MethodComparison method = commonMethods.get(i);
			if (method.effectivelyEqual) continue; //Already handled this method

			List<Lambda> originalLambdas = method.getOriginalLambads();
			List<Lambda> patchedLambdas = method.getPatchedLambads();

			out: if (originalLambdas.size() == patchedLambdas.size()) {
				for (Iterator<Lambda> itOriginal = originalLambdas.iterator(), itPatched = patchedLambdas.iterator(); itOriginal.hasNext() && itPatched.hasNext();) {
					Lambda originalLambda = itOriginal.next();
					Lambda patchedLambda = itPatched.next();

					//Check if the lambdas are acting as the same method implementation
					if (!Objects.equals(originalLambda.method, patchedLambda.method)) {
						int originalSplit = originalLambda.method.indexOf('(');
						int patchedSplit = patchedLambda.method.indexOf('(');

						//They're not the same exact methods, but they might produce the same result in such a way that it doesn't matter (this ignores the case where equivalently looking lambdas are switched)
						if (originalSplit != patchedSplit || !originalLambda.method.regionMatches(0, patchedLambda.method, 0, originalSplit) || !Type.getReturnType(originalLambda.method).equals(Type.getReturnType(patchedLambda.method))) break out;
						//System.out.printf("Proposing fuzzing %s as %s, producing %s <= %s%n", patchedLambda.method, originalLambda.method, originalLambda.getFullName(), patchedLambda.getName());
					}
				}

				pairUp(className, commonMethods, lostMethods, gainedMethods, originalLambdas, patchedLambdas, nameToLosses, possibleLambdas, () -> {
					for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
						MethodComparison innerMethod = commonMethods.get(j);

						if (innerMethod.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, innerMethod, nameToLosses, possibleLambdas);
					}
				});

				continue; //Matched all the lambdas up for method
			}

			Collector<Lambda, ?, Map<String, Map<String, List<Lambda>>>> lambdaCategorisation = Collectors.groupingBy(lambda -> lambda.desc, Collectors.groupingBy(lambda -> lambda.method));
			Map<String, Map<String, List<Lambda>>> descToOriginalLambda = originalLambdas.stream().collect(lambdaCategorisation);
			Map<String, Map<String, List<Lambda>>> descToPatchedLambda = patchedLambdas.stream().collect(lambdaCategorisation);

			Set<String> commonDescs = Sets.intersection(descToOriginalLambda.keySet(), descToPatchedLambda.keySet()); //Unique descriptions that are found in both the lost methods and gained lambdas
			if (!commonDescs.isEmpty()) {
				int fixedLambdas = 0;

				for (String desc : commonDescs) {
					Map<String, List<Lambda>> typeToOriginalLambda = descToOriginalLambda.get(desc);
					Map<String, List<Lambda>> typeToPatchedLambda = descToPatchedLambda.get(desc);

					for (String type : Sets.intersection(typeToOriginalLambda.keySet(), typeToPatchedLambda.keySet())) {
						List<Lambda> matchedOriginalLambdas = typeToOriginalLambda.get(type);
						List<Lambda> matchedPatchedLambdas = typeToPatchedLambda.get(type);

						if (matchedOriginalLambdas.size() == matchedPatchedLambdas.size()) {//Presume if the size is more than one they're in the same order
							fixedLambdas += matchedOriginalLambdas.size();

							pairUp(className, commonMethods, lostMethods, gainedMethods, matchedOriginalLambdas, matchedPatchedLambdas, nameToLosses, possibleLambdas, () -> {
								for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
									MethodComparison innerMethod = commonMethods.get(j);

									if (innerMethod.effectivelyEqual) resolveCloseMethod(className, commonMethods, lostMethods, gainedMethods, innerMethod, nameToLosses, possibleLambdas);
								}
							});
						}
					}
				}

				if (fixedLambdas == originalLambdas.size()) return 0; //Caught all the lambdas
			}
		}

		return possibleLambdas.size(); //All the lambda-like methods which could be matched up if possibleLambdas is empty
	}

	private void resolveCloseMethod(String className, List<MethodComparison> commonMethods, List<MethodNode> lostMethods, List<MethodNode> gainedMethods,
			MethodComparison method, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas) {
		assert method.effectivelyEqual;

		if (!method.equal) {
			if (method.getOriginalLambads().size() != method.getPatchedLambads().size()) {
				throw new IllegalStateException("Bytecode in " + className + '#' + method.node.name + method.node.desc + " appeared unchanged but lambda count changed?");
			}

			pairUp(className, commonMethods, lostMethods, gainedMethods, method.getOriginalLambads(), method.getPatchedLambads(), nameToLosses, possibleLambdas, Runnables.doNothing());
		}
	}

	private void pairUp(String className, List<MethodComparison> commonMethods, List<MethodNode> lostMethods, List<MethodNode> gainedMethods,
			List<Lambda> originalLambdas, List<Lambda> patchedLambdas, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas, Runnable onPair) {
		assert originalLambdas.size() == patchedLambdas.size(); //It would be silly to pair up lists which aren't the same length

		for (Iterator<Lambda> itOriginal = originalLambdas.iterator(), itPatched = patchedLambdas.iterator(); itOriginal.hasNext() && itPatched.hasNext();) {
			Lambda lost = itOriginal.next();
			Lambda gained = itPatched.next();

			if (!className.equals(lost.owner)) return;
			assert className.equals(gained.owner);

			MethodNode lostMethod = nameToLosses.remove(lost.getName());
			MethodNode gainedMethod = possibleLambdas.remove(gained.getName());

			if (lostMethod == null) {
				if (gainedMethod == null) {
					assert Objects.equals(lost.getFullName(), gained.getFullName());
					continue;
				} else {
					throw new IllegalStateException("Couldn't find original method for lambda: " + lost.getFullName());
				}
			} else if (gainedMethod == null) {
				throw new IllegalStateException("Couldn't find patched method for lambda: " + gained.getFullName());
			}

			if (addFix(className, commonMethods, gainedMethod, lostMethod)) {
				lostMethods.remove(lostMethod);
				gainedMethods.remove(gainedMethod);
				onPair.run();
			}
		}
	}

	private boolean addFix(String className, List<MethodComparison> commonMethods, MethodNode from, MethodNode to) {
		boolean vague = !from.desc.equals(to.desc); //Are we trying to fudge a fix?

		if (vague && !ALLOW_VAGUE_EQUIVALENCE) {
			System.err.println("Description changed remapping lambda handle: " + className + '#' + from.name + from.desc + " => " + className + '#' + to.name + to.desc);
			return false; //Don't add the fix if it is wrong
		} else if (vague) {
			System.out.printf("Fuzzing %s#%s%s as %s%s%n", className, from.name, from.desc, to.name, to.desc);
		}

		fixes.put(new Member(className, from.name, from.desc), Pair.of(to.name, to.desc));

		from.name = to.name; //Apply the rename to the actual method node too
		commonMethods.add(new MethodComparison(to, from, vague));
		return true;
	}

	public Map<String, Map<String, String>> load(String from, MappingAcceptor out) {
		MappingResolver mapper = FabricLoader.getInstance().getMappingResolver();
		Map<String, Map<String, String>> fuzzes = ALLOW_VAGUE_EQUIVALENCE ? new HashMap<>() : Collections.emptyMap();

		for (Entry<Member, Pair<String, String>> entry : fixes.entrySet()) {
			Member lambda = entry.getKey();
			Pair<String, String> remap = entry.getValue();

			String lambdaOwner = lambda.owner.replace('/', '.');
			String vanilla = mapper.mapMethodName(from, lambdaOwner, remap.getLeft(), remap.getRight());
			out.acceptMethod(lambda, vanilla);

			if (!lambda.desc.equals(remap.getRight())) {
				assert ALLOW_VAGUE_EQUIVALENCE;
				fuzzes.computeIfAbsent(mapper.mapClassName(from, lambdaOwner).replace('.', '/'), k -> new HashMap<>()).put(map(mapper, from, vanilla, lambda.desc), map(mapper, from, vanilla, remap.getRight()));
			}
		}

		return fuzzes;
	}

	private static String map(MappingResolver mapper, String from, String name, String desc) {
		StringBuffer buf = new StringBuffer(name);

		Matcher matcher = Pattern.compile("L([^;/]+?);").matcher(desc);
		while (matcher.find()) {
			matcher.appendReplacement(buf, Matcher.quoteReplacement('L' + mapper.mapClassName(from, matcher.group(1).replace('/', '.')).replace('.', '/') + ';'));
		}

		return matcher.appendTail(buf).toString();
	}
}
