package net.alummahnow.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TvApp() }
    }
}

/** The TV app's root screen (the store screenshots render this too). The TV UI itself lands with feature-tv. */
@Composable
fun TvApp() {
    Text(stringResource(R.string.app_name))
}
