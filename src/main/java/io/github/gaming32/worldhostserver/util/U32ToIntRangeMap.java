package io.github.gaming32.worldhostserver.util;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static java.lang.Integer.compareUnsigned;

public class U32ToIntRangeMap {
    private int[] key;
    private int[] value;
    private int size;

    public U32ToIntRangeMap(int initialCapacity) {
        key = new int[initialCapacity << 1];
        value = new int[initialCapacity];
    }

    public U32ToIntRangeMap() {
        this(16);
    }

    public int size() {
        return size;
    }

    public void trimToSize() {
        if (size == value.length) return;
        key = Arrays.copyOf(key, size << 1);
        value = Arrays.copyOf(value, size);
    }

    public void put(int min, int max, int value) {
        final int keyIndex = size << 1;
        if (size > 0) {
            final int prevIndex = keyIndex - 2;
            final int prevMax = key[prevIndex + 1];
            if (compareUnsigned(min, prevMax) <= 0) {
                final int prevMin = key[prevIndex];
                throw new IllegalArgumentException(
                    "Range " + rangeToString(min, max) + " isn't greater than previous max range " + rangeToString(prevMin, prevMax)
                );
            }
        }
        if (size == this.value.length) {
            expand();
        }
        key[keyIndex] = min;
        key[keyIndex + 1] = max;
        this.value[size++] = value;
    }

    @Nullable
    public Integer get(int key) {
        int index = binarySearch(this.key, size << 1, key);
        if (index < 0) {
            index = -index - 1;
        }
        if ((index & 1) == 1 || (index < (size << 1) && this.key[index] == key)) {
            return value[index >> 1];
        }
        return null;
    }

    private void expand() {
        key = Arrays.copyOf(key, key.length + (key.length >> 1));
        value = Arrays.copyOf(value, value.length + (value.length >> 1));
    }

    private static String rangeToString(int min, int max) {
        return Integer.toUnsignedString(min) + "-" + Integer.toUnsignedString(max);
    }

    private static int binarySearch(int[] a, int size, int key) {
        int low = 0;
        int high = size - 1;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final int midVal = a[mid];

            final int compare = Integer.compareUnsigned(midVal, key);
            if (compare < 0)
                low = mid + 1;
            else if (compare > 0)
                high = mid - 1;
            else
                return mid;
        }
        return -(low + 1);
    }
}
