package com.nexti.minisoporte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nexti.minisoporte.ui.navigation.AppNavHost
import com.nexti.minisoporte.ui.theme.MiniSoporteTheme

/**
 * Single-Activity Compose host (CA-04, design.md Decision 3): ONE Activity, NO
 * Fragments. `onCreate` mounts the single Compose tree — the themed
 * [AppNavHost] graph for S-01..S-04. All navigation happens inside that tree;
 * no other Activity is ever launched.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiniSoporteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
