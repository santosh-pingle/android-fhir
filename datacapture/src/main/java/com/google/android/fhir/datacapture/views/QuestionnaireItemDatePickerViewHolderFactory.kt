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

import android.annotation.SuppressLint
import android.content.Context
import android.icu.text.DateFormat
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.doAfterTextChanged
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.utilities.isAndroidIcuSupported
import com.google.android.fhir.datacapture.utilities.localizedString
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.validation.getSingleStringValidationMessage
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.ParseException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.QuestionnaireResponse

internal object QuestionnaireItemDatePickerViewHolderFactory :
  QuestionnaireItemViewHolderFactory(R.layout.questionnaire_item_date_picker_view) {
  override fun getQuestionnaireItemViewHolderDelegate() =
    object : QuestionnaireItemViewHolderDelegate {
      private lateinit var header: QuestionnaireItemHeaderView
      private lateinit var textInputLayout: TextInputLayout
      private lateinit var textInputEditText: TextInputEditText
      override lateinit var questionnaireItemViewItem: QuestionnaireItemViewItem
      private var textWatcher: TextWatcher? = null
      private val localePattern =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(
          FormatStyle.SHORT,
          null,
          IsoChronology.INSTANCE,
          Locale.getDefault()
        )

      override fun init(itemView: View) {
        header = itemView.findViewById(R.id.header)
        textInputLayout = itemView.findViewById(R.id.text_input_layout)
        textInputEditText = itemView.findViewById(R.id.text_input_edit_text)
        textInputLayout.setEndIconOnClickListener {
          // The application is wrapped in a ContextThemeWrapper in QuestionnaireFragment
          // and again in TextInputEditText during layout inflation. As a result, it is
          // necessary to access the base context twice to retrieve the application object
          // from the view's context.
          val context = itemView.context.tryUnwrapContext()!!
          createMaterialDatePicker()
            .apply {
              addOnPositiveButtonClickListener { epochMilli ->
                textInputEditText.setText(
                  Instant.ofEpochMilli(epochMilli).atZone(ZONE_ID_UTC).toLocalDate().localizedString
                )
                questionnaireItemViewItem.setAnswer(
                  QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                    val localDate =
                      Instant.ofEpochMilli(epochMilli).atZone(ZONE_ID_UTC).toLocalDate()
                    value = DateType(localDate.year, localDate.monthValue - 1, localDate.dayOfMonth)
                  }
                )
                // Clear focus so that the user can refocus to open the dialog
                textInputEditText.clearFocus()
              }
            }
            .show(context.supportFragmentManager, TAG)
        }
      }

      @SuppressLint("NewApi") // java.time APIs can be used due to desugaring
      override fun bind(questionnaireItemViewItem: QuestionnaireItemViewItem) {
        header.bind(questionnaireItemViewItem.questionnaireItem)
        textInputLayout.hint = localePattern
        textInputEditText.removeTextChangedListener(textWatcher)
        textInputEditText.setText(
          questionnaireItemViewItem.answers.singleOrNull()
            ?.valueDateType
            ?.localDate
            ?.localizedString
        )
        var parseJob: Job? = null
        textWatcher =
          textInputEditText.doAfterTextChanged { text ->
            // allow user to enter the input, e.g year may be y, yy, yyyy
            parseJob?.cancel()
            parseJob =
              CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                updateAnswer(text.toString())
              }
          }
      }

      override fun displayValidationResult(validationResult: ValidationResult) {
        textInputLayout.error =
          if (validationResult.getSingleStringValidationMessage() == "") null
          else validationResult.getSingleStringValidationMessage()
      }

      override fun setReadOnly(isReadOnly: Boolean) {
        textInputEditText.isEnabled = !isReadOnly
        textInputLayout.isEnabled = !isReadOnly
      }

      private fun createMaterialDatePicker(): MaterialDatePicker<Long> {
        val selectedDate =
          questionnaireItemViewItem
            .answers
            .singleOrNull()
            ?.valueDateType
            ?.localDate
            ?.atStartOfDay(ZONE_ID_UTC)
            ?.toInstant()
            ?.toEpochMilli()
            ?: MaterialDatePicker.todayInUtcMilliseconds()
        return MaterialDatePicker.Builder.datePicker()
          .setTitleText(R.string.select_date)
          .setSelection(selectedDate)
          .build()
      }

      private fun updateAnswer(text: CharSequence?) {
        var localDate: LocalDate? = null
        try {
          val date = parseDate(text, textInputEditText.context.applicationContext)
          localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (e: ParseException) {
          questionnaireItemViewItem.clearAnswer()
        }

        localDate?.run {
          questionnaireItemViewItem.setAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
              value = DateType(localDate!!.year, localDate!!.monthValue - 1, localDate!!.dayOfMonth)
            }
          )
        }
      }
    }
}

internal const val TAG = "date-picker"
internal val ZONE_ID_UTC = ZoneId.of("UTC")

/**
 * Returns the [AppCompatActivity] if there exists one wrapped inside [ContextThemeWrapper] s, or
 * `null` otherwise.
 *
 * This function is inspired by the function with the same name in `AppCompateDelegateImpl`. See
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:appcompat/appcompat/src/main/java/androidx/appcompat/app/AppCompatDelegateImpl.java;l=1615
 *
 * TODO: find a more robust way to do this as it is not guaranteed that the activity is an
 * AppCompatActivity.
 */
fun Context.tryUnwrapContext(): AppCompatActivity? {
  var context = this
  while (true) {
    when (context) {
      is AppCompatActivity -> return context
      is ContextThemeWrapper -> context = context.baseContext
      else -> return null
    }
  }
}

internal val DateType.localDate
  get() =
    LocalDate.of(
      year,
      month + 1,
      day,
    )

internal fun parseDate(text: CharSequence?, context: Context) =
  if (isAndroidIcuSupported()) {
    DateFormat.getDateInstance(DateFormat.SHORT).parse(text.toString())
  } else {
    android.text.format.DateFormat.getDateFormat(context).parse(text.toString())
  }
