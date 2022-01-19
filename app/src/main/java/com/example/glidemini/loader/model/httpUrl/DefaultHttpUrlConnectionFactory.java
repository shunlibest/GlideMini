package com.example.glidemini.loader.model.httpUrl;

import androidx.annotation.NonNull;

import com.example.glidemini.util.Synthetic;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

class DefaultHttpUrlConnectionFactory implements HttpUrlFetcher.HttpUrlConnectionFactory {

    @Synthetic
    DefaultHttpUrlConnectionFactory() {
    }

    @NonNull
    @Override
    public HttpURLConnection build(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }
}