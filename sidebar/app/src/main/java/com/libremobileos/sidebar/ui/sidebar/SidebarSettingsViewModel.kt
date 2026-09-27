package com.libremobileos.sidebar.ui.sidebar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_PROFILE_AVAILABLE
import android.content.Intent.ACTION_PROFILE_UNAVAILABLE
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.SidebarAppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.KEY_SHOW_PREDICTED_APPS
import com.libremobileos.sidebar.service.SidebarService
import com.libremobileos.sidebar.service.SidebarMonitorService
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.contains
import com.libremobileos.sidebar.utils.getSidebarFilteredUsers
import com.libremobileos.sidebar.utils.isResizeableActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import java.text.Collator

/**
 * @author KindBrave
 * @since 2023/10/21
 */
class SidebarSettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    private val logger = Logger("SidebarSettingsViewModel")
    private val repository = DatabaseRepository(application)
    private val allAppList = ArrayList<SidebarAppInfo>()
    val appListFlow: StateFlow<List<SidebarAppInfo>>
        get() = _appList.asStateFlow()
    private val _appList = MutableStateFlow<List<SidebarAppInfo>>(emptyList())
    private val appComparator = AppComparator()
    private var initJob: Job? = null

    val isEnabled = UserHandle.myUserId() == 0
    private val appContext = application.applicationContext
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var sp: SharedPreferences

    private val userProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d("userProfileReceiver received ${intent.action}")
            initAllAppList()
        }
    }

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            initAllAppList()
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            initAllAppList()
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) {
            initAllAppList()
        }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            initAllAppList()
        }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            initAllAppList()
        }
    }

    init {
        if (isEnabled) {
            logger.d("init")
            launcherApps = application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            userManager = application.getSystemService(Context.USER_SERVICE) as UserManager
            sp = appContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)

            initAllAppList()
            launcherApps.registerCallback(launcherAppsCallback)
            appContext.registerReceiverAsUser(
                userProfileReceiver,
                UserHandle.CURRENT,
                IntentFilter().apply {
                    addAction(ACTION_PROFILE_AVAILABLE)
                    addAction(ACTION_PROFILE_UNAVAILABLE)
                },
                null,
                null
            )
        }
    }

    override fun onCleared() {
        logger.d("onCleared")
        if (!isEnabled) return
        launcherApps.unregisterCallback(launcherAppsCallback)
        appContext.unregisterReceiver(userProfileReceiver)
    }

    fun getSidebarEnabled(): Boolean =
        isEnabled && sp.getBoolean(SidebarService.SIDELINE, false)

    fun setSidebarEnabled(enabled: Boolean) =
        sp.edit()
            .putBoolean(SidebarService.SIDELINE, enabled)
            .apply()

    fun addSidebarApp(appInfo: SidebarAppInfo) {
        repository.insertSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
    }

    fun deleteSidebarApp(appInfo: SidebarAppInfo) {
        repository.deleteSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
    }

    fun getPredictedAppsEnabled(): Boolean =
        sp.getBoolean(KEY_SHOW_PREDICTED_APPS, true)

    fun setPredictedAppsEnabled(enabled: Boolean) =
        sp.edit()
            .putBoolean(KEY_SHOW_PREDICTED_APPS, enabled)
            .apply()

    fun getAutoEnableSelectedAppsEnabled(): Boolean =
        sp.getBoolean(SidebarMonitorService.KEY_AUTO_ENABLE_SELECTED_APPS, false)

    fun setAutoEnableSelectedAppsEnabled(enabled: Boolean) =
        sp.edit()
            .putBoolean(SidebarMonitorService.KEY_AUTO_ENABLE_SELECTED_APPS, enabled)
            .apply()

    fun getFreeformControlMode(): String =
        Settings.System.getString(
            appContext.contentResolver,
            FREEFORM_CONTROL_MODE
        )?.let {
            when {
                it.equals(FREEFORM_BUTTON_MODE, ignoreCase = true) ||
                        it == LEGACY_BUTTON_MODE -> FREEFORM_BUTTON_MODE
                it.equals(FREEFORM_GESTURE_MODE, ignoreCase = true) ||
                        it == LEGACY_GESTURE_MODE -> FREEFORM_GESTURE_MODE
                else -> FREEFORM_GESTURE_MODE
            }
        } ?: FREEFORM_GESTURE_MODE

    fun setFreeformControlMode(mode: String) {
        Settings.System.putString(
            appContext.contentResolver,
            FREEFORM_CONTROL_MODE,
            mode
        )
    }

    private fun initAllAppList() {
        initJob?.cancel()
        initJob = viewModelScope.launch(Dispatchers.IO) {
            allAppList.clear()
            userManager.getSidebarFilteredUsers().forEach { userInfo ->
                logger.d("initAllAppList for user $userInfo")
                val list = launcherApps.getActivityList(null, userInfo.userHandle)

                list.forEach { info ->
                    val component = info.componentName
                    if (!application.isResizeableActivity(component)) {
                        logger.d("activity not resizeable, skipped $component")
                    } else {
                        allAppList.add(
                            SidebarAppInfo(
                                "${info.label}${userInfo.suffix}",
                                info.getBadgedIcon(0),
                                component.packageName,
                                component.className,
                                userInfo.userId,
                                false
                            )
                        )
                    }
                }
            }

            repository.getAllSidebarAppsByFlow().collectLatest { pinnedApps ->
                val updatedList = allAppList.map { app ->
                    app.copy(isSidebarApp = pinnedApps?.contains(app.packageName, app.activityName, app.userId) ?: false)
                }
                _appList.value = updatedList.sortedWith(appComparator)
                logger.d("emitted updatedList: size=${_appList.value.size}")
            }
        }
    }

    companion object {
        private const val FREEFORM_CONTROL_MODE = "freeform_control_mode"
        const val FREEFORM_BUTTON_MODE = "Button"
        const val FREEFORM_GESTURE_MODE = "Gesture"
        private const val LEGACY_BUTTON_MODE = "0"
        private const val LEGACY_GESTURE_MODE = "1"

        val Factory = viewModelFactory {
            initializer {
                SidebarSettingsViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                )
            }
        }
    }

    private inner class AppComparator : Comparator<SidebarAppInfo> {
        override fun compare(p0: SidebarAppInfo, p1: SidebarAppInfo): Int {
            return when {
                // put checked items first
                p0.isSidebarApp && !p1.isSidebarApp -> -1
                p1.isSidebarApp && !p0.isSidebarApp -> 1
                else -> Collator.getInstance().compare(p0.label, p1.label)
            }
        }
    }
}
