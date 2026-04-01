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
import org.apache.lucene.util.NumericUtils;

import java.io.IOException;

/**
 * Implements {@link IterableSortedNumericValues} and reads double doc values.
 */
public class IterableSortedNumericDoubleValues implements IterableSortedNumericValues {

    private final SortedNumericDocValues docValues;

    public IterableSortedNumericDoubleValues(SortedNumericDocValues docValues) {
        this.docValues = docValues;
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
        return docValues.advanceExact(target);
    }

    @Override
    public double nextDoubleValue() throws IOException {
        return NumericUtils.sortableLongToDouble(docValues.nextValue());
    }

    /**
     * Calls {@link #nextDoubleValue()} and casts it to long
     */
    @Override
    public long nextLongValue() throws IOException {
        return (long) nextDoubleValue();
    }

    @Override
    public int docValueCount() {
        return docValues.docValueCount();
    }

    @Override
    public int advance(int target) throws IOException {
        return docValues.advance(target);
    }

    @Override
    public int docID() {
        return docValues.docID();
    }
}
