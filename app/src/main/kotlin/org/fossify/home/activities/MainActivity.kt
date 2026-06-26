package com.sw.launcher.activities

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.WallpaperManager
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import android.util.DisplayMetrics
import android.os.Build
import android.net.Uri
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.viewbinding.ViewBinding
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DARK_GREY
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus
import org.fossify.commons.helpers.isQPlus
import com.sw.launcher.R
import com.sw.launcher.databinding.ActivityMainBinding
import com.sw.launcher.databinding.AllAppsFragmentBinding
import com.sw.launcher.databinding.WidgetsFragmentBinding
import com.sw.launcher.dialogs.RenameItemDialog
import com.sw.launcher.extensions.config
import com.sw.launcher.extensions.getDrawableForPackageName
import com.sw.launcher.extensions.getLabel
import com.sw.launcher.extensions.handleGridItemPopupMenu
import com.sw.launcher.extensions.hiddenIconsDB
import com.sw.launcher.extensions.homeScreenGridItemsDB
import com.sw.launcher.extensions.isDefaultLauncher
import com.sw.launcher.extensions.launchApp
import com.sw.launcher.extensions.launchAppInfo
import com.sw.launcher.extensions.launchersDB
import com.sw.launcher.extensions.roleManager
import com.sw.launcher.extensions.supportsDarkText
import com.sw.launcher.extensions.uninstallApp
import com.sw.launcher.fragments.MyFragment
import com.sw.launcher.helpers.ITEM_TYPE_FOLDER
import com.sw.launcher.helpers.ITEM_TYPE_ICON
import com.sw.launcher.helpers.ITEM_TYPE_SHORTCUT
import com.sw.launcher.helpers.ITEM_TYPE_WIDGET
import com.sw.launcher.helpers.IconCache
import com.sw.launcher.helpers.REQUEST_ALLOW_BINDING_WIDGET
import com.sw.launcher.helpers.REQUEST_CONFIGURE_WIDGET
import com.sw.launcher.helpers.REQUEST_CREATE_SHORTCUT
import com.sw.launcher.helpers.REQUEST_SET_DEFAULT
import com.sw.launcher.helpers.UNINSTALL_APP_REQUEST_CODE
import com.sw.launcher.interfaces.FlingListener
import com.sw.launcher.interfaces.ItemMenuListener
import com.sw.launcher.models.AppLauncher
import com.sw.launcher.models.HiddenIcon
import com.sw.launcher.models.HomeScreenGridItem
import com.sw.launcher.receivers.LockDeviceAdminReceiver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : SimpleActivity(), FlingListener {
    private var mTouchDownX = -1
    private var mTouchDownY = -1
    private var mAllAppsFragmentY = 0
    private var mWidgetsFragmentY = 0
    private var mScreenHeight = 0
    private var mMoveGestureThreshold = 0
    private var mIgnoreUpEvent = false
    private var mIgnoreMoveEvents = false
    private var mIgnoreXMoveEvents = false
    private var mIgnoreYMoveEvents = false
    private var mLongPressedIcon: HomeScreenGridItem? = null
    private var mOpenPopupMenu: PopupMenu? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var wasJustPaused: Boolean = false

    private var wallpaperColorChangeListener: WallpaperManager.OnColorsChangedListener? = null
    private var wallpaperSupportsDarkText: Boolean? = null

    private val swWallpaperImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            applySWWallpaperAdjusted(it)
        }
    }

    private lateinit var mDetector: GestureDetectorCompat
    private val binding by viewBinding(ActivityMainBinding::inflate)

    companion object {
        private var mLastUpEvent = 0L
        private const val ANIMATION_DURATION = 150L
        private const val APP_DRAWER_CLOSE_DELAY = 300L
        private const val APP_DRAWER_STATE = "app_drawer_state"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false

        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.allAppsFragment.root, binding.widgetsFragment.root),
            padBottomImeAndSystem = listOf(
                binding.allAppsFragment.allAppsGrid, binding.widgetsFragment.widgetsList
            ),
            padBottomSystem = listOf(binding.homeScreenGrid.root)
        )

        mDetector = GestureDetectorCompat(this, MyGestureListener(this))

        mScreenHeight = realScreenSize.y
        mAllAppsFragmentY = mScreenHeight
        mWidgetsFragmentY = mScreenHeight
        mMoveGestureThreshold = resources.getDimensionPixelSize(R.dimen.move_gesture_threshold)

        arrayOf(
            binding.allAppsFragment.root as MyFragment<*>,
            binding.widgetsFragment.root as MyFragment<*>
        ).forEach { fragment ->
            fragment.setupFragment(this)
            fragment.y = mScreenHeight.toFloat()
            fragment.beVisible()
        }

        handleIntentAction(intent)

        binding.homeScreenGrid.root.itemClickListener = {
            performItemClick(it)
        }

        binding.homeScreenGrid.root.itemLongClickListener = {
            performItemLongClick(
                x = binding.homeScreenGrid.root.getClickableRect(it).left.toFloat(),
                clickedGridItem = it
            )
        }

        if (!isDefaultLauncher()) {
            requestHomeRole()
        }

        setupWallpaperColorListener()
    }

    private fun setupWallpaperColorListener() {
        if (isOreoMr1Plus()) {
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperColorChangeListener = WallpaperManager.OnColorsChangedListener { colors, which ->
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                    wallpaperSupportsDarkText = colors?.supportsDarkText() ?: run {
                        refreshWallpaperSupportsDarkText()
                        wallpaperSupportsDarkText
                    }
                    if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
                        runOnUiThread {
                            updateStatusBarIcons()
                        }
                    }
                }
            }
            wallpaperManager.addOnColorsChangedListener(
                wallpaperColorChangeListener!!,
                Handler(Looper.getMainLooper())
            )

            refreshWallpaperSupportsDarkText()
        }
    }

    private fun refreshWallpaperSupportsDarkText() {
        if (!isOreoMr1Plus()) return
        wallpaperSupportsDarkText = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.supportsDarkText()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val wasAnyFragmentOpen = isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()
        if (wasJustPaused) {
            if (isAllAppsFragmentExpanded()) {
                hideFragment(binding.allAppsFragment)
            }
            if (isWidgetsFragmentExpanded()) {
                hideFragment(binding.widgetsFragment)
            }
        } else {
            closeAppDrawer()
            closeWidgetsFragment()
        }

        binding.allAppsFragment.searchBar.closeSearch()

        // scroll to first page when home button is pressed
        val alreadyOnHome = intent.flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (alreadyOnHome && !wasAnyFragmentOpen) {
            binding.homeScreenGrid.root.skipToPage(0)
        }

        handleIntentAction(intent)
    }

    override fun onStart() {
        super.onStart()
        binding.homeScreenGrid.root.appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        wasJustPaused = false
        refreshWallpaperSupportsDarkText()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
                updateStatusBarIcons(getProperBackgroundColor())
            } else {
                updateStatusBarIcons()
            }
        }, ANIMATION_DURATION)

        with(binding.mainHolder) {
            onGlobalLayout {
                binding.allAppsFragment.root.setupViews()
                binding.widgetsFragment.root.setupViews()
            }
        }

        ensureBackgroundThread {
            if (IconCache.launchers.isEmpty()) {
                val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
                    it.getIconIdentifier()
                }

                IconCache.launchers = launchersDB.getAppLaunchers().filter {
                    val showIcon = !hiddenIcons.contains(it.getLauncherIdentifier())
                    if (!showIcon) {
                        try {
                            launchersDB.deleteById(it.id!!)
                        } catch (_: Exception) {
                        }
                    }
                    showIcon
                }.toMutableList() as ArrayList<AppLauncher>
            }

            binding.allAppsFragment.root.gotLaunchers(IconCache.launchers)
            refreshLaunchers()
        }

        binding.homeScreenGrid.root.resizeGrid(
            newRowCount = config.homeRowCount,
            newColumnCount = config.homeColumnCount
        )
        binding.homeScreenGrid.root.updateColors()
        binding.allAppsFragment.root.onResume()
    }

    override fun onStop() {
        super.onStop()
        try {
            binding.homeScreenGrid.root.appWidgetHost.stopListening()
        } catch (_: Exception) {
        }

        wasJustPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isOreoMr1Plus() && wallpaperColorChangeListener != null) {
            wallpaperColorChangeListener?.let { WallpaperManager.getInstance(this).removeOnColorsChangedListener(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isAllAppsFragmentExpanded()) {
            if (!binding.allAppsFragment.root.onBackPressed()) {
                hideFragment(binding.allAppsFragment)
                true
            } else {
                true
            }
        } else if (isWidgetsFragmentExpanded()) {
            if (binding.widgetsFragment.searchBar.isSearchOpen) {
                clearWidgetsSearch()
            } else {
                hideFragment(binding.widgetsFragment)
            }
            true
        } else if (binding.homeScreenGrid.resizeFrame.isVisible) {
            binding.homeScreenGrid.root.hideResizeLines()
            true
        } else {
            // this is a home launcher app, prevent back press from doing anything
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            UNINSTALL_APP_REQUEST_CODE -> {
                ensureBackgroundThread {
                    refreshLaunchers()
                }
            }

            REQUEST_ALLOW_BINDING_WIDGET -> mActionOnCanBindWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CONFIGURE_WIDGET -> mActionOnWidgetConfiguredWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CREATE_SHORTCUT -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val launcherApps =
                        applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                    if (launcherApps.hasShortcutHostPermission()) {
                        val item = launcherApps.getPinItemRequest(resultData)
                        val shortcutInfo = item?.shortcutInfo ?: return
                        if (item.accept()) {
                            val shortcutId = shortcutInfo.id
                            val label = shortcutInfo.getLabel()
                            val icon = launcherApps.getShortcutBadgedIconDrawable(
                                shortcutInfo,
                                resources.displayMetrics.densityDpi
                            )
                            mActionOnAddShortcut?.invoke(shortcutId, label, icon)
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.allAppsFragment.root.onConfigurationChanged()
        binding.widgetsFragment.root.onConfigurationChanged()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }

        if (mLongPressedIcon != null && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mLastUpEvent = System.currentTimeMillis()
        }

        try {
            mDetector.onTouchEvent(event)
        } catch (_: Exception) {
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mTouchDownX = event.x.toInt()
                mTouchDownY = event.y.toInt()
                mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
                mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
                mIgnoreUpEvent = false
            }

            MotionEvent.ACTION_MOVE -> {
                // if the initial gesture was handled by some other view, fix the Down values
                val hasFingerMoved = if (mTouchDownX == -1 || mTouchDownY == -1) {
                    mTouchDownX = event.x.toInt()
                    mTouchDownY = event.y.toInt()
                    false
                } else {
                    hasFingerMoved(event)
                }

                if (mLongPressedIcon != null && (mOpenPopupMenu != null) && hasFingerMoved) {
                    mOpenPopupMenu?.dismiss()
                    mOpenPopupMenu = null
                    binding.homeScreenGrid.root.itemDraggingStarted(mLongPressedIcon!!)
                    hideFragment(binding.allAppsFragment)
                }

                if (mLongPressedIcon != null && hasFingerMoved) {
                    binding.homeScreenGrid.root.draggedItemMoved(event.x.toInt(), event.y.toInt())
                }

                if (hasFingerMoved && !mIgnoreMoveEvents) {
                    val diffY = mTouchDownY - event.y
                    val diffX = mTouchDownX - event.x

                    if (abs(diffY) > abs(diffX) && !mIgnoreYMoveEvents) {
                        mIgnoreXMoveEvents = true
                        if (isWidgetsFragmentExpanded()) {
                            val newY = mWidgetsFragmentY - diffY
                            binding.widgetsFragment.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        } else if (mLongPressedIcon == null) {
                            val newY = mAllAppsFragmentY - diffY
                            binding.allAppsFragment.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        }
                    } else if (abs(diffX) > abs(diffY) && !mIgnoreXMoveEvents) {
                        mIgnoreYMoveEvents = true
                        binding.homeScreenGrid.root.setSwipeMovement(diffX)
                    }
                }

                mLastTouchCoords = Pair(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                mTouchDownX = -1
                mTouchDownY = -1
                mIgnoreMoveEvents = false
                mLongPressedIcon = null
                mLastTouchCoords = Pair(-1f, -1f)
                resetFragmentTouches()
                binding.homeScreenGrid.root.itemDraggingStopped()

                if (!mIgnoreUpEvent) {
                    if (!mIgnoreYMoveEvents) {
                        if (binding.allAppsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.allAppsFragment)
                        } else if (isAllAppsFragmentExpanded()) {
                            hideFragment(binding.allAppsFragment)
                        }

                        if (binding.widgetsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.widgetsFragment)
                        } else if (isWidgetsFragmentExpanded()) {
                            hideFragment(binding.widgetsFragment)
                        }
                    }

                    if (!mIgnoreXMoveEvents) {
                        binding.homeScreenGrid.root.finalizeSwipe()
                    }
                }

                mIgnoreXMoveEvents = false
                mIgnoreYMoveEvents = false
            }
        }

        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(APP_DRAWER_STATE, isAllAppsFragmentExpanded())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(APP_DRAWER_STATE)) {
            showFragment(binding.allAppsFragment, 0L)
        }
    }

    private fun handleIntentAction(intent: Intent) {
        if (intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) {
            val launcherApps =
                applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val item = launcherApps.getPinItemRequest(intent)
            val shortcutInfo = item?.shortcutInfo ?: return

            ensureBackgroundThread {
                val shortcutId = shortcutInfo.id
                val label = shortcutInfo.getLabel()
                val icon = launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
                val (page, rect) = findFirstEmptyCell()
                val gridItem = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = page,
                    packageName = shortcutInfo.`package`,
                    activityName = "",
                    title = label,
                    type = ITEM_TYPE_SHORTCUT,
                    className = "",
                    widgetId = -1,
                    shortcutId = shortcutId,
                    icon = icon.toBitmap(),
                    docked = false,
                    parentId = null,
                    drawable = icon
                )

                runOnUiThread {
                    binding.homeScreenGrid.root.skipToPage(page)
                }
                // delay showing the shortcut both to let the user see adding it in realtime and hackily avoid concurrent modification exception at HomeScreenGrid
                Thread.sleep(2000)

                try {
                    item.accept()
                    binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun findFirstEmptyCell(): Pair<Int, Rect> {
        val gridItems = homeScreenGridItemsDB.getAllItems() as ArrayList<HomeScreenGridItem>
        val maxPage = gridItems.maxOf { it.page }
        val occupiedCells = ArrayList<Triple<Int, Int, Int>>()
        gridItems.toImmutableList().filter { it.parentId == null }.forEach { item ->
            for (xCell in item.left..item.right) {
                for (yCell in item.top..item.bottom) {
                    occupiedCells.add(Triple(item.page, xCell, yCell))
                }
            }
        }

        for (page in 0 until maxPage) {
            for (checkedYCell in 0 until config.homeColumnCount) {
                for (checkedXCell in 0 until config.homeRowCount - 1) {
                    val wantedCell = Triple(page, checkedXCell, checkedYCell)
                    if (!occupiedCells.contains(wantedCell)) {
                        return Pair(
                            first = page,
                            second = Rect(
                                wantedCell.second,
                                wantedCell.third,
                                wantedCell.second,
                                wantedCell.third
                            )
                        )
                    }
                }
            }
        }

        return Pair(maxPage + 1, Rect(0, 0, 0, 0))
    }

    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
    private fun hasFingerMoved(event: MotionEvent): Boolean {
        return mTouchDownX != -1 && mTouchDownY != -1 &&
                (abs(mTouchDownX - event.x) > mMoveGestureThreshold || abs(mTouchDownY - event.y) > mMoveGestureThreshold)
    }

    private fun refreshLaunchers() {
        val launchers = getAllAppLaunchers()
        binding.allAppsFragment.root.gotLaunchers(launchers)
        binding.widgetsFragment.root.getAppWidgets()

        IconCache.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName)) {
                launchersDB.deleteApp(packageName)
                homeScreenGridItemsDB.deleteByPackageName(packageName)
            }
        }

        IconCache.launchers = launchers

        if (!config.wasHomeScreenInit) {
            ensureBackgroundThread {
                getDefaultAppPackages(launchers)
                config.wasHomeScreenInit = true
                binding.homeScreenGrid.root.fetchGridItems()
            }
        } else {
            binding.homeScreenGrid.root.fetchGridItems()
        }
    }

    fun isAllAppsFragmentExpanded() = binding.allAppsFragment.root.y != mScreenHeight.toFloat()

    private fun isWidgetsFragmentExpanded() =
        binding.widgetsFragment.root.y != mScreenHeight.toFloat()

    fun startHandlingTouches(touchDownY: Int) {
        mLongPressedIcon = null
        mTouchDownY = touchDownY
        mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
        mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
        mIgnoreUpEvent = false
    }

    private fun showFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        ObjectAnimator.ofFloat(fragment.root, "y", 0f).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            start()
        }

        window.navigationBarColor = resources.getColor(R.color.semitransparent_navigation)
        binding.homeScreenGrid.root.fragmentExpanded()
        binding.homeScreenGrid.root.hideResizeLines()

        @SuppressLint("AccessibilityFocus")
        fragment.root.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        if (
            fragment is AllAppsFragmentBinding
            && config.showSearchBar
            && config.autoShowKeyboardInAppDrawer
        ) {
            fragment.root.post {
                showKeyboard(fragment.searchBar.binding.topToolbarSearch)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusBarIcons(getProperBackgroundColor())
        }, animationDuration)
    }

    private fun hideFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        ObjectAnimator.ofFloat(fragment.root, "y", mScreenHeight.toFloat()).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            start()
        }

        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGrid.root.fragmentCollapsed()
        updateStatusBarIcons()
        if (fragment is WidgetsFragmentBinding) {
            clearWidgetsSearch()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (fragment is AllAppsFragmentBinding) {
                fragment.allAppsGrid.scrollToPosition(0)
                fragment.root.touchDownY = -1
            } else if (fragment is WidgetsFragmentBinding) {
                fragment.widgetsList.scrollToPosition(0)
                fragment.root.touchDownY = -1
            }
        }, animationDuration)
    }

    fun homeScreenLongPressed(eventX: Float, eventY: Float) {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
            return
        }

        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        mIgnoreMoveEvents = true
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemLongClick(x, clickedGridItem)
            return
        }

        binding.mainHolder.performHapticFeedback()
        showMainLongPressMenu(x, y)
    }

    fun homeScreenClicked(eventX: Float, eventY: Float) {
        binding.homeScreenGrid.root.hideResizeLines()
        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemClick(clickedGridItem)
        }
        if (clickedGridItem?.type != ITEM_TYPE_FOLDER) {
            binding.homeScreenGrid.root.closeFolder(redraw = true)
        }
    }

    fun homeScreenDoubleTapped(eventX: Float, eventY: Float) {
        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            return
        }

        val devicePolicyManager =
            getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
            ComponentName(this, LockDeviceAdminReceiver::class.java)
        )
        if (isLockDeviceAdminActive) {
            devicePolicyManager.lockNow()
        }
    }

    fun closeAppDrawer(delayed: Boolean = false) {
        if (isAllAppsFragmentExpanded()) {
            val close = {
                binding.allAppsFragment.root.y = mScreenHeight.toFloat()
                binding.allAppsFragment.allAppsGrid.scrollToPosition(0)
                binding.allAppsFragment.root.touchDownY = -1
                binding.homeScreenGrid.root.fragmentCollapsed()
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun closeWidgetsFragment(delayed: Boolean = false) {
        if (isWidgetsFragmentExpanded()) {
            val close = {
                binding.widgetsFragment.root.y = mScreenHeight.toFloat()
                binding.widgetsFragment.widgetsList.scrollToPosition(0)
                clearWidgetsSearch()
                binding.widgetsFragment.root.touchDownY = -1
                binding.homeScreenGrid.root.fragmentCollapsed()
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun clearWidgetsSearch() {
        binding.widgetsFragment.searchBar.closeSearch()
    }

    private fun performItemClick(clickedGridItem: HomeScreenGridItem) {
        when (clickedGridItem.type) {
            ITEM_TYPE_ICON -> launchApp(clickedGridItem.packageName, clickedGridItem.activityName)
            ITEM_TYPE_FOLDER -> openFolder(clickedGridItem)
            ITEM_TYPE_SHORTCUT -> {
                val id = clickedGridItem.shortcutId
                val packageName = clickedGridItem.packageName
                val userHandle = android.os.Process.myUserHandle()
                val shortcutBounds = binding.homeScreenGrid.root.getClickableRect(clickedGridItem)
                val launcherApps =
                    applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
            }
        }
    }

    private fun openFolder(folder: HomeScreenGridItem) {
        binding.homeScreenGrid.root.openFolder(folder)
    }

    private fun performItemLongClick(x: Float, clickedGridItem: HomeScreenGridItem) {
        if (clickedGridItem.type == ITEM_TYPE_ICON || clickedGridItem.type == ITEM_TYPE_SHORTCUT || clickedGridItem.type == ITEM_TYPE_FOLDER) {
            binding.mainHolder.performHapticFeedback()
        }

        val anchorY = binding.homeScreenGrid.root.sideMargins.top +
                (clickedGridItem.top * binding.homeScreenGrid.root.cellHeight.toFloat())
        showHomeIconMenu(x, anchorY, clickedGridItem, false)
    }

    fun showHomeIconMenu(
        x: Float,
        y: Float,
        gridItem: HomeScreenGridItem,
        isOnAllAppsFragment: Boolean,
    ) {
        binding.homeScreenGrid.root.hideResizeLines()
        mLongPressedIcon = gridItem
        val anchorY = if (isOnAllAppsFragment || gridItem.type == ITEM_TYPE_WIDGET) {
            val iconSize = realScreenSize.x / config.drawerColumnCount
            y - iconSize / 2f
        } else {
            val clickableRect = binding.homeScreenGrid.root.getClickableRect(gridItem)
            clickableRect.top.toFloat() - binding.homeScreenGrid.root.getCurrentIconSize() / 2f
        }

        binding.homeScreenPopupMenuAnchor.x = x
        binding.homeScreenPopupMenuAnchor.y = anchorY

        if (mOpenPopupMenu == null) {
            mOpenPopupMenu = handleGridItemPopupMenu(
                anchorView = binding.homeScreenPopupMenuAnchor,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener
            )
        }
    }

    fun widgetLongPressedOnList(gridItem: HomeScreenGridItem) {
        mLongPressedIcon = gridItem
        hideFragment(binding.widgetsFragment)
        binding.homeScreenGrid.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun showMainLongPressMenu(x: Float, y: Float) {
        binding.homeScreenGrid.root.hideResizeLines()
        binding.homeScreenPopupMenuAnchor.x = x
        binding.homeScreenPopupMenuAnchor.y =
            y - resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * 2
        val contextTheme = ContextThemeWrapper(this, getPopupMenuTheme())
        PopupMenu(
            contextTheme,
            binding.homeScreenPopupMenuAnchor,
            Gravity.TOP or Gravity.END
        ).apply {
            inflate(R.menu.menu_home_screen)
            menu.findItem(R.id.set_as_default).isVisible = !isDefaultLauncher()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.widgets -> showWidgetsFragment()
                    R.id.wallpapers -> launchWallpapersIntent()
                    R.id.launcher_settings -> launchSettings()
                    R.id.set_as_default -> launchSetAsDefaultIntent()
                }
                true
            }
            show()
        }
    }

    private fun resetFragmentTouches() {
        binding.widgetsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }

        binding.allAppsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }
    }

    private fun showWidgetsFragment() {
        showFragment(binding.widgetsFragment)
    }

    private fun hideIcon(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            val hiddenIcon = HiddenIcon(null, item.packageName, item.activityName, item.title, null)
            hiddenIconsDB.insert(hiddenIcon)

            runOnUiThread {
                binding.allAppsFragment.root.onIconHidden(item)
            }
        }
    }

    private fun renameItem(homeScreenGridItem: HomeScreenGridItem) {
        RenameItemDialog(this, homeScreenGridItem) {
            binding.homeScreenGrid.root.fetchGridItems()
        }
    }


    private fun applySwWallpaperBitmap(finalBitmap: android.graphics.Bitmap) {
    openDeviceWallpaperSettings()
}



private fun openSystemWallpaperPicker(finalBitmap: android.graphics.Bitmap) {
    openDeviceWallpaperSettings()
}

private fun openDeviceWallpaperSettings() {
    val intents = arrayOf(
        android.content.Intent("android.settings.WALLPAPER_SETTINGS"),
        android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER),
        android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
        android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
    )

    for (intent in intents) {
        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                toast("Altere o papel de parede pelo sistema.")
                return
            }
        } catch (_: Exception) {
        }
    }

    toast("Não encontrei o alterador de papel de parede do sistema.")
}





