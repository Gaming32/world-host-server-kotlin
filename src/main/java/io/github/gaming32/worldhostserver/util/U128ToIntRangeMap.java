package io.github.gaming32.worldhostserver.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Arrays;

public class U128ToIntRangeMap {
    private long[] keys;
    private int[] values;
    private int size = 0;

    public U128ToIntRangeMap() {
        this(16);
    }

    public U128ToIntRangeMap(int initialCapacity) {
        keys = new long[initialCapacity * 4];
        values = new int[initialCapacity];
    }

    public int size() {
        return size;
    }

    public void trimToSize() {
        if (size == values.length) return;
        keys = Arrays.copyOf(keys, size << 2);
        values = Arrays.copyOf(values, size);
    }

    private int valueIndexToKeyIndex(int valueIndex) {
        return valueIndex << 2;
    }

    public void put(@NotNull BigInteger start, @NotNull BigInteger end, int value) {
        if (size == values.length) {
            keys = Arrays.copyOf(keys, keys.length + (keys.length >> 1));
            values = Arrays.copyOf(values, values.length + (values.length >> 1));
        }
        // check that insertion is in order
        BigInteger lastEnd = size == 0 ? null : get(keys, (size - 1) << 1);
        if (lastEnd != null && lastEnd.compareTo(start) >= 0) {
            throw new IllegalArgumentException("Ranges must be inserted in order");
        }
        int keyIndex = valueIndexToKeyIndex(size);
        keys[keyIndex] = start.shiftRight(64).longValue();
        keys[keyIndex + 1] = start.longValue();
        keys[keyIndex + 2] = end.shiftRight(64).longValue();
        keys[keyIndex + 3] = end.longValue();
        values[size++] = value;
    }

    @Nullable
    public Integer get(@NotNull BigInteger key) {
        int index = binarySearch(keys, size << 1, key);
        if (index < 0) {
            index = -index - 1;
        }
        if ((index & 1) == 1 || (index < (size << 1) && get(keys, index).equals(key))) {
            return values[index >> 1];
        }
        return null;
    }

    private static BigInteger fromU128(long high, long low) {
        return toUnsignedBigInteger(high).shiftLeft(64).add(toUnsignedBigInteger(low));
    }

    private static BigInteger toUnsignedBigInteger(long i) {
        if (i >= 0L) {
            return BigInteger.valueOf(i);
        } else {
            int upper = (int)(i >>> 32);
            int lower = (int)i;
            return BigInteger.valueOf(Integer.toUnsignedLong(upper))
                .shiftLeft(32)
                .add(BigInteger.valueOf(Integer.toUnsignedLong(lower)));
        }
    }

    private static int binarySearch(long[] a, int size, BigInteger key) {
        int low = 0;
        int high = size - 1;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final BigInteger midVal = get(a, mid);

            final int compare = midVal.compareTo(key);
            if (compare < 0)
                low = mid + 1;
            else if (compare > 0)
                high = mid - 1;
            else
                return mid;
        }
        return -(low + 1);
    }

    private static BigInteger get(long[] a, int index) {
        return fromU128(a[index << 1], a[(index << 1) + 1]);
    }
}
