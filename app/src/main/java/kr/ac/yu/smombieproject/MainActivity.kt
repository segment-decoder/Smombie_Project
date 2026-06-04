package kr.ac.yu.smombieproject

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.*

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

        btnStopService.setOnClickListener {
            stopSmombieService()
        }

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
    }

    override fun onStop() {
        super.onStop()
        // Stop UI updates but service keeps running camera analysis
        SmombieService.setPreview(null)
        SmombieService.setListener(null)
    }

    override fun onDataUpdated(area: Double, isHazardous: Boolean, type: Int) {
        runOnUiThread { updateUI(area, isHazardous, type) }
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
