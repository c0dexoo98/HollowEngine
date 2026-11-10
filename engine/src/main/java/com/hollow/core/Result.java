package com.hollow.core;

/** Minimal Result type (CORE): explicit success/failure without exceptions in hot paths.
 *  Java lacks pattern matching here, so we keep it tiny: `Result.ok(v)` / `Result.fail(msg)`. */
public final class Result<V> {
    private static final Result<?> EMPTY_OK = new Result<>(null, null);
    private final V value;
    private final String error;

    private Result(V value, String error){ this.value = value; this.error = error; }

    @SuppressWarnings("unchecked")
    public static <V> Result<V> ok(V value){ return value == null ? (Result<V>) EMPTY_OK : new Result<>(value, null); }
    public static <V> Result<V> fail(String message){ return new Result<>(null, message == null ? "unknown error" : message); }

    public boolean isOk(){ return error == null; }
    public boolean isFail(){ return error != null; }
    public V value(){ return value; }
    public String error(){ return error; }

    /** value or a fallback */
    public V orElse(V fallback){ return error == null ? value : fallback; }
    /** value or throw IllegalStateException (for call sites that consider failure a bug) */
    public V unwrap(){ if(error != null) throw new IllegalStateException("unwrap of failed result: " + error); return value; }
    public <R> Result<R> map(java.util.function.Function<V, R> fn){
        return error != null ? fail(error) : ok(fn.apply(value));
    }

    @Override public String toString(){ return error != null ? "FAIL(" + error + ")" : "OK(" + value + ")"; }
}
