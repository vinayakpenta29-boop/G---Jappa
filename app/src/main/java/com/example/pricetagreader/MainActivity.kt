package com.example.pricetagreader

import android.graphics.Rect
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
import com.google.mlkit.vision.text.Text
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
                        extractAndAddData(visionText, barcodeValue)
                    }
            }
    }

    private fun extractAndAddData(visionText: Text, barcodeScanResult: String) {
        val rawText = visionText.text
        
        // "flatText" puts everything on one line to prevent regex breaking on new lines
        val flatText = rawText.replace("\n", " ").replace(Regex("\\s+"), " ")
        // "noSpaceText" removes all spaces. Perfect for finding the Barcode even if the image is sideways.
        val noSpaceText = rawText.replace(Regex("\\s+"), "") 

        // 1. Date
        val dateRegex = Regex("(\\d{2}/\\d{2}/\\d{4})")
        val date = dateRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 2. Jappa ("E" value)
        val jappaRegex = Regex("\\b(E\\d+)\\b", RegexOption.IGNORE_CASE)
        val jappa = jappaRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 3. Barcode (FIXED for sideways/rotated images)
        var barcode = barcodeScanResult
        if (barcode == "-" || barcode.isEmpty()) {
            // Because rotated images scramble text with spaces, we search the 'noSpaceText' string
            val textBarcodeRegex = Regex("(\\d-\\d{2}-\\d{4})")
            barcode = textBarcodeRegex.find(noSpaceText)?.groupValues?.get(1) ?: "-"
        }

        // 4. Mill Rate / VRP (FIXED to differentiate from MRP)
        var millRate = "-"
        val vrpRegex = Regex("VRP.*?(\\d+)\\s*/\\s*-", RegexOption.IGNORE_CASE)
        val vrpMatch = vrpRegex.find(flatText)
        
        if (vrpMatch != null) {
            // If it finds VRP explicitly, grab the number attached to it
            millRate = vrpMatch.groupValues[1]
        } else {
            // Fallback: If VRP isn't explicitly readable, find any number ending in "/-"
            val generalRateRegex = Regex("(\\d+)\\s*/\\s*-")
            val matches = generalRateRegex.findAll(flatText).toList()
            if (matches.isNotEmpty()) {
                // If multiple prices exist (like MRP and actual price), grab the last one
                millRate = matches.last().groupValues[1]
            }
        }

        // 5. Bill No (FIXED by anchoring to the word "Bill" and grabbing the first 5/6 digit number)
        var billNo = "-"
        val billRegex = Regex("Bill.*?(\\d{5,6})", RegexOption.IGNORE_CASE)
        val billMatch = billRegex.find(flatText)
        
        if (billMatch != null) {
            billNo = billMatch.groupValues[1]
        } else {
            // Fallback: If the word "Bill" isn't read, grab the first 5 or 6 digit number found anywhere
            val fallbackMatches = Regex("\\b(\\d{5,6})\\b").findAll(flatText).toList()
            if (fallbackMatches.isNotEmpty()) {
                billNo = fallbackMatches.first().groupValues[1]
            }
        }

        // 6. Salesman No (Spatial Extraction - Works Perfectly)
        var salesmanNo = "-"
        val threeDigitBlocks = mutableListOf<Pair<String, Rect>>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text
                    if (text.matches(Regex("\\b\\d{3}\\b"))) {
                        element.boundingBox?.let { rect ->
                            threeDigitBlocks.add(Pair(text, rect))
                        }
                    }
                }
            }
        }

        if (threeDigitBlocks.isNotEmpty()) {
            val topMostBlock = threeDigitBlocks.minByOrNull { it.second.top }
            salesmanNo = topMostBlock?.first ?: "-"
        }

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
