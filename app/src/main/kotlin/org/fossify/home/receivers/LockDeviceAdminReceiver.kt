package com.sw.launcher.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.sw.launcher.R

class LockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.lock_device_admin_warning)
    }
}
