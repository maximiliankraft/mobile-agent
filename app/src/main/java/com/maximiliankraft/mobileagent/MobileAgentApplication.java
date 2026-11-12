package com.maximiliankraft.mobileagent;

import android.app.Application;

public class MobileAgentApplication extends Application {
    private String apiKey;

    String getApiKey() {
        return apiKey;
    }

    void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
