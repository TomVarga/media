package androidx.media3.demo.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DisabledMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // do nothing to disable playback resumption
    }
}
