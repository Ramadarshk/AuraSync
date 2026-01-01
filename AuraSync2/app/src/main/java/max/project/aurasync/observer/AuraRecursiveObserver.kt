package max.project.aurasync.observer

import android.os.FileObserver
import java.io.File
import javax.security.auth.callback.Callback

class AuraRecursiveObserver(val file: File,val callback: (Int,String)-> Unit) {
    private val observer = mutableMapOf<String, FileObserver>()
    fun start(){

    }

}