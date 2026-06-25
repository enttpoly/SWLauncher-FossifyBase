package com.sw.launcher.interfaces

import com.sw.launcher.models.AppWidget

interface WidgetsFragmentListener {
    fun onWidgetLongPressed(appWidget: AppWidget)
}
