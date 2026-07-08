package com.burtleburtle.jenny.bootstrap;

/**
 * Thrown when {@link TupleEnumerator#enumerate} would admit more allowed
 * tuples than {@link TupleEnumerator#TUPLE_CAP}. Mirrors the Go port's
 * {@code tupleCap}/{@code tooLarge} bound (jennygo's {@code cmd/jennygo/jenny.go}):
 * pathological {@code -n}/dimension-size combinations fail cleanly here
 * instead of growing the enumerated-tuple list without bound.
 */
public final class TupleEnumerationTooLargeException extends RuntimeException {

    public TupleEnumerationTooLargeException(String message) {
        super(message);
    }
}
