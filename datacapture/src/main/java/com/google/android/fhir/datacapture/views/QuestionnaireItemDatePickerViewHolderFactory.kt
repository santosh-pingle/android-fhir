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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.utilities.canonicalizeDatePattern
import com.google.android.fhir.datacapture.utilities.format
import com.google.android.fhir.datacapture.utilities.getDateSeparator
import com.google.android.fhir.datacapture.utilities.parseDate
import com.google.android.fhir.datacapture.utilities.tryUnwrapContext
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.MaxValueConstraintValidator.getMaxValue
import com.google.android.fhir.datacapture.validation.MinValueConstraintValidator.getMinValue
import com.google.android.fhir.datacapture.validation.NotValidated
import com.google.android.fhir.datacapture.validation.Valid
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CalendarConstraints.DateValidator
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
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
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
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
      private lateinit var canonicalizedDatePattern: String
      private lateinit var textWatcher: DatePatternTextWatcher

      override fun init(itemView: View) {
        header = itemView.findViewById(R.id.header)
        textInputLayout = itemView.findViewById(R.id.text_input_layout)
        textInputEditText = itemView.findViewById(R.id.text_input_edit_text)
        textInputEditText.setOnFocusChangeListener { view, hasFocus ->
          if (!hasFocus) {
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
              .hideSoftInputFromWindow(view.windowToken, 0)
          }
        }
        textInputLayout.setEndIconOnClickListener {
          // The application is wrapped in a ContextThemeWrapper in QuestionnaireFragment
          // and again in TextInputEditText during layout inflation. As a result, it is
          // necessary to access the base context twice to retrieve the application object
          // from the view's context.
          val context = itemView.context.tryUnwrapContext()!!
          val localDateInput =
            questionnaireItemViewItem.answers.singleOrNull()?.valueDateType?.localDate
          buildMaterialDatePicker(localDateInput)
            .apply {
              addOnPositiveButtonClickListener { epochMilli ->
                with(Instant.ofEpochMilli(epochMilli).atZone(ZONE_ID_UTC).toLocalDate()) {
                  textInputEditText.setText(this?.format(canonicalizedDatePattern))
                }
                // Clear focus so that the user can refocus to open the dialog
                textInputEditText.clearFocus()
              }
            }
            .show(context.supportFragmentManager, TAG)
        }
        val localeDatePattern = getLocalizedDateTimePattern()
        // Special character used in date pattern
        val datePatternSeparator = getDateSeparator(localeDatePattern)
        textWatcher = DatePatternTextWatcher(datePatternSeparator)
        canonicalizedDatePattern = canonicalizeDatePattern(localeDatePattern)
      }

      @SuppressLint("NewApi") // java.time APIs can be used due to desugaring
      override fun bind(questionnaireItemViewItem: QuestionnaireItemViewItem) {
        clearPreviousState()
        header.bind(questionnaireItemViewItem.questionnaireItem)
        textInputLayout.hint = canonicalizedDatePattern
        textInputEditText.removeTextChangedListener(textWatcher)

        val questionnaireItemViewItemDateAnswer =
          questionnaireItemViewItem.answers.singleOrNull()?.valueDateType?.localDate

        val dateStringToDisplay =
          questionnaireItemViewItemDateAnswer?.format(canonicalizedDatePattern)
            ?: questionnaireItemViewItem.draftAnswer as? String

        if (textInputEditText.text.toString() != dateStringToDisplay) {
          textInputEditText.setText(dateStringToDisplay)
        }

        setQuestionnaireResponseAnswerAndValidate(questionnaireItemViewItem, dateStringToDisplay)
        textInputEditText.addTextChangedListener(textWatcher)
      }

      override fun setReadOnly(isReadOnly: Boolean) {
        textInputEditText.isEnabled = !isReadOnly
        textInputLayout.isEnabled = !isReadOnly
      }

      private fun buildMaterialDatePicker(localDate: LocalDate?): MaterialDatePicker<Long> {
        val selectedDateMillis =
          localDate?.atStartOfDay(ZONE_ID_UTC)?.toInstant()?.toEpochMilli()
            ?: MaterialDatePicker.todayInUtcMilliseconds()

        return MaterialDatePicker.Builder.datePicker()
          .setTitleText(R.string.select_date)
          .setSelection(selectedDateMillis)
          .setCalendarConstraints(getCalenderConstraint())
          .build()
      }

      private fun getCalenderConstraint(): CalendarConstraints {
        val min =
          (getMinValue(questionnaireItemViewItem.questionnaireItem) as? DateType)?.value?.time
        val max =
          (getMaxValue(questionnaireItemViewItem.questionnaireItem) as? DateType)?.value?.time

        if (min != null && max != null && min > max) {
          throw IllegalArgumentException("minValue cannot be greater than maxValue")
        }

        val listValidators = ArrayList<DateValidator>()
        min?.let { listValidators.add(DateValidatorPointForward.from(it)) }
        max?.let { listValidators.add(DateValidatorPointBackward.before(it)) }
        val validators = CompositeDateValidator.allOf(listValidators)

        return CalendarConstraints.Builder().setValidator(validators).build()
      }

      private fun clearPreviousState() {
        textInputEditText.isEnabled = true
        textInputLayout.isEnabled = true
      }

      /** Set the answer in the [QuestionnaireResponse]. */
      private fun setQuestionnaireItemViewItemAnswer(localDate: LocalDate) =
        questionnaireItemViewItem.setAnswer(
          QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
            value = localDate.dateType
          }
        )

      /**
       * Set the answer in the [QuestionnaireResponse]. Throw an error if the date cannot be parsed
       */
      private fun setQuestionnaireResponseAnswerAndValidate(
        questionnaireItemViewItem: QuestionnaireItemViewItem,
        dateToDisplay: String?
      ) =
        try {
          dateToDisplay?.let {
            val localDate = parseDate(it, canonicalizedDatePattern)
            setQuestionnaireItemViewItemAnswer(localDate)
          }
          displayValidationResult(questionnaireItemViewItem.validationResult)
        } catch (e: ParseException) {
          displayValidationResult(
            Invalid(
              listOf(invalidDateErrorText(textInputEditText.context, canonicalizedDatePattern))
            )
          )
        }

      private fun displayValidationResult(validationResult: ValidationResult) {
        textInputLayout.error =
          when (validationResult) {
            is NotValidated,
            Valid -> null
            is Invalid -> validationResult.getSingleStringValidationMessage()
          }
      }

      /** Automatically appends date separator (e.g. "/") during date input. */
      inner class DatePatternTextWatcher(private val dateFormatSeparator: Char) : TextWatcher {
        private var isDeleting = false

        override fun beforeTextChanged(
          charSequence: CharSequence,
          start: Int,
          count: Int,
          after: Int
        ) {
          isDeleting = count > after
        }

        override fun onTextChanged(
          charSequence: CharSequence,
          start: Int,
          before: Int,
          count: Int
        ) {}

        override fun afterTextChanged(editable: Editable) {
          handleDateFormatAfterTextChange(
            editable,
            canonicalizedDatePattern,
            dateFormatSeparator,
            isDeleting
          )
          questionnaireItemViewItem.setDraftAnswer(editable.toString())
        }
      }
    }
}

