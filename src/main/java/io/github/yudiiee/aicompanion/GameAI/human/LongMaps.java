package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.Arrays;

/**
 * Small open-addressing hash maps keyed by packed block positions. The pathfinder looks up
 * hundreds of thousands of cells per search; boxed HashMap<Long, ...> entries made it
 * allocate a lot of short-lived garbage.
 */
final class LongMaps {
    private LongMaps() {}

    private static final long EMPTY = Long.MIN_VALUE + 1;

    private static int mix(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k;
    }

    /** long -> int, with a default for missing keys. */
    static final class LongInt {
        private long[] keys;
        private int[] vals;
        private int size, mask;

        LongInt(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
            keys = new long[cap];
            vals = new int[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
        }

        int get(long k, int missing) {
            int i = mix(k) & mask;
            while (true) {
                long c = keys[i];
                if (c == EMPTY) return missing;
                if (c == k) return vals[i];
                i = (i + 1) & mask;
            }
        }

        void put(long k, int v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = mix(k) & mask;
            while (true) {
                long c = keys[i];
                if (c == EMPTY) { keys[i] = k; vals[i] = v; size++; return; }
                if (c == k) { vals[i] = v; return; }
                i = (i + 1) & mask;
            }
        }

        int size() { return size; }

        private void grow() {
            long[] ok = keys;
            int[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new int[ok.length * 2];
            Arrays.fill(keys, EMPTY);
            mask = keys.length - 1;
            size = 0;
            for (int j = 0; j < ok.length; j++) if (ok[j] != EMPTY) put(ok[j], ov[j]);
        }
    }

    /** long -> object. */
    static final class LongObj<V> {
        private long[] keys;
        private Object[] vals;
        private int size, mask;

        LongObj(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
            keys = new long[cap];
            vals = new Object[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
        }

        @SuppressWarnings("unchecked")
        V get(long k) {
            int i = mix(k) & mask;
            while (true) {
                long c = keys[i];
                if (c == EMPTY) return null;
                if (c == k) return (V) vals[i];
                i = (i + 1) & mask;
            }
        }

        void put(long k, V v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = mix(k) & mask;
            while (true) {
                long c = keys[i];
                if (c == EMPTY) { keys[i] = k; vals[i] = v; size++; return; }
                if (c == k) { vals[i] = v; return; }
                i = (i + 1) & mask;
            }
        }

        int size() { return size; }

        @SuppressWarnings("unchecked")
        private void grow() {
            long[] ok = keys;
            Object[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new Object[ok.length * 2];
            Arrays.fill(keys, EMPTY);
            mask = keys.length - 1;
            size = 0;
            for (int j = 0; j < ok.length; j++) if (ok[j] != EMPTY) put(ok[j], (V) ov[j]);
        }
    }
}
