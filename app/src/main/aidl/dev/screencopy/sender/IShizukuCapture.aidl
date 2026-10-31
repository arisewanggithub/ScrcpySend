package dev.screencopy.sender;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

interface IShizukuCapture {
    void destroy() = 16777114;
    int attach(in IBinder owner, boolean allowRoot) = 1;
    void deploy(String id, in ParcelFileDescriptor source) = 2;
    String capabilities(String id) = 3;
    ParcelFileDescriptor launch(String id, String settings) = 4;
    ParcelFileDescriptor openMedia(String id, int track) = 5;
    void heartbeat(String id) = 6;
    void closeSession(String id) = 7;
    oneway void cancel(String id) = 8;
    String supervisorAbi() = 9;
    void deploySupervisor(String id, in ParcelFileDescriptor source, String sha256) = 10;
    String displayInventory() = 11;
    String validateVideo(String id, String settings) = 12;
}
