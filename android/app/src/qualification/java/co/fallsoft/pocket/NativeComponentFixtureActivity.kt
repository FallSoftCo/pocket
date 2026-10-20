package co.fallsoft.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** Component-only native journeys. Never packaged in shipped debug/release APKs. */
class NativeComponentFixtureActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
 }
}
