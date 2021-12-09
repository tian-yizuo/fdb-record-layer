/*
 * LuceneIndexMaintainer.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.IndexScanType;
import com.apple.foundationdb.record.IsolationLevel;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.lucene.synonymandngram.SynonymAndNgramAnalyzerFactory;
import com.apple.foundationdb.record.metadata.IndexAggregateFunction;
import com.apple.foundationdb.record.metadata.IndexRecordFunction;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBIndexableRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerState;
import com.apple.foundationdb.record.provider.foundationdb.IndexOperation;
import com.apple.foundationdb.record.provider.foundationdb.IndexOperationResult;
import com.apple.foundationdb.record.provider.foundationdb.indexes.InvalidIndexEntry;
import com.apple.foundationdb.record.provider.foundationdb.indexes.StandardIndexMaintainer;
import com.apple.foundationdb.record.query.QueryToKeyMatcher;
import com.apple.foundationdb.tuple.Tuple;
import com.google.common.base.Verify;
import com.google.protobuf.Message;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.apple.foundationdb.record.lucene.IndexWriterCommitCheckAsync.getOrCreateIndexWriter;
import static com.apple.foundationdb.record.lucene.LuceneKeyExpression.listIndexFieldNames;
import static com.apple.foundationdb.record.lucene.LuceneKeyExpression.validateLucene;

/**
 * Index maintainer for Lucene Indexes backed by FDB.  The insert, update, and delete functionality
 * coupled with the scan functionality is implemented here.
 *
 */
@API(API.Status.EXPERIMENTAL)
public class LuceneIndexMaintainer extends StandardIndexMaintainer {
    private static final Logger LOG = LoggerFactory.getLogger(LuceneIndexMaintainer.class);
    private final Analyzer indexAnalyzer;
    private final Analyzer queryAnalyzer;
    protected static final String PRIMARY_KEY_FIELD_NAME = "p"; // TODO: Need to find reserved names..
    private static final String PRIMARY_KEY_SEARCH_NAME = "s"; // TODO: Need to find reserved names..
    private final Executor executor;
    private final Function<String, List<String>> fieldSplitter;

    public LuceneIndexMaintainer(@Nonnull final IndexMaintainerState state, @Nonnull Executor executor,
                                 @Nonnull Analyzer indexAnalyzer, @Nonnull Analyzer queryAnalyzer,
                                 @Nonnull Function<String, List<String>> fieldSplitter) {
        super(state);
        this.indexAnalyzer = indexAnalyzer;
        this.queryAnalyzer = queryAnalyzer;
        KeyExpression rootExpression = this.state.index.getRootExpression();
        validateLucene(rootExpression);
        this.executor = executor;
        this.fieldSplitter = fieldSplitter;
    }

    /**
     * The scan uses the low element in the range to execute the
     * MultiFieldQueryParser.
     *
     * @param scanType the {@link IndexScanType type} of scan to perform
     * @param range the range to scan
     * @param continuation any continuation from a previous scan invocation
     * @param scanProperties skip, limit and other properties of the scan
     * @return RecordCursor
     */
    @Nonnull
    @Override
    public RecordCursor<IndexEntry> scan(@Nonnull IndexScanType scanType, @Nonnull TupleRange range,
                                         @Nullable byte[] continuation,
                                         @Nonnull ScanProperties scanProperties) {
        LOG.trace("scan scanType={}", scanType);
        Verify.verify(range.getLow() != null);
        Verify.verify(scanType == IndexScanType.BY_LUCENE || scanType == IndexScanType.BY_LUCENE_FULL_TEXT);
        try {
            // This cannot work with nested documents the way that we currently use them. BlockJoin will be essential for this
            // functionality in this way.
            QueryParser parser;
            if (scanType == IndexScanType.BY_LUCENE_FULL_TEXT) {
                final List<String> fieldNames = listIndexFieldNames(state.index.getRootExpression()).stream().map(fieldSplitter::apply).flatMap(List::stream).collect(Collectors.toList());
                parser = new MultiFieldQueryParser(fieldNames.toArray(new String[fieldNames.size()]), queryAnalyzer);
                parser.setDefaultOperator(QueryParser.Operator.OR);
            } else {
                // initialize default to scan primary key.
                parser = new QueryParser(PRIMARY_KEY_SEARCH_NAME, queryAnalyzer);
            }
            Query query1 = parser.parse(range.getLow().getString(0));
            Query query2 = parser.parse("ng-text:(+\"unit\" AND +\"organism\" AND +\"lay\")");
            BooleanQuery booleanQuery = new BooleanQuery.Builder().add(query1, BooleanClause.Occur.SHOULD).add(query2, BooleanClause.Occur.SHOULD).build();
            return new LuceneRecordCursor(executor, scanProperties, state, booleanQuery, continuation,
                    state.index.getRootExpression().normalizeKeyForPositions(), Tuple.fromStream(range.getLow().stream().skip(1)));
        } catch (Exception ioe) {
            throw new RecordCoreArgumentException("Unable to parse range given for query", "range", range,
                    "internalException", ioe);
        }
    }

