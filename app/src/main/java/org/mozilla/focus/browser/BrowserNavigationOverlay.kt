/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlinx.android.synthetic.main.browser_overlay.view.*
import kotlinx.android.synthetic.main.browser_overlay_top_nav.view.*
import kotlinx.android.synthetic.main.pocket_video_mega_tile.view.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import mozilla.components.support.ktx.android.view.hideKeyboard
import org.mozilla.focus.R
import org.mozilla.focus.autocomplete.UrlAutoCompleteFilter
import org.mozilla.focus.ext.forEachChild
import org.mozilla.focus.ext.isVisible
import org.mozilla.focus.ext.updateLayoutParams
import org.mozilla.focus.home.HomeTilesManager
import org.mozilla.focus.home.pocket.Pocket
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.TurboMode
import org.mozilla.focus.utils.UrlUtils
import org.mozilla.focus.widget.IgnoreFocusMovementMethod
import org.mozilla.focus.widget.InlineAutocompleteEditText
import kotlin.properties.Delegates
import org.mozilla.focus.ext.isVoiceViewEnabled
import java.lang.ref.WeakReference

private const val NAVIGATION_BUTTON_ENABLED_ALPHA = 1.0f
private const val NAVIGATION_BUTTON_DISABLED_ALPHA = 0.3f

private const val SHOW_UNPIN_TOAST_COUNTER_PREF = "show_upin_toast_counter"
private const val MAX_UNPIN_TOAST_COUNT = 3

private const val COL_COUNT = 5
private val uiHandler = Handler(Looper.getMainLooper())

enum class NavigationEvent {
    SETTINGS, BACK, FORWARD, RELOAD, LOAD_URL, LOAD_TILE, TURBO, PIN_ACTION, POCKET;

    companion object {
        fun fromViewClick(viewId: Int?) = when (viewId) {
            R.id.navButtonBack -> BACK
            R.id.navButtonForward -> FORWARD
            R.id.navButtonReload -> RELOAD
            R.id.navButtonSettings -> SETTINGS
            R.id.turboButton -> TURBO
            R.id.pinButton -> PIN_ACTION
            R.id.pocketVideoMegaTileView -> POCKET
            else -> null
        }

        const val VAL_CHECKED = "checked"
        const val VAL_UNCHECKED = "unchecked"
    }
}

@Suppress("LargeClass") // TODO remove this. See https://github.com/mozilla-mobile/firefox-tv/issues/1187
class BrowserNavigationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle), View.OnClickListener {

    interface BrowserNavigationStateProvider {
        fun isBackEnabled(): Boolean
        fun isForwardEnabled(): Boolean
        fun getCurrentUrl(): String?
        fun isURLPinned(): Boolean
        fun isPinEnabled(): Boolean
        fun isRefreshEnabled(): Boolean
    }

    enum class ParentFragment {
        SETTINGS,
        POCKET,
        DEFAULT
    }

    /**
     * Used to cancel background->UI threads: we attach them as children to this job
     * and cancel this job at the end of the UI lifecycle, cancelling the children.
     */
    var uiLifecycleCancelJob: Job

    // We need this in order to show the unpin toast, at max, once per
    // instantiation of the BrowserNavigationOverlay
    var canShowUpinToast: Boolean = false

    // Setting the onTileLongClick function in the HomeTileAdapter is fragile
    // since we init the tiles in View.init and Android is inflating the view for us,
    // thus we need to use Delegates.observable to update onTileLongClick.
    var openHomeTileContextMenu: (() -> Unit) by Delegates.observable({}) { _, _, newValue ->
        with(tileContainer) {
            (adapter as HomeTileAdapter).onTileLongClick = newValue
        }
    }

    var onNavigationEvent: ((
        event: NavigationEvent,
        value: String?,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?
    ) -> Unit)? = null
    var navigationStateProvider: BrowserNavigationStateProvider? = null

    var onPreSetVisibilityListener: ((isVisible: Boolean) -> Unit)? = null

    private var pocketVideos = Pocket.getRecommendedVideos()

    var parentFrag = ParentFragment.DEFAULT

    private var hasUserChangedURLSinceEditTextFocused = false

