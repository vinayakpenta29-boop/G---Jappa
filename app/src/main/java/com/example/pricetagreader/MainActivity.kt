package com.example.pricetagreader

import android.net.Uri
import android.os.Bundle
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private var tempImageUri: Uri? = null
    private var serialIndex = 1
    private lateinit var tableLayout: TableLayout

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processImage(it) }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { processImage(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Using findViewById bypasses the ActivityMainBinding generation errors
        tableLayout = findViewById(R.id.tableLayout)
        val fabCamera = findViewById<FloatingActionButton>(R.id.fabCamera)

        fabCamera.setOnClickListener {
            showImageOptions()
        }
    }

    private fun showImageOptions() {
        val options = arrayOf("Gallery", "Open Camera")
        AlertDialog.Builder(this)
            .setTitle("Select Image Source")
            .setItems(options) { _, which ->
                if (which == 0) {
                    galleryLauncher.launch("image/*")
                } else {
                    val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
                    // Fixed: Using packageName instead of applicationId
                    tempImageUri = FileProvider.getUriForFile(this, "${packageName}.provider", tempFile)
                    cameraLauncher.launch(tempImageUri)
                }
            }
            .show()
    }

    private fun processImage(uri: Uri) {
        val image = InputImage.fromFilePath(this, uri)
        val barcodeScanner = BarcodeScanning.getClient()
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        var barcodeValue = "-"
        
        // 1. Scan for Barcode / QR Code
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    barcodeValue = barcodes[0].rawValue ?: "-"
                }
                
                // 2. Scan for Text
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        extractAndAddData(visionText.text, barcodeValue)
                    }
            }
    }

    private fun extractAndAddData(rawText: String, barcode: String) {
        // Regex patterns to find specific values from the text block
        val dateRegex = Regex("(\\d{2}/\\d{2}/\\d{4})")
        val billNoRegex = Regex("Bill No\\s*\\n*\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val vrpRegex = Regex("VRP Rate\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val jappaRegex = Regex("\\b(E\\d+)\\b", RegexOption.IGNORE_CASE)
        val salesmanRegex = Regex("\\b(\\d{3})\\b") // 3 digits

        val date = dateRegex.find(rawText)?.groupValues?.get(1) ?: "-"
        val billNo = billNoRegex.find(rawText)?.groupValues?.get(1) ?: "-"
        val millRate = vrpRegex.find(rawText)?.groupValues?.get(1) ?: "-"
        val jappa = jappaRegex.find(rawText)?.groupValues?.get(1) ?: "-"
        val salesmanNo = salesmanRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        addRowToTable(
            no = (serialIndex++).toString(),
            salesman = salesmanNo,
            barcode = barcode,
            millRate = millRate,
            billNo = billNo,
            date = date,
            jappa = jappa
        )
    }

    private fun addRowToTable(no: String, salesman: String, barcode: String, millRate: String, billNo: String, date: String, jappa: String) {
        val row = TableRow(this).apply {
            setPadding(0, 8, 0, 8)
        }

        val dataList = listOf(no, salesman, barcode, millRate, billNo, date, jappa)

        for (text in dataList) {
            val textView = TextView(this).apply {
                this.text = text
                setPadding(8, 8, 8, 8)
            }
            row.addView(textView)
        }

        tableLayout.addView(row)
    }
}
