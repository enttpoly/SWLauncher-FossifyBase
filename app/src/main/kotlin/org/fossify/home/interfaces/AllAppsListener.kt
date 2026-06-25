package com.sw.launcher.interfaces

import com.sw.launcher.models.AppLauncher

interface AllAppsListener {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher)
}
