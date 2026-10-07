package com.example.pricetagreader

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private var tempImageUri: Uri? = null
    private var serialIndex = 1
    private lateinit var tableLayout: TableLayout
    
    private var totalJappaAmount = 0
    private var cutoffPercentage = 0.0
    private lateinit var switchCutOff: SwitchCompat
    private var totalTableRow: TableRow? = null
    private var savedDataArray = JSONArray()

    private val PREFS_NAME = "PriceTagPrefs"
    private val DATA_KEY = "TableData"
    private val CUTOFF_PERCENT_KEY = "CutoffPercent"
    private val CUTOFF_SWITCH_KEY = "CutoffSwitch"

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

        switchCutOff.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(CUTOFF_SWITCH_KEY, isChecked).apply()
            updateTableTotalRow()
        }

        loadSavedData()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_cutoff_percentage -> {
                showPercentageDialog()
                true
            }
            R.id.action_clear_data -> {
                clearAllData()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

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
                
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(CUTOFF_PERCENT_KEY, value.toFloat()).apply()
                
                switchCutOff.text = "Apply Cut Off ($cutoffPercentage%)"
                if (!switchCutOff.isChecked && cutoffPercentage > 0) {
                    switchCutOff.isChecked = true
                }
                updateTableTotalRow()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllData() {
        AlertDialog.Builder(this)
            .setTitle("Clear Data")
            .setMessage("Are you sure you want to clear all scanned tags? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(DATA_KEY).apply()
                savedDataArray = JSONArray()
                
                val childCount = tableLayout.childCount
                if (childCount > 1) {
                    tableLayout.removeViews(1, childCount - 1)
                }
                
                totalJappaAmount = 0
                serialIndex = 1
                totalTableRow = null
                updateTableTotalRow()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(DATA_KEY, "[]")
        savedDataArray = JSONArray(jsonString)

        cutoffPercentage = prefs.getFloat(CUTOFF_PERCENT_KEY, 0f).toDouble()
        val isCutoffEnabled = prefs.getBoolean(CUTOFF_SWITCH_KEY, false)

        switchCutOff.text = "Apply Cut Off ($cutoffPercentage%)"
        switchCutOff.isChecked = isCutoffEnabled

        for (i in 0 until savedDataArray.length()) {
            val obj = savedDataArray.getJSONObject(i)
            val no = obj.optString("no", "")
            val salesman = obj.optString("salesman", "")
            val barcode = obj.optString("barcode", "")
            val millRate = obj.optString("millRate", "")
            val billNo = obj.optString("billNo", "")
            val date = obj.optString("date", "")
            val jappa = obj.optString("jappa", "")

            if (jappa != "-") {
                val amountString = jappa.replace(Regex("[^0-9]"), "")
                val amount = amountString.toIntOrNull() ?: 0
                totalJappaAmount += amount
            }
            
            serialIndex++
            addRowToTable(no, salesman, barcode, millRate, billNo, date, jappa)
        }
        updateTableTotalRow()
    }

    private fun saveNewRow(no: String, salesman: String, barcode: String, millRate: String, billNo: String, date: String, jappa: String) {
        val obj = JSONObject()
        obj.put("no", no)
        obj.put("salesman", salesman)
        obj.put("barcode", barcode)
        obj.put("millRate", millRate)
        obj.put("billNo", billNo)
        obj.put("date", date)
        obj.put("jappa", jappa)
        
        savedDataArray.put(obj)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(DATA_KEY, savedDataArray.toString()).apply()
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

        val currentIndex = (serialIndex++).toString()
        
        saveNewRow(currentIndex, salesmanNo, barcode, millRate, billNo, date, jappa)
        addRowToTable(currentIndex, salesmanNo, barcode, millRate, billNo, date, jappa)
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
                
                // Forces the text to center exactly by matching the column width
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER 
            }
            row.addView(textView)
        }

        tableLayout.addView(row)
    }

    private fun updateTableTotalRow() {
        totalTableRow?.let { tableLayout.removeView(it) }

        totalTableRow = TableRow(this).apply { 
            setPadding(0, 16, 0, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#E8E8E8"))
        }

        for (i in 0..4) {
            val emptyView = TextView(this).apply {
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
            }
            totalTableRow?.addView(emptyView)
        }

        val labelView = TextView(this).apply {
            text = "Total:"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 8)
            
            // Forces label to center
            layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER 
        }
        totalTableRow?.addView(labelView)

        val totalView = TextView(this).apply {
            var displayText = "$totalJappaAmount"
            
            if (switchCutOff.isChecked && cutoffPercentage > 0) {
                val discount = totalJappaAmount * (cutoffPercentage / 100.0)
                val finalAmt = totalJappaAmount - discount
                displayText = "$totalJappaAmount - $cutoffPercentage% = ${String.format("%.1f", finalAmt)}"
            }
            
            text = displayText
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#006400"))
            setPadding(8, 8, 8, 8)
            
            // Forces total amount to center
            layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER 
        }
        totalTableRow?.addView(totalView)

        tableLayout.addView(totalTableRow)
    }
}
