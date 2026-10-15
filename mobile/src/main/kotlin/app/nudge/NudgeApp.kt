package app.nudge

import android.app.Application
import app.nudge.data.Store
import app.nudge.engine.Engine
import app.nudge.engine.Notifier

class NudgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The speech model is loaded by the screens that record (Asr.warmUp), not here, so
        // background wake-ups for alarms and wifi changes stay light.
        Store.init(this)
        Engine.init(this)
        Notifier.channels(this)
    }
}
