package com.brax.apkstation.presentation.ui.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.repository.FeedbackRepository
import com.brax.apkstation.utils.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val feedbackRepository: FeedbackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FeedbackEvent>()
    val events = _events.asSharedFlow()

    fun onSheetOpen() {
        _uiState.value = _uiState.value.copy(showSheet = true)
    }

    fun onSheetDismiss() {
        _uiState.value = FeedbackUiState()
    }

    fun onTitleChanged(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    fun onDescriptionChanged(value: String) {
        _uiState.value = _uiState.value.copy(description = value)
    }

    fun submit() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(submitting = true)
            when (val result = feedbackRepository.submitFeedback(
                title = title,
                description = _uiState.value.description.trim()
            )) {
                is Result.Success -> {
                    _uiState.value = FeedbackUiState()
                    _events.emit(FeedbackEvent.ShowMessage("Thank you for your feedback!"))
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(submitting = false)
                    _events.emit(FeedbackEvent.ShowMessage("Failed to submit feedback: ${result.message}"))
                }
                else -> {
                    _uiState.value = _uiState.value.copy(submitting = false)
                }
            }
        }
    }
}

data class FeedbackUiState(
    val showSheet: Boolean = false,
    val title: String = "",
    val description: String = "",
    val submitting: Boolean = false
)

sealed interface FeedbackEvent {
    data class ShowMessage(val message: String) : FeedbackEvent
}
