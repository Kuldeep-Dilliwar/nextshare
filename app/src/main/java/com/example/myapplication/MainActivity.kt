package com.example.myapplication

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {

    private val fileViewModel: FileViewModel by viewModels()

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
        uris?.let { fileViewModel.updateFileUris(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen(
                fileViewModel = fileViewModel,
                onFilePickerClick = { launchFilePicker() },
                stopServer = { stopFileServer() }
            )
        }
    }

    private fun launchFilePicker() {
        if (fileViewModel.isServerRunning.value) {
            Toast.makeText(this, "Server is already running.", Toast.LENGTH_SHORT).show()
        } else {
            getContent.launch(arrayOf("*/*"))
        }
    }

    private fun stopFileServer() {
        fileViewModel.stopServer()
        Toast.makeText(this, "Server stopped.", Toast.LENGTH_SHORT).show()
    }
}

class FileViewModel : ViewModel() {

    private var fileServer: FileServer? = null

    private val _fileUris = mutableStateListOf<Uri>()
    val fileUris: List<Uri> = _fileUris

    private val _isServerRunning = mutableStateOf(false)
    val isServerRunning: State<Boolean> = _isServerRunning

    fun updateFileUris(context: Context, uris: List<Uri>) {
        _fileUris.clear()
        _fileUris.addAll(uris.map { uri -> uri.buildUpon().appendQueryParameter("timestamp", System.currentTimeMillis().toString()).build() })
        startFileServer(context)
    }

    private fun startFileServer(context: Context) {
        fileServer?.stop()
        fileServer = FileServer(context, fileUris).apply {
            start()
        }
        _isServerRunning.value = true
    }

    fun stopServer() {
        fileServer?.stop()
        _isServerRunning.value = false
        _fileUris.clear()
    }

    override fun onCleared() {
        super.onCleared()
        fileServer?.stop()
    }

    class FileServer(private val context: Context, private val fileUris: List<Uri>) : NanoHTTPD(8080) {

        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/" -> serveHomePage()
                "/file" -> serveFile(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", "404 Not Found")
            }
        }

        private fun serveHomePage(): Response {
            val html = """
                <html>
                <head></head>
                <body>
                    <h1>File Download</h1>
                    <div>${generateFileLinks(fileUris)}</div>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun generateFileLinks(fileUris: List<Uri>): String {
            return fileUris.joinToString("<br>") {
                val cleanUri = Uri.parse(it.toString().substringBeforeLast("?"))
                "<a href=\"/file?uri=${Uri.encode(cleanUri.toString())}\">Download -> ${cleanUri.lastPathSegment?.substringAfterLast('/')}</a>"
            }
        }

        private fun serveFile(session: IHTTPSession): Response {
            val fileUri = session.parameters["uri"]?.firstOrNull()?.let { Uri.parse(it) }
            return fileUri?.let { uri ->
                try {
                    val inputStream: InputStream? = fileUris.firstOrNull { Uri.parse(it.toString().substringBeforeLast("?")) == uri }
                        ?.let { context.contentResolver.openInputStream(uri) }
                    if (inputStream != null) {
                        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: -1
                        if (fileSize >= 0) {
                            newFixedLengthResponse(
                                Response.Status.OK,
                                context.contentResolver.getType(uri) ?: "application/octet-stream",
                                inputStream,
                                fileSize
                            ).apply {
                                addHeader("Content-Disposition", "attachment; filename=\"${getFileNameFromUri(uri)}\"")
                                addHeader("Content-Length", fileSize.toString())
                            }
                        } else {
                            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", "Unable to determine file size")
                        }
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", "File not found")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", "Error serving file")
                }
            } ?: newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/html", "Bad Request")
        }

        private fun getFileNameFromUri(uri: Uri): String {
            val cleanUri = Uri.parse(uri.toString().substringBeforeLast("?"))
            return cleanUri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
        }
    }
}

@Composable
fun MainScreen(
    fileViewModel: FileViewModel = viewModel(),
    onFilePickerClick: () -> Unit,
    stopServer: () -> Unit
) {
    val fileUris by remember { mutableStateOf(fileViewModel.fileUris) }
    val isServerRunning by remember { fileViewModel.isServerRunning }

    val context = LocalContext.current
    val ipAddress = getLocalIpAddress(context)

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "1) Turn your mobile data on or connect to a Wi-Fi to get a valid IP address.")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "2) Turn on your hotspot.")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "3) Turn on your computer's Wi-Fi and connect it to your phone's hotspot to share your file wirelessly at high speed.")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "3) Select the file that you want to send to your computer")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onFilePickerClick() }) {
            Text("Open File Picker")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "4. After done picking files, open this URL on Receivers (PC/Mobile)'s browser to download files: http://$ipAddress:8080/")
        Spacer(modifier = Modifier.height(16.dp))

        if (fileUris.isNotEmpty()) {
            Log.e("kuldeep", "Files selected: $fileUris")
            Text("5) Selected files:\n")
            DisplaySelectedFiles(fileUris)
        } else {
            Text("No files selected.")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isServerRunning) {
            Button(onClick = { stopServer() }) {
                Text("Stop Server")
            }
        }
    }
}


@Composable
fun DisplaySelectedFiles(fileUris: List<Uri>) {
    fileUris.forEachIndexed { index, uri ->
        val fileName = getFileNameFromUri(uri)
        Text("${index + 1}. $fileName")
    }
}

private fun getLocalIpAddress(context: Context): String? {
    try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in networkInterfaces) {
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    for (address in Collections.list(networkInterface.inetAddresses)) {
                        if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                            return address.hostAddress
                        }
                    }
                }
            }
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)

    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return null
}

private fun getFileNameFromUri(uri: Uri): String {
    val cleanUri = Uri.parse(uri.toString().substringBeforeLast("?"))
    return cleanUri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
}
