package dev.screencopy.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Debug-only, non-exported fixture. The user must approve Android's real permission dialogs. */
class ProjectionConsentTestActivity : Activity() {
    val completed = CountDownLatch(1)
    @Volatile var consent: Intent? = null
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = if (intent.getBooleanExtra("networkOnly", false))
                "Projection network diagnostic\nApprove Android's sharing dialog.\nNo screen or audio data will be captured."
            else "MediaProjection capture test\nApprove Android's permission dialogs to test this screen only.\nNo capture is saved."
            textSize = 24f; setPadding(32, 64, 32, 32)
            setBackgroundColor(android.graphics.Color.rgb(24, 80, 160))
            setTextColor(android.graphics.Color.WHITE)
        })
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra("requestAudio", true) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            else requestProjection()
        }
    }
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        startActivityForResult(intent, 2)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
            else completed.countDown()
        }
    }
    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            if (resultCode == RESULT_OK) consent = data
            completed.countDown()
        }
    }
}
