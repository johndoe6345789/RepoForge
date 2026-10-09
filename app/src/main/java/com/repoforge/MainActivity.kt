package com.repoforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.repoforge.ui.AppViewModel
import com.repoforge.ui.RepoForgeRoot
import com.repoforge.ui.theme.RepoForgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RepoForgeTheme {
                RepoForgeRoot(viewModel)
            }
        }
    }
}
