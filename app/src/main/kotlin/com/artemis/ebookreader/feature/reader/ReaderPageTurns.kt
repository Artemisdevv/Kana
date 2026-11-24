package com.artemis.ebookreader.feature.reader

import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
internal class ReaderPageTurns(navigator: OverflowableNavigator) : InputListener {
    private val taps = DirectionalNavigationAdapter(navigator, animatedTransition = false)
    private val keys = DirectionalNavigationAdapter(navigator, animatedTransition = true)

    override fun onTap(event: TapEvent): Boolean = taps.onTap(event)

    override fun onKey(event: KeyEvent): Boolean = keys.onKey(event)
}
