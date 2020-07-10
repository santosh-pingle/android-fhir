/*
 * Copyright 2020 Google LLC
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

package com.google.fhirengine.example

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.fhirengine.example.data.SamplePatients

/**
 * An activity representing a list of Patients.
 */
class SamplePatientListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = title

        // Launch the old Fhir and CQL resources loading screen.
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            val resLoadIntent = Intent(baseContext, MainActivity::class.java)
            startActivity(resLoadIntent)
        }

        // val jsonString = getJsonStrForPatientData()
        val jsonStringPatients = getJsonStrForPatientData()
        val jsonStringObservations = getJsonStrForObservationData()

        val patientListViewModel = ViewModelProvider(this, PatientListViewModelFactory(
            jsonStringPatients, jsonStringObservations))
            .get(PatientListViewModel::class.java)
        val recyclerView: RecyclerView = findViewById(R.id.samplepatient_list)

        // Click handler to help display the details about the patients from the list.
        val onPatientItemClicked: (View) -> Unit = { v ->
            val item = v.tag as SamplePatients.PatientItem
            val intent = Intent(v.context, SamplePatientDetailActivity::class.java).apply {
                putExtra(SamplePatientDetailFragment.ARG_ITEM_ID, item.id)
            }
            v.context.startActivity(intent)
        }

        val adapter = SampleItemRecyclerViewAdapter(onPatientItemClicked)
        recyclerView.adapter = adapter

        patientListViewModel.getPatients().observe(this,
            Observer<List<SamplePatients.PatientItem>> {
            adapter.submitList(it)
        })

        patientListViewModel.getObservations().observe(this,
            Observer<List<SamplePatients.ObservationItem>> {
                //adapter.submitList(it)
            })
    }

    // To suppress the warning. Seems to be an issue with androidx library.
    // "MenuBuilder.setOptionalIconsVisible can only be called from within the same library group
    // prefix (referenced groupId=androidx.appcompat with prefix androidx from groupId=fhir-engine"
    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflator: MenuInflater = menuInflater
        inflator.inflate(R.menu.list_options_menu, menu)
        // To ensure that icons show up in the overflow options menu. Icons go missing without this.
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        // return super.onCreateOptionsMenu(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val view: View = findViewById(R.id.app_bar)

        // Handle item selection
        return when (item.itemId) {
            R.id.sync_resources -> {
                sync_resources(view)
                true
            }
            R.id.load_resource -> {
                load_resources()
                true
            }
            R.id.about -> {
                showAbout(view)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun load_resources() {
        val resLoadIntent = Intent(baseContext, MainActivity::class.java)
        startActivity(resLoadIntent)
    }

    private fun showAbout(view: View) {
        Snackbar.make(view, R.string.about_text, Snackbar.LENGTH_LONG)
            .setAction("Action", null).show()
    }

    private fun sync_resources(view: View) {
        Snackbar.make(view, "For finding more about Fhir Engine: go/afya", Snackbar.LENGTH_LONG)
            .setAction("Action", null).show()
    }

    /**
     * Helper function to read patient asset file data as string.
     */
    private fun getJsonStrForPatientData(): String {
        val patientJsonFilename = "sample_patients_bundle.json"

        return this.applicationContext.assets.open(patientJsonFilename).bufferedReader().use {
            it.readText()
        }
    }

    /**
     * Helper function to read observation asset file data as string.
     */
    private fun getJsonStrForObservationData(): String {
        val observationJsonFilename = "sample_observations_bundle.json"

        return this.applicationContext.assets.open(observationJsonFilename).bufferedReader().use {
            it.readText()
        }
    }

}
