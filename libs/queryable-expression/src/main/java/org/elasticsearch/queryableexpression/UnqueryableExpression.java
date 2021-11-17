/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.queryableexpression;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;

import java.util.List;
import java.util.function.LongFunction;

/**
 * An expression that approximates itself as {@link MatchAllDocsQuery}.
 */
class UnqueryableExpression implements QueryableExpression, LongQueryableExpression {
    static final UnqueryableExpression UNQUERYABLE = new UnqueryableExpression();

    UnqueryableExpression() {}

    @Override
    public QueryableExpression add(QueryableExpression rhs) {
        return this;
    }

    @Override
    public QueryableExpression multiply(QueryableExpression rhs) {
        return this;
    }

    @Override
    public QueryableExpression divide(QueryableExpression rhs) {
        return this;
    }

    @Override
    public LongQueryableExpression castToLong() {
        return this;
    }

    @Override
    public Query approximateTermQuery(long term) {
        return new MatchAllDocsQuery();
    }

    @Override
    public Query approximateRangeQuery(long lower, long upper) {
        return new MatchAllDocsQuery();
    }

    @Override
    public QueryableExpression mapConstant(LongFunction<QueryableExpression> map) {
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    @Override
    public List<String> expectedFields() {
        return List.of();
    }
}
