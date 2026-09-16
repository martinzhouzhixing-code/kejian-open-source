package app.kejian.mobile

/** Resolve the active status in place; a result is not a second overlapping lazy-list item. */
internal fun resolveAiPending(messages:List<AiChatMessage>,result:AiChatMessage):List<AiChatMessage>{
    val pending=messages.lastOrNull {it.pending}
    val resolved=if(pending==null)result else result.copy(id=pending.id,createdAt=pending.createdAt,taskId=result.taskId?:pending.taskId)
    return messages.filterNot {it.pending}+resolved
}
