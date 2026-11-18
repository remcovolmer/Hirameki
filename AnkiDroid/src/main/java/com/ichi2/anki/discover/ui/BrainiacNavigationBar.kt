// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.navigation3.runtime.NavKey
import com.ichi2.anki.R
import com.ichi2.anki.discover.DiscoverTab
import com.ichi2.anki.navigation.Navigator
import com.ichi2.anki.ui.compose.navigation.AppNavigationItem
import kotlinx.serialization.Serializable

/*
 * The bottom navigation bar of the fork on phones: Decks, Lessons and Books. Tablets keep the
 * upstream navigation rail, which lists Lessons and Books too. Kept out of the deck picker files
 * so upstream changes there stay easy to merge.
 */

enum class BrainiacTab(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    Decks(R.drawable.list_24px, R.string.decks),
    Lessons(R.drawable.ic_lightbulb, R.string.discover_tab_lessons),
    Books(R.drawable.book_ribbon_24px, R.string.discover_tab_books),
}

/** Navigation key of the Lessons tab in the deck picker's Nav3 graph. */
@Serializable
object LessonsDestination : NavKey

/** Navigation key of the Books tab in the deck picker's Nav3 graph. */
@Serializable
object BooksDestination : NavKey

/** Lessons and Books live in the bottom bar (phones) and the rail (tablets), not in the drawer. */
val AppNavigationItem.showInDrawer: Boolean get() = this != AppNavigationItem.Lessons && this != AppNavigationItem.Books

val DiscoverTab.brainiacTab: BrainiacTab get() = if (this == DiscoverTab.Lessons) BrainiacTab.Lessons else BrainiacTab.Books

/**
 * Shows [tab]. The tabs replace each other on top of the deck list instead of piling up, so back
 * from either tab leads to the decks.
 */
fun Navigator.showBrainiacTab(tab: BrainiacTab) {
    val stack = state.backStacks[state.topLevelRoute] ?: return
    while (stack.lastOrNull() is LessonsDestination || stack.lastOrNull() is BooksDestination) goBack()
    when (tab) {
        BrainiacTab.Decks -> {}
        BrainiacTab.Lessons -> navigate(LessonsDestination)
        BrainiacTab.Books -> navigate(BooksDestination)
    }
}

@Composable
fun BrainiacNavigationBar(
    selected: BrainiacTab,
    onSelect: (BrainiacTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier) {
        BrainiacTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { if (selected != tab) onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
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