private fun launchWallpapersIntent() {
    openDeviceWallpaperSettings()
}



    private fun launchSWWallpaperPicker() {
        try {
            swWallpaperImagePicker.launch("image/*")
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun launchSystemWallpapersIntent() {
        try {
            Intent(Intent.ACTION_SET_WALLPAPER).apply {
                startActivity(this)
            }
        } catch (_: ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.no_app_found)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun applySWWallpaperAdjusted(uri: Uri) {
        ensureBackgroundThread {
            try {
                val sourceBitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw IllegalStateException("Não foi possível carregar a imagem.")

                val wallpaperSize = getSWWallpaperTargetSize()
                val targetWidth = wallpaperSize.first
                val targetHeight = wallpaperSize.second

                val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(finalBitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

                val fillScale = maxOf(
                    targetWidth.toFloat() / sourceBitmap.width.toFloat(),
                    targetHeight.toFloat() / sourceBitmap.height.toFloat()
                )

                val fillWidth = sourceBitmap.width * fillScale
                val fillHeight = sourceBitmap.height * fillScale
                val fillLeft = (targetWidth - fillWidth) / 2f
                val fillTop = (targetHeight - fillHeight) / 2f

                paint.alpha = 145
                canvas.drawBitmap(
                    sourceBitmap,
                    null,
                    RectF(fillLeft, fillTop, fillLeft + fillWidth, fillTop + fillHeight),
                    paint
                )

                paint.alpha = 255

                val fitScale = minOf(
                    targetWidth.toFloat() / sourceBitmap.width.toFloat(),
                    targetHeight.toFloat() / sourceBitmap.height.toFloat()
                )

                val fitWidth = sourceBitmap.width * fitScale
                val fitHeight = sourceBitmap.height * fitScale
                val fitLeft = (targetWidth - fitWidth) / 2f
                val fitTop = (targetHeight - fitHeight) / 2f

                canvas.drawBitmap(
                    sourceBitmap,
                    null,
                    RectF(fitLeft, fitTop, fitLeft + fitWidth, fitTop + fitHeight),
                    paint
                )

                val manager = WallpaperManager.getInstance(this)
                manager.suggestDesiredDimensions(targetWidth, targetHeight)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    applySwWallpaperBitmap(finalBitmap)
                } else {
                    applySwWallpaperBitmap(finalBitmap)
                }

                sourceBitmap.recycle()
                finalBitmap.recycle()

                runOnUiThread {
                    Toast.makeText(this, "Wallpaper aplicado pela SW Launcher", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showErrorToast(e)
                }
            }
        }
    }

    private fun getSWWallpaperTargetSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val width = bounds.width().coerceAtLeast(resources.displayMetrics.widthPixels)
            val height = bounds.height().coerceAtLeast(resources.displayMetrics.heightPixels)
            Pair(width, height)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun launchSettings() {
        startActivity(
            Intent(this@MainActivity, SettingsActivity::class.java)
        )
    }

    private fun launchSetAsDefaultIntent() {
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent != null) {
            startActivity(intent)
        }
    }

    private fun requestHomeRole() {
        if (isQPlus()) {
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                REQUEST_SET_DEFAULT
            )
        }
    }

    val menuListener: ItemMenuListener = object : ItemMenuListener {
        override fun onAnyClick() {
            resetFragmentTouches()
        }

        override fun hide(gridItem: HomeScreenGridItem) {
            hideIcon(gridItem)
        }

        override fun rename(gridItem: HomeScreenGridItem) {
            renameItem(gridItem)
        }

        override fun resize(gridItem: HomeScreenGridItem) {
            binding.homeScreenGrid.root.widgetLongPressed(gridItem)
        }

        override fun appInfo(gridItem: HomeScreenGridItem) {
            launchAppInfo(gridItem.packageName)
        }

        override fun remove(gridItem: HomeScreenGridItem) {
            binding.homeScreenGrid.root.removeAppIcon(gridItem)
        }

        override fun uninstall(gridItem: HomeScreenGridItem) {
            uninstallApp(gridItem.packageName)
        }

        override fun onDismiss() {
            mOpenPopupMenu = null
            resetFragmentTouches()
        }

        override fun beforeShow(menu: Menu) {
            var visibleMenuItems = 0
            for (item in menu.iterator()) {
                if (item.isVisible) {
                    visibleMenuItems++
                }
            }
            val yOffset =
                resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * (visibleMenuItems - 1)
            binding.homeScreenPopupMenuAnchor.y -= yOffset
        }
    }

    private class MyGestureListener(
        private val flingListener: FlingListener,
    ) : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            (flingListener as MainActivity).homeScreenClicked(event.x, event.y)
            return super.onSingleTapUp(event)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            (flingListener as MainActivity).homeScreenDoubleTapped(event.x, event.y)
            return super.onDoubleTap(event)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            // ignore fling events just after releasing an icon from dragging
            if (System.currentTimeMillis() - mLastUpEvent < 500L) {
                return true
            }

            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY > 0) {
                    flingListener.onFlingDown()
                } else {
                    flingListener.onFlingUp()
                }
            } else if (abs(velocityX) > abs(velocityY)) {
                if (velocityX > 0) {
                    flingListener.onFlingRight()
                } else {
                    flingListener.onFlingLeft()
                }
            }

            return true
        }

        override fun onLongPress(event: MotionEvent) {
            (flingListener as MainActivity).homeScreenLongPressed(event.x, event.y)
        }
    }

    override fun onFlingUp() {
        if (mIgnoreYMoveEvents) {
            return
        }

        if (!isWidgetsFragmentExpanded()) {
            mIgnoreUpEvent = true
            showFragment(binding.allAppsFragment)
        }
    }

    @SuppressLint("WrongConstant")
    override fun onFlingDown() {
        if (mIgnoreYMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (isAllAppsFragmentExpanded()) {
            hideFragment(binding.allAppsFragment)
        } else if (isWidgetsFragmentExpanded()) {
            hideFragment(binding.widgetsFragment)
        } else {
            try {
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(getSystemService("statusbar"))
            } catch (_: Exception) {
            }
        }
    }

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        binding.homeScreenGrid.root.prevPage(redraw = true)
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        binding.homeScreenGrid.root.nextPage(redraw = true)
    }

    @SuppressLint("WrongConstant")
    fun getAllAppLaunchers(): ArrayList<AppLauncher> {
        val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
            it.getIconIdentifier()
        }

        val allApps = ArrayList<AppLauncher>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val simpleLauncher = applicationContext.packageName
        val microG = "com.google.android.gms"
        val list = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
        for (info in list) {
            val componentInfo = info.activityInfo.applicationInfo
            val packageName = componentInfo.packageName
            if (packageName == simpleLauncher || packageName == microG) {
                continue
            }

            val activityName = info.activityInfo.name
            if (hiddenIcons.contains("$packageName/$activityName")) {
                continue
            }

            val label = info.loadLabel(packageManager).toString()
            val drawable = info.loadIcon(packageManager)
                ?: getDrawableForPackageName(packageName)
                ?: continue

            val bitmap = drawable.toBitmap(
                width = max(drawable.intrinsicWidth, 1),
                height = max(drawable.intrinsicHeight, 1),
                config = Bitmap.Config.ARGB_8888
            )
            val placeholderColor = calculateAverageColor(bitmap)
            allApps.add(
                AppLauncher(
                    id = null,
                    title = label,
                    packageName = packageName,
                    activityName = activityName,
                    order = 0,
                    thumbnailColor = placeholderColor,
                    drawable = bitmap.toDrawable(resources)
                )
            )
        }

        launchersDB.insertAll(allApps)
        return allApps
    }

    private fun getDefaultAppPackages(appLaunchers: ArrayList<AppLauncher>) {
        val homeScreenGridItems = ArrayList<HomeScreenGridItem>()
        try {
            val defaultDialerPackage =
                (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
            appLaunchers.firstOrNull { it.packageName == defaultDialerPackage }?.apply {
                val dialerIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 0,
                        top = config.homeRowCount - 1,
                        right = 0,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultDialerPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(dialerIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val defaultSMSMessengerPackage = Telephony.Sms.getDefaultSmsPackage(this)
            appLaunchers.firstOrNull { it.packageName == defaultSMSMessengerPackage }?.apply {
                val messengerIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 1,
                        top = config.homeRowCount - 1,
                        right = 1,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultSMSMessengerPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(messengerIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
            val resolveInfo =
                packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultBrowserPackage = resolveInfo!!.activityInfo.packageName
            appLaunchers.firstOrNull { it.packageName == defaultBrowserPackage }?.apply {
                val browserIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 2,
                        top = config.homeRowCount - 1,
                        right = 2,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultBrowserPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(browserIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val potentialStores = arrayListOf(
                "com.android.vending", "org.fdroid.fdroid", "com.aurora.store"
            )
            val storePackage = potentialStores.firstOrNull {
                isPackageInstalled(it) && appLaunchers.map { it.packageName }.contains(it)
            }
            if (storePackage != null) {
                appLaunchers.firstOrNull { it.packageName == storePackage }?.apply {
                    val storeIcon = HomeScreenGridItem(
                        id = null,
                        left = 3,
                        top = config.homeRowCount - 1,
                        right = 3,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = storePackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                    homeScreenGridItems.add(storeIcon)
                }
            }
        } catch (_: Exception) {
        }

        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE")
            val resolveInfo =
                packageManager.resolveActivity(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultCameraPackage = resolveInfo!!.activityInfo.packageName
            appLaunchers.firstOrNull { it.packageName == defaultCameraPackage }?.apply {
                val cameraIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 4,
                        top = config.homeRowCount - 1,
                        right = 4,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultCameraPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(cameraIcon)
            }
        } catch (_: Exception) {
        }

        homeScreenGridItemsDB.insertAll(homeScreenGridItems)
    }

    fun handleWidgetBinding(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        appWidgetInfo: AppWidgetProviderInfo,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnCanBindWidget = null
        val canCreateWidget =
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider)
        if (canCreateWidget) {
            callback(true)
        } else {
            mActionOnCanBindWidget = callback
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider)
                startActivityForResult(this, REQUEST_ALLOW_BINDING_WIDGET)
            }
        }
    }

    fun handleWidgetConfigureScreen(
        appWidgetHost: AppWidgetHost,
        appWidgetId: Int,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnWidgetConfiguredWidget = callback
        appWidgetHost.startAppWidgetConfigureActivityForResult(
            this,
            appWidgetId,
            0,
            REQUEST_CONFIGURE_WIDGET,
            null
        )
    }

    fun handleShorcutCreation(
        activityInfo: ActivityInfo,
        callback: (shortcutId: String, label: String, icon: Drawable) -> Unit,
    ) {
        mActionOnAddShortcut = callback
        val componentName = ComponentName(activityInfo.packageName, activityInfo.name)
        Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            component = componentName
            startActivityForResult(this, REQUEST_CREATE_SHORTCUT)
        }
    }

    private fun updateStatusBarIcons(backgroundColor: Int? = null) {
        val isLightBackground = when {
            backgroundColor != null -> backgroundColor.getContrastColor() == DARK_GREY
            wallpaperSupportsDarkText != null -> wallpaperSupportsDarkText!!
            else -> {
                refreshWallpaperSupportsDarkText()
                wallpaperSupportsDarkText ?: false
            }
        }
        window.insetsController().apply {
            isAppearanceLightStatusBars = isLightBackground
            isAppearanceLightNavigationBars = isLightBackground
        }
    }

    // taken from https://gist.github.com/maxjvh/a6ab15cbba9c82a5065d
    private fun calculateAverageColor(bitmap: Bitmap): Int {
        var red = 0
        var green = 0
        var blue = 0
        val height = bitmap.height
        val width = bitmap.width
        var n = 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var i = 0
        while (i < pixels.size) {
            val color = pixels[i]
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            n++
            i += 1
        }

        return Color.rgb(red / n, green / n, blue / n)
    }
}
