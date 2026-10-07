package com.example.pricetagreader

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
    
    // Variables for the new features
    private var totalJappaAmount = 0
    private var cutoffPercentage = 0.0
    private lateinit var switchCutOff: SwitchCompat
    private var totalTableRow: TableRow? = null

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
        switchCutOff = findViewById(R.id.switchCutOff)
        val fabCamera = findViewById<FloatingActionButton>(R.id.fabCamera)

        fabCamera.setOnClickListener {
            showImageOptions()
        }

        // Listens to the Cut Off Switch
        switchCutOff.setOnCheckedChangeListener { _, _ ->
            updateTableTotalRow()
        }
    }

    // Creates the three-dots menu
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // Handles the menu click
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_cutoff_percentage) {
            showPercentageDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Opens a popup to ask for the Percentage
    private fun showPercentageDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Enter percentage (e.g., 10)"

        AlertDialog.Builder(this)
            .setTitle("Set Cut off Percentage")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: 0.0
                cutoffPercentage = value
                switchCutOff.text = "Apply Cut Off ($cutoffPercentage%)"
                
                // Automatically turn on the switch when they set a percentage
                if (!switchCutOff.isChecked && cutoffPercentage > 0) {
                    switchCutOff.isChecked = true
                }
                updateTableTotalRow()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    barcodeValue = barcodes[0].rawValue ?: "-"
                }
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        extractAndAddData(visionText, barcodeValue)
                    }
            }
    }

    private fun extractAndAddData(visionText: Text, barcodeScanResult: String) {
        val rawText = visionText.text
        val flatText = rawText.replace("\n", " ").replace(Regex("\\s+"), " ")
        val noSpaceText = rawText.replace(Regex("\\s+"), "") 

        val dateRegex = Regex("(\\d{2}/\\d{2}/\\d{4})")
        val date = dateRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        val jappaRegex = Regex("\\b(E\\d+)\\b", RegexOption.IGNORE_CASE)
        val jappa = jappaRegex.find(rawText)?.groupValues?.get(1) ?: "-"

        // Parse Jappa string to Integer (Extracts '500' from 'E500')
        if (jappa != "-") {
            val amountString = jappa.replace(Regex("[^0-9]"), "")
            val amount = amountString.toIntOrNull() ?: 0
            totalJappaAmount += amount
        }

        var barcode = barcodeScanResult
        if (barcode == "-" || barcode.isEmpty()) {
            val textBarcodeRegex = Regex("(\\d-\\d{2}-\\d{4})")
            barcode = textBarcodeRegex.find(noSpaceText)?.groupValues?.get(1) ?: "-"
        }

        var millRate = "-"
        val vrpRegex = Regex("VRP.*?(\\d+)\\s*/\\s*-", RegexOption.IGNORE_CASE)
        val vrpMatch = vrpRegex.find(flatText)
        
        if (vrpMatch != null) {
            millRate = vrpMatch.groupValues[1]
        } else {
            val generalRateRegex = Regex("(\\d+)\\s*/\\s*-")
            val matches = generalRateRegex.findAll(flatText).toList()
            if (matches.isNotEmpty()) {
                millRate = matches.last().groupValues[1]
            }
        }

        var billNo = "-"
        val billRegex = Regex("Bill.*?(\\d{5,6})", RegexOption.IGNORE_CASE)
        val billMatch = billRegex.find(flatText)
        
        if (billMatch != null) {
            billNo = billMatch.groupValues[1]
        } else {
            val fallbackMatches = Regex("\\b(\\d{5,6})\\b").findAll(flatText).toList()
            if (fallbackMatches.isNotEmpty()) {
                billNo = fallbackMatches.first().groupValues[1]
            }
        }

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

        // Update the Total Row at the bottom of the table after a new item is added
        updateTableTotalRow()
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

    // Creates, Calculates, and Updates the Total Row at the end of the Table
    private fun updateTableTotalRow() {
        // Remove the old total row if it exists so we can place the new one at the very bottom
        totalTableRow?.let { tableLayout.removeView(it) }

        totalTableRow = TableRow(this).apply { 
            setPadding(0, 16, 0, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#E8E8E8")) // Light grey background
        }

        // Add 5 empty cells to push the totals under Date and Jappa columns
        for (i in 0..4) {
            totalTableRow?.addView(TextView(this))
        }

        // 6th Column (Under Date)
        val labelView = TextView(this).apply {
            text = "Total:"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 8)
        }
        totalTableRow?.addView(labelView)

        // 7th Column (Under Jappa) - Calculates Cut Off
        val totalView = TextView(this).apply {
            var displayText = "$totalJappaAmount"
            
            if (switchCutOff.isChecked && cutoffPercentage > 0) {
                val discount = totalJappaAmount * (cutoffPercentage / 100.0)
                val finalAmt = totalJappaAmount - discount
                // Displays the math clearly (e.g., "1000 - 10% = 900")
                displayText = "$totalJappaAmount - $cutoffPercentage% = ${String.format("%.1f", finalAmt)}"
            }
            
            text = displayText
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#006400")) // Dark Green Text
            setPadding(8, 8, 8, 8)
        }
        totalTableRow?.addView(totalView)

        // Add the updated total row to the end of the table
        tableLayout.addView(totalTableRow)
    }
}
