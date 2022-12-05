/*
 * Copyright 2022 Google LLC
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

package com.google.android.fhir.datacapture.views

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.fhir.datacapture.DisplayItemControlType
import com.google.android.fhir.datacapture.EXTENSION_DISPLAY_CATEGORY_SYSTEM
import com.google.android.fhir.datacapture.EXTENSION_DISPLAY_CATEGORY_URL
import com.google.android.fhir.datacapture.EXTENSION_ITEM_CONTROL_SYSTEM
import com.google.android.fhir.datacapture.EXTENSION_ITEM_CONTROL_URL
import com.google.android.fhir.datacapture.INSTRUCTIONS
import com.google.android.fhir.datacapture.R
import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QuestionnaireItemHeaderViewInReviewModeTest {
  private val parent =
    FrameLayout(
      RuntimeEnvironment.getApplication().apply { setTheme(R.style.Theme_Material3_DayNight) }
    )
  private val view = QuestionnaireItemHeaderViewInReviewMode(parent.context, null)

  @Test
  fun shouldShowPrefix() {
    view.bind(Questionnaire.QuestionnaireItemComponent().apply { prefix = "Prefix?" })

    assertThat(view.findViewById<TextView>(R.id.prefix).isVisible).isTrue()
    assertThat(view.findViewById<TextView>(R.id.prefix).text.toString()).isEqualTo("Prefix?")
  }

  @Test
  fun shouldHidePrefix() {
    view.bind(Questionnaire.QuestionnaireItemComponent().apply { prefix = "" })

    assertThat(view.findViewById<TextView>(R.id.prefix).isVisible).isFalse()
  }

  @Test
  fun shouldShowQuestion() {
    view.bind(
      Questionnaire.QuestionnaireItemComponent().apply {
        repeats = true
        text = "Question?"
      }
    )

    assertThat(view.findViewById<TextView>(R.id.question).text.toString()).isEqualTo("Question?")
  }

  @Test
  fun `shows instructions`() {
    view.bind(
      Questionnaire.QuestionnaireItemComponent().apply {
        item =
          listOf(
            Questionnaire.QuestionnaireItemComponent().apply {
              linkId = "nested-display-question"
              text = "subtitle text"
              extension = listOf(displayCategoryExtensionWithInstructionsCode)
              type = Questionnaire.QuestionnaireItemType.DISPLAY
            }
          )
      }
    )

    assertThat(view.findViewById<TextView>(R.id.hint).isVisible).isTrue()
    assertThat(view.findViewById<TextView>(R.id.hint).text.toString()).isEqualTo("subtitle text")
  }

  @Test
  fun `hides instructions`() {
    view.bind(
      Questionnaire.QuestionnaireItemComponent().apply {
        item =
          listOf(
            Questionnaire.QuestionnaireItemComponent().apply {
              linkId = "nested-display-question"
              type = Questionnaire.QuestionnaireItemType.DISPLAY
            }
          )
      }
    )

    assertThat(view.findViewById<TextView>(R.id.hint).visibility).isEqualTo(View.GONE)
  }

  @Test
  fun `shows headerItem view`() {
    view.bind(
      Questionnaire.QuestionnaireItemComponent().apply {
        item =
          listOf(
            Questionnaire.QuestionnaireItemComponent().apply {
              linkId = "nested-display-question"
              text = "subtitle text"
              extension = listOf(displayCategoryExtensionWithInstructionsCode)
              type = Questionnaire.QuestionnaireItemType.DISPLAY
            }
          )
      }
    )

    assertThat(view.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun shouldHideHeaderView() {
    view.bind(Questionnaire.QuestionnaireItemComponent())

    assertThat(view.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun `shows * at the end of question text`() {
    view.bind(
      Questionnaire.QuestionnaireItemComponent().apply {
        text = "Question?"
        required = true
      }
    )

    assertThat(view.findViewById<TextView>(R.id.question).text.toString()).isEqualTo("Question? *")
  }

  @Test
  fun `does not show * at the end of question text`() {
    view.bind(Questionnaire.QuestionnaireItemComponent().apply { text = "Question?" })

    assertThat(view.findViewById<TextView>(R.id.question).text.toString()).isEqualTo("Question?")
  }

  @Test
  fun `shows * even if question text is missing`() {
    view.bind(Questionnaire.QuestionnaireItemComponent().apply { required = true })

    assertThat(view.findViewById<TextView>(R.id.question).text.toString())
      .isEqualTo(view.context.applicationContext.resources.getString(R.string.space_asterisk))
  }

  private val displayCategoryExtensionWithInstructionsCode =
    Extension().apply {
      url = EXTENSION_DISPLAY_CATEGORY_URL
      setValue(
        CodeableConcept().apply {
          coding =
            listOf(
              Coding().apply {
                code = INSTRUCTIONS
                system = EXTENSION_DISPLAY_CATEGORY_SYSTEM
              }
            )
        }
      )
    }

  private val itemControlExtensionWithHelpCode =
    Extension().apply {
      url = EXTENSION_ITEM_CONTROL_URL
      setValue(
        CodeableConcept().apply {
          coding =
            listOf(
              Coding().apply {
                code = DisplayItemControlType.HELP.extensionCode
                system = EXTENSION_ITEM_CONTROL_SYSTEM
              }
            )
        }
      )
    }
}