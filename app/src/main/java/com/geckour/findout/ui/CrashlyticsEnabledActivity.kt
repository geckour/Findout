package com.geckour.findout.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.crashlytics.android.Crashlytics
import com.geckour.findout.BuildConfig
import io.fabric.sdk.android.Fabric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

abstract class CrashlyticsEnabledActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG.not()) Fabric.with(this, Crashlytics())
    }
}