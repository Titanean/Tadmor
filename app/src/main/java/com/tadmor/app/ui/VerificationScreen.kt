package com.tadmor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.domain.model.Planet
import com.tadmor.domain.repository.PlanetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Temporary verification screen for Phase 1.
 * Triggers sync, shows planet count, logs sample data to Logcat.
 */
@Composable
fun VerificationScreen(
    viewModel: VerificationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            BasicText(
                text = "Tadmor",
                style = TextStyle(
                    color = Color(0xFFC8D0DC),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.W200,
                ),
            )

            Spacer(Modifier.height(16.dp))

            BasicText(
                text = state.statusText,
                style = TextStyle(
                    color = Color(0xFF8B95A8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                ),
            )

            if (state.planetCount > 0) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "${state.planetCount} planets cached",
                    style = TextStyle(
                        color = Color(0xFFB89660),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.W200,
                    ),
                )
            }
        }
    }
}

data class VerificationState(
    val statusText: String = "Initializing...",
    val planetCount: Int = 0,
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val repository: PlanetRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VerificationState())
    val state: StateFlow<VerificationState> = _state

    init {
        viewModelScope.launch {
            // Check if we already have cached data
            val cached = repository.observeAllPlanets().first()
            if (cached.isNotEmpty()) {
                _state.value = VerificationState(
                    statusText = "Loaded from cache",
                    planetCount = cached.size,
                )
                logSample(cached)
            }

            // Trigger sync
            _state.value = _state.value.copy(statusText = "Syncing from TAP API...")
            try {
                repository.sync()
                val planets = repository.observeAllPlanets().first()
                _state.value = VerificationState(
                    statusText = "Sync complete",
                    planetCount = planets.size,
                )
                logSample(planets)
            } catch (e: Exception) {
                Timber.e(e, "Sync failed")
                _state.value = _state.value.copy(
                    statusText = "Sync failed: ${e.message}",
                )
            }
        }
    }

    private fun logSample(planets: List<Planet>) {
        Timber.d("=== Planet Data Sample (first 20) ===")
        planets.take(20).forEach { p ->
            Timber.d(
                "%-30s | host=%-20s | M♃=%-8s | R⊕=%-8s | T=%-6s | d=%-8s",
                p.name,
                p.hostname,
                p.massJupiter?.let { "%.3f".format(it) } ?: "—",
                p.radiusEarth?.let { "%.2f".format(it) } ?: "—",
                p.eqTempK?.let { "%.0f K".format(it) } ?: "—",
                p.densityGCm3?.let { "%.2f".format(it) } ?: "—",
            )
        }
        Timber.d("=== End Sample ===")
    }
}
