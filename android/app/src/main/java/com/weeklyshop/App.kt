package com.weeklyshop

import android.app.Application
import android.os.Build
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Startup hooks the BOOX Pen SDK needs before TouchHelper can deliver raw pen
 * callbacks on recent firmware. Without either of these the raw layer opens
 * cleanly but stays silent (the exact failure seen on the Note Air 2 Plus):
 *
 *  - the SDK's input reader touches hidden platform APIs, which Android 11+
 *    blocks unless the app registers an exemption;
 *  - the reader runs through the SDK's Rx pipeline, which needs the app
 *    context before anything subscribes.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        runCatching { RxManager.Builder.initAppContext(this) }
    }
}
