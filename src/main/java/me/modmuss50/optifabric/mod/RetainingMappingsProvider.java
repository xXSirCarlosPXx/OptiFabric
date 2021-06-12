package me.modmuss50.optifabric.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor;

public class RetainingMappingsProvider implements MappingAcceptor, IMappingProvider {
	private static class LocalMapping {
		final Member method;
		final int lvtIndex;
		final int start;
		final int asmIndex;
		final String name;

		public LocalMapping(Member method, int lvtIndex, int start, int asmIndex, String name) {
			this.method = method;
			this.lvtIndex = lvtIndex;
			this.start = start;
			this.asmIndex = asmIndex;
			this.name = name;
		}
	}
	private final List<Pair<String, String>> classes = new ArrayList<>();
	private final List<Pair<Member, String>> methods = new ArrayList<>();
	private final List<Triple<Member, Integer, String>> parameters = new ArrayList<>();
	private final List<LocalMapping> locals = new ArrayList<>();
	private final List<Pair<Member, String>> fields = new ArrayList<>();

	@Override
	public void acceptClass(String srcName, String dstName) {
		classes.add(Pair.of(srcName, dstName));
	}

	@Override
	public void acceptMethod(Member method, String name) {
		methods.add(Pair.of(method, name));
	}

	@Override
	public void acceptMethodArg(Member method, int lvtIndex, String name) {
		parameters.add(Triple.of(method, lvtIndex, name));
	}

	@Override
	public void acceptMethodVar(Member method, int lvtIndex, int start, int asmIndex, String name) {
		locals.add(new LocalMapping(method, lvtIndex, start, asmIndex, name));
	}

	@Override
	public void acceptField(Member field, String name) {
		fields.add(Pair.of(field, name));
	}

	@Override
	public void load(MappingAcceptor out) {
		take(classes, out::acceptClass);
		take(methods, out::acceptMethod);
		take(fields, out::acceptField);

		for (Triple<Member, Integer, String> triple : parameters) {
			out.acceptMethodArg(triple.getLeft(), triple.getMiddle(), triple.getRight());
		}
		parameters.clear();

		for (LocalMapping mapping : locals) {
			out.acceptMethodVar(mapping.method, mapping.lvtIndex, mapping.start, mapping.asmIndex, mapping.name);
		}
		locals.clear();
	}

	private <T, U> void take(List<Pair<T, U>> things, BiConsumer<T, U> acceptor) {
		for (Pair<T, U> pair : things) {
			acceptor.accept(pair.getLeft(), pair.getRight());
		}
		things.clear();
	}
}