/**
 * Format entered date to acceptable date format where 2 digits for day and month, 4 digits for
 * year.
 */
internal fun handleDateFormatAfterTextChange(
  editable: Editable,
  canonicalizedDatePattern: String,
  dateFormatSeparator: Char,
  isDeleting: Boolean
) {
  val editableLength = editable.length
  if (editable.isEmpty()) {
    return
  }
  // restrict date entry upto acceptable date length
  if (editableLength > canonicalizedDatePattern.length) {
    editable.replace(canonicalizedDatePattern.length, editableLength, "")
    return
  }
  // handle delete text and separator
  if (editableLength < canonicalizedDatePattern.length) {
    // Do not add the separator again if the user has just deleted it.
    if (!isDeleting && canonicalizedDatePattern[editableLength] == dateFormatSeparator) {
      // 02 is entered with dd/MM/yyyy so appending / to editable 02/
      editable.append(dateFormatSeparator)
    }
    if (canonicalizedDatePattern[editable.lastIndex] == dateFormatSeparator &&
        editable[editable.lastIndex] != dateFormatSeparator
    ) {
      // Add separator to break different date components, e.g. converting "123" to "12/3"
      editable.insert(editable.lastIndex, dateFormatSeparator.toString())
    }
  }
}

internal const val TAG = "date-picker"
internal val ZONE_ID_UTC = ZoneId.of("UTC")

/**
 * Medium and long format styles use alphabetical month names which are difficult for the user to
 * input. Use short format style which is always numerical.
 */
internal fun getLocalizedDateTimePattern(): String {
  return DateTimeFormatterBuilder.getLocalizedDateTimePattern(
    FormatStyle.SHORT,
    null,
    IsoChronology.INSTANCE,
    Locale.getDefault()
  )
}

internal val DateType.localDate
  get() =
    if (!this.hasValue()) null
    else
      LocalDate.of(
        year,
        month + 1,
        day,
      )

internal val LocalDate.dateType
  get() = DateType(year, monthValue - 1, dayOfMonth)

internal val Date.localDate
  get() = LocalDate.of(year + 1900, month + 1, date)

// Count the number of digits in an Integer
internal fun Int.length() =
  when (this) {
    0 -> 1
    else -> log10(abs(toDouble())).toInt() + 1
  }

/**
 * Replaces 'dd' with '31', 'MM' with '01' and 'yyyy' with '2023' and returns new string. For
 * example, given a `formatPattern` of dd/MM/yyyy, returns 31/01/2023
 */
internal fun invalidDateErrorText(context: Context, formatPattern: String) =
  context.getString(
    R.string.date_format_validation_error_msg,
    formatPattern,
    formatPattern.replace("dd", "31").replace("MM", "01").replace("yyyy", "2023")
  )
