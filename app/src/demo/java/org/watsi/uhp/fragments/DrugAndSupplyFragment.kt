package org.watsi.uhp.fragments

import android.app.AlertDialog
import android.app.SearchManager
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.database.MatrixCursor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SimpleCursorAdapter
import dagger.android.support.DaggerFragment
import io.reactivex.Single
import kotlinx.android.synthetic.main.fragment_drug_and_supply.container
import kotlinx.android.synthetic.main.fragment_drug_and_supply.done_button
import kotlinx.android.synthetic.main.fragment_drug_and_supply.drug_search
import kotlinx.android.synthetic.main.fragment_drug_and_supply.line_item_count
import kotlinx.android.synthetic.main.fragment_drug_and_supply.line_items_list
import kotlinx.android.synthetic.main.fragment_drug_and_supply.select_billable_box
import org.threeten.bp.Clock
import org.watsi.device.managers.Logger
import org.watsi.domain.entities.Billable
import org.watsi.domain.relations.BillableWithPriceSchedule
import org.watsi.uhp.R
import org.watsi.uhp.activities.ClinicActivity
import org.watsi.uhp.adapters.EncounterItemAdapter
import org.watsi.uhp.flowstates.EncounterFlowState
import org.watsi.uhp.helpers.QueryHelper
import org.watsi.uhp.helpers.RecyclerViewHelper
import org.watsi.uhp.helpers.SnackbarHelper
import org.watsi.uhp.helpers.SwipeHandler
import org.watsi.uhp.helpers.scrollToBottom
import org.watsi.uhp.helpers.setBottomPadding
import org.watsi.uhp.managers.KeyboardManager
import org.watsi.uhp.managers.NavigationManager
import org.watsi.uhp.viewmodels.DrugAndSupplyViewModel
import java.util.UUID
import javax.inject.Inject

class DrugAndSupplyFragment : DaggerFragment(), NavigationManager.HandleOnBack {
    @Inject lateinit var clock: Clock
    @Inject lateinit var navigationManager: NavigationManager
    @Inject lateinit var keyboardManager: KeyboardManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var logger: Logger

    lateinit var viewModel: DrugAndSupplyViewModel
    lateinit var observable: LiveData<DrugAndSupplyViewModel.ViewState>
    lateinit var billableAdapter: ArrayAdapter<BillablePresenter>
    lateinit var swipeHandler: SwipeHandler
    lateinit var encounterItemAdapter: EncounterItemAdapter
    lateinit var showSaveButtonRunnable: Runnable
    lateinit var encounterFlowState: EncounterFlowState

    companion object {
        const val PARAM_ENCOUNTER = "encounter"
        const val SHOW_BUTTON_DELAY_TIME_IN_MS = 200L

        fun forEncounter(encounter: EncounterFlowState): DrugAndSupplyFragment {
            val fragment = DrugAndSupplyFragment()
            fragment.arguments = Bundle().apply {
                putSerializable(PARAM_ENCOUNTER, encounter)
            }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        encounterFlowState = arguments.getSerializable(PARAM_ENCOUNTER) as EncounterFlowState

        billableAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1)

        viewModel = ViewModelProviders.of(this, viewModelFactory).get(DrugAndSupplyViewModel::class.java)
        observable = viewModel.getObservable(encounterFlowState)
        observable.observe(this, Observer {
            it?.let { viewState ->
                val cursor = buildSearchResultCursor(viewState.selectableBillableRelations.map { it.billable })
                drug_search.suggestionsAdapter.changeCursor(cursor)

                updateLineItems(viewState.encounterFlowState)
            }
        })
    }

    private fun updateLineItems(encounterFlowState: EncounterFlowState) {
        // NOTE: we are only calling for encounter items of type DRUG and _not_ SUPPLY because
        // on the Ethiopia side we have both drugs and supplies under type DRUG
        val lineItems = encounterFlowState.getEncounterItemsOfType(Billable.Type.DRUG)
        line_item_count.text = resources.getQuantityString(
                R.plurals.encounter_item_count, lineItems.size, lineItems.size)
        encounterItemAdapter.setEncounterItems(lineItems)
    }

    override fun onResume() {
        super.onResume()

        // this is required for when the user back navigates into this screen
        // the observable does not trigger, so we need to set the adapter from the viewModel
        viewModel.getEncounterFlowState()?.let { encounterFlowState ->
            updateLineItems(encounterFlowState)
        }
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (activity as ClinicActivity).setToolbar(
                context.getString(R.string.drug_and_supply), R.drawable.ic_arrow_back_white_24dp)
        (activity as ClinicActivity).setSoftInputModeToResize()
        setHasOptionsMenu(true)
        return inflater?.inflate(R.layout.fragment_drug_and_supply, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        showSaveButtonRunnable = Runnable({
            done_button?.let { it.visibility = View.VISIBLE }
        })

        container.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                keyboardManager.hideKeyboard(v)
                onHideKeyboard()
            }
        }