    private void insertField(LuceneDocumentFromRecord.DocumentEntry entry, final Document document) {

        Object value = entry.value;
        String fieldName = entry.fieldName;
        LuceneFieldKeyExpression expression = entry.expression;
        switch (expression.getType()) {
            case INT:
                // Todo: figure out how to expand functionality to include storage, sorting etc.
                document.add(new IntPoint(fieldName, (Integer)value));
                break;
            case STRING:
                for (String field : fieldSplitter.apply(fieldName)) {
                    document.add(new TextField(field, value == null ? "" : value.toString(), expression.isStored() ? Field.Store.YES : Field.Store.NO));
                }
                break;
            case LONG:
                document.add(new LongPoint(fieldName, (long)value));
                break;
            default:
                throw new RecordCoreArgumentException("Invalid type for lucene index field", "type", entry.expression.getType());
        }
    }

    private void writeDocument(@Nonnull List<LuceneDocumentFromRecord.DocumentEntry> keys, Tuple groupingKey, byte[] primaryKey) throws IOException {
        final IndexWriter newWriter = getOrCreateIndexWriter(state, indexAnalyzer, executor, groupingKey);
        BytesRef ref = new BytesRef(primaryKey);
        Document document = new Document();
        document.add(new StoredField(PRIMARY_KEY_FIELD_NAME, ref));
        document.add(new SortedDocValuesField(PRIMARY_KEY_SEARCH_NAME, ref));
        for (LuceneDocumentFromRecord.DocumentEntry entry : keys ) {
            insertField(entry, document);
        }
        newWriter.addDocument(document);
    }

    private void deleteDocument(Tuple groupingKey, byte[] primaryKey) throws IOException {
        final IndexWriter oldWriter = getOrCreateIndexWriter(state, indexAnalyzer, executor, groupingKey);
        Query query = SortedDocValuesField.newSlowExactQuery(PRIMARY_KEY_SEARCH_NAME, new BytesRef(primaryKey));
        oldWriter.deleteDocuments(query);
    }

