package com.iltan.settle.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.trash.Deletion
import com.iltan.settle.domain.deletion.VanishedReason
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TrashViewModel(deletion: Deletion) : ViewModel() {
    val state: StateFlow<TrashState> =
        combine(deletion.trash, deletion.undecided) { items, undecided ->
                // A mass disappearance is answered on the timeline instead.
                TrashState(
                    items = items,
                    undecided = undecided.filter { it.reason == VanishedReason.ASK },
                    loading = false,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashState())
}
