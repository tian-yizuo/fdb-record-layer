/*
 * LuceneIndexMaintainerValidator.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexValidator;
import com.apple.foundationdb.record.metadata.MetaDataException;
import com.apple.foundationdb.record.metadata.MetaDataValidator;
import com.apple.foundationdb.record.metadata.RecordType;
import com.google.common.annotations.VisibleForTesting;

import javax.annotation.Nonnull;

/**
 * Validator for Lucene indexes.
 */
@API(API.Status.EXPERIMENTAL)
public class LuceneIndexValidator extends IndexValidator {
    public LuceneIndexValidator(@Nonnull final Index index) {
        super(index);
    }

    @Override
    public void validate(@Nonnull MetaDataValidator metaDataValidator) {
        super.validate(metaDataValidator);
        validateNotVersion();
        for (RecordType recordType : metaDataValidator.getRecordMetaData().recordTypesForIndex(index)) {
            LuceneIndexExpressions.validate(index.getRootExpression(), recordType.getDescriptor());
        }

        validateIndexOptions(index);
    }

    @VisibleForTesting
    public static void validateIndexOptions(@Nonnull Index index) {
        validateAnalyzerNamePerFieldOption(LuceneIndexOptions.LUCENE_ANALYZER_NAME_PER_FIELD_OPTION, index);
        validateAnalyzerNamePerFieldOption(LuceneIndexOptions.AUTO_COMPLETE_ANALYZER_NAME_PER_FIELD_OPTION, index);
        validateAutoCompleteExcludedFields(index);
    }

    private static void validateAnalyzerNamePerFieldOption(@Nonnull String optionKey, @Nonnull Index index) {
        String analyzerNamePerFieldOption = index.getOption(optionKey);
        if (analyzerNamePerFieldOption != null) {
            analyzerNamePerFieldOption = analyzerNamePerFieldOption.strip();
            if (analyzerNamePerFieldOption.startsWith(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS)
                    || analyzerNamePerFieldOption.endsWith(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS)) {
                throw new MetaDataException("Index " + index.getName() + " has invalid option value for " + optionKey + ": " + analyzerNamePerFieldOption);
            }
            final String[] keyValuePairs = analyzerNamePerFieldOption.split(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS);
            for (int i = 0 ; i < keyValuePairs.length; i++) {
                final String keyValue = keyValuePairs[i].strip();
                int firstIndex = keyValue.indexOf(LuceneIndexOptions.DELIMITER_BETWEEN_KEY_AND_VALUE);
                if (keyValue.isEmpty()
                        || firstIndex < 1 || firstIndex > keyValue.length() - 2
                        || firstIndex != keyValue.lastIndexOf(LuceneIndexOptions.DELIMITER_BETWEEN_KEY_AND_VALUE)) {
                    throw new MetaDataException("Index " + index.getName() + " has invalid option value for " + optionKey + ": " + analyzerNamePerFieldOption);
                }
            }
        }
    }

    private static void validateAutoCompleteExcludedFields(@Nonnull Index index) {
        String autoCompleteExcludedFieldsOption = index.getOption(LuceneIndexOptions.AUTO_COMPLETE_EXCLUDED_FIELDS);
        if (autoCompleteExcludedFieldsOption != null) {
            autoCompleteExcludedFieldsOption = autoCompleteExcludedFieldsOption.strip();
            if (autoCompleteExcludedFieldsOption.startsWith(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS)
                    || autoCompleteExcludedFieldsOption.endsWith(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS)
                    || autoCompleteExcludedFieldsOption.contains(LuceneIndexOptions.DELIMITER_BETWEEN_KEY_AND_VALUE)) {
                throw new MetaDataException("Index " + index.getName() + " has invalid option value for " + LuceneIndexOptions.AUTO_COMPLETE_EXCLUDED_FIELDS + ": " + autoCompleteExcludedFieldsOption);
            }
            final String[] fieldNames = autoCompleteExcludedFieldsOption.split(LuceneIndexOptions.DELIMITER_BETWEEN_ELEMENTS);
            for (int i = 0; i < fieldNames.length; i++) {
                final String fieldName = fieldNames[i].strip();
                if (fieldName.isEmpty()) {
                    throw new MetaDataException("Index " + index.getName() + " has invalid option value for " + LuceneIndexOptions.AUTO_COMPLETE_EXCLUDED_FIELDS + ": " + autoCompleteExcludedFieldsOption);
                }
            }
        }
    }
}
