package max.project.aurasync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.FileObserver.ACCESS
import android.os.FileObserver.ATTRIB
import android.os.FileObserver.CLOSE_NOWRITE
import android.os.FileObserver.CLOSE_WRITE
import android.os.FileObserver.CREATE
import android.os.FileObserver.DELETE
import android.os.FileObserver.DELETE_SELF
import android.os.FileObserver.MODIFY
import android.os.FileObserver.MOVED_FROM
import android.os.FileObserver.MOVED_TO
import android.os.FileObserver.MOVE_SELF
import android.os.FileObserver.OPEN
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import max.project.aurasync.R
import max.project.aurasync.proto.SyncEvent
import org.apache.commons.codec.digest.Blake3
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections

class AuraService : Service() {
    companion object {
        val isServiceRunning = MutableStateFlow(false)
    }

    private val channel_id = "AuraSyncChannel"
    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var fileObserver: FileObserver? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var multicastLock: WifiManager.MulticastLock? = null
//    private val ignoreSet = Collections.synchronizedSet(HashSet<String>())
    private val incomingHashes = Collections.synchronizedSet(HashSet<String>())
    private val eventFlow = MutableSharedFlow<Pair<Int, String>>(
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == "STOP_SERVICE") {
            Log.d("AuraService", "Stopping service via notification action")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        isServiceRunning.value = true
        createNotificationChannel()

        val stopIntent = Intent(this, AuraService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channel_id)
            .setContentTitle("Auro-core sync")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("Syncing with PC. Tap Stop to exit.")
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
//            .setExtras()
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                1,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            )
        } else {
            startForeground(1, notification)
        }
        startDiscoveryAndSync()
        return START_STICKY
    }

    private val ignorePatterns = listOf(
        Regex("^\\..*"),             // Hidden files starting with dot
        Regex(".*\\.tmp$"),          // .tmp files
        Regex(".*~$"),               // Linux/Gedit swap files
        Regex("^~\\$.*"),            // Office temporary files
        Regex("(?i)desktop\\.ini"),  // Windows system files
        Regex("(?i)thumbs\\.db")     // Thumbnail caches
    )

    private fun isIgnored(path: String): Boolean {
        val fileName = path.substringAfterLast(File.separator)
        return ignorePatterns.any { it.matches(fileName) }
    }
    private fun startDiscoveryAndSync() {
        serviceScope.launch {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wm.createMulticastLock("AuroLock").apply {
                    acquire()
                }
                udpSocket?.close()
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8888))
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                Log.d("AuraServiceDiscovery", "startDiscoveryAndSync: ")
                udpSocket?.receive(packet)
                tcpSocket = Socket(packet.address.hostAddress, 9999)
                tcpSocket?.let { handleCommunication(it) }
            } catch (e: Exception) {
                Log.d("AuraServiceDiscovery", "startDiscoveryAndSync: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun handleCommunication(tcpSockets: Socket) {
        val input = DataInputStream(tcpSockets.getInputStream())
        val output = tcpSockets.getOutputStream()
        val parentPath =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val syncFile = File(parentPath, "AuroSync").apply { if (!exists()) mkdirs() }

        serviceScope.launch {
            try {
                while (tcpSockets.isConnected && !tcpSockets.isClosed) {
                    val msgLen = input.readInt()
                    val msg = ByteArray(msgLen)
                    input.readFully(msg)
                    val event = SyncEvent.parseFrom(msg)
                    val normalizedPath = event.filePath.trimStart('/')
                    val localHash = if (event.action == SyncEvent.Action.DELETE) {
                        ""
                    } else {
                        val targetFile = File(syncFile, normalizedPath)
                        if (targetFile.exists() && targetFile.isFile) {
                            calculateBlake3(targetFile)
                        } else {
                            ""  // File doesn't exist yet (for CREATE events)
                        }
                    }

                    if (localHash == event.checksum) {
                        // This is an echo of our own event, skip processing
                        Log.d("AuraServiceReceiver", "Echo detected, skipping: ${event.filePath}")
                        return@launch
                    }
                    incomingHashes.add(event.checksum)
                    processRemoteEvent(event, input, syncFile)
                }
            } catch (e: Exception) {
                Log.d("AuraService", "handleCommunication: ${e.message}")
                e.printStackTrace()
            }
        }
        serviceScope.launch {
            eventFlow.debounce(500).collect { (event, path) ->
                val file = File(syncFile, path)
                val hash = when(event){
                    DELETE,DELETE_SELF->"DELETE_${path}"
                    else -> calculateBlake3(file)
                }
                if (incomingHashes.contains(hash)) {
                    incomingHashes.remove(hash)
                    Log.d("AuraServiceObserver", "Ignoring echo event(${eventNameSeeker(event)}) for: $path")
                    return@collect
                }
                when(event) {
                    MOVED_TO,CLOSE_WRITE->{
                        val mode =
                            if (event == MOVED_TO) SyncEvent.Action.CREATE else SyncEvent.Action.MODIFY
                        Log.d(
                            "AuraServiceObserver",
                            "Sending Create onEvent: ${eventNameSeeker(event)}  $path ${file.absolutePath}"
                        )
                        sendFullFile(output, path, file, syncFile.absolutePath, mode, hash)
                    }
                    DELETE,DELETE_SELF->{
                        sendDelete(output, path)
                        Log.d(
                            "AuraServiceObserver",
                            "Sending Delete onEvent: ${eventNameSeeker(event)}  $path"
                        )
                    }
                }
            }
        }

        Log.d("AuraService", "handleCommunication: ${syncFile.absolutePath}")
        fileObserver = object : FileObserver(syncFile) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || isIgnored(path)) {
                    Log.d("AuraServiceObserver", "Ignoring echo event(${eventNameSeeker(event)}) for: $path")
                    return
                }
                val mask = event and ALL_EVENTS
                if(mask==DELETE||mask==DELETE_SELF){
                    sendDelete(output, path)
                }
                else if (mask == CLOSE_WRITE || mask == MOVED_TO) {
                    Log.d("AuraServiceObserver", "onEvent: ${eventNameSeeker(event)}  $path")
                    eventFlow.tryEmit(Pair(event, path))
                }
//                Log.d("AuraServiceObserver", "onEvent: $event  ")
            }

        }
        fileObserver?.startWatching()
    }