    init {
        LayoutInflater.from(context)
                .inflate(R.layout.browser_overlay, this, true)

        topNavContainer.forEachChild {
            it.nextFocusDownId = navUrlInput.id
            if (it.isFocusable) it.setOnClickListener(this)
        }

        uiLifecycleCancelJob = Job()

        initTiles()
        initMegaTile()
        setupUrlInput()
        turboButton.isChecked = TurboMode.isEnabled(context)
        navButtonSettings.setImageResource(R.drawable.ic_settings) // Must be set in code for SVG to work correctly.

        val tintDrawable: (Drawable?) -> Unit = { it?.setTint(ContextCompat.getColor(context, R.color.photonGrey10_a60p)) }
        navUrlInput.compoundDrawablesRelative.forEach(tintDrawable)
    }

    private fun initTiles() = with(tileContainer) {
        val homeTiles = HomeTilesManager.getTilesCache(context)

        canShowUpinToast = true

        adapter = HomeTileAdapter(uiLifecycleCancelJob, homeTiles, loadUrl = { urlStr ->
            with(navUrlInput) {
                if (urlStr.isNotEmpty()) {
                    onNavigationEvent?.invoke(NavigationEvent.LOAD_TILE, urlStr, null)
                }
            }
        }, onTileLongClick = openHomeTileContextMenu, onTileFocused = {
            val prefInt = PreferenceManager.getDefaultSharedPreferences(context).getInt(SHOW_UNPIN_TOAST_COUNTER_PREF, 0)
            if (prefInt < MAX_UNPIN_TOAST_COUNT && canShowUpinToast) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putInt(SHOW_UNPIN_TOAST_COUNTER_PREF, prefInt + 1)
                        .apply()

                val contextReference = WeakReference(context)
                val showToast = showToast@{
                    val context = contextReference.get() ?: return@showToast
                    Toast.makeText(context, R.string.homescreen_unpin_tutorial_toast, Toast.LENGTH_LONG).show()
                }
                if (context.isVoiceViewEnabled()) uiHandler.postDelayed(showToast, 1500)
                else showToast.invoke()

                canShowUpinToast = false
            }
        })
        layoutManager = HomeTileManager(context, COL_COUNT)

        setHasFixedSize(true)

