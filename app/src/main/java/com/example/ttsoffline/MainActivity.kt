package com.example.ttsoffline

import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.microsoft.cognitiveservices.speech.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import com.microsoft.cognitiveservices.speech.audio.AudioConfig



class MainActivity : AppCompatActivity() {

    private lateinit var selectPdfButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var convertButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var pdfUri: Uri? = null
    private var extractedText: String = ""
    private var outputFolderUri: Uri? = null

    private var speechKey: String = ""
    private var serviceRegion: String = ""

    private fun updateStatus() {
        val pdfName = if (pdfUri != null) {
            contentResolver.query(pdfUri!!, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "Unknown"
        } else "None"

        val folderName = outputFolderUri?.lastPathSegment?.substringAfter(":")?.substringAfterLast("/") ?: "None"

        val ready = pdfUri != null && outputFolderUri != null

        statusText.text = "PDF: $pdfName\nFolder: $folderName\n\n${if (ready) "Ready to convert!" else "Select PDF and folder to continue."}"
        convertButton.isEnabled = ready
    }

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pdfUri = it
            extractTextFromPdf(it)
            updateStatus()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            outputFolderUri = it
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load user credentials.
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        speechKey = prefs.getString("SPEECH_KEY", "") ?: ""
        serviceRegion = prefs.getString("SERVICE_REGION", "") ?: ""

        if (speechKey.isEmpty() || serviceRegion.isEmpty()) {
            // Should not happen, but just in case!!
            logout()
            return
        }

        // Initialize PDFBox.
        PDFBoxResourceLoader.init(applicationContext)

        selectPdfButton = findViewById(R.id.selectPdfButton)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        convertButton = findViewById(R.id.convertButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        convertButton.isEnabled = false

        selectPdfButton.setOnClickListener {
            pdfPickerLauncher.launch("application/pdf")
        }

        selectFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        convertButton.setOnClickListener {
            if (extractedText.isNotEmpty()) {
                checkPermissionsAndConvert()
            } else {
                Toast.makeText(this, "No text extracted from PDF", Toast.LENGTH_SHORT).show()
            }
        }

        checkStoragePermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun logout() {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun checkStoragePermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun checkPermissionsAndConvert() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                101
            )
        } else {
            convertToSpeech()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            convertToSpeech()
        }
    }

    private fun extractTextFromPdf(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val document = PDDocument.load(inputStream)
                val stripper = PDFTextStripper()
                extractedText = stripper.getText(document)
                document.close()

                withContext(Dispatchers.Main) {
                    statusText.text = "Text extracted: ${extractedText.length} characters"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error extracting text: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun convertToSpeech() {
        progressBar.visibility = ProgressBar.VISIBLE
        convertButton.isEnabled = false
        statusText.text = "Converting to speech..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // If the text is too large, network issues will crash the whole document.
                val chunkSize = 3000
                val chunks = mutableListOf<String>()
                var startIndex = 0

                while (startIndex < extractedText.length) {
                    val endIndex = minOf(startIndex + chunkSize, extractedText.length)
                    chunks.add(extractedText.substring(startIndex, endIndex))
                    startIndex = endIndex
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "Processing ${chunks.size} chunks..."
                }

                val pdfName = contentResolver.query(pdfUri!!, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "pdf_speech"

                val fileName = pdfName.replace(".pdf", "", ignoreCase = true) + ".mp3"

                val treeUri = outputFolderUri!!
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                // Create file in user's selected folder.
                val documentFile = DocumentsContract.createDocument(
                    contentResolver,
                    docUri,
                    "audio/mpeg",
                    fileName
                ) ?: throw Exception("Failed to create file in selected folder")

                // Turns out the Azure SDK requires a direct path instead of the Android file system one... (So stupid btw).
                val tempFile = File(cacheDir, "temp_conversion.mp3")
                if (tempFile.exists()) tempFile.delete()

                val speechConfig = SpeechConfig.fromSubscription(speechKey, serviceRegion)
                speechConfig.setSpeechSynthesisVoiceName("en-US-AndrewNeural")
                speechConfig.setSpeechSynthesisOutputFormat(
                    SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3
                )

                val audioConfig = AudioConfig.fromWavFileOutput(tempFile.absolutePath)
                val synthesizer = SpeechSynthesizer(speechConfig, audioConfig)

                chunks.forEachIndexed { index, chunk ->
                    withContext(Dispatchers.Main) {
                        statusText.text = "Processing chunk ${index + 1}/${chunks.size}...\n\nTip: Audio is being saved as each chunk completes!"
                    }

                    val result = synthesizer.SpeakText(chunk)

                    when (result.reason) {
                        ResultReason.SynthesizingAudioCompleted -> {
                            // Copying from the temp file to the real file.
                            contentResolver.openOutputStream(documentFile, "wa")?.use { outputStream ->
                                tempFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                statusText.text = "✓ Chunk ${index + 1}/${chunks.size} saved!\n\nFile: $fileName\n\nYou can listen to it now!"
                            }
                        }
                        ResultReason.Canceled -> {
                            val cancellation = SpeechSynthesisCancellationDetails.fromResult(result)
                            throw Exception("Failed at chunk ${index + 1}: ${cancellation.errorDetails}")
                        }
                        else -> {
                            throw Exception("Unexpected result at chunk ${index + 1}")
                        }
                    }
                }

                synthesizer.close()
                speechConfig.close()
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    convertButton.isEnabled = true
                    statusText.text = "Success! Conversion done!"
                    Toast.makeText(
                        this@MainActivity,
                        "MP3 created successfully!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    convertButton.isEnabled = true
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}