        encounterItemAdapter = EncounterItemAdapter(
                onQuantitySelected = {
                    swipeHandler.disableSwipe()
                    onShowKeyboard()
                },
                onQuantityChanged = { encounterItemId: UUID, newQuantity: Int? ->
                    if (newQuantity == null || newQuantity == 0) {
                        SnackbarHelper.show(done_button, context, R.string.error_blank_or_zero_quantity)
                    } else {
                        viewModel.setItemQuantity(encounterItemId, newQuantity)
                    }
                },
                onRemoveEncounterItem = { encounterItemId: UUID ->
                    viewModel.removeItem(encounterItemId)
                },
                onPriceTap = { encounterItemId: UUID ->
                    viewModel.getEncounterFlowState()?.let { flowState ->
                        encounterFlowState.encounterItemRelations = flowState.encounterItemRelations
                        navigationManager.goTo(EditPriceFragment.forEncounterItem(
                                encounterItemId, encounterFlowState))
                    } ?: run {
                        logger.error("EncounterFlowState not set")
                    }
                },
                onSurgicalScoreTap = null
        )

        swipeHandler = SwipeHandler(context, onSwipe = { position: Int ->
            AlertDialog.Builder(activity)
                .setTitle(getString(R.string.delete_items_confirmation))
                .setPositiveButton(R.string.yes) { _, _ ->
                    encounterItemAdapter.removeAt(position)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    // This call is necessary to force a redraw of the adapter. If its not included
                    // the line item will stay red with the trash icon as it was at the end of the swipe
                    encounterItemAdapter.notifyDataSetChanged()
                }
                .setCancelable(false)
                .create().show()
        })

        RecyclerViewHelper.setRecyclerView(
            recyclerView = line_items_list,
            adapter = encounterItemAdapter,
            context = context,
            swipeHandler = swipeHandler
        )

        drug_search.suggestionsAdapter = SimpleCursorAdapter(
                activity, R.layout.item_billable_search_suggestion, null,
                arrayOf(SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_TEXT_2),
                intArrayOf(R.id.text1, R.id.text2), 0)
        drug_search.setOnQueryTextListener(QueryHelper.ThrottledQueryListener(
                drug_search,
                { query: String -> viewModel.updateQuery(query) }
        ))
        drug_search.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (hasFocus) { onShowKeyboard(hidePanel = false) }
        }
        drug_search.setOnSuggestionListener(object : android.widget.SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true

            override fun onSuggestionClick(position: Int): Boolean {
                observable.value?.selectableBillableRelations?.get(position)?.let {
                    viewModel.addItem(it)
                    line_items_list.scrollToBottom()
                    drug_search.setQuery("", false)
                }
                return true
            }
        })


        done_button.setOnClickListener {
            viewModel.getEncounterFlowState()?.let { encounterFlowState ->
                navigationManager.popTo(ReceiptFragment.forEncounter(encounterFlowState))
            } ?: run {
                logger.error("EncounterFlowState not set")
            }
        }
    }

    private fun buildSearchResultCursor(billables: List<Billable>): MatrixCursor {
        val cursorColumns = arrayOf("_id", SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_TEXT_2, "id")
        val cursor = MatrixCursor(cursorColumns)
        billables.forEach {
            cursor.addRow(arrayOf(it.id.mostSignificantBits, it.name, it.details(), it.id.toString()))
        }
        return cursor
    }

    /**
     * Android has no native method to detect whether the keyboard is showing, so this is a proxy
     * method that should be called in places where we assume the keyboard has appeared.
     */
    private fun onShowKeyboard(hidePanel: Boolean = true) {
        line_items_list.setBottomPadding(0)

        if (hidePanel) {
            select_billable_box.visibility = View.GONE
        }

        // If the delayed "show button" task has not run at this point, make sure we stop it
        // so it doesn't run and show the button. This handles race conditions like the case
        // where a user deselects an EditText (triggering the "show button" delayed task)
        // and quickly reselects another EditText (before the delayed task has run).
        done_button.removeCallbacks(showSaveButtonRunnable)
        done_button.visibility = View.GONE
    }

    /**
     * Android has no native method to detect whether the keyboard has been hidden, so this is a proxy
     * method that should be called in places where we assume the keyboard has been hidden.
     */
    private fun onHideKeyboard() {
        swipeHandler.enableSwipe()

        line_items_list.setBottomPadding(context.resources.getDimensionPixelSize(R.dimen.scrollingFragmentBottomPadding))

        select_billable_box.visibility = View.VISIBLE

        if (done_button.visibility != View.VISIBLE) {
            // Delay showing the button to prevent jumpy visual behavior.
            done_button.postDelayed(showSaveButtonRunnable, SHOW_BUTTON_DELAY_TIME_IN_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as ClinicActivity).setSoftInputModeToPan()
    }

    override fun onBack(): Single<Boolean> {
        return Single.fromCallable {
            viewModel.getEncounterFlowState()?.encounterItemRelations?.let {
                encounterFlowState.encounterItemRelations = it
            }
            true
        }
    }

    /**
     * Used to customize toString behavior for use in an ArrayAdapter
     */
    data class BillablePresenter(val billableWithPrice: BillableWithPriceSchedule?, val context: Context) {
        override fun toString(): String = billableWithPrice?.billable?.name ?: context.getString(R.string.select_prompt)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                navigationManager.goBack()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
