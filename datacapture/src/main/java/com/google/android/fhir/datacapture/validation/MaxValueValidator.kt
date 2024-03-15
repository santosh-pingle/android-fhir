/*
 * Copyright 2023-2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.validation

import android.content.Context
import com.google.android.fhir.compareTo
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.getValueAsString
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Type

internal const val MAX_VALUE_EXTENSION_URL = "http://hl7.org/fhir/StructureDefinition/maxValue"

/** A validator to check if the value of an answer exceeded the permitted value. */
internal object MaxValueValidator :
  AnswerExtensionConstraintValidator(
    url = MAX_VALUE_EXTENSION_URL,
    predicate = {
      constraintValue: Type,
      answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent,
      ->
      answer.value > constraintValue
    },
    messageGenerator = { constraintValue: Type, context: Context ->
      context.getString(
        R.string.max_value_validation_error_msg,
        constraintValue.getValueAsString(context),
      )
    },
  )