//    private fun ignoreEvent(event: Int): Boolean {
//        return
//    }
    private fun eventNameSeeker(event: Int): String {
        return when(event){
            ACCESS ->"ACCESS"
            MODIFY ->"MODIFY"
            ATTRIB ->"ATTRIB"
            CLOSE_WRITE ->"CLOSE_WRITE"
            CLOSE_NOWRITE->"CLOSE_NOWRITE"
            OPEN->"OPEN"
            MOVED_FROM->"MOVED_FROM"
            MOVED_TO->"MOVED_TO"
            CREATE->"CREATE"
            DELETE->"DELETE"
            DELETE_SELF->"DELETE_SELF"
            MOVE_SELF->"MOVE_SELF"
            else -> "UNKNOWN ($event)"
        }
    }

    private suspend fun sendFullFile(
        output: OutputStream,
        path: String,
        file: File,
        rootPath: String,
        action: SyncEvent.Action,
        hash: String
    ) {
        try {
            val event = SyncEvent.newBuilder().setAction(action)
                 .setFilePath(
                    file.absolutePath
                        .removePrefix(rootPath)
                        .trimStart('/')  // Ensure no leading slash
                        .replace('\\', '/')  // Normalize separators
                ).setChecksum(hash)
                .setFileSize(file.length())
                .build()
            Log.d(
                "AuraServiceFileSender",
                "sendFullFile: ${file.absolutePath} ${event.filePath} ${event.fileSize}  "
            )
            val bytes = event.toByteArray()
            val length = bytes.size
            output.write(ByteBuffer.allocate(4).putInt(length).array())
            output.write(bytes)
            file.inputStream().use {
                it.copyTo(output)
            }
            output.flush()
            Log.d("AuraServiceFileSender", "sent FullFile: $path")
        } catch (e: Exception) {
            Log.e("AuraServiceFileSender", "Error sending file: ${e.message}")
            e.printStackTrace()
            try {
                tcpSocket?.close()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    private fun processRemoteEvent(
        event: SyncEvent,
        input: DataInputStream,
        root: File
    ) {
        val targetFile = File(root, event.filePath)
//        targetFile.isFile

        Log.d("AuraServiceReceiver", "processRemoteEvent: ${targetFile.absolutePath} ${targetFile.isFile}")
        Log.d("AuraServiceReceiver", "processRemoteEvent: ${targetFile.absolutePath} ${event.filePath} ")
        Log.d("AuraServiceReceiver", "processRemoteEvent: ${event.action} ")
        when (event.action) {
            SyncEvent.Action.DELETE -> {
                if (targetFile.exists()) {
                    targetFile.delete()
                    Log.d("AuraServiceReceiver", "delected: ${targetFile.absolutePath}")
                } else {
                    Log.d(
                        "AuraServiceReceiver",
                        "not delected: ${targetFile.absolutePath} because not exists"
                    )
                }
            }

            SyncEvent.Action.CREATE, SyncEvent.Action.MODIFY -> {
                Log.d("AuraServiceReceiver", "processed upserted: ${event.filePath}")
//                ignoreSet.add(event.filePath)
                try {
                    val tempFile = File(root, "${event.filePath}.tmp")
//                    ignoreSet.add(event.t)
                    val storageManager =
                        getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
                    val uuid = storageManager.getUuidForPath(root)
                    if (storageManager.getAllocatableBytes(uuid) >= event.fileSize) {
                        storageManager.allocateBytes(uuid, event.fileSize)
                    }
                    val usableSpace = root.usableSpace
                    if (usableSpace < event.fileSize) {
                        Log.e("AuraServiceReceiver", "Not enough space!")
                        return
                    }

                    tempFile.outputStream().use { it ->
                        var remaining = event.fileSize
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val bytesToRead = minOf(remaining, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, bytesToRead)
                            it.write(buffer, 0, bytesToRead)
                            remaining -= bytesToRead
                        }
                        it.flush()
                    }

                    tempFile.renameTo(targetFile)
                    Log.d("AuraServiceReceiver", "processed upserted: ${targetFile.absolutePath} ${targetFile.isFile} ")

                    Log.d("AuraServiceReceiver", "processed upserted : ${targetFile.absolutePath}")
                } catch (e: Exception) {
                    Log.d("AuraServiceReceiver", "processRemoteEvent: ${e.message}")
                    e.printStackTrace()
                }
                finally {

                }
            }

            SyncEvent.Action.RENAME -> {
                if (targetFile.exists()) {
                    targetFile.renameTo(File(root, event.newPath))
                    Log.d("AuraServiceReceiver", "renamed: ${targetFile.absolutePath}")
                } else {
                    Log.d(
                        "AuraServiceReceiver",
                        "not renamed: ${targetFile.absolutePath} because not exists"
                    )
                }
            }

            SyncEvent.Action.ATTR_CHANGE -> TODO()
            SyncEvent.Action.UNRECOGNIZED -> TODO()
        }
    }
    private fun calculateBlake3(file: File): String {
        if (!file.exists()) {
            Log.d("Blake3", "File does not exist, returning empty hash: ${file.absolutePath} ${file.isFile}")
            return ""
        }

        try {
            val hasher = Blake3.initHash()
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    hasher.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = ByteArray(32)
            hasher.doFinalize(hashBytes)
            // Convert to HEX string
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("Blake3", "Error calculating hash for ${file.absolutePath}: ${e.message}")
            return ""  // Return empty string on error
        }
    }

    private fun sendDelete(output: OutputStream, path: String) {
        try {
            val event = SyncEvent.newBuilder()
                .setAction(SyncEvent.Action.DELETE)
                .setFilePath(path)
                .setChecksum("")
                .build()
            val bytes = event.toByteArray()
            val length = bytes.size
            output.write(ByteBuffer.allocate(4).putInt(length).array())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e("AuraServiceSender", "Error sending delete: ${e.message}")
            e.printStackTrace()
            try {
                tcpSocket?.close()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(channel_id, "AuraSync", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isServiceRunning.value = false
        if (multicastLock?.isHeld == true)
            multicastLock?.release()
        serviceScope.cancel()
        fileObserver?.stopWatching()
        try {
            tcpSocket?.close()
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}