        // We add bottomMargin to each tile in order to add spacing between them: this makes the
        // RecyclerView slightly larger than necessary and makes the default start screen scrollable
        // even though it doesn't need to be. To undo this, we add negative margins on the tile container.
        // I tried other solutions (ItemDecoration, dynamically changing margins) but this is more
        // complex because we need to relayout more than the changed view when adding/removing a row.
        val tileBottomMargin = resources.getDimensionPixelSize(R.dimen.home_tile_margin_bottom) -
                resources.getDimensionPixelSize(R.dimen.home_tile_container_margin_bottom)
        updateLayoutParams {
            val marginLayoutParams = it as MarginLayoutParams
            marginLayoutParams.bottomMargin = -tileBottomMargin
        }
    }

    /**
     * Used to show an error screen on the Pocket megatile when Pocket does not return any videos.
     */
    fun showMegaTileError() {
        pocketVideosContainer.visibility = View.GONE
        pocketErrorContainer.visibility = View.VISIBLE
        pocketMegaTileLoadError.text = resources.getString(R.string.pocket_video_feed_failed_to_load,
                resources.getString(R.string.pocket_brand_name))
        megaTileTryAgainButton.contentDescription = resources.getString(R.string.pocket_video_feed_failed_to_load,
                resources.getString(R.string.pocket_brand_name)) + " " + resources.getString(R.string.pocket_video_feed_reload_button)

        megaTileTryAgainButton.setOnClickListener { _ ->
            pocketVideos = Pocket.getRecommendedVideos()
            initMegaTile()
            updateOverlayForCurrentState()
            pocketVideoMegaTileView.requestFocus()
        }
    }

    private fun initMegaTile() {
        pocketVideoMegaTileView.setOnClickListener(this)

        if (pocketVideos.isCompleted) {
            pocketVideoMegaTileView.pocketVideos = pocketVideos.getCompleted()

            if (pocketVideoMegaTileView.pocketVideos == null) {
                showMegaTileError()
            } else {
                pocketVideosContainer.visibility = View.VISIBLE
                pocketErrorContainer.visibility = View.GONE
                pocketVideosContainer.contentDescription = resources.getString(R.string.pocket_home_a11y_tile_focused,
                        resources.getString(R.string.pocket_brand_name))
            }
        } else {
            // TODO: #864 show loading screen
            launch(UI) {
                pocketVideoMegaTileView.pocketVideos = pocketVideos.await()
            }
        }
    }

    private fun setupUrlInput() = with(navUrlInput) {
        setOnCommitListener {
            val userInput = text.toString()
            if (userInput == BrowserFragment.APP_URL_HOME) {
                // If the input points to home, we short circuit and hide the keyboard, returning
                // the user to the home screen
                this.hideKeyboard()
                return@setOnCommitListener
            }

            if (userInput.isNotEmpty()) {
                val cachedAutocompleteResult = lastAutocompleteResult // setText clears the reference so we cache it here.
                setText(cachedAutocompleteResult.text)
                onNavigationEvent?.invoke(NavigationEvent.LOAD_URL, userInput, cachedAutocompleteResult)
            }
        }
        this.movementMethod = IgnoreFocusMovementMethod()
        val autocompleteFilter = UrlAutoCompleteFilter()
        autocompleteFilter.load(context.applicationContext)
        setOnFilterListener { searchText, view -> autocompleteFilter.onFilter(searchText, view) }

        setOnUserInputListener { hasUserChangedURLSinceEditTextFocused = true }
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hasUserChangedURLSinceEditTextFocused = false
                updateOverlayForCurrentState() // Update URL to overwrite user input, ensuring the url's accuracy.
            }
        }
    }

    override fun onClick(view: View?) {
        val event = NavigationEvent.fromViewClick(view?.id) ?: return
        var value: String? = null

        val isTurboButtonChecked = turboButton.isChecked
        val isPinButtonChecked = pinButton.isChecked
        when (event) {
            NavigationEvent.TURBO -> {
                TurboMode.toggle(context, isTurboButtonChecked)
            }
            NavigationEvent.PIN_ACTION -> {
                value = if (isPinButtonChecked) NavigationEvent.VAL_CHECKED
                else NavigationEvent.VAL_UNCHECKED
            }
            else -> Unit // Nothing to do.
        }
        onNavigationEvent?.invoke(event, value, null)
        TelemetryWrapper.overlayClickEvent(event, isTurboButtonChecked, isPinButtonChecked)
    }

    fun updateOverlayForCurrentState() {
        fun updateOverlayButtonState(isEnabled: Boolean, overlayButton: ImageButton) {
            overlayButton.isEnabled = isEnabled
            overlayButton.isFocusable = isEnabled
            overlayButton.alpha =
                    if (isEnabled) NAVIGATION_BUTTON_ENABLED_ALPHA else NAVIGATION_BUTTON_DISABLED_ALPHA
        }

        val focusedView = findFocus()

        val canGoBack = navigationStateProvider?.isBackEnabled() ?: false
        updateOverlayButtonState(canGoBack, navButtonBack)

        val canGoForward = navigationStateProvider?.isForwardEnabled() ?: false
        updateOverlayButtonState(canGoForward, navButtonForward)

        val isPinEnabled = navigationStateProvider?.isPinEnabled() ?: false
        updateOverlayButtonState(isPinEnabled, pinButton)
        pinButton.isChecked = navigationStateProvider?.isURLPinned() ?: false

        val isRefreshEnabled = navigationStateProvider?.isRefreshEnabled() ?: false
        updateOverlayButtonState(isRefreshEnabled, navButtonReload)

        // Prevent the focus from looping to the bottom row when reaching the last
        // focusable element in the top row
        navButtonReload.nextFocusLeftId = when {
            canGoForward -> R.id.navButtonForward
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonReload
        }
        navButtonForward.nextFocusLeftId = when {
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonForward
        }

        navUrlInput.nextFocusDownId = when {
            pocketVideosContainer.isVisible -> R.id.pocketVideoMegaTileView
            else -> R.id.megaTileTryAgainButton
        }

        navUrlInput.nextFocusUpId = when {
            canGoBack -> R.id.navButtonBack
            canGoForward -> R.id.navButtonForward
            isRefreshEnabled -> R.id.navButtonReload
            isPinEnabled -> R.id.pinButton
            else -> R.id.turboButton
        }

        pocketVideoMegaTileView.nextFocusDownId = when {
            tileContainer.adapter.itemCount == 0 -> R.id.pocketVideoMegaTileView
            else -> R.id.tileContainer
        }

        // We may have lost focus when disabling the focused view above.
        val isFocusLost = focusedView != null && findFocus() == null
        if (isFocusLost) {
            navUrlInput.requestFocus()
        }

        maybeUpdateOverlayURLForCurrentState()
    }

    private fun maybeUpdateOverlayURLForCurrentState() {
        // The url can get updated in the background, e.g. if a loading page is redirected. We
        // don't want a url update to interrupt the user typing so we don't update the url from
        // the background if the user has already updated the url themselves.
        //
        // We revert this state when the view is unfocused: it ensures the URL is usually accurate
        // (for security reasons) and it's simple compared to other options which keep more state.
        //
        // One problem this solution has is that if the URL is updated in the background rapidly,
        // sometimes key events will be dropped, but I don't think there's much we can do about this:
        // we can't determine if the keyboard is up or not and focus isn't a good indicator because
        // we can focus the EditText without opening the soft keyboard and the user won't even know
        // these are inaccurate!
        if (!hasUserChangedURLSinceEditTextFocused) {
            val displayText = navigationStateProvider?.getCurrentUrl()
                    ?.let { UrlUtils.toUrlBarDisplay(it) }
            navUrlInput.setText(displayText)
        }
    }

    override fun setVisibility(visibility: Int) {
        onPreSetVisibilityListener?.invoke(visibility == View.VISIBLE)
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            overlayScrollView.scrollTo(0, 0)
            when (parentFrag) {
                ParentFragment.SETTINGS -> {
                    navButtonSettings.requestFocus()
                }
                ParentFragment.POCKET -> {
                    pocketVideoMegaTileView.requestFocus()
                }
                ParentFragment.DEFAULT -> {
                    navUrlInput.requestFocus()
                }
            }
            updateOverlayForCurrentState()
        }
    }

    fun refreshTilesForInsertion() {
        val adapter = tileContainer.adapter as HomeTileAdapter
        adapter.updateAdapterSingleInsertion(HomeTilesManager.getTilesCache(context))
        updateOverlayForCurrentState()
    }

    fun removePinnedSiteFromTiles(tileId: String) {
        val adapter = tileContainer.adapter as HomeTileAdapter
        adapter.removeTile(tileId)
        updateOverlayForCurrentState()
    }

    /**
     * Focus may be lost if all pinned items are removed via onContextItemSelected()
     */
    fun checkIfTilesFocusNeedRefresh() {
        if (tileContainer.adapter.itemCount == 0) {
            if (pocketVideosContainer.isVisible) {
                pocketVideoMegaTileView.requestFocus()
            } else {
                megaTileTryAgainButton.requestFocus()
            }
        }
        updateOverlayForCurrentState()
    }

    inner class HomeTileManager(
        context: Context,
        colCount: Int
    ) : GridLayoutManager(context, colCount) {
        override fun onRequestChildFocus(parent: RecyclerView?, state: RecyclerView.State?, child: View?, focused: View?): Boolean {
            var position = spanCount
            if (focused != null) {
                position = getPosition(focused)
            }

            // if position is less than spanCount, implies first row
            if (position < spanCount) {
                focused?.nextFocusUpId = when {
                    pocketVideosContainer.isVisible -> R.id.pocketVideoMegaTileView
                    else -> R.id.megaTileTryAgainButton
                }
            }
            return super.onRequestChildFocus(parent, state, child, focused)
        }
    }
}

/**
 * A [ScrollView] with functionality overridden for the specific requirements of the overlay.
 *
 * One crappy thing with the current implementation is that when a scroll is interrupted (e.g. user
 * clicks up twice quickly), it will skip and not scroll smoothly. Since we don't scroll often,
 * this seems fine.
 */
class BrowserNavigationOverlayScrollView(
    context: Context,
    attrs: AttributeSet
) : ScrollView(context, attrs) {

    private val deltaScrollPadding = resources.getDimensionPixelSize(R.dimen.browser_overlay_delta_scroll_padding)

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int {
        // We modify the scroll offset to ensure:
        // 1) Scrolling through the tiles will show enough of the next tile to indicate scrollability.
        // 2) When focusing the last vertical view in the layout, the default implementation will
        //    leave some empty space at the edge of the view such that an additional dpad click will
        //    scroll the screen but nothing new is focused: we don't want that.
        val deltaScrollForOnScreen = super.computeScrollDeltaToGetChildRectOnScreen(rect)
        return deltaScrollForOnScreen + deltaScrollPadding * Integer.signum(deltaScrollForOnScreen)
    }
}
