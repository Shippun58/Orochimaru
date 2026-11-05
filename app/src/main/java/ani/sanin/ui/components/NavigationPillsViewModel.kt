package ani.sanin.ui.components

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NavigationPillsViewModel : ViewModel() {

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    fun setTab(index: Int) { _currentTab.value = index }
    fun setExpanded(expanded: Boolean) { _isExpanded.value = expanded }
    fun toggleExpanded() { _isExpanded.value = !_isExpanded.value }
}