    @Nonnull
    @Override
    public <M extends Message> CompletableFuture<Void> update(@Nullable FDBIndexableRecord<M> oldRecord,
                                                              @Nullable FDBIndexableRecord<M> newRecord) {
        LOG.trace("update oldRecord={}, newRecord={}", oldRecord, newRecord);

        // Extract information for grouping from old and new records
        final KeyExpression root = state.index.getRootExpression();
        final Map<Tuple, List<LuceneDocumentFromRecord.DocumentEntry>> oldRecordFields = LuceneDocumentFromRecord.getRecordFields(root, oldRecord);
        final Map<Tuple, List<LuceneDocumentFromRecord.DocumentEntry>> newRecordFields = LuceneDocumentFromRecord.getRecordFields(root, newRecord);

        final Set<Tuple> unchanged = new HashSet<>();
        for (Map.Entry<Tuple, List<LuceneDocumentFromRecord.DocumentEntry>> entry : oldRecordFields.entrySet()) {
            if (entry.getValue().equals(newRecordFields.get(entry.getKey()))) {
                unchanged.add(entry.getKey());
            }
        }
        for (Tuple t : unchanged) {
            newRecordFields.remove(t);
            oldRecordFields.remove(t);
        }

        LOG.trace("update oldFields={}, newFields{}", oldRecordFields, newRecordFields);

        // delete old
        try {
            for (Tuple t : oldRecordFields.keySet()) {
                deleteDocument(t, oldRecord.getPrimaryKey().pack());
            }
        } catch (IOException e) {
            throw new RecordCoreException("Issue deleting old index keys", "oldRecord", oldRecord, e);
        }

        // update new
        try {
            for (Map.Entry<Tuple, List<LuceneDocumentFromRecord.DocumentEntry>> entry : newRecordFields.entrySet()) {
                writeDocument(entry.getValue(), entry.getKey(), newRecord.getPrimaryKey().pack());
            }
        } catch (IOException e) {
            throw new RecordCoreException("Issue updating new index keys", "newRecord", newRecord, e);
        }

        return AsyncUtil.DONE;
    }

    @Nonnull
    @Override
    public RecordCursor<IndexEntry> scanUniquenessViolations(@Nonnull TupleRange range, @Nullable byte[] continuation, @Nonnull ScanProperties scanProperties) {
        LOG.trace("scanUniquenessViolations");
        return RecordCursor.empty();
    }

    @Nonnull
    @Override
    public RecordCursor<InvalidIndexEntry> validateEntries(@Nullable byte[] continuation, @Nullable ScanProperties scanProperties) {
        LOG.trace("validateEntries");
        return RecordCursor.empty();
    }

    @Override
    public boolean canEvaluateRecordFunction(@Nonnull IndexRecordFunction<?> function) {
        LOG.trace("canEvaluateRecordFunction() function={}", function);
        return false;
    }

    @Nonnull
    @Override
    public <T, M extends Message> CompletableFuture<T> evaluateRecordFunction(@Nonnull EvaluationContext context,
                                                                              @Nonnull IndexRecordFunction<T> function,
                                                                              @Nonnull FDBRecord<M> record) {
        LOG.warn("evaluateRecordFunction() function={}", function);
        return unsupportedRecordFunction(function);
    }

    @Override
    public boolean canEvaluateAggregateFunction(@Nonnull IndexAggregateFunction function) {
        LOG.trace("canEvaluateAggregateFunction() function={}", function);
        return false;
    }

    @Nonnull
    @Override
    public CompletableFuture<Tuple> evaluateAggregateFunction(@Nonnull IndexAggregateFunction function,
                                                              @Nonnull TupleRange range,
                                                              @Nonnull IsolationLevel isolationLevel) {
        LOG.warn("evaluateAggregateFunction() function={}", function);
        return unsupportedAggregateFunction(function);
    }

    @Override
    public boolean isIdempotent() {
        LOG.trace("isIdempotent()");
        return true;
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> addedRangeWithKey(@Nonnull Tuple primaryKey) {
        LOG.trace("addedRangeWithKey primaryKey={}", primaryKey);
        return AsyncUtil.READY_FALSE;
    }

    @Override
    public boolean canDeleteWhere(@Nonnull QueryToKeyMatcher matcher, @Nonnull Key.Evaluated evaluated) {
        LOG.trace("canDeleteWhere matcher={}", matcher);
        return false;
    }

    @Override
    @Nonnull
    public CompletableFuture<Void> deleteWhere(Transaction tr, @Nonnull Tuple prefix) {
        LOG.trace("deleteWhere transaction={}, prefix={}", tr, prefix);
        return AsyncUtil.DONE;
    }

    @Override
    @Nonnull
    public CompletableFuture<IndexOperationResult> performOperation(@Nonnull IndexOperation operation) {
        LOG.trace("performOperation operation={}", operation);
        return CompletableFuture.completedFuture(new IndexOperationResult() {
        });
    }


}
