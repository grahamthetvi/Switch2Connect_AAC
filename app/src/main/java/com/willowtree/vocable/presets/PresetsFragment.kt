package com.willowtree.vocable.presets

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.willowtree.vocable.BaseFragment
import com.willowtree.vocable.BindingInflater
import com.willowtree.vocable.MainActivity
import com.willowtree.vocable.R
import com.willowtree.vocable.customviews.PointerListener
import com.willowtree.vocable.customviews.VocablePhraseButton
import com.willowtree.vocable.databinding.FragmentPresetsBinding
import com.willowtree.vocable.eyegazetracking.EyeGazeTrackingViewModel
import com.willowtree.vocable.utils.SpokenText
import com.willowtree.vocable.utils.VocableFragmentStateAdapter
import com.willowtree.vocable.utils.VocableSharedPreferences
import com.willowtree.vocable.utils.VocableTextToSpeech
import com.willowtree.vocable.utils.PhraseTextBubble
import com.willowtree.vocable.utils.VocableSpeechRecognizer
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.scopeActivity
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.getViewModel
import kotlin.math.abs

class PresetsFragment : BaseFragment<FragmentPresetsBinding>() {

    override val bindingInflater: BindingInflater<FragmentPresetsBinding> =
        FragmentPresetsBinding::inflate
    private val allViews = mutableListOf<View>()

    private var maxCategories = 1
    private var isPortraitMode = true
    private var isTabletMode = false

    private val presetsViewModel: PresetsViewModel by activityViewModel()
    private val sharedPrefs: VocableSharedPreferences by inject()
    private var eyeGazeViewModel: EyeGazeTrackingViewModel? = null
    private lateinit var categoriesAdapter: CategoriesPagerAdapter
    private lateinit var phrasesAdapter: PhrasesPagerAdapter

    private var recentsCategorySelected = false
    private var currentPhrases: List<PhraseGridItem> = emptyList()
    private var currentPage = 0
    private var symbolCount = 2

    // Symbol buttons array for easy access
    private val symbolButtons = mutableListOf<VocablePhraseButton>()

    // Gesture detector for swipe navigation
    private lateinit var gestureDetector: GestureDetectorCompat

    // Default colors for each position (1-indexed)
    private val defaultColors = mapOf(
        1 to 0xFFE53935.toInt(),  // Red
        2 to 0xFF1E88E5.toInt(),  // Blue
        3 to 0xFF43A047.toInt(),  // Green
        4 to 0xFFFB8C00.toInt(),  // Orange
        5 to 0xFF8E24AA.toInt(),  // Purple
        6 to 0xFF00ACC1.toInt(),  // Cyan
        7 to 0xFFF06292.toInt(),  // Pink
        8 to 0xFFFFEE58.toInt(),  // Yellow
        9 to 0xFF78909C.toInt()   // Grey
    )

