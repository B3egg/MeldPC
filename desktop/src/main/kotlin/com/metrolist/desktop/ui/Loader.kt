package com.metrolist.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Runs [block] whenever [key] changes (or on retry) and exposes the result as state. */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> Result<T>): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = block().fold({ Load.Ready(it) }, { Load.Failed(it.message ?: "Something went wrong") })
    }
    val retry: () -> Unit = { attempt++ }
    return state to retry
}

@Composable
fun <T> LoadContent(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> ErrorBox(load.message, retry)
        is Load.Ready -> content(load.value)
    }
}
