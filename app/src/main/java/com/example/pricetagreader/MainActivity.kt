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
                        // Pass the entire visionText object so we can check physical placement
                        extractAndAddData(visionText, barcodeValue)
                    }
            }
    }

    private fun extractAndAddData(visionText: Text, barcode: String) {
        val rawText = visionText.text

        // 1. Date: Matches standard DD/MM/YYYY format
        val dateRegex = Regex("(\\d{2}/\\d{2}/\\d{4})")
        val date = dateRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 2. Jappa ("E" value): Matches E followed by numbers (e.g., E200, E500)
        val jappaRegex = Regex("\\b(E\\d+)\\b", RegexOption.IGNORE_CASE)
        val jappa = jappaRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 3. Mill Rate (VRP Rate): Now safely ignores currency symbols (₹) and spaces between the label and number
        val vrpRegex = Regex("VRP\\s*Rate.*?(\\d{3,5})", RegexOption.IGNORE_CASE)
        val millRate = vrpRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 4. Bill No: Targets the distinct 5 or 6 digit number (e.g., 49147 or 203849), ignoring 3-digit Tokens
        val billNoRegex = Regex("\\b(\\d{5,6})\\b")
        val billNo = billNoRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // 5. Salesman No (Spatial Extraction):
        var salesmanNo = "-"
        val threeDigitBlocks = mutableListOf<Pair<String, Rect>>()

        // Scan through all text blocks visually mapped on the page
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text
                    // Find ALL 3-digit numbers on the page (This will find both Salesman No and Token No)
                    if (text.matches(Regex("\\b\\d{3}\\b"))) {
                        element.boundingBox?.let { rect ->
                            threeDigitBlocks.add(Pair(text, rect))
                        }
                    }
                }
            }
        }

        if (threeDigitBlocks.isNotEmpty()) {
            // Because the Salesman No is always at the top right of the page, 
            // we filter the 3-digit numbers by their Y-axis coordinate (top).
            // This guarantees we pick the number physically highest on the page, avoiding the Token number below it.
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
