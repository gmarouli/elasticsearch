/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.fielddata;

import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.LongValues;

import java.io.IOException;

/**
 * Clone of {@link SortedNumericDocValues} for numeric values.
 */
public interface SortedNumericValues {

    /** Advance the iterator to exactly {@code target} and return whether
     *  {@code target} has a value.
     *  {@code target} must be greater than or equal to the current
     *  doc ID and must be a valid doc ID, ie. &ge; 0 and
     *  &lt; {@code maxDoc}.*/
    boolean advanceExact(int target) throws IOException;

    /**
     * Iterates to the next value as a double in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    double nextDoubleValue() throws IOException;

    /**
     * Iterates to the next value as a long in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    long nextLongValue() throws IOException;

    /**
     * Retrieves the number of values for the current document.  This must always
     * be greater than zero.
     * It is illegal to call this method after {@link #advanceExact(int)}
     * returned {@code false}.
     */
    int docValueCount();

    /**
     * Converts a {@link SortedNumericValues} values to a singly valued {@link DoubleValues}
     * if possible
     */
    default DoubleValues unwrapSingletonDoubleValues() {
        return null;
    }

    /**
     * Converts a {@link SortedNumericValues} values to a singly valued {@link LongValues}
     * if possible
     */
    default LongValues unwrapSingletonLongValues() {
        return null;
    }

    /**
     * Converts a {@link DoubleValues} to a {@link SortedNumericValues}
     */
    static SortedNumericValues singleton(DoubleValues values) {
        return SortedNumericDoubleValues.singleton(values);
    }

    /**
     * Converts a {@link LongValues} to a {@link SortedNumericValues}
     */
    static SortedNumericValues singleton(LongValues values) {
        return SortedNumericLongValues.singleton(values);
    }
}