    @SuppressLint("NullSafeMutableLiveData", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        maxCategories = resources.getInteger(R.integer.max_categories)
        isPortraitMode =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        isTabletMode = resources.getBoolean(R.bool.is_tablet)

        // Load symbol count from preferences
        symbolCount = sharedPrefs.getSymbolCount()

        // Initialize symbol buttons
        initializeSymbolButtons()

        // Setup gesture detector for swipe navigation
        setupGestureDetector()

        // Apply gesture detector to the symbol grid
        binding.symbolGrid?.setOnTouchListener { _, event ->
            val isTouchscreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            if (!isTouchscreen) {
                return@setOnTouchListener false
            }
            gestureDetector.onTouchEvent(event)
            true
        }

        // Category navigation (using swipe now, but keeping for compatibility)
        binding.categoryForwardButton?.action = {
            navigateToNextCategory()
        }

        binding.categoryBackButton?.action = {
            navigateToPreviousCategory()
        }

        // Action buttons (now directly in layout for proper binding)
        binding.keyboardButton?.action = {
            if (findNavController().currentDestination?.id == R.id.presetsFragment) {
                findNavController().navigate(R.id.action_presetsFragment_to_keyboardFragment)
            }
        }

        binding.settingsButton?.action = {
            if (findNavController().currentDestination?.id == R.id.presetsFragment) {
                findNavController().navigate(R.id.action_presetsFragment_to_settingsFragment)
            }
        }

        binding.choiceButton?.action = {
            if (sharedPrefs.getChoiceModeEnabled()) {
                VocableSpeechRecognizer.startListening(requireContext())
            } else {
                // Show a brief message that choice mode is disabled
                binding.currentText.text = "Choice mode is disabled in settings"
                view?.postDelayed({
                    binding.currentText.text = getString(R.string.select_something)
                }, 2000)
            }
        }

        // Try to get the EyeGazeTrackingViewModel for recenter functionality
        try {
            eyeGazeViewModel = scopeActivity?.getViewModel()
        } catch (e: Exception) {
            // ViewModel not available (eye tracking not enabled)
        }

        // Recenter button - recenters the eye gaze cursor
        binding.recenterButton?.action = {
            eyeGazeViewModel?.recenterCursorManual()
        }

        binding.emptyAddPhraseButton?.action = {
            val action =
                presetsViewModel.selectedCategory.value?.let { category ->
                    PresetsFragmentDirections.actionPresetsFragmentToAddPhraseKeyboardFragment(
                        category
                    )
                }
            if (action != null) {
                findNavController().navigate(action)
            }
        }

        categoriesAdapter = CategoriesPagerAdapter(childFragmentManager)
        phrasesAdapter = PhrasesPagerAdapter(childFragmentManager)

        binding.categoryView.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activity?.let { activity ->
                    allViews.clear()
                    if (activity is MainActivity) {
                        activity.resetAllViews()
                    }
                }
            }
        })

        SpokenText.postValue(null)

        subscribeToViewModel()
    }

    private fun initializeSymbolButtons() {
        symbolButtons.clear()
        binding.symbol1?.let { symbolButtons.add(it) }
        binding.symbol2?.let { symbolButtons.add(it) }
        binding.symbol3?.let { symbolButtons.add(it) }
        binding.symbol4?.let { symbolButtons.add(it) }
        binding.symbolCenter?.let { symbolButtons.add(it) }

        // Set up click handlers for each symbol button
        symbolButtons.forEachIndexed { index, button ->
            button.action = {
                val phraseIndex = currentPage * symbolCount + index
                if (phraseIndex < currentPhrases.size) {
                    val phraseItem = currentPhrases[phraseIndex]
                    if (phraseItem is PhraseGridItem.Phrase) {
                        // Check if we're in choice mode (choice phrases have special IDs)
                        if (phraseItem.phraseId.startsWith("choice_")) {
                            // Speak the choice and clear choice mode
                            VocableTextToSpeech.speak(resources.configuration.locale, phraseItem.text)
                            presetsViewModel.clearChoices()
                        } else {
                            // Normal phrase selection
                            presetsViewModel.addToRecents(phraseItem.phraseId)
                        }
                    }
                }
            }
        }

        // Update visibility based on symbol count
        updateSymbolVisibility()

    }

    private fun updateSymbolVisibility() {
        // Always show 4 corner symbols, center is only for 5+
        binding.symbol1?.isVisible = symbolCount >= 1
        binding.symbol2?.isVisible = symbolCount >= 2
        binding.symbol3?.isVisible = symbolCount >= 3
        binding.symbol4?.isVisible = symbolCount >= 4
        binding.symbolCenter?.isVisible = symbolCount >= 5
    }

    /**
     * Applies custom colors from preferences to symbol buttons.
     */
    private fun applyCustomColors() {
        symbolButtons.forEachIndexed { index, button ->
            val position = index + 1
            val color = sharedPrefs.getSymbolColor(position) ?: defaultColors[position]!!
            applyColorToButton(button, color)
        }
    }

    /**
     * Applies a color to a symbol button by creating a state-aware drawable.
     */
    private fun applyColorToButton(button: VocablePhraseButton, color: Int) {
        val cornerRadius = 16f * resources.displayMetrics.density
        val strokeWidth = (4 * resources.displayMetrics.density).toInt()
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.selectedColor)

        // Darken color for pressed state
        val pressedColor = darkenColor(color, 0.7f)

        // Create state list drawable
        val stateListDrawable = StateListDrawable()

        // Pressed state
        val pressedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(pressedColor)
            setStroke(strokeWidth, selectedColor)
        }
        stateListDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)

        // Selected state
        val selectedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(color)
            setStroke(strokeWidth, selectedColor)
        }
        stateListDrawable.addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)

        // Normal state (must be added last as it's the default)
        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(color)
        }
        stateListDrawable.addState(intArrayOf(), normalDrawable)

        button.background = stateListDrawable
    }
    
    /**
     * Darkens a color by the given factor (0-1, where 0 = black, 1 = original).
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY)) {
                    // Horizontal swipe
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe right - go to previous page
                            navigateToPreviousPage()
                        } else {
                            // Swipe left - go to next page
                            navigateToNextPage()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
    }

    private fun navigateToNextPage() {
        val totalPages = calculateTotalPages()
        if (totalPages > 1) {
            currentPage = (currentPage + 1) % totalPages
            updateSymbolsForCurrentPage()
        }
    }

    private fun navigateToPreviousPage() {
        val totalPages = calculateTotalPages()
        if (totalPages > 1) {
            currentPage = if (currentPage == 0) totalPages - 1 else currentPage - 1
            updateSymbolsForCurrentPage()
        }
    }

    private fun navigateToNextCategory() {
        when (val currentPosition = binding.categoryView.currentItem) {
            categoriesAdapter.itemCount - 1 -> {
                selectCategory(0)
            }
            else -> {
                selectCategory(currentPosition + 1)
            }
        }
    }

    private fun navigateToPreviousCategory() {
        when (val currentPosition = binding.categoryView.currentItem) {
            0 -> {
                selectCategory(categoriesAdapter.itemCount - 1)
            }
            else -> {
                selectCategory(currentPosition - 1)
            }
        }
    }

    private fun selectCategory(newPosition: Int) {
        binding.categoryView.setCurrentItem(newPosition, true)
        if (isPortraitMode && !isTabletMode) {
            presetsViewModel.onCategorySelected(
                categoriesAdapter.getCategory(
                    newPosition
                ).categoryId
            )
        }
    }

    private fun calculateTotalPages(): Int {
        return if (currentPhrases.isEmpty()) 0
        else (currentPhrases.size + symbolCount - 1) / symbolCount
    }

    private fun updateSymbolsForCurrentPage() {
        val startIndex = currentPage * symbolCount

        // Update each symbol button with the phrase for this page
        for (i in 0 until symbolCount.coerceAtMost(symbolButtons.size)) {
            val phraseIndex = startIndex + i
            val button = symbolButtons.getOrNull(i) ?: continue
            val position = i + 1 // 1-indexed for color lookup

            if (phraseIndex < currentPhrases.size) {
                val phraseItem = currentPhrases[phraseIndex]
                when (phraseItem) {
                    is PhraseGridItem.Phrase -> {
                        button.text = phraseItem.text
                        button.isVisible = true
                        button.isEnabled = true
                        
                        // Apply phrase-specific style if available, otherwise use position color
                        if (phraseItem.style != null) {
                            applyPhraseStyle(button, phraseItem.style)
                        } else {
                            // Fall back to position-based color
                            val color = sharedPrefs.getSymbolColor(position) ?: defaultColors[position]!!
                            applyColorToButton(button, color)
                            PhraseTextBubble.apply(button, null)
                        }
                    }
                    is PhraseGridItem.AddPhrase -> {
                        button.text = getString(R.string.add_phrase_plus)
                        button.isVisible = true
                        button.isEnabled = true
                        // Use position-based color for add button
                        val color = sharedPrefs.getSymbolColor(position) ?: defaultColors[position]!!
                        applyColorToButton(button, color)
                        PhraseTextBubble.apply(button, null)
                    }
                }
            } else {
                // No phrase for this slot - hide or show as empty
                button.text = ""
                button.isVisible = i < symbolCount
                button.isEnabled = false
                // Use position-based color for empty slots too
                val color = sharedPrefs.getSymbolColor(position) ?: defaultColors[position]!!
                applyColorToButton(button, color)
                PhraseTextBubble.apply(button, null)
            }
        }

        // Update page indicator
        val totalPages = calculateTotalPages()
        if (totalPages > 1) {
            binding.pageIndicator?.text = getString(R.string.swipe_for_more, currentPage + 1, totalPages)
            binding.pageIndicator?.isVisible = true
        } else {
            binding.pageIndicator?.isVisible = false
        }
    }
    
    /**
     * Applies per-phrase style to a button, including background color, text color, size, and bold.
     */
    private fun applyPhraseStyle(button: VocablePhraseButton, style: com.willowtree.vocable.room.PhraseStyle) {
        val cornerRadius = 16f * resources.displayMetrics.density
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.selectedColor)
        val backgroundColor = style.effectiveBackgroundColor()
        
        // Darken color for pressed state
        val pressedColor = darkenColor(backgroundColor, 0.7f)

        // Create state list drawable
        val stateListDrawable = StateListDrawable()

        // Pressed state
        val pressedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(pressedColor)
            setStroke((4 * resources.displayMetrics.density).toInt(), selectedColor)
        }
        stateListDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)

        // Selected state
        val selectedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(backgroundColor)
            setStroke((4 * resources.displayMetrics.density).toInt(), selectedColor)
        }
        stateListDrawable.addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)

        // Normal state
        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(backgroundColor)
        }
        stateListDrawable.addState(intArrayOf(), normalDrawable)

        button.background = stateListDrawable
        
        // Apply text color
        button.setTextColor(style.effectiveTextColor())
        
        // Apply text size
        button.textSize = style.effectiveTextSize()
        
        // Apply bold
        button.setTypeface(
            null,
            if (style.isBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )

        PhraseTextBubble.apply(button, style)
    }

    private fun subscribeToViewModel() {
        SpokenText.observe(viewLifecycleOwner) {
            binding.currentText.text = if (it.isNullOrBlank()) {
                getString(R.string.select_something)
            } else {
                it
            }
        }

        VocableTextToSpeech.isSpeaking.observe(viewLifecycleOwner) {
            binding.speakerIcon.isVisible = it
        }

        VocableSpeechRecognizer.speechResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is VocableSpeechRecognizer.SpeechResult.ListeningStarted -> {
                    // Show visual feedback that listening has started
                    binding.currentText.text = "Listening for choices..."
                    if (sharedPrefs.getChoiceVisualFeedbackEnabled()) {
                        binding.microphoneIcon?.isVisible = true
                    }
                }
                is VocableSpeechRecognizer.SpeechResult.ChoicesParsed -> {
                    // Show the parsed choices
                    presetsViewModel.showChoices(result.optionA, result.optionB)
                    binding.currentText.text = "Say your choice"
                }
                is VocableSpeechRecognizer.SpeechResult.ListeningStopped -> {
                    // Reset to normal text and hide microphone
                    binding.currentText.text = getString(R.string.select_something)
                    binding.microphoneIcon?.isVisible = false
                }
                is VocableSpeechRecognizer.SpeechResult.Error -> {
                    // Show error message briefly and hide microphone
                    binding.currentText.text = result.message
                    binding.microphoneIcon?.isVisible = false
                    // Reset after a delay
                    view?.postDelayed({
                        binding.currentText.text = getString(R.string.select_something)
                    }, 3000)
                }
            }
        }

        presetsViewModel.isChoiceMode.observe(viewLifecycleOwner) { isChoiceMode ->
            if (!isChoiceMode) {
                // Clear any choice mode visual feedback
                binding.currentText.text = getString(R.string.select_something)
            }
        }

        presetsViewModel.apply {
            categoryList.observe(viewLifecycleOwner, ::handleCategories)
            currentPhrases.observe(viewLifecycleOwner, ::handlePhrases)
            selectedCategoryLiveData.observe(viewLifecycleOwner, ::handleSelectedCategory)
        }

        presetsViewModel.navToAddPhrase.observe(viewLifecycleOwner) {
            if (it) {
                val action =
                    presetsViewModel.selectedCategory.value?.let { category ->
                        PresetsFragmentDirections.actionPresetsFragmentToAddPhraseKeyboardFragment(
                            category
                        )
                    }
                if (action != null) {
                    findNavController().navigate(action)
                }
            }
        }
    }

    override fun getAllViews(): List<View> {
        if (allViews.isEmpty()) {
            getAllChildViews(binding.presetsParent)
        }
        return allViews
    }

    private fun getAllChildViews(viewGroup: ViewGroup?) {
        viewGroup?.children?.forEach {
            if (it is PointerListener) {
                allViews.add(it)
            } else if (it is ViewGroup) {
                getAllChildViews(it)
            }
        }
    }

    private fun handleCategories(categories: List<Category>) {
        with(binding.categoryView) {
            val categoriesExist = categories.isNotEmpty()
            // if there are no categories to show (the user has hidden them all), then show the empty state
            isVisible = categoriesExist
            binding.symbolGrid?.isVisible = categoriesExist
            binding.categoryScrollView?.isVisible = categoriesExist

            binding.emptyCategoriesText?.isVisible = !categoriesExist

            isSaveEnabled = false
            adapter = categoriesAdapter
            categoriesAdapter.setItems(categories)
        }
    }

    private fun handleSelectedCategory(selectedCategory: Category?) {
        with(binding.categoryView) {
            for (i in 0 until categoriesAdapter.numPages) {
                val pageCategories = categoriesAdapter.getItemsByPosition(i)

                if (pageCategories.find { it.categoryId == selectedCategory?.categoryId } != null) {
                    setCurrentItem(i, false)
                    break
                }
            }
            recentsCategorySelected =
                selectedCategory?.categoryId == PresetCategories.RECENTS.id
        }
    }

    private fun handlePhrases(phrases: List<PhraseGridItem>) {
        currentPhrases = phrases
        currentPage = 0

        // Handle empty states
        val isEmpty = phrases.isEmpty()
        val isCustomCategory = !recentsCategorySelected && categoriesAdapter.getSize() > 0

        binding.emptyPhrasesText?.isVisible = isEmpty && isCustomCategory && !recentsCategorySelected
        binding.emptyAddPhraseButton?.isVisible = isEmpty && isCustomCategory && !recentsCategorySelected

        binding.noRecentsTitle?.isVisible = isEmpty && recentsCategorySelected
        binding.noRecentsMessage?.isVisible = isEmpty && recentsCategorySelected
        binding.clockIcon?.isVisible = isEmpty && recentsCategorySelected

        binding.symbolGrid?.isVisible = !isEmpty

        // Update symbols for current page
        if (!isEmpty) {
            updateSymbolsForCurrentPage()
        }

        // Keep phrasesView adapter for compatibility with other parts of the system
        binding.phrasesView?.apply {
            isSaveEnabled = false
            adapter = phrasesAdapter

            val maxPhrases =
                if (presetsViewModel.selectedCategory.value?.categoryId == PresetCategories.USER_KEYPAD.id) {
                    NumberPadFragment.MAX_PHRASES
                } else {
                    resources.getInteger(R.integer.max_phrases)
                }

            phrasesAdapter.setItems(phrases)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh symbol count in case it changed in settings
        val newSymbolCount = sharedPrefs.getSymbolCount()
        if (newSymbolCount != symbolCount) {
            symbolCount = newSymbolCount
            updateSymbolVisibility()
            currentPage = 0
            updateSymbolsForCurrentPage()
        }
        // Refresh symbols so phrase styles and colors stay in sync
        updateSymbolsForCurrentPage()
    }

    inner class CategoriesPagerAdapter(fm: FragmentManager) :
        VocableFragmentStateAdapter<Category>(fm, viewLifecycleOwner.lifecycle) {

        override fun getMaxItemsPerPage(): Int = maxCategories

        override fun createFragment(position: Int) =
            CategoriesFragment.newInstance(getItemsByPosition(position))

        fun getSize(): Int = items.size

        fun getCategory(position: Int): Category {
            return if (position >= items.size) {
                items[position % items.size]
            } else {
                items[position]
            }
        }
    }

    inner class PhrasesPagerAdapter(fm: FragmentManager) :
        VocableFragmentStateAdapter<PhraseGridItem>(fm, viewLifecycleOwner.lifecycle) {

        override fun setItems(items: List<PhraseGridItem>) {
            super.setItems(items)
        }

        override fun getMaxItemsPerPage(): Int = symbolCount

        override fun createFragment(position: Int): Fragment {
            val phrases = getItemsByPosition(position)
            return if (presetsViewModel.selectedCategory.value?.categoryId == PresetCategories.USER_KEYPAD.id) {
                NumberPadFragment.newInstance(phrases)
            } else if (presetsViewModel.selectedCategory.value?.categoryId == PresetCategories.MY_SAYINGS.id && items.isEmpty()) {
                MySayingsEmptyFragment.newInstance(false)
            } else {
                PhrasesFragment.newInstance(phrases)
            }
        }

    }
}
