package co.fallsoft.pocket

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** The release runner verifies the process, start ticks and serial before passing this name. */
internal fun requireOwnedNativeTarget(device: UiDevice) {
 val expected=InstrumentationRegistry.getArguments().getString("qaAvdName") ?: "nextcomp_env_qa"
 check(expected.matches(Regex("(?:nextcomp_[a-zA-Z0-9_]+|harness_native_room)")))
 check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()==expected) {
  "Native qualification requires the explicitly owned emulator"
 }
}
