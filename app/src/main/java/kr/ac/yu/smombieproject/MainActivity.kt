package kr.ac.yu.smombieproject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.*
import kotlin.math.roundToInt

/**
 * [Software Tech: UI Layer]
 * Bridges the SmombieService with the user interface.
 */
class MainActivity : AppCompatActivity(), SmombieService.ServiceCallback {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvAreaData: android.widget.TextView
    private lateinit var tvBufferData: android.widget.TextView
    private lateinit var statusCard: com.google.android.material.card.MaterialCardView
    private lateinit var btnStopService: com.google.android.material.button.MaterialButton
    private lateinit var btnDebugView: com.google.android.material.button.MaterialButton
    private lateinit var ivDebugFrame: android.widget.ImageView
    private lateinit var spinnerTuningParam: Spinner
    private lateinit var etTuningValue: EditText
    private lateinit var btnApplyTuning: com.google.android.material.button.MaterialButton
    private lateinit var tvTuningFeedback: TextView

    private val tuningPrefs by lazy {
        getSharedPreferences(TuningParameters.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val debugModes = intArrayOf(
        SmombieService.DEBUG_VIEW_CAMERA,
        SmombieService.DEBUG_VIEW_CANNY,
        SmombieService.DEBUG_VIEW_ROI_EDGE,
        SmombieService.DEBUG_VIEW_MORPHOLOGY
    )
    private val debugLabels = arrayOf("CAMERA", "CANNY", "ROI EDGE", "MORPH")
    private var debugModeIndex = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startSmombieService()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        tvAreaData = findViewById(R.id.tvAreaData)
        tvBufferData = findViewById(R.id.tvBufferData)
        statusCard = findViewById(R.id.statusCard)
        btnStopService = findViewById(R.id.btnStopService)
        btnDebugView = findViewById(R.id.btnDebugView)
        ivDebugFrame = findViewById(R.id.ivDebugFrame)
        spinnerTuningParam = findViewById(R.id.spinnerTuningParam)
        etTuningValue = findViewById(R.id.etTuningValue)
        btnApplyTuning = findViewById(R.id.btnApplyTuning)
        tvTuningFeedback = findViewById(R.id.tvTuningFeedback)

        btnStopService.setOnClickListener {
            stopSmombieService()
        }
        btnDebugView.setOnClickListener {
            cycleDebugView()
        }
        applyDebugViewMode()
        setupTuningUi()

        if (!OpenCVLoader.initDebug()) Log.e("Smombie", "OpenCV init failed.")

        if (allPermissionsGranted()) {
            startSmombieService()
        } else {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startSmombieService() {
        val intent = android.content.Intent(this, SmombieService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Link UI components to the service
        SmombieService.setPreview(viewFinder)
        SmombieService.setListener(this)
        SmombieService.setDebugMode(debugModes[debugModeIndex])
        applySavedTuningValuesToRuntime()
    }

    private fun stopSmombieService() {
        val intent = android.content.Intent(this, SmombieService::class.java)
        stopService(intent)
        finish() // Exit app when stopping monitoring
    }

    override fun onStart() {
        super.onStart()
        SmombieService.setPreview(viewFinder)
        SmombieService.setListener(this)
        SmombieService.setDebugMode(debugModes[debugModeIndex])
        applySavedTuningValuesToRuntime()
    }

    override fun onStop() {
        super.onStop()
        // Stop UI updates but service keeps running camera analysis
        SmombieService.setPreview(null)
        SmombieService.setListener(null)
        ivDebugFrame.setImageDrawable(null)
    }

    override fun onDataUpdated(area: Double, isHazardous: Boolean, type: Int) {
        runOnUiThread { updateUI(area, isHazardous, type) }
    }

    override fun onDebugFrameUpdated(bitmap: Bitmap?) {
        runOnUiThread {
            if (debugModes[debugModeIndex] == SmombieService.DEBUG_VIEW_CAMERA || bitmap == null) {
                ivDebugFrame.setImageDrawable(null)
            } else {
                ivDebugFrame.setImageBitmap(bitmap)
            }
        }
    }

    override fun onBufferUpdated(bufferState: String) {
        runOnUiThread {
            tvBufferData.text = String.format(Locale.US, "Hazard Buffer: %s", bufferState)
        }
    }

    private fun setupTuningUi() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            TuningParameters.all.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTuningParam.adapter = adapter
        spinnerTuningParam.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTuningInput(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnApplyTuning.setOnClickListener {
            applySelectedTuningValue()
        }

        updateTuningInput(0)
    }

    private fun updateTuningInput(position: Int) {
        val parameter = TuningParameters.all.getOrNull(position) ?: return
        val value = getStoredTuningValue(parameter)
        val formattedValue = formatTuningValue(parameter, value)
        etTuningValue.setText(formattedValue)
        etTuningValue.setSelection(etTuningValue.text.length)
        tvTuningFeedback.text = String.format(Locale.US, "%s = %s", parameter.label, formattedValue)
    }

    private fun applySelectedTuningValue() {
        val position = spinnerTuningParam.selectedItemPosition
        val parameter = TuningParameters.all.getOrNull(position) ?: return
        val requestedValue = etTuningValue.text.toString().trim().toDoubleOrNull()

        if (requestedValue == null) {
            Toast.makeText(this, "Enter a number.", Toast.LENGTH_SHORT).show()
            return
        }

        val sanitizedValue = TuningParameters.sanitize(parameter, requestedValue)
        val runtimeValue = SmombieService.applyTuningValue(parameter.id, sanitizedValue) ?: sanitizedValue
        val formattedValue = formatTuningValue(parameter, runtimeValue)

        tuningPrefs.edit()
            .putString(TuningParameters.key(parameter), runtimeValue.toString())
            .apply()

        etTuningValue.setText(formattedValue)
        etTuningValue.setSelection(etTuningValue.text.length)
        tvTuningFeedback.text = String.format(Locale.US, "%s = %s", parameter.label, formattedValue)
        hideKeyboard()
    }

    private fun applySavedTuningValuesToRuntime() {
        for (parameter in TuningParameters.all) {
            SmombieService.applyTuningValue(parameter.id, getStoredTuningValue(parameter))
        }
    }

    private fun getStoredTuningValue(parameter: TuningParameter): Double {
        return tuningPrefs.getString(TuningParameters.key(parameter), null)
            ?.toDoubleOrNull()
            ?: parameter.defaultValue
    }

    private fun formatTuningValue(parameter: TuningParameter, value: Double): String {
        if (parameter.integerOnly) return value.roundToInt().toString()

        return String.format(Locale.US, "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(etTuningValue.windowToken, 0)
        etTuningValue.clearFocus()
    }

    private fun cycleDebugView() {
        debugModeIndex = (debugModeIndex + 1) % debugModes.size
        applyDebugViewMode()
        SmombieService.setDebugMode(debugModes[debugModeIndex])
    }

    private fun applyDebugViewMode() {
        btnDebugView.text = debugLabels[debugModeIndex]
        if (debugModes[debugModeIndex] == SmombieService.DEBUG_VIEW_CAMERA) {
            ivDebugFrame.visibility = View.GONE
            ivDebugFrame.setImageDrawable(null)
        } else {
            ivDebugFrame.visibility = View.VISIBLE
        }
    }

    private fun updateUI(area: Double, isHazardous: Boolean, type: Int) {
        val typeStr = when (type) {
            1 -> "BRAILLE"
            2 -> "CROSSWALK"
            3 -> "OBSTACLE"
            else -> "UNKNOWN"
        }
        tvAreaData.text = String.format(Locale.US, "Detected: %s (Area: %.1f)", typeStr, area)

        if (isHazardous) {
            tvStatus.text = String.format(Locale.US, "DANGER: %s", typeStr)
            statusCard.setCardBackgroundColor(android.graphics.Color.RED)
        } else if (area > 3000.0) {
            tvStatus.text = "CAUTION"
            statusCard.setCardBackgroundColor(android.graphics.Color.parseColor("#FFA500"))
        } else {
            tvStatus.text = "SAFE"
            statusCard.setCardBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return cameraGranted && notificationGranted
    }
}
