/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.queryableexpression;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.NormsFieldExistsQuery;
import org.apache.lucene.search.Query;

import java.util.List;
import java.util.function.LongFunction;

/**
 * {@code long} flavored expression.
 */
public interface LongQueryableExpression extends QueryableExpression {
    interface LongQueries {
        Query approximateTermQuery(long term);

        Query approximateRangeQuery(long lower, long upper);
    }

    static LongQueryableExpression field(String name, LongQueries queries) {
        return new AbstractLongQueryableExpression.Field(name, queries);
    }

    interface IntQueries {
        Query approximateTermQuery(int term);

        Query approximateRangeQuery(int lower, int upper);
    }

    static LongQueryableExpression field(String name, IntQueries queries) {
        return field(name, new AbstractLongQueryableExpression.IntQueriesToLongQueries(queries));
    }

    /**
     * Build a query that approximates a term query on this expression.
     */
    Query approximateTermQuery(long term);

    /**
     * Build a query that approximates a range query on this expression.
     */
    Query approximateRangeQuery(long lower, long upper);

    /**
     * Transform this expression, returning an {@link UnqueryableExpression}
     * if there isn't a way to query.
     */
    QueryableExpression mapConstant(LongFunction<QueryableExpression> map);

    List<String> expectedFields();

    default Query approximateNullSafeTermQuery(long term) {
        return nullSafe(approximateTermQuery(term));
    }

    default Query approximateNullSafeRangeQuery(long lower, long upper) {
        return nullSafe(approximateRangeQuery(lower, upper));
    }

    private Query nullSafe(Query query) {
        List<String> fields = expectedFields();
        BooleanQuery.Builder fieldsQueryBuilder = new BooleanQuery.Builder();
        for (String field : fields) {
            fieldsQueryBuilder.add(new NormsFieldExistsQuery(field), BooleanClause.Occur.MUST_NOT);
        }

        return new BooleanQuery.Builder().add(query, BooleanClause.Occur.SHOULD)
            .add(fieldsQueryBuilder.build(), BooleanClause.Occur.SHOULD)
            .build();
    }
}
