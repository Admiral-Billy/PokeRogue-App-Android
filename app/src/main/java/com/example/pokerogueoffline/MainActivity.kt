package com.example.pokerogueoffline

import android.Manifest
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile


class AndroidInterface(private val context: Context) {
    @JavascriptInterface
    fun downloadFile(fileName: String, mimeType: String?, base64Data: String) {
        try {
            val downloadsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            val file = File(downloadsDir, fileName)
            val outputStream = FileOutputStream(file)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            outputStream.write(bytes)
            outputStream.close()

            // Show a toast or notification to indicate successful download
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "File downloaded to Downloads folder", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Show a toast or notification to indicate download failure
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "File download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class MainActivity : AppCompatActivity() {
    private inner class CustomDownloadListener : DownloadListener {
        override fun onDownloadStart(url: String?, userAgent: String?, contentDisposition: String?, mimetype: String?, contentLength: Long) {
            if (url?.startsWith("blob:") == true) {
                val fileName = "data.prsv"
                convertBlobToFile(url, fileName, mimetype)
            } else {
                // Handle regular download URLs if needed
                // ...
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var deleteButton: Button
    private lateinit var updateButton: Button
    private lateinit var futabaButton: Button
    private lateinit var playButton: Button
    private lateinit var playOnlineButton: Button
    private lateinit var startScreenLayout: LinearLayout

    private var focusedButtonIndex: Int? = null
    private lateinit var buttons: Array<Button>

    private val gameDir by lazy { File(getExternalFilesDir(null), "game") }
    private var server: NanoHTTPD? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1
        private const val PERMISSIONS_REQUEST_CODE = 2
    }

    private var mFilePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        deleteButton = findViewById(R.id.deleteOfflineFilesButton)
        updateButton = findViewById(R.id.updateButton)
        futabaButton = findViewById(R.id.futabaButton)
        playButton = findViewById(R.id.playButton)
        playOnlineButton = findViewById(R.id.playOnlineButton)
        startScreenLayout = findViewById(R.id.startScreenLayout)

        val currentApiVersion = Build.VERSION.SDK_INT
        if (currentApiVersion < 30) {
            Toast.makeText(
                this@MainActivity,
                "WARNING: Recommended minimum Android version is 11. You are currently using Android ${Build.VERSION.RELEASE}, which is very likely to not work properly.",
                Toast.LENGTH_LONG
            ).show()
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.allowFileAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.setDownloadListener(CustomDownloadListener())

        webView.addJavascriptInterface(AndroidInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    """
            (function() {
                var gameContainer = document.getElementById('app');
                var webViewWidth = document.documentElement.clientWidth;
                var webViewHeight = document.documentElement.clientHeight;
                var gameWidth = gameContainer.offsetWidth;
                var gameHeight = gameContainer.offsetHeight;
                var scaleX = webViewWidth / gameWidth;
                var scaleY = webViewHeight / gameHeight;
                var scale = Math.min(scaleX, scaleY);
                gameContainer.style.transform = 'translate(-50%, -50%) scale(' + scale + ')';
                gameContainer.style.position = 'absolute';
                gameContainer.style.left = '50%';
                gameContainer.style.top = '50%';
            })();
            """,
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
                Log.d("WebView", "Console: $message")
            }

            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                mFilePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(Intent.createChooser(intent, "Choose File"), FILE_CHOOSER_REQUEST_CODE)
                webView?.onPause() // Pause the WebView when the file chooser is opened
                return true
            }
        }

        deleteButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                deleteGameFiles()
            }
        }

        updateButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                updateGameFiles(false)
            }
        }

        futabaButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                updateGameFiles(true)
            }
        }

        playButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                startLocalServer(applicationContext)
                withContext(Dispatchers.Main) {
                    startScreenLayout.visibility = View.GONE
                }
            }
        }

        playOnlineButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                startOnline(applicationContext)
                withContext(Dispatchers.Main) {
                    startScreenLayout.visibility = View.GONE
                }
            }
        }

        // Initialize buttons array with your button views
        buttons = arrayOf(deleteButton, playOnlineButton, playButton, updateButton, futabaButton)

        // Set initial focus and highlight the first button
        focusedButtonIndex = null

        requestPermissions()
        checkGameFiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isGameLoaded", startScreenLayout.visibility == View.GONE)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val isGameLoaded = savedInstanceState.getBoolean("isGameLoaded", false)
        if (isGameLoaded) {
            startScreenLayout.visibility = View.GONE
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView.evaluateJavascript(
            """
        (function() {
            var gameContainer = document.getElementById('app');
            var webViewWidth = document.documentElement.clientWidth;
            var webViewHeight = document.documentElement.clientHeight;
            var gameWidth = gameContainer.offsetWidth;
            var gameHeight = gameContainer.offsetHeight;
            var scaleX = webViewWidth / gameWidth;
            var scaleY = webViewHeight / gameHeight;
            var scale = Math.min(scaleX, scaleY);
            gameContainer.style.transform = 'translate(-50%, -50%) scale(' + scale + ')';
        })();
        """,
            null
        )
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission granted
            } else {
                // Request permission
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
                }
            }
        } else {
            // Below Android 11
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                // Permission granted
            } else {
                // Permission denied
                Toast.makeText(this, "Storage permission is required for this app to download offline files", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // Permission granted
                } else {
                    // Permission denied
                    Toast.makeText(this, "Manage all files access is required for this app to download offline files", Toast.LENGTH_LONG).show()
                }
            }
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val result = data?.data?.let { arrayOf(it) }
                mFilePathCallback?.onReceiveValue(result)
            } else {
                mFilePathCallback?.onReceiveValue(null)
            }
            mFilePathCallback = null
            webView.onResume() // Resume the WebView when the file chooser is closed
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    private fun convertBlobToFile(blobUrl: String, fileName: String, mimeType: String?) {
        val javascriptCode = """
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '$blobUrl', true);
        xhr.responseType = 'blob';
        xhr.onload = function() {
            if (this.status === 200) {
                var blob = this.response;
                var reader = new FileReader();
                reader.onloadend = function() {
                    var base64data = reader.result.split(',')[1];
                    Android.downloadFile('$fileName', '$mimeType', base64data);
                };
                reader.readAsDataURL(blob);
            }
        };
        xhr.send();
    """
        webView.evaluateJavascript(javascriptCode, null)
    }

    private fun checkGameFiles() {
        GlobalScope.launch(Dispatchers.IO) {
            if (gameDir.exists() && gameDir.listFiles()?.isNotEmpty() == true) {
                withContext(Dispatchers.Main) {
                    runOnUiThread {
                        playButton.isEnabled = true
                        deleteButton.isEnabled = true
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    runOnUiThread {
                        playButton.isEnabled = false
                        deleteButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun deleteGameFiles() {
        GlobalScope.launch(Dispatchers.Main) {
            // Create a progress dialog to show the current file being downloaded
            val progressDialog = ProgressDialog(this@MainActivity)
            progressDialog.setMessage("Deleting game files...")
            progressDialog.setCancelable(false)
            progressDialog.show()

            try {
                withContext(Dispatchers.IO) {
                    // Delete existing game files
                    gameDir.deleteRecursively()
                    gameDir.mkdirs()
                    // Update UI components from the main/UI thread
                    runOnUiThread {
                        // Update UI on the main dispatcher
                        playButton.isEnabled = false
                        deleteButton.isEnabled = false
                        progressDialog.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            "Offline files deleted. Re-download before playing offline again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Log the exception message
                Log.e("deleteGameFiles", "Error deleting game files: ${e.message}", e)
                // Update UI on the main dispatcher
                Toast.makeText(this@MainActivity, "Failed to delete game files. Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateGameFiles(modded: Boolean) {
        GlobalScope.launch(Dispatchers.Main) {

            // Create a progress dialog to show the current file being downloaded
            val progressDialog = ProgressDialog(this@MainActivity)
            progressDialog.setMessage("Updating game files...")
            progressDialog.setCancelable(false)
            progressDialog.show()

            try {
                withContext(Dispatchers.IO) {
                        // Download the artifact zip file
                        val artifactUrl = getLatestReleaseAssetDownloadUrl(modded)
                        var zipFile: File
                        if (modded) {
                            zipFile = File(cacheDir, "game_futaba_mod.zip")
                        } else {
                            zipFile = File(cacheDir, "game.zip")
                        }

                        val connection = URL(artifactUrl).openConnection().apply {
                            connectTimeout = 10000 // Set connection timeout
                            readTimeout = 10000 // Set read timeout
                        }

                        connection.getInputStream().use { inputStream ->
                            val totalFileSize = connection.contentLength // Get total file size
                            val buffer = ByteArray(1024) // Buffer for reading data
                            var downloadedSize = 0 // Size of downloaded data
                            var bytesRead: Int // Number of bytes read in each iteration



                            // Open output stream to write data to the zip file
                            zipFile.outputStream().use { outputStream ->
                                // Read from input stream and write to output stream in chunks
                                while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                                    outputStream.write(
                                        buffer,
                                        0,
                                        bytesRead
                                    ) // Write data to output stream
                                    downloadedSize += bytesRead // Update downloaded size

                                    // Calculate progress percentage
                                    val progressPercentage =
                                        ((downloadedSize.toDouble() / totalFileSize.toDouble()) * 100).toInt()

                                    // Update progress dialog
                                    //if (progressPercentage in 0..99) {
                                    //    progressDialog.setMessage("Downloading game files ($progressPercentage%)")
                                    //}
                                }
                            }
                        }

                        // Delete existing game files
                        gameDir.deleteRecursively()
                        gameDir.mkdirs()

                        // Extract the zip file to the game directory
                        ZipFile(zipFile).use { zip ->
                            val totalEntries = zip.size()
                            var currentEntry = 0
                            zip.entries().asSequence().forEach { entry ->
                                currentEntry++
                                // Update progress dialog
                                val progressPercentage =
                                    ((currentEntry.toDouble() / totalEntries.toDouble()) * 100).toInt()
                                //progressDialog.setMessage("Extracting game files ($progressPercentage%)")

                                if (entry.isDirectory) {
                                    // Create directories
                                    val directory = File(gameDir, entry.name)
                                    directory.mkdirs()
                                } else {
                                    // Extract files
                                    val file = File(gameDir, entry.name)
                                    zip.getInputStream(entry).use { input ->
                                        file.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        }

                        // Delete the temporary zip file
                        zipFile.delete()

                    runOnUiThread {
                        // Update UI on the main dispatcher
                        Toast.makeText(this@MainActivity, "Game files updated successfully.", Toast.LENGTH_SHORT).show()
                        playButton.isEnabled = true
                        deleteButton.isEnabled = true
                        progressDialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Log the exception message
                Log.e("UpdateGameFiles", "Error updating game files: ${e.message}", e)
                // Update UI on the main dispatcher
                Toast.makeText(this@MainActivity, "Failed to update game files. Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getLatestReleaseAssetDownloadUrl(modded: Boolean): String {
        val repoOwner = "Admiral-Billy"
        val repoName = "pokerogue"
        val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        val response = connection.inputStream.bufferedReader().use { it.readText() }

        Log.d("ReleaseResponse", response)

        val jsonObject = JSONObject(response)
        val assets = jsonObject.getJSONArray("assets")
        var assetIndex: Int
        if (modded)
        {
            assetIndex = 1
        }
        else
        {
            assetIndex = 0
        }
        val gameAsset = assets.getJSONObject(assetIndex)
        val downloadUrl = gameAsset.getString("browser_download_url")

        return downloadUrl
    }

    private fun startLocalServer(context: Context) {
        server = object : NanoHTTPD(8000) {
            override fun serve(session: IHTTPSession?): Response {
                val uri = session?.uri ?: "/"
                val file = File(gameDir, uri)
                return try {
                    val inputStream = FileInputStream(file)
                    newChunkedResponse(Response.Status.OK, getMimeTypeForFile(uri), inputStream)
                } catch (e: IOException) {
                    Log.e("GameServer", "Failed to open file: ${file.absolutePath}", e)
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                }
            }
        }
        server?.start()

        runOnUiThread {
            webView.loadUrl("http://localhost:8000/index.html")
            webView.visibility = View.VISIBLE
            startScreenLayout.visibility = View.GONE
            deleteButton.visibility = View.GONE
        }
    }

    private fun startOnline(context: Context) {
        runOnUiThread {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().acceptCookie();
            webView.loadUrl("http://pokerogue.net")
            webView.visibility = View.VISIBLE
            startScreenLayout.visibility = View.GONE
            deleteButton.visibility = View.GONE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (startScreenLayout.visibility == View.VISIBLE)
        {
            if (focusedButtonIndex == null) {
                // If no button is focused, set focus to the first button
                focusedButtonIndex = 1
                setButtonFocus(1)
                Toast.makeText(this@MainActivity, "Press any face button to confirm option.", Toast.LENGTH_LONG).show()
                return true
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocus(-1) // Move focus to the previous button
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocus(1) // Move focus to the next button
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveFocus(-1) // Move focus to the previous button
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveFocus(1) // Move focus to the next button
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_4,
                KeyEvent.KEYCODE_ENTER -> {
                    handleButtonClick(focusedButtonIndex!!)
                    return true
                }
                // Add cases for other controller buttons as needed
                else -> {
                    Toast.makeText(this@MainActivity, "Use DPad to select option. Press any face button to confirm option.", Toast.LENGTH_LONG).show()
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun moveFocus(delta: Int) {
        val newFocusedIndex = (focusedButtonIndex!! + delta + buttons.size) % buttons.size
        if (newFocusedIndex == 0)
        {
            if (!deleteButton.isEnabled)
            {
                if (delta > 0)
                {
                    setButtonFocus(1)
                }
                else
                {
                    setButtonFocus(4)
                }
            }
            else {
                setButtonFocus(newFocusedIndex)
            }
        }
        else if (newFocusedIndex == 2)
        {
            if (!playButton.isEnabled)
            {
                if (delta > 0)
                {
                    setButtonFocus(3)
                }
                else
                {
                    setButtonFocus(1)
                }
            }
            else {
                setButtonFocus(newFocusedIndex)
            }
        }
        else {
            setButtonFocus(newFocusedIndex)
        }
    }

    fun isDarkModeEnabled(configuration: Configuration): Boolean {
        return configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setButtonFocus(index: Int) {
        if (focusedButtonIndex != null) {
            // Reset previous focus if exists
            if (isDarkModeEnabled(resources.configuration))
            {
                buttons[focusedButtonIndex!!].setTextColor(Color.BLACK)
            }
            else
            {
                buttons[focusedButtonIndex!!].setTextColor(Color.WHITE)
            }
        }

        focusedButtonIndex = index
        // Set focus
        buttons[focusedButtonIndex!!].setTextColor(Color.RED)
    }

    private fun handleButtonClick(buttonIndex: Int) {
        when (buttonIndex) {
            0 -> {
                if (deleteButton.isEnabled) {
                    deleteGameFiles()
                    setButtonFocus(1)
                }
                else
                {
                    Toast.makeText(this@MainActivity, "Error: Files can't be deleted if they don't exist", Toast.LENGTH_LONG).show()
                }
            }
            1 -> {
                // Action for the first button (playOnlineButton)
                // For example, start online play and hide the start screen
                startOnline(applicationContext)
                startScreenLayout.visibility = View.GONE
            }
            2 -> {
                // Action for the second button (playButton)
                // For example, start the local server and hide the start screen
                if (playButton.isEnabled) {
                    startLocalServer(applicationContext)
                    startScreenLayout.visibility = View.GONE
                }
                else
                {
                    Toast.makeText(this@MainActivity, "Error: Cannot play offline until game files are downloaded", Toast.LENGTH_LONG).show()
                }
            }
            3 -> {
                // Action for the third button (updateButton)
                // For example, trigger an update action
                updateGameFiles(false)
            }
            4 -> {
                // Action for the fourth button (futabaButton)
                // For example, trigger a modded update action
                updateGameFiles(true)
            }
        }
    }

    private var isWebViewPaused = false

    override fun onPause() {
        super.onPause()
        // Pause the WebView when the app goes into the background
        isWebViewPaused = true
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Resume the WebView when the app comes back into the foreground
        if (isWebViewPaused) {
            webView.onResume()
            isWebViewPaused = false
        }
    }

    override fun onStop() {
        CookieManager.getInstance().flush();
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}