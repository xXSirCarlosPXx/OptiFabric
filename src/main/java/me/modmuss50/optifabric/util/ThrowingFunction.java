package me.modmuss50.optifabric.util;

import java.util.Objects;

public interface ThrowingFunction<T, R, E extends Exception> {
	R apply(T thing) throws E;

	default <V> ThrowingFunction<V, R, E> compose(ThrowingFunction<? super V, ? extends T, E> before) {
		Objects.requireNonNull(before);
		return v -> apply(before.apply(v));
	}

	default <V> ThrowingFunction<T, V, E> andThen(ThrowingFunction<? super R, ? extends V, E> after) {
		Objects.requireNonNull(after);
		return t -> after.apply(apply(t));
	}

	static <T, E extends Exception> ThrowingFunction<T, T, E> identity() {
		return t -> t;
	}
}