package com.maximiliankraft.mobileagent;

import android.app.Application;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MobileAgentApplication extends Application {
    private String apiKey;
    private boolean apiKeyLoaded;

    synchronized String getApiKey() throws GeneralSecurityException, IOException {
        if (!apiKeyLoaded) {
            apiKey = ApiKeyStore.load(this);
            apiKeyLoaded = true;
        }
        return apiKey;
    }

    synchronized void setApiKey(String apiKey) throws GeneralSecurityException, IOException {
        ApiKeyStore.save(this, apiKey);
        this.apiKey = apiKey;
        apiKeyLoaded = true;
    }

    synchronized void clearApiKey() throws GeneralSecurityException, IOException {
        try {
            ApiKeyStore.clear(this);
        } catch (GeneralSecurityException | IOException exception) {
            apiKey = null;
            apiKeyLoaded = false;
            throw exception;
        }
        apiKey = null;
        apiKeyLoaded = true;
    }

    boolean hasSavedApiKey() {
        return ApiKeyStore.hasSavedKey(this);
    }
}
