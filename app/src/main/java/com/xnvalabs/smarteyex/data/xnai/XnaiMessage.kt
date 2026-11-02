package com.xnvalabs.smarteyex.data.xnai

/** One turn in a conversation with XNAI. [role] is "user" or "assistant". */
data class XnaiMessage(val role: String, val text: String)
