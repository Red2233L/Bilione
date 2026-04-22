package com.android.bilione

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.purebilibili.data.model.response.ReplyItem
//import com.android.purebilibili.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 评论状态
data class CommentUiState(
    val replies: List<ReplyItem> = emptyList(),
    val isRepliesLoading: Boolean = false,
    val replyCount: Int = 0,
    val repliesError: String? = null,
    val isRepliesEnd: Boolean = false,
    val nextPage: Int = 1
)

// 二级评论状态 (从 PlayerViewModel 移过来)
data class SubReplyUiState(
    val visible: Boolean = false,
    val rootReply: ReplyItem? = null,
    val items: List<ReplyItem> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 1,
    val isEnd: Boolean = false,
    val error: String? = null
)

class VideoCommentViewModel : ViewModel() {
    private val _commentState = MutableStateFlow(CommentUiState())
    val commentState = _commentState.asStateFlow()

    private val _subReplyState = MutableStateFlow(SubReplyUiState())
    val subReplyState = _subReplyState.asStateFlow()

    private var currentAid: Long = 0

    // 初始化/重置
    fun init(aid: Long) {
        if (currentAid == aid) return
        currentAid = aid
        _commentState.value = CommentUiState()
        loadComments()
    }

    fun loadComments() {
        val currentState = _commentState.value
        if (currentState.isRepliesEnd || currentState.isRepliesLoading) return

        _commentState.value = currentState.copy(isRepliesLoading = true, repliesError = null)

        viewModelScope.launch {
            val pageToLoad = currentState.nextPage
            val result = VideoRepository.getComments(currentAid, pageToLoad, 20)

            result.onSuccess { data ->
                val current = _commentState.value
                val isEnd = data.cursor.isEnd || data.replies.isNullOrEmpty()
                _commentState.value = current.copy(
                    replies = (current.replies + (data.replies ?: emptyList())).distinctBy { it.rpid },
                    replyCount = data.cursor.allCount,
                    isRepliesLoading = false,
                    repliesError = null,
                    isRepliesEnd = isEnd,
                    nextPage = pageToLoad + 1
                )
            }.onFailure { e ->
                _commentState.value = _commentState.value.copy(
                    isRepliesLoading = false,
                    repliesError = e.message ?: "加载评论失败"
                )
            }
        }
    }

    // --- 二级评论逻辑 ---

    fun openSubReply(rootReply: ReplyItem) {
        _subReplyState.value = SubReplyUiState(
            visible = true,
            rootReply = rootReply,
            isLoading = true,
            page = 1
        )
        loadSubReplies(rootReply.oid, rootReply.rpid, 1)
    }

    fun closeSubReply() {
        _subReplyState.value = _subReplyState.value.copy(visible = false)
    }

    fun loadMoreSubReplies() {
        val state = _subReplyState.value
        if (state.isLoading || state.isEnd || state.rootReply == null) return
        val nextPage = state.page + 1
        _subReplyState.value = state.copy(isLoading = true)
        loadSubReplies(state.rootReply.oid, state.rootReply.rpid, nextPage)
    }

    private fun loadSubReplies(oid: Long, rootId: Long, page: Int) {
        viewModelScope.launch {
            val result = VideoRepository.getSubComments(oid, rootId, page)
            result.onSuccess { data ->
                val current = _subReplyState.value
                val newItems = data.replies ?: emptyList()
                val isEnd = data.cursor.isEnd || newItems.isEmpty()

                _subReplyState.value = current.copy(
                    items = if (page == 1) newItems else (current.items + newItems).distinctBy { it.rpid },
                    isLoading = false,
                    page = page,
                    isEnd = isEnd,
                    error = null
                )
            }.onFailure {
                _subReplyState.value = _subReplyState.value.copy(
                    isLoading = false,
                    error = it.message
                )
            }
        }
    }
}
