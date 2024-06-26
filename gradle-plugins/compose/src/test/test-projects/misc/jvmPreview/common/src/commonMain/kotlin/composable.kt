/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

import androidx.compose.material.Text
import androidx.compose.material.Button
import androidx.compose.runtime.*

@Composable
fun ExampleComposable() {
    var text by remember { mutableStateOf("Hello, World!") }

    Button(onClick = {
        text = "Hello, $platformName!"
    }) {
        Text(text)
    }
}

val platformName: String = "Desktop"
