package edu.wisc.maplens

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder entry point. Area C replaces this with the real UI (Compose or Views). */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                setPadding(48, 48, 48, 48)
            },
        )
    }
}
