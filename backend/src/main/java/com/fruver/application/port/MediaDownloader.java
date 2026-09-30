package com.fruver.application.port;

public interface MediaDownloader {
    record MediaFile(byte[] data, String mime) {}

    MediaFile download(String mediaId);
}
