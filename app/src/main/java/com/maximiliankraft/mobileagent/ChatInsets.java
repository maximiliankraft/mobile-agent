package com.maximiliankraft.mobileagent;

final class ChatInsets {
    private ChatInsets() {
    }

    static int bottomPadding(int contentPadding, int navigationBarInset, int imeInset) {
        return contentPadding + Math.max(0, Math.max(navigationBarInset, imeInset));
    }
}
