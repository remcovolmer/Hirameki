// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ichi2.anki.R
import com.ichi2.anki.ui.compose.navigation.AppNavigationItem

/*
 * The bottom navigation bar of the fork on phones: Decks and Discover. Tablets keep the upstream
 * navigation rail. Kept out of the deck picker files so upstream changes there stay easy to merge.
 */

enum class BrainiacTab { Decks, Discover }

/** Discover lives in the bottom bar (phones) and the rail (tablets), not in the drawer. */
val AppNavigationItem.showInDrawer: Boolean get() = this != AppNavigationItem.Discover

@Composable
fun BrainiacNavigationBar(
    selected: BrainiacTab,
    onSelect: (BrainiacTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier) {
        NavigationBarItem(
            selected = selected == BrainiacTab.Decks,
            onClick = { if (selected != BrainiacTab.Decks) onSelect(BrainiacTab.Decks) },
            icon = { Icon(painterResource(R.drawable.list_24px), contentDescription = null) },
            label = { Text(stringResource(R.string.decks)) },
        )
        NavigationBarItem(
            selected = selected == BrainiacTab.Discover,
            onClick = { if (selected != BrainiacTab.Discover) onSelect(BrainiacTab.Discover) },
            icon = { Icon(painterResource(R.drawable.explore_24px), contentDescription = null) },
            label = { Text(stringResource(R.string.discover_title)) },
        )
    }
}

/**
 * Shows [content] above the bottom bar. The bar draws behind the system navigation itself, so the
 * content gives up that inset: its FAB, snackbars and list end above the bar, not under it.
 */
@Composable
fun WithBrainiacNavigationBar(
    selected: BrainiacTab,
    onSelect: (BrainiacTab) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
        BrainiacNavigationBar(selected, onSelect)
    }
}
