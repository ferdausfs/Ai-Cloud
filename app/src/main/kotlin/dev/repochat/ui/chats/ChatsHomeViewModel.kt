package dev.repochat.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.model.ConversationSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatsHomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    val conversations: StateFlow<List<ConversationSummary>> =
        chatRepository.conversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(repoKey: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(repoKey)
        }
    }
}
