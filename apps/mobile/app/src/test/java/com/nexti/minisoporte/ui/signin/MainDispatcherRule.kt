/*
 * JUnit rule swapping Dispatchers.Main for a StandardTestDispatcher so
 * ViewModel coroutines (viewModelScope) run under kotlinx-coroutines-test
 * virtual time (design.md Decision 6). Pass the rule's dispatcher into
 * runTest(...) so the TestScope and viewModelScope share one scheduler.
 */
package com.nexti.minisoporte.ui.signin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
