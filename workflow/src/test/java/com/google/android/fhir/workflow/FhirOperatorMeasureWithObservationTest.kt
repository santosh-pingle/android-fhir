/*
 * Copyright 2021 Google LLC
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

package com.google.android.fhir.workflow

import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngineProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FhirOperatorMeasureWithObservationTest {
  private val fhirEngine =
    FhirEngineProvider.getInstance(ApplicationProvider.getApplicationContext())
  private val fhirOperator = FhirOperator(fhirContext, fhirEngine)

  companion object {
    private val libraryBundle: Bundle by lazy { parseJson("/ANCIND01-bundle.json") }
    private val fhirContext = FhirContext.forR4()
    private val jsonParser = fhirContext.newJsonParser()
    private val xmlParser = fhirContext.newXmlParser()

    private fun parseJson(path: String): Bundle =
      jsonParser.parseResource(jsonParser.javaClass.getResourceAsStream(path)) as Bundle
  }

  @Before fun setUp() = runBlocking { fhirEngine.run { loadBundle(libraryBundle) } }

  @Test
  fun `evaluateMeasure for subject with observation has denominator and numerator`() = runBlocking {
    fhirEngine.run {
      loadFile("/validated-resources/anc-patient-example.json")
      loadFile("/validated-resources/Antenatal-care-case.json")
      loadFile("/validated-resources/First-antenatal-care-contact.json")
      loadFile("/validated-resources/observation-anc-b6-de17-example.json")
      loadFile("/validated-resources/Practitioner.xml")
      loadFile("/validated-resources/PractitionerRole.xml")
    }

    val measureReport =
      fhirOperator.evaluateMeasure(
        measureUrl = "http://fhir.org/guides/who/anc-cds/Measure/ANCIND01",
        start = "2020-01-01",
        end = "2020-01-31",
        reportType = "subject",
        subject = "anc-patient-example",
        practitioner = "jane",
        lastReceivedOn = null
      )
    assertThat(measureReport.evaluatedResource[1].reference)
      .isEqualTo("Observation/anc-b6-de17-example")
    assertThat(measureReport.evaluatedResource[0].reference)
      .isEqualTo("Encounter/First-antenatal-care-contact-example")
    val population = measureReport.group.first().population
    assertThat(population[1].id).isEqualTo("denominator")
    assertThat(population[2].id).isEqualTo("numerator")
  }

  private suspend fun loadFile(path: String) {
    if (path.endsWith(suffix = ".xml")) {
      val resource = xmlParser.parseResource(javaClass.getResourceAsStream(path)) as Resource
      fhirEngine.create(resource)
    } else if (path.endsWith(".json")) {
      val resource = jsonParser.parseResource(javaClass.getResourceAsStream(path)) as Resource
      fhirEngine.create(resource)
    }
  }
  private suspend fun loadBundle(bundle: Bundle) {
    for (entry in bundle.entry) {
      when (entry.resource.resourceType) {
        ResourceType.Library -> fhirOperator.loadLib(entry.resource as Library)
        ResourceType.Bundle -> Unit
        else -> fhirEngine.create(entry.resource)
      }
    }
  }
}
