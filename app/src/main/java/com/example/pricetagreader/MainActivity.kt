package com.example.pricetagreader

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tempImageUri: Uri? = null
    private var serialIndex = 1
    
    private var displayedRowIndex = 0 
    
    private lateinit var tableLayout: TableLayout
    
    private var totalJappaAmount = 0
    private var cutoffPercentage = 0.0
    private lateinit var switchCutOff: SwitchCompat
    private var totalTableRow: TableRow? = null

    private var activeFilters = mutableMapOf<String, String>()
    private lateinit var database: AppDatabase

    private val PREFS_NAME = "PriceTagSettings"
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
        supportActionBar?.hide() 
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)
        
        tableLayout = findViewById(R.id.tableLayout)
        switchCutOff = findViewById(R.id.switchCutOff)
        val fabCamera = findViewById<ExtendedFloatingActionButton>(R.id.fabCamera)

        fabCamera.setOnClickListener {
            showImageOptions()
        }

        switchCutOff.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(CUTOFF_SWITCH_KEY, isChecked).apply()
            updateTableTotalRow()
        }

        loadSavedData()
    }

    override fun onStart() {
        super.onStart()
        findViewById<TextView>(R.id.tvAppTitle).setOnClickListener {
            val menuView = layoutInflater.inflate(R.layout.dialog_menu, null)
            
            val dialog = MaterialAlertDialogBuilder(this, R.style.RoundedDialogTheme)
                .setTitle("Menu Options")
                .setView(menuView)
                .show()

            menuView.findViewById<TextView>(R.id.menuFilter).setOnClickListener {
                dialog.dismiss()
                showFilterDialog()
            }
            menuView.findViewById<TextView>(R.id.menuPercentage).setOnClickListener {
                dialog.dismiss()
                showPercentageDialog()
            }
            menuView.findViewById<TextView>(R.id.menuClear).setOnClickListener {
                dialog.dismiss()
                clearAllData()
            }
        }
    }

    private fun showFilterDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allTags = database.priceTagDao().getAllTags()
            
            val availableMonths = allTags.mapNotNull { tag ->
                val parts = tag.date.split("/")
                if (parts.size == 3) "${parts[1]}/${parts[2]}" else null
            }.distinct().sorted()

            val spinnerOptions = mutableListOf("All Months")
            spinnerOptions.addAll(availableMonths)

            withContext(Dispatchers.Main) {
                val view = layoutInflater.inflate(R.layout.dialog_filter, null)
                
                val spinnerMonth = view.findViewById<AutoCompleteTextView>(R.id.spinnerFilterMonth)
                val etDate = view.findViewById<EditText>(R.id.etFilterDate)
                val etBarcode = view.findViewById<EditText>(R.id.etFilterBarcode)
                val etBillNo = view.findViewById<EditText>(R.id.etFilterBillNo)
                val etJappa = view.findViewById<EditText>(R.id.etFilterJappa)
                val etMillRate = view.findViewById<EditText>(R.id.etFilterMillRate)
                val etSalesman = view.findViewById<EditText>(R.id.etFilterSalesman)

                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, spinnerOptions)
                spinnerMonth.setAdapter(adapter)

                val activeMonth = activeFilters["month"]
                if (!activeMonth.isNullOrEmpty()) {
                    spinnerMonth.setText(activeMonth, false) 
                } else {
                    spinnerMonth.setText("All Months", false)
                }

                etDate.setText(activeFilters["date"] ?: "")
                etBarcode.setText(activeFilters["barcode"] ?: "")
                etBillNo.setText(activeFilters["billNo"] ?: "")
                etJappa.setText(activeFilters["jappa"] ?: "")
                etMillRate.setText(activeFilters["millRate"] ?: "")
                etSalesman.setText(activeFilters["salesman"] ?: "")

                MaterialAlertDialogBuilder(this@MainActivity, R.style.RoundedDialogTheme)
                    .setTitle("Filter Data")
                    .setView(view)
                    .setPositiveButton("Apply") { _, _ ->
                        val selectedMonth = spinnerMonth.text.toString()
                        if (selectedMonth == "All Months" || selectedMonth.isEmpty()) {
                            activeFilters.remove("month")
                        } else {
                            activeFilters["month"] = selectedMonth
                        }

                        activeFilters["date"] = etDate.text.toString().trim()
                        activeFilters["barcode"] = etBarcode.text.toString().trim()
                        activeFilters["billNo"] = etBillNo.text.toString().trim()
                        activeFilters["jappa"] = etJappa.text.toString().trim()
                        activeFilters["millRate"] = etMillRate.text.toString().trim()
                        activeFilters["salesman"] = etSalesman.text.toString().trim()

                        refreshTable()
                    }
                    .setNeutralButton("Clear") { _, _ ->
                        activeFilters.clear()
                        refreshTable()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showPercentageDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_percentage, null)
        val etPercentage = view.findViewById<EditText>(R.id.etPercentage)

        MaterialAlertDialogBuilder(this, R.style.RoundedDialogTheme)
            .setTitle("Set Cut off Percentage")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val value = etPercentage.text.toString().toDoubleOrNull() ?: 0.0
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
        val view = layoutInflater.inflate(R.layout.dialog_password, null)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)

        MaterialAlertDialogBuilder(this, R.style.RoundedDialogTheme)
            .setTitle("Authentication Required")
            .setMessage("Enter password to clear all data.") // Clues removed completely
            .setView(view)
            .setPositiveButton("Clear Data") { _, _ ->
                val enteredPassword = etPassword.text.toString().trim()
                
                val currentTime4Digit = SimpleDateFormat("hhmm", Locale.getDefault()).format(Date())
                val currentTime3Digit = SimpleDateFormat("hmm", Locale.getDefault()).format(Date())

                if (enteredPassword == currentTime4Digit || enteredPassword == currentTime3Digit) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        database.priceTagDao().deleteAllTags()
                        activeFilters.clear()
                        
                        withContext(Dispatchers.Main) {
                            serialIndex = 1
                            refreshTable()
                            Toast.makeText(this@MainActivity, "All Data Cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Incorrect Password!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cutoffPercentage = prefs.getFloat(CUTOFF_PERCENT_KEY, 0f).toDouble()
        val isCutoffEnabled = prefs.getBoolean(CUTOFF_SWITCH_KEY, false)

        switchCutOff.text = "Apply Cut Off ($cutoffPercentage%)"
        switchCutOff.isChecked = isCutoffEnabled

        lifecycleScope.launch(Dispatchers.IO) {
            val allTags = database.priceTagDao().getAllTags()
            serialIndex = allTags.size + 1
            
            withContext(Dispatchers.Main) {
                refreshTable()
            }
        }
    }

    private fun refreshTable() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allTags = database.priceTagDao().getAllTags()
            
            val filteredTags = allTags.filter { tag ->
                var matches = true
                if (activeFilters["month"]?.isNotEmpty() == true) matches = matches && tag.date.contains(activeFilters["month"]!!)
                if (activeFilters["date"]?.isNotEmpty() == true) matches = matches && tag.date == activeFilters["date"]
                if (activeFilters["barcode"]?.isNotEmpty() == true) matches = matches && tag.barcode.contains(activeFilters["barcode"]!!, true)
                if (activeFilters["billNo"]?.isNotEmpty() == true) matches = matches && tag.billNo.contains(activeFilters["billNo"]!!, true)
                if (activeFilters["jappa"]?.isNotEmpty() == true) matches = matches && tag.jappa.contains(activeFilters["jappa"]!!, true)
                if (activeFilters["millRate"]?.isNotEmpty() == true) matches = matches && tag.millRate.contains(activeFilters["millRate"]!!, true)
                if (activeFilters["salesman"]?.isNotEmpty() == true) matches = matches && tag.salesman.contains(activeFilters["salesman"]!!, true)
                matches
            }

            withContext(Dispatchers.Main) {
                val childCount = tableLayout.childCount
                if (childCount > 1) {
                    tableLayout.removeViews(1, childCount - 1)
                }

                totalJappaAmount = 0
                displayedRowIndex = 0
                totalTableRow = null

                for (tag in filteredTags) {
                    if (tag.jappa != "-") {
                        val amountString = tag.jappa.replace(Regex("[^0-9]"), "")
                        totalJappaAmount += (amountString.toIntOrNull() ?: 0)
                    }
                    addRowToTable(tag.no, tag.salesman, tag.barcode, tag.millRate, tag.billNo, tag.date, tag.jappa)
                }
                
                updateTableTotalRow()
            }
        }
    }

    private fun showImageOptions() {
        val options = arrayOf("Gallery", "Open Camera")
        
        MaterialAlertDialogBuilder(this, R.style.RoundedDialogTheme)
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
        
        lifecycleScope.launch(Dispatchers.IO) {
            val newTag = PriceTag(
                no = currentIndex,
                salesman = salesmanNo,
                barcode = barcode,
                millRate = millRate,
                billNo = billNo,
                date = date,
                jappa = jappa
            )
            database.priceTagDao().insertTag(newTag)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Tag Saved!", Toast.LENGTH_SHORT).show()
                refreshTable() 
            }
        }
    }

    private fun addRowToTable(no: String, salesman: String, barcode: String, millRate: String, billNo: String, date: String, jappa: String) {
        val row = TableRow(this).apply {
            val bgColor = if (displayedRowIndex % 2 == 0) R.color.tableRowBg1 else R.color.tableRowBg2
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, bgColor))
        }

        val dataList = listOf(no, salesman, barcode, millRate, billNo, date, jappa)

        for (text in dataList) {
            val textView = TextView(this).apply {
                this.text = text
                setPadding(16, 24, 16, 24)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER 
            }
            row.addView(textView)
        }

        tableLayout.addView(row)
        displayedRowIndex++
    }

    private fun updateTableTotalRow() {
        totalTableRow?.let { tableLayout.removeView(it) }

        totalTableRow = TableRow(this).apply { 
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.tableTotalBg))
        }

        for (i in 0..4) {
            val emptyView = TextView(this).apply {
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
            }
            totalTableRow?.addView(emptyView)
        }

        val labelView = TextView(this).apply {
            text = "Total:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(16, 24, 16, 24)
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSuccess))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(16, 24, 16, 24)
            layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER 
        }
        totalTableRow?.addView(totalView)

        tableLayout.addView(totalTableRow)
    }
}
