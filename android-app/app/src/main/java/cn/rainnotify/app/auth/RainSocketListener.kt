package cn.rainnotify.app.auth

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Python's reference receiver handles both str and bytes. Accept either JSON frame type. */
open class RainSocketListener : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (bytes.size > 1_048_576) {
            onFailure(webSocket, java.net.ProtocolException("Oversized binary frame"), null)
        } else {
            onMessage(webSocket, bytes.utf8())
        }
    }
}
