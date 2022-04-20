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
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Ignore("Refactor the API to accommodate local end points")
class FhirOperatorGenerateCarePlanTest {
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
  fun generateCarePlan() = runBlocking {
    fhirEngine.run {
      loadBundle(parseJson("/RuleFilters-1.0.0-bundle.json"))
      loadBundle(parseJson("/tests-Reportable-bundle.json"))
      loadBundle(parseJson("/tests-NotReportable-bundle.json"))

      loadFile("/first-contact/01-registration/patient-charity-otala-1.json")
      loadFile("/first-contact/02-enrollment/careplan-charity-otala-1-pregnancy-plan.xml")
      loadFile("/first-contact/02-enrollment/episodeofcare-charity-otala-1-pregnancy-episode.xml")
      loadFile("/first-contact/03-contact/encounter-anc-encounter-charity-otala-1.xml")
    }

    assertThat(
        fhirOperator.generateCarePlan(
          planDefinitionId = "plandefinition-RuleFilters-1.0.0",
          patientId = "Reportable",
          encounterId = "reportable-encounter"
        )
      )
      .isNotNull()
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
