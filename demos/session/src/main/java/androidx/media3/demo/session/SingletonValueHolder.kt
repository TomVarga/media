
package androidx.media3.demo.session

import android.util.Log

object SingletonValueHolder {

    var someValue = false
        set(value) {
            Log.d("SingletonValueHolder", "someValue: new value $value")
            field = value
        }
}
