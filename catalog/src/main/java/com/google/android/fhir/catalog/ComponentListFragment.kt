/*
 * Copyright 2023 Google LLC
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

package com.google.android.fhir.catalog

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Fragment for the component list. */
class ComponentListFragment : Fragment(R.layout.component_list_fragment) {
  private val viewModel: ComponentListViewModel by viewModels()
  val getContent =
    registerForActivityResult(ActivityResultContracts.GetContent()) {
      it?.let { launchQuestionnaireFragmentWithUri(it) }
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    setUpComponentsRecyclerView()
  }

  override fun onResume() {
    super.onResume()
    setUpActionBar()
    (activity as MainActivity).showBottomNavigationView(View.VISIBLE)
  }

  override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    inflater.inflate(R.menu.select_questionnaire_menu, menu)
    true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.select_questionnaire_menu -> {
        getContent.launch("application/json")
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun setUpActionBar() {
    (requireActivity() as AppCompatActivity).supportActionBar?.apply {
      setDisplayHomeAsUpEnabled(false)
    }
    (activity as MainActivity).setActionBar(
      getString(R.string.toolbar_text),
      Gravity.CENTER_HORIZONTAL
    )
    setHasOptionsMenu(true)
  }

  private fun setUpComponentsRecyclerView() {
    val adapter =
      ComponentsRecyclerViewAdapter(::onItemClick).apply { submitList(viewModel.viewItemList) }
    with(requireView().findViewById<RecyclerView>(R.id.componentsRecyclerView)) {
      this.adapter = adapter
      layoutManager =
        GridLayoutManager(requireContext(), ComponentsRecyclerViewAdapter.ViewType.HEADER.spanCount)
          .apply {
            spanSizeLookup =
              object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                  return if (adapter.getItemViewType(position) ==
                      ComponentsRecyclerViewAdapter.ViewType.HEADER.ordinal
                  )
                    ComponentsRecyclerViewAdapter.ViewType.HEADER.spanCount
                  else ComponentsRecyclerViewAdapter.ViewType.ITEM.spanCount
                }
              }
          }
    }
  }

  private fun onItemClick(component: ComponentListViewModel.Component) {
    // TODO Remove check when all components questionnaire json are updated.
    // https://github.com/google/android-fhir/issues/1076
    if (component.questionnaireFile.isEmpty()) {
      return
    }
    launchQuestionnaireFragment(component)
  }

  private fun launchQuestionnaireFragment(component: ComponentListViewModel.Component) {
    findNavController()
      .navigate(
        ComponentListFragmentDirections.actionComponentsFragmentToGalleryQuestionnaireFragment(
          questionnaireTitleKey = context?.getString(component.textId) ?: "",
          questionnaireFilePathKey = component.questionnaireFile,
          questionnaireFileWithValidationPathKey = component.questionnaireFileWithValidation,
          workflow = component.workflow,
        )
      )
  }

  private fun launchQuestionnaireFragmentWithUri(uri: Uri) {
    findNavController()
      .navigate(
        ComponentListFragmentDirections.actionComponentsFragmentToGalleryQuestionnaireFragment(
          workflow = WorkflowType.DEFAULT,
          questionnaireFileUri = uri,
        )
      )
  }
}
