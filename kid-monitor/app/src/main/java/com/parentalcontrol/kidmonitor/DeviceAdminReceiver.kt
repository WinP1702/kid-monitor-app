package com.parentalcontrol.kidmonitor

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver.
 * Being a device admin prevents the app from being easily uninstalled.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin activated
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device admin deactivated - attempt restart
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling this will break system optimization features."
    }